# Chat Reconnect Redesign

Working plan for making bot and admin reconnects to the signaling server reliable. Steps 1 and 2 are done and verified. Steps 3 to 5 are designed but not built. Anything marked "not verified" comes from reading the code, not from running it.

---

## Status

| Step | Description | State |
|------|-------------|-------|
| 1 | Measure: log sessions, send errors to stdout | Done, deployed, evidence collected |
| 2 | Server: stop an old session erasing a new one | Done, deployed, verified on real reconnects |
| 3 | Client: reconnect properly | Not started |
| 4 | Both sides notice silence | Not started |
| 5 | Resync state after a reconnect | Not started |

---

## What was wrong

Found by reading `Client.java`, `ClientHandler.java`, `ChatRoom.java`, and `RedisClientRegistry.java`.

### Server side

- Cleanup was by username, not by session. When any handler ended, it removed the name from `localClients` and deleted `client:<name>` from Redis, whichever session owned the entry at that moment. **Fixed in step 2.**
- No dead-client detection: no read timeout, `PING` gets no reply, Redis entries never expire. **Still open — step 4.**
- `register` wrote several Redis fields separately, not atomically. **Fixed in step 2.**
- The handshake had no timeout; a client that dropped before sending a name caused a null pointer error. **Fixed in step 2.**

### Client side

- If `readLine` throws, the socket is never closed and the writer is never cleared. **Still open — step 3.**
- No connect timeout, no read timeout. **Still open — steps 3 and 4.**
- The retry counter resets on connect success, not on proven health, causing a fast retry storm on a flaky link. Five failed connects in a row ends the loop for good. **Still open — step 3.**
- State is lost across a reconnect gap. **Still open — step 5.**

---

## Evidence from step 1

Changed `ClientHandler.java` and `ChatRoom.java`, logging only. Each session gets a short session ID, and the server logs session start, end, overlap, and cleanup lines. Errors now print to standard output so `kubectl logs` shows them.

**Observed on the real system, two server pods, in time order:**

1. Pod A: session one starts for username `admin2`.
2. Pod B: session two starts for `admin2`; the server logs an overlap, since the name was already registered to pod A.
3. Session two ends after 96 seconds; its cleanup deletes the Redis record.
4. Redis check while session one was still connected: the record was empty, and the online count for `admin2` was zero.
5. Session one ends after 798 seconds; its own cleanup finds no Redis pod recorded at all.

**Proves:** one session ending erased the registration of a different session that was still connected, across two pods.

**Note:** `kubectl logs` with a label selector returns only the last 10 lines per pod unless `--tail=-1` is set. Always add that flag. This may have affected earlier conclusions in this project, which have not been rechecked.

---

## Design rules

1. One live session per username.
2. Cleaning up an old session must never touch a newer one.
3. Every session ends through one path that always closes its socket.
4. Connected means the peer answered recently, not that a socket object exists.
5. The retry policy depends on how long the last session lived, not on whether the connect succeeded.

---

## Decisions

| Question | Decision | Status |
|----------|----------|--------|
| Same username twice | Newest session wins, older is told to stop | Confirmed correct for step 2's scope |
| Online count | A separate session-ended notice keeps the count balanced | Confirmed working |
| Mesh | Not touched by this work | Confirmed |
| Grace period before announcing a leave | Skipped for now | Confirmed |
| Forced takeover on join | Skipped for now | Confirmed |

---

## Step 2 — Server ownership (done)

**Files changed:** `RedisClientRegistry.java`, `ChatRoom.java`, `ClientHandler.java`, `MessageType.java`, `ServerMetrics.java`, `MetricsSubscriber.java`. Server rebuild only — no bot image or admin jar changes needed.

### What was built

- Every user's Redis record now stores its session ID alongside endpoint and relay info, written in one atomic `hset` call instead of several separate writes.
- Removing a record is now a single atomic check-and-delete, run as a Redis script: it reads the stored session ID and only deletes the record and removes the username from the online set if the caller's session ID still matches. If a newer session has since registered, the script does nothing — the newer session's data is left completely untouched.
- `ChatRoom.join` passes the session ID through to the registry on every registration.
- `ChatRoom.leave` now removes its own entry from the in-memory client list only if it's still the exact same session object registered there, and reports back whether the Redis check-and-delete actually succeeded.
- `ClientHandler`'s cleanup now closes the socket first, before anything else, so a failure elsewhere (Redis down, a broadcast error) can never skip it. Each remaining cleanup step is now in its own try/catch.
- A 10-second timeout was added to the initial handshake, and a blank or missing username is now rejected outright instead of causing a null pointer error later.
- "Left the chat" is now only announced if the session was confirmed to still be the owner at cleanup time. If it wasn't the owner, a separate, silent signal (`SESSION_ENDED`) fires instead, which only corrects the live online count without announcing a false departure or counting it as a real leave.

### Verification

Confirmed twice on real, unplanned network events during testing — not staged scenarios.

