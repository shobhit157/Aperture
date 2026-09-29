# Chat Connection Reconnects — Investigation and Future Direction

> [!NOTE]
> **Status as of the reconnect redesign work (see `chat-reconnect-plan.md` and
> `chat-reconnect-steps-1-2.md` in `docs/fixes/`):** this investigation correctly
> ruled out several causes and correctly identified genuine network instability
> as a real factor. It did not yet know about a separate, confirmed server-side
> bug found afterward — cleanup running by username instead of by session,
> which let a dying old connection erase a live, newer connection's registration.
> That bug has since been fixed and verified on real reconnects. Given that,
> some of the disconnect patterns described below may have been this bug
> compounding with real network instability, not network instability alone.
> This document is being kept as-is for its investigation trail, but its
> conclusions should be read alongside the newer, more complete picture. The
> reconnect story is still under active development — steps 3 to 5 of the
> redesign plan remain unbuilt, and further causes may still surface.

> [!WARNING]
> **Observed:** Admin's and bots' chat connections to the signaling server
> intermittently drop and reconnect, with two different underlying errors
> (`Connection reset` and `Connection refused`), at unrelated times.
>
> **Status:** root cause not fully identified at the time of this
> investigation. Several plausible causes were ruled out with direct evidence.
> The remaining explanation identified here is genuine, external network
> instability between the client machines and the EC2 server — not a bug in
> the reconnect logic itself, which is working correctly as designed. A
> separate, genuine code defect was found and fixed afterward; see the note
> above.

---

## 1. What was observed

```text
receiving bot_large.bin [====================] 100%
[peer-app] EVENT:FILE_RECEIVED:...

Connection lost (attempt 0/5): Connection reset
Reconnecting in 5s...
Connected to server!
```

Separately, a bot showed a different error at a different time:

```text
Connection lost (attempt 1/5): Connection refused
Reconnecting in 5s...
Connection lost (attempt 2/5): Connection refused
Reconnecting in 5s...
Connected to server!
```

The reconnect logic itself worked correctly in both cases — it detected
the drop, retried within the configured limit, and successfully
re-established the connection. The question investigated here is *why*
the underlying TCP connection breaks in the first place, not whether the
recovery mechanism works.

---

## 2. What the reconnect logic actually is

```text
Client.java main() loop:

  attempt++
       │
       ▼
  try: runConnection()
       │
   ┌───┴────┐
   │        │
success   IOException
   │        │
   ▼        ▼
 break   print "Connection lost (attempt N/5)"
          │
     attempt >= 5?
          │
    ┌─────┴─────┐
    │           │
   yes          no
    │           │
    ▼           ▼
 give up    wait 5s, retry

runConnection(), on successful connect:
    attemptCounter[0] = 0   ← resets for the NEXT disconnect episode
```

This design is correct: a fresh, successful connection should not be
penalized by failures from an earlier, unrelated episode.

---

## 3. Root-cause investigation — what was checked and ruled out

### Checked: server pod crashes / restarts
```text
kubectl get pods -l app=signaling-server
RESTARTS: 0, AGE: 6h59m (both pods)
```
**Ruled out.** Neither server pod restarted at any point relevant to the
observed disconnects.

### Checked: memory / resource exhaustion
```text
free -h
Mem: 7.6Gi total, 5.9Gi available
```
**Ruled out.** The instance has ample free memory; this is not an
`OutOfMemoryError` or general resource-starvation scenario.

### Checked: a Kubernetes readiness/liveness probe briefly failing
```yaml
# k8s-server.yaml — no livenessProbe or readinessProbe configured at all
```
**Ruled out.** Without a configured probe, Kubernetes has no mechanism to
temporarily pull a healthy pod out of the Service's endpoint list, which
was the working theory for the bot's `Connection refused` error.

### Checked: a bug in message parsing on the receiving `readLine()`
The exception was traced to `ClientHandler.java:93`, which is:
```java
line = in.readLine();
```
A plain socket read. This kind of call throwing (rather than cleanly
returning `null`) is characteristic of the underlying TCP connection
breaking at the network level — not of malformed message content, which
would surface later, inside the parsing `switch` statement instead.
**Ruled out as a data/protocol bug.**

### A real, separate gap found along the way: `System.err` is not visible in `kubectl logs`
The actual exception message was never recovered, because `ClientHandler`'s
catch block writes to `System.err`, and that stream is not being captured
by `kubectl logs` for this container — despite `System.out` (`[LOG]`,
`[METRICS]` lines) being captured correctly throughout the whole project.

> [!IMPORTANT]
> This is a genuine observability gap, independent of the reconnect
> problem itself, and worth fixing on its own: either redirect error
> logging to `System.out`, or fix the container's stderr capture, so a
> future occurrence of this or any other exception is actually visible.

### Timing check: were the two incidents related?
```text
Bot:   21:27:16, 21:27:33  — "Connection refused"
Admin: 21:35:53            — "Connection reset"
```
**Not simultaneous** — roughly 8 minutes apart, different error types.
This rules out a single shared triggering event (e.g. a brief cluster-wide
network blip) and points instead toward ongoing, intermittent instability
rather than one identifiable incident.

