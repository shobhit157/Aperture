# Chat Reconnect Redesign

Working plan for making bot and admin reconnects to the signaling server reliable. Tick items below as they're completed — this checklist is the single place to update; the sections below it are reference material, filled in once per step and left alone afterward.

---

## Checklist

- [x] Step 1 — Measure: log sessions, send errors to stdout
- [x] Step 2 — Server: stop an old session erasing a new one
- [x] Step 3 — Client: reconnect properly
- [ ] Step 4 — Both sides notice silence
- [ ] Step 5 — Resync state after a reconnect

---

## What was wrong

Found by reading `Client.java`, `ClientHandler.java`, `ChatRoom.java`, and `RedisClientRegistry.java`.

### Server side

- [x] Cleanup was by username, not by session — fixed in step 2.
- [ ] No dead-client detection: no read timeout, `PING` gets no reply, Redis entries never expire — step 4.
- [x] `register` wrote several Redis fields separately, not atomically — fixed in step 2.
- [x] The handshake had no timeout; a client that dropped before sending a name caused a null pointer error — fixed in step 2.

### Client side

- [x] If `readLine` threw, the socket was never closed and the writer was never cleared — fixed in step 3.
- [x] No connect timeout — fixed in step 3.
- [ ] No read timeout on the message loop — step 4.
- [x] The retry counter reset on connect success, not on proven health — fixed in step 3.
- [ ] State is lost across a reconnect gap — step 5.

---

## Design rules

1. One live session per username.
2. Cleaning up an old session must never touch a newer one.
3. Every session ends through one path that always closes its socket.
4. Connected means the peer answered recently, not that a socket object exists.
5. The retry policy depends on how long the last session lived, not on whether the connect succeeded.

---

## Decisions

| Question | Decision |
|----------|----------|
| Same username twice | Newest session wins, older is told to stop |
| Online count | A separate session-ended notice keeps the count balanced |
| Mesh | Not touched by this work |
| Grace period before announcing a leave | Skipped for now |
| Forced takeover on join | Skipped for now |
| Backoff jitter | Not added — plain doubling was enough |

---

## Step 1 — Measure

**Files changed:** `ClientHandler.java`, `ChatRoom.java`, logging only.

Each session gets a short session ID, and the server logs session start, end, overlap, and cleanup lines. Errors now print to standard output so `kubectl logs` shows them.

**Evidence, two server pods, in time order:**
1. Pod A: session one starts for `admin2`.
2. Pod B: session two starts for `admin2`; the server logs an overlap.
3. Session two ends after 96 seconds; its cleanup deletes the Redis record.
4. Redis check while session one was still connected: record empty, online count zero.
5. Session one ends after 798 seconds; its cleanup finds no Redis pod recorded at all.

**Proves:** one session ending erased the registration of a different session that was still connected, across two pods.

**Note:** `kubectl logs` with a label selector returns only the last 10 lines per pod unless `--tail=-1` is set. Always add that flag.

---

## Step 2 — Server ownership

**Files changed:** `RedisClientRegistry.java`, `ChatRoom.java`, `ClientHandler.java`, `MessageType.java`, `ServerMetrics.java`, `MetricsSubscriber.java`. Server rebuild only.

### What was built

- Every user's Redis record stores its session ID, written in one atomic `hset` call.
- Removing a record is a single atomic check-and-delete script — only deletes if the caller's session ID still matches.
- `ChatRoom.join` passes the session ID through on every registration.
- `ChatRoom.leave` only removes its own local entry if it's still the same session object, and reports back whether the Redis delete actually succeeded.
- `ClientHandler`'s cleanup closes the socket first, then guards each remaining step separately.
- A 10-second handshake timeout; a blank or missing username is rejected outright.
- "Left the chat" is only announced if the session was still the owner. Otherwise a silent `SESSION_ENDED` signal corrects the online count without a false announcement.

### Verification

Confirmed multiple times on real, unplanned network events, not staged tests.

- A bot's connection reset; its reconnect took over cleanly; the old session, once it eventually failed, correctly found `wasOwner=false` and stayed silent.
- An admin session survived a mobile-to-WiFi switch; the old session took roughly 1200-1450 seconds to notice its own connection was dead, and correctly found `wasOwner=false` when it did.
- Multiple overlapping zombie sessions were observed coexisting simultaneously without causing any harm — Redis and the online count stayed correct throughout.
- Ordinary, uncontested sessions continue to show `wasOwner=true` on a normal shutdown.

