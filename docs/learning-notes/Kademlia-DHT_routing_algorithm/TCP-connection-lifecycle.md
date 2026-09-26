# Long-Lived Connections — TCP, Keepalive, Heartbeats, and What Broke Tonight

*Why a healthy TCP connection can die with no code bug involved.*

> [!NOTE]
> Personal study notes, written after tonight's investigation into
> admin's and the bots' chat disconnects in Aperture.

---

## 1. Does TCP reconnect on its own?

No. Once a TCP connection is established, it stays open indefinitely —
hours, days — as long as nothing actively breaks it. Silence alone
proves nothing is wrong.

```text
Bot ──── SYN ────► Server
Bot ◄─ SYN+ACK ──  Server
Bot ──── ACK ────► Server
Bot ◄══ established, then silence ══► Server   (still fine)
```

> [!IMPORTANT]
> If a connection drops at a suspiciously *regular* interval, that
> regularity is a clue — something specific is killing it. Find that
> something, don't assume "TCP just times out."

---

## 2. What we ruled out tonight, with evidence

```text
Suspected                     Checked with            Result
────────────────────────────────────────────────────────────────
Server pod crashing        →  kubectl get pods     →  0 restarts
Memory exhaustion          →  free -h              →  5.9Gi free
Readiness probe flapping   →  k8s-server.yaml      →  no probe configured
Bad message parsing        →  exception line #      →  plain readLine()
Bots on a bad route        →  cluster topology      →  no shortcut exists
```

Ruling things out with real evidence *is* the investigation — not a
detour from it.

---

## 3. Two different errors, two different meanings

```text
Admin: "Connection reset"   → something WAS working, then got torn down
Bot:   "Connection refused" → a NEW attempt was never even accepted
```

Found ~8 minutes apart, from different clients. That rules out one
shared triggering event and points toward ongoing, low-grade network
instability — not one fixable bug.

---

## 4. The core distinction: a live socket vs. a live peer

```text
"the socket object still exists"  ≠  "the other end is still there"
```

A `Socket` can sit valid in memory long after whatever was on the other
end has vanished. Holding a reference proves nothing about the present.

---

## 5. TCP keepalive vs. an application heartbeat

```text
TCP keepalive → "is the network path still reachable?"
Heartbeat     → "is the actual application still alive and working?"
```

Keepalive is a network-layer sanity check — it doesn't know your
`ClientHandler` thread is stuck, or that the JVM paused for GC. A
heartbeat (a small message sent every 20-30s, tracked as `last_seen`)
answers the question that actually matters here.

---

## 6. Keep three concerns separate

```text
Connection   — do I have an open socket right now?
Liveness     — is the other side actually responding?
Reconnection — what do I do once I know the answer is "no"?
```

Aperture's client already handles reconnection correctly (capped
retries, resets on success). What's missing is liveness — right now the
only way to learn a connection is dead is the hard way, via an
exception.

---

## 7. At bot-fleet scale, fixed retry delays cause their own problem

```text
500 bots drop together → all retry at t+5s → server gets hit by
                                              500 requests at once
```

Fix: exponential backoff with jitter — growing delays, spread out with
randomness — so retries don't arrive in one synchronized wave. Worth
designing in before Aperture scales toward 50+ bots.

---

## 8. Users never see their chat app "reconnect"

```text
what the user feels:   one continuous connection
what's actually true:  Connection 1 (dead) → Connection 2 → Connection 3
```

The logical session outlives any one physical connection. Right now,
Aperture's admin terminal fails to hide this — every reconnect prints
visibly instead of happening silently underneath.

---

## 9. Where Iroh changes the picture

Iroh runs on QUIC over UDP, not TCP — which is why `peer-app`'s file
transfers survive things tonight's plain TCP chat socket couldn't.

```text
TCP:  IP/port changes → connection is dead, no recovery
QUIC: IP/port changes → connection MIGRATES, often silently
```

The peer *relationship* can survive even when the network path under it
doesn't — exactly what tonight's chat socket lacked.

---

## 10. What "online" should mean as the bot count grows

```text
Wrong:  socket exists → bot is online

Better: TCP: CONNECTED
        Heartbeat: last ACK 4s ago
        Application: READY
```

At 4-5 bots, checking logs by hand is fine. At 50-100, this needs to be
real, queryable data — exactly what `bot_connections`, `bot_last_seen`,
`bot_reconnects_total` as Prometheus metrics would give, feeding
straight into Grafana.

---

## 11. Before building anything new, check the timing

```text
CONNECTED     04:17:01
DISCONNECTED  04:18:01   reason: SocketException
reconnecting in 5s
```

Suspiciously exact gaps (60.02s, 60.01s, 59.98s) point to a specific
configured timeout. Tonight's actual gaps (~8 minutes, different errors,
different clients) did *not* look like that — which is itself evidence
for "ongoing instability," not "one fixable timer."

---

## 12. The mental model to carry forward

```text
Application:  "Is my peer actually alive?"     — Heartbeat
       │
Transport:    "Can bytes move at all?"          — TCP / QUIC
       │
Network:      Internet, NAT, firewalls, EC2, Kubernetes
```

The network layer never promises a connection lasts forever. Transport
tells you the moment it can't move bytes — not a moment before, not why.
Liveness only exists if the application asks for it on its own schedule,
instead of waiting to find out the hard way.