### Checked: whether bots could avoid the public network path entirely
Bots run on a separate, local Kubernetes cluster (Docker Desktop), not
inside the same cluster as the EC2-hosted signaling server. This means
bots have no internal-network shortcut available — they, like the
interactive `admin` client, have no option but to reach the server over
the public internet, through the EC2 instance's public IP and NodePort.
**This was checked and confirmed NOT to be a fixable routing
misconfiguration** — it is a genuine architectural consequence of running
clients and server on physically separate infrastructure.

---

## 4. Current conclusion

> [!WARNING]
> No single, fixable root cause was identified **at the time of this
> investigation**. The most likely explanation is genuine, intermittent
> network instability along the real path between client machines (a home
> network, a separate local Kubernetes cluster) and the EC2 server —
> consistent with other evidence gathered earlier in this project
> (symmetric-NAT-like behavior, elevated relay latency). The existing
> reconnect logic is a correct, working response to this reality, not a
> workaround for a code defect. **A separate, genuine code defect was found
> and fixed afterward — see the status note above.**

---

## 5. Why plain TCP is structurally fragile here — and what Iroh already solves

The chat connection is a plain TCP socket. TCP connections are bound to a
specific IP:port pair on both ends — if either side's network path
changes even slightly (a WiFi reconnect assigning a new local address, a
brief mobile-network handover), the connection cannot survive; it must be
torn down and rebuilt from nothing. There is no recovery mechanism at the
protocol level.

```text
TCP:                                  QUIC (what peer-app already uses
                                       for file transfer via Iroh):

  IP/port changes                       IP/port changes
       │                                     │
       ▼                                     ▼
  connection is dead,                  connection MIGRATES to the
  must reconnect from                  new path — often survives
  scratch                              silently, no rebuild needed
```

`peer-app`'s file-transfer connections already benefit from this — they
are built on Iroh, which uses QUIC specifically for this kind of
resilience. The chat connection does not, because it was built as a
separate, plain TCP socket to the Java signaling server.

> [!NOTE]
> This means the reconnects investigated in this document may specifically
> reflect TCP's structural limitation, not a limitation of the network
> itself. The same underlying network conditions might not have required
> any reconnect at all if the chat channel had the same resilience
> `peer-app`'s file transfers already have.

---

## 6. Future direction: move chat onto Iroh instead of plain TCP

This is not a small patch — it is a genuine architectural shift, roughly
comparable in scope to the transfer-state accuracy work already completed
in this project. It should not be attempted as a quick fix; it needs its
own proper design pass first.

### Open design questions, unresolved

- **Does the server still relay chat, just over Iroh instead of TCP?**
  Or do peers connect directly to each other for chat, mesh-style?
- **How does broadcast (`!chat all`, `!send all all`) work** if chat is
  peer-to-peer rather than hub-and-spoke? Today, one TCP connection to the
  server implicitly reaches "everyone." Iroh connections are
  point-to-point — reaching everyone would require either maintaining a
  connection to every other peer, or keeping some form of central
  relay/introduction point (likely still the Java server, in a reduced
  role).
- **What replaces the plain-text line protocol** currently used over the
  TCP socket (`JOIN`, `FILE_REQUEST|...`, etc.)? A new message framing
  needs designing for Iroh streams.
- **The server still has to exist for initial peer introduction** — Iroh
  does not remove the need for a signaling/discovery mechanism; it only
  changes what happens *after* two peers know about each other.

> [!TIP]
> Recommended approach: write a complete design doc addressing the
> questions above *before* writing any code, the same discipline already
> applied to the transfer-state accuracy work. Rushing this migration
> risks introducing new fragility while trying to fix an existing
> reliability problem.

---

## 7. Immediate, smaller mitigations available now, independent of the migration

- [x] Fix the `System.err` visibility gap so any future exception here is
      actually observable, rather than silently lost. *(Done as part of
      the reconnect redesign's step 1 — errors now print to `System.out`.)*
- [x] Consider a lightweight keepalive on the chat TCP socket. *(Done and
      verified — see `chat-heartbeat-fix.md`.)*
- [ ] Fix the reconnect attempt counter's display, which currently shows
      "attempt 0/5" on the first failure of a fresh episode due to the
      reset happening before the increment is next applied — cosmetic
      only, does not affect actual retry behavior. Still open, planned
      for step 3 of the reconnect redesign.

---

## 8. Relationship to other project documents

- Builds on the transfer-state accuracy work (`transfer-state-vs-mesh-state.md`)
  — the same principle applies: don't guess at causes, gather real
  evidence before concluding.
- Superseded in part by the reconnect redesign work: see
  `chat-reconnect-plan.md` and `chat-reconnect-steps-1-2.md` in
  `docs/fixes/`, which found and fixed a genuine session-cleanup defect
  this investigation did not know about at the time.
- The Iroh-migration idea, if pursued, should get its own dedicated
  future-plan document once the open design questions in Section 6 are
  worked through, following the same pattern as
  `self-hosted-relay-plan.md` and `swarm-distribution-plan.md`.