### What it does not fix

A dying session's thread can stay alive on the server for roughly 1200-1450 seconds before it notices its own connection failed — this is what step 4 addresses.

---

## Step 3 — Client reconnect

**File:** `Client.java`. Rebuild both the bot image and the admin jar.

### What was built

- The retry delay grows on each failure: 1s, 2s, 4s, 8s, 16s, capped at 30s.
- No give-up limit — the client retries indefinitely.
- The delay only resets to the start once a connection has stayed up at least 60 seconds.
- An explicit 5-second connect timeout, replacing reliance on the OS's own, much slower TCP retry behavior.
- The connection setup and message loop are wrapped in `try/finally`, always closing the socket and clearing the writer.

### Verification

- Growing backoff confirmed against an unreachable server: 1s → 2s → 4s → 8s → 16s → 30s, then held at 30s through 13+ attempts, well past the old 5-attempt limit.
- Reset-on-health confirmed on a real episode: a 0-second connection kept the delay growing; a 172-second connection correctly reset it back to 1s.
- Connect timeout confirmed before and after: the first failure went from an unpredictable, long wait to consistently appearing within about 5 seconds, with the reported error changing from "Connection timed out" to "Connect timed out."

### What it does not fix

A connection that goes silent without any error at all is not detected — observed directly by disabling the local network, which produced no reconnect attempt and no output. This is the client-side mirror of step 2's original gap, and step 4 is what closes it.

---

## Step 4 — Both sides notice silence

Not started. Rollout order matters, since older clients must not break.

1. Ship a client that ignores a pong reply it doesn't yet expect, and stops reconnecting if told it's been superseded.
2. Server replies to `PING` with `PONG`, but only for the session that currently owns the name. A non-owner is told it's superseded and closed.
3. Add a server read timeout of about 60 seconds, only once every bot image and the admin jar send heartbeats.
4. Client hangs up if the server has been silent for about 45 seconds — this directly closes the silent-disconnect gap found testing step 3.
5. Add a Redis expiry on each user record, refreshed only by the owning session's heartbeat; move the online set to a new key name.

**Suggested timing:** heartbeat every 20s, client read timeout 45s, server read timeout 60s, Redis expiry around three heartbeat intervals.

**Not verified:** how the Prometheus metrics server reads the online count if the key name changes.

---

## Step 5 — Resync

Not started.

- The server sends the current online list when a client joins.
- The client rebuilds its known-users list from that.
- Clients must tolerate unknown lines before the server starts sending this.

---

## Known limits

- Messages sent during a disconnect are lost. This work makes recovery correct, not lossless.
- The mesh's failure threshold is untouched. If a bot's chat connection is down for more than about 40 seconds during a file transfer, the dashboard can show that transfer as failed even though the transfer itself continues on its own connection. Judge reconnect tests from the session log and Redis, not the mesh colors.
- A write into a half-open socket could eventually block a broadcasting thread. Step 4 bounds this.
- Zombie sessions can accumulate for a genuinely long time (1200-1450 seconds observed) before step 2's ownership check gets a chance to run. Step 4 shortens this window.
- None of this stops the network from dropping. It only makes recovery afterward clean.

---

## Out of scope for now

- Raising the mesh failure threshold, and resending finished-transfer events after a reconnect.
- The grace period before announcing a leave, and forced takeover on join.
- Moving chat onto Iroh gossip — documented separately.

---

## Test plan for steps 4 and 5

1. Drop outbound traffic to the server port for 10 seconds, then 90 seconds, then 5 minutes.
2. Kill the client process and start it again.
3. Delete a server pod.
4. Delete the Redis pod.
5. Open two admin windows with the same name.
6. Kill the `peer-app` process inside a bot.
7. Suspend the laptop or WSL for a minute, then wake it.
8. Cut the link for 3 seconds every 10 seconds, for ten minutes.
9. Disable the local network entirely with an active session, and confirm step 4's read timeout actually notices.

**Pass conditions:** the newest session owns the name, a non-owner's cleanup is logged as skipped, the online gauge returns to the correct number, and both a chat broadcast and one small file transfer succeed afterward.