**Case 1 — a bot's connection was reset.** Its own reconnect logic opened a new session under the same username, which correctly took over the Redis registration immediately. When the old session's socket eventually failed with a real `SocketException`, its cleanup ran, checked Redis, found it no longer owned the name, and correctly did nothing. The log showed `wasOwner=false` and "ended without owning the name — no LEAVE announced." The bot never appeared to leave while it was still fully connected and working.

**Case 2 — an admin session survived a network change from mobile to WiFi.** The old connection became unreachable, but the operating system didn't report the failure to the server for about 20 minutes (1202 seconds). In that gap, the client's reconnect logic opened a new session, which correctly took over. When the old session finally got a "No route to host" error, its cleanup checked Redis, found it was no longer the owner, and again correctly did nothing.

In both cases, checking Redis directly afterward (`HGET client:<name> sessionId`) confirmed the currently live sessions were exactly the ones recorded as owners, and `SMEMBERS online_users` matched exactly what was actually connected, with nothing stale left behind.

Both sessions later ended normally (a plain client shutdown) after running for 2082 and 1788 seconds respectively, each showing `wasOwner=true` on cleanup — confirming the ordinary, uncontested case still works exactly as before.

### What step 2 does not fix

The old, dying session's thread stays alive on the server for however long it takes the underlying connection to actually fail and for the operating system to report that — 1202 seconds in the observed case. During that entire window the socket is technically still open on the server's side, even though it no longer owns anything. Making the server notice a dead connection sooner is step 4. Making the client's own reconnect behavior more careful is step 3.

---

## Step 3 — Client reconnect

**File:** `Client.java`. Needs a new bot image and a new admin jar.

- One try/finally block per session that always closes the socket and clears the writer.
- A connect timeout of about 5 seconds.
- Backoff that grows — roughly 1, 2, 4 seconds up to a 30 second cap, with random jitter. Never give up entirely.
- Reset the backoff only after a session stays up about 60 seconds.
- Expire pending file handshakes after about 60 seconds.
- Bots exit if the `peer-app` process dies, so Kubernetes restarts the pod. Admin prints an error instead.
- The heartbeat checks for a write error on the current writer.

---

## Step 4 — Both sides notice silence

Rollout order matters here, since older clients must not break.

1. Ship a client that ignores a pong reply it doesn't yet expect, and that stops reconnecting if told it's been superseded.
2. Server replies to a ping with a pong, but only for the session that currently owns the name. A non-owner is told it's superseded and is closed.
3. Add a server read timeout of about 60 seconds, only once every bot image and the admin jar send heartbeats.
4. Client hangs up if the server has been silent for about 45 seconds.
5. Add a Redis expiry on each user record, refreshed only by the owning session's heartbeat; move the online set to a new key name, since the current one can't support expiring members.

**Suggested timing:** heartbeat every 20s, client read timeout 45s, server read timeout 60s, Redis expiry around three heartbeat intervals. Round-trip time reached several seconds on the worst night recorded, so don't set these any tighter.

**Not verified:** how the Prometheus metrics server reads the online count. It currently reads `SCARD online_users` live via a callback, which is self-correcting — but if the key name changes in step 4, this needs re-checking.

---

## Step 5 — Resync

- The server sends the current online list when a client joins.
- The client rebuilds its known-users list from that.
- Clients must tolerate unknown lines before the server starts sending this, so older clients aren't broken.

---

## Known limits

- Messages sent during a disconnect are lost. This work makes recovery correct, not lossless.
- The mesh's failure threshold is untouched. If a bot's chat connection is down for more than about 40 seconds during a file transfer, the dashboard can show that transfer as failed even though the transfer itself is still running on its own separate connection. When testing reconnects, judge from the session log and Redis, not from the mesh colors.
- A write into a half-open socket could eventually block a thread that's broadcasting to everyone. Steps 3 and 4 bound this risk.
- None of this stops the network from dropping in the first place. It only makes recovery afterward clean.

---

## Out of scope for now

- Raising the mesh failure threshold, and resending finished-transfer events after a reconnect.
- The grace period before announcing a leave, and forced takeover on join.
- Moving chat onto Iroh gossip — documented separately as a future direction.

---

## Test plan for steps 3 to 5

Judge each run from the Redis record for the user, the session log, and the online gauge — not from what the chat window prints.

1. Drop outbound traffic to the server port for 10 seconds, then 90 seconds, then 5 minutes.
2. Kill the client process and start it again.
3. Delete a server pod.
4. Delete the Redis pod.
5. Open two admin windows with the same name.
6. Kill the `peer-app` process inside a bot.
7. Suspend the laptop or WSL for a minute, then wake it.
8. Cut the link for 3 seconds every 10 seconds, for ten minutes.

**Pass conditions after each fault:** the newest session owns the name, a non-owner's cleanup is logged as skipped, the online gauge returns to the correct number, and both a chat broadcast and one small file transfer succeed afterward.
