# Chat Reconnect Redesign

Working plan for making bot and admin reconnects to the signaling server reliable. Step 1 is done. Steps 2 to 5 are designed but not built. Anything marked "not verified" comes from reading the code, not from running it.

---

## Status

| Step | Description | State |
|------|-------------|-------|
| 1 | Measure: log sessions, send errors to stdout | Done, deployed |
| 2 | Server: stop an old session erasing a new one | Not started |
| 3 | Client: reconnect properly | Not started |
| 4 | Both sides notice silence | Not started |
| 5 | Resync state after a reconnect | Not started |

---

## What is wrong today

Found by reading `Client.java`, `ClientHandler.java`, `ChatRoom.java`, and `RedisClientRegistry.java`.

### Server side

- Cleanup is by username, not by session. When any handler ends, it removes the name from `localClients` and deletes `client:<name>` from Redis, whichever session owns the entry at that moment.
- No dead-client detection: no read timeout, `PING` gets no reply, Redis entries never expire.
- `register` writes several Redis fields separately, not atomically.
- The handshake has no timeout; a client that drops before sending a name causes a null pointer error.

### Client side

- If `readLine` throws, the socket is never closed and the writer is never cleared. Heartbeat and progress writes then go into a dead socket silently.
- No connect timeout, no read timeout.
- The retry counter resets on connect success, not on proven health, causing a fast retry storm on a flaky link. Five failed connects in a row ends the loop for good.
- State is lost across a reconnect gap: a transfer-finished event arriving while the writer is null gets dropped, and the known-users list only learns of users who join after you.

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

**Does not prove:** that real network reconnects hit this exact path. This run used two live windows with the same name, not a dropped connection. A real reconnect would show an overlap line followed later by a cleanup line where the session no longer owns the entry. The logging stays in place so a bad network stretch can confirm this.

> `kubectl logs` with a label selector returns only the last 10 lines per pod unless `--tail=-1` is set. Always add that flag. This may have affected earlier conclusions in this project, which have not been rechecked.

---

## Design rules

1. One live session per username.
2. Cleaning up an old session must never touch a newer one.
3. Every session ends through one path that always closes its socket.
4. Connected means the peer answered recently, not that a socket object exists.
5. The retry policy depends on how long the last session lived, not on whether the connect succeeded.

---

## Decisions

| Question | Decision | Confidence |
|----------|----------|------------|
| Same username twice | Newest session wins, older is told to stop | Assumed, needs confirming |
| Online count | A separate session-ended notice keeps the count balanced | Assumed, depends on metrics files |
| Mesh | Not touched by this plan | Confirmed |
| Grace period before announcing a leave | Skipped for now | Confirmed |
| Forced takeover on join | Skipped for now | Confirmed |

---

## Step 2 — Server ownership

**Files:** `RedisClientRegistry.java`, `ChatRoom.java`, `ClientHandler.java`, `MessageType.java`, `MetricsSubscriber.java`. Server rebuild only.

- Store the session ID in each user's Redis record, written in one atomic operation.
- `unregister` deletes only if the stored session ID matches, using a single atomic compare-and-delete.
- `ChatRoom.leave` uses a conditional remove, keyed on both username and handler.
- In `ClientHandler` cleanup: close the socket first, then guard each remaining step in its own try/catch.
- Add a handshake timeout of about 10 seconds; refuse a null or blank name.
- Publish the leave notice only if this session still owned the name.
- Add a separate session-ended notice that always fires, so the metrics subscriber still decrements the online count.

**Limit:** two live windows with the same name are only partly fixed until step 4, since the older window stays connected and unregistered until the server can tell it to stop.

**Test, not yet run:** freeze an admin process with a stop signal, start a second admin with the same name, then kill the frozen one. This gives the real ordering — new session registers first, old one ends later. After this step, the new session's Redis record should survive.

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

**Not verified:** how the Prometheus metrics server reads the online count. If it reads the existing online-users key directly, changing that key changes the reported number.

---

## Step 5 — Resync

- The server sends the current online list when a client joins.
- The client rebuilds its known-users list from that.
- Clients must tolerate unknown lines before the server starts sending this, so older clients aren't broken.

---

## Known limits

- Messages sent during a disconnect are lost. This plan makes recovery correct, not lossless.
- The mesh's failure threshold is untouched. If a bot's chat connection is down for more than about 40 seconds during a file transfer, the dashboard can show that transfer as failed even though the transfer itself is still running on its own separate connection. When testing reconnects, judge from the session log and Redis, not from the mesh colors.
- A write into a half-open socket could eventually block a thread that's broadcasting to everyone. Steps 3 and 4 bound this risk. A per-client outbound queue would remove it entirely.
- None of this stops the network from dropping in the first place. It only makes recovery afterward clean.

---

## Out of scope for now

- Raising the mesh failure threshold, and resending finished-transfer events after a reconnect, since that needs the mesh to match transfers by ID rather than by sender and receiver.
- The grace period before announcing a leave, and forced takeover on join.
- Moving chat onto Iroh gossip — documented separately as a future direction.

---

## Test plan

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

Cutting a bot's link from the EC2 side probably needs a firewall rule on the raw prerouting chain, since NodePort traffic is translated and forwarded rather than delivered locally. Not verified.

---

## Open items

- [ ] Paste `EventBus.java`, `ServerMetrics.java`, and `PrometheusMetricsServer.java` before step 2 is written in full — the online count change depends on how they work.
- [ ] Confirm the two assumed decisions listed above.
