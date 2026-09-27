# Fix: Application Heartbeat for the Chat Socket

> [!NOTE]
> This documents a completed, tested fix. For the original investigation
> that led here — including everything ruled out before landing on this —
> see `docs/problems/chat-reconnect-investigation.md`.

---

## 1. The problem this fixes

Admin's and bots' chat connections to the signaling server intermittently
dropped with `Connection reset` or `Connection refused`, at irregular
intervals as short as ~8 minutes. The root cause was never fully
identified with certainty, but the leading, best-supported theory was: an
idle TCP connection getting silently forgotten by some intermediate
network device (a NAT, router, or similar), even though neither endpoint
ever closed it.

---

## 2. The fix

Both `Client.java` and bots now send a small, harmless `PING` message on
the chat socket every 20 seconds. The server silently ignores it — no
broadcast, no logging, no side effect. The only purpose is to guarantee
the connection is never fully idle, so nothing along the network path has
a reason to consider it dead.

```text
Bot / Admin                          Server
     │                                  │
     │──── PING (every 20s) ──────────►│
     │                                  │  (silently ignored,
     │                                  │   read succeeds — that's
     │                                  │   the entire point)
```

### `Client.java`

```java
private static final long HEARTBEAT_INTERVAL_MS = 20_000;

Thread heartbeatThread = new Thread(() -> {
    while (!shuttingDown.get()) {
        try {
            Thread.sleep(HEARTBEAT_INTERVAL_MS);
            PrintWriter out = currentOut[0];
            if (out != null) {
                out.println("PING");
            }
        } catch (InterruptedException ignored) {}
    }
});
heartbeatThread.setDaemon(true);
heartbeatThread.start();
```

Runs for both bots and admin — either can suffer the same idle-timeout
issue, so there's no `if (!isBot)` guard.

### `ClientHandler.java`

```java
case "PING" -> {
    // Heartbeat: no action needed, just proves the client is alive.
    // Deliberately not published to the EventBus — this isn't a chat
    // message and shouldn't appear anywhere or trigger anything.
}
```

Placed as the first case in the message-handling switch, so a `PING`
never falls through to the `default` branch — which would otherwise
broadcast it to every connected client as if it were a real chat message.

---

## 3. What this fix does NOT do

> [!WARNING]
> This does not, and cannot, prevent a genuine network outage from
> killing the connection. If the physical network path actually breaks —
> WiFi disconnects, an ISP link genuinely drops — the heartbeat cannot
> keep that connection alive. What it prevents is a *healthy* connection
> being mistaken for dead and torn down by an intermediate device simply
> because nothing happened to cross it recently.

---

## 4. Verification — a controlled before/after comparison

Rather than relying on a single successful run (which could just be a
naturally calm network moment), the fix was verified with a direct A/B
test: the same client code, same server, same network, with only the
heartbeat thread's `.start()` call toggled on or off between runs.

```text
Heartbeat OFF (control):
  admin connects → !chat all exchange → disconnects
  Time to first disconnect: ~7-8 minutes

Heartbeat ON (fix):
  admin connects → periodic !chat all checks, no issues
  Time stable, run 1: 30+ minutes
  Time stable, run 2: 35+ minutes
```

Two independent runs with the heartbeat enabled both landed in a similar,
much longer range than the disabled run — consistent, not a one-off
coincidence.

| Condition | Result |
|---|---|
| Heartbeat disabled | Disconnected within ~7-8 minutes |
| Heartbeat enabled (run 1) | Stable 30+ minutes |
| Heartbeat enabled (run 2) | Stable 35+ minutes |

> [!IMPORTANT]
> This is a genuine controlled comparison, not just "it worked once." The
> only variable changed between the failing run and the passing runs was
> whether the heartbeat thread was started — everything else (code,
> server, network) was held constant.

---

## 5. Honest scope of what's proven vs. what's inferred

**Proven, with direct evidence:** the heartbeat measurably and
repeatably extends how long the chat connection stays alive, compared to
an otherwise-identical setup without it.

**Inferred, not directly proven:** that the specific mechanism is an
idle-connection timeout somewhere in the network path. This remains the
best-supported explanation given the evidence, but the exact device or
layer responsible (home router, ISP-side NAT, something on AWS's edge)
was never directly identified — `System.err` output was found to not be
captured by `kubectl logs` for this container, which prevented recovering
the actual exception message during the original investigation.

---

## 6. Related, still-open items

- [ ] The `System.err` visibility gap in `kubectl logs` is still unfixed
      — worth addressing so any future exception here is actually
      observable.
- [ ] TCP keepalive (`socket.setKeepAlive(true)`) was considered as a
      complementary, lower-level addition but not implemented — the
      application-level heartbeat was judged sufficient given it directly
      targets the confirmed failure mode.
- [ ] The reconnect attempt counter's display bug ("attempt 0/5" on a
      fresh episode's first failure) remains unfixed — cosmetic only.
- [ ] The larger question of moving chat onto Iroh/QUIC instead of plain
      TCP (see the original investigation doc, Section 5-6) remains a
      separate, future architectural direction — this heartbeat fix
      reduces how often the underlying TCP fragility is triggered, but
      does not remove that fragility itself.

---

## 7. Files changed

- `src/com/shobhit/Network_lab/Client.java` — added the heartbeat thread.
- `src/com/shobhit/Network_lab/ClientHandler.java` — added the `PING`
  case; also removed a temporary `[DEBUG]` line left over from the
  transfer-progress investigation, since its purpose was already served.
