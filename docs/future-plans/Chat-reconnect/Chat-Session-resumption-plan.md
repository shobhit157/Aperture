# Session Resumption Plan

Status: **Planned, not started.** Begins after the Chat Reconnect Redesign is complete (step 4's Redis expiry and step 5).

---

## Context

The reconnect redesign (steps 1–5) makes reconnecting **correct**: zombie sessions are cleaned up, an old session never erases a new one, and both sides notice silence. It does not make reconnecting **seamless**. Every reconnect still creates a brand-new session, announces leave/join, and loses anything sent during the gap.

This plan changes that: a client's session survives short disconnects, and the client picks up where it left off.

---

## The problem

Today, **a session is one TCP connection.**

```text
Process starts → connection 1 → session A
WiFi drops     → connection 1 dies → session A dies (zombie → cleaned up)
Reconnect      → connection 2 → session B (new ID, JOIN again, state lost)
```

Consequences observed in testing:

- Every network blip produces a new session ID, a new Redis registration, and ownership churn (overlap → supersede → cleanup).
- Other users can see "left the chat" / "joined the chat" noise for a single short drop (when the old session is still the owner at cleanup).
- Messages sent while disconnected are lost (Known limit in the reconnect plan).
- The client's local state (e.g. `knownUsers`) goes stale across the gap (what step 5 patches).
- The server cannot tell "same process reconnecting" from "another process using the same name" (the two-window problem).

---

## Goal and design rules

**Goal:** a short disconnect is invisible. Same session, no leave/join, no lost chat messages.

Design rules:

1. **Session ≠ connection.** A session is a logical identity that outlives individual TCP connections.
2. **One attached connection per session.** Attaching a new connection retires the old one.
3. **A session ends only by explicit close or by grace expiry**, never because one connection died.
4. **Only the holder of the session's secret token can resume it.**
5. **Session state lives in Redis**, so a resume can land on any server pod.
6. **Replay is best-effort and bounded.** If the gap is too large, the client is told so and does a full resync.
7. **Everything from steps 1–5 still holds**, now applied at the connection level.

---

## Proposed design

### Identifiers

| Name | Scope | Lifetime | Today's equivalent |
|---|---|---|---|
| `sessionId` | Logical session | Until close or grace expiry | none |
| `connectionId` | One TCP connection | One socket | today's `sessionId` in `[SESSION]` logs |
| `resumeToken` | Secret for one session | Same as session; held only in the client process's memory | none |

### Lifecycle

```text
NEW ──attach──▶ ATTACHED ──connection dies──▶ DETACHED ──resume within grace──▶ ATTACHED
                    │                              │
                    └──explicit close──▶ ENDED ◀───┴──grace expires
```

- **ATTACHED:** a live connection is bound to the session. PINGs refresh the session's expiry.
- **DETACHED:** no connection. No LEAVE announced. A grace timer is running (proposed: 120s).
- **ENDED:** LEAVE is announced exactly once, the Redis records are removed, and the online count drops.

### Handshake

- **First connect:** the client sends `NEW`. The server creates a session and replies `SESSION|<sessionId>|<token>`.
- **Reconnect:** the client sends `RESUME|<sessionId>|<token>|<lastSeq>`.
  - Valid and not expired → `RESUMED|<sessionId>|<fromSeq>`, then replay of missed messages.
  - Invalid or expired → `RESUME_FAILED|<reason>`, and the server continues as `NEW` (`SESSION|...`). The client clears its local state.

### Grace period

- When a connection dies (timeout, EOF, reset, or superseded), the session moves to DETACHED. It does **not** end.
- The session's Redis expiry is set to the grace length. Each PING from the attached connection pushes it back while attached.
- A **sweeper** (on every pod, every ~10s) finds sessions whose grace has expired, claims each one atomically (so only one pod does it), announces LEAVE, and removes the records.
- This reuses the Redis-expiry mechanism from reconnect step 4, so build that part with reuse in mind.

### Message sequencing and replay

- Every chat-visible message is appended once to a **Redis Stream** (`chat_log`, capped with `MAXLEN`) **at the publishing pod, before** it is fanned out through SNS. The stream entry ID is the message's sequence number.
- Server → client lines carry the sequence number: `#<seq> <original line>`. The client tracks `lastSeq`.
- PING carries the client's progress: `PING|<lastSeq>`.
- On `RESUME`, the server replays stream entries after `lastSeq`, keeping only those visible to this user (broadcasts, plus messages targeted at them).
- If `lastSeq` is older than the oldest retained entry → `RESUMED|<sessionId>|GAP`, and the client does a full resync (the step 5 online list).

**Replayed:** CHAT, JOIN, LEAVE.
**Not replayed:** FILE_REQUEST, FILE_ACCEPT, PEER_INFO, transfer events. These are time-sensitive and stale after a gap. In-flight transfers continue on their own Iroh connection.

---

## Protocol changes

| Direction | Line | When |
|---|---|---|
| C → S | `NEW` | 4th handshake line, first connect |
| C → S | `RESUME\|<sessionId>\|<token>\|<lastSeq>` | 4th handshake line, reconnect |
| S → C | `SESSION\|<sessionId>\|<token>` | New session created |
| S → C | `RESUMED\|<sessionId>\|<fromSeq or GAP>` | Resume accepted |
| S → C | `RESUME_FAILED\|<reason>` | Resume rejected (`expired`, `bad_token`, `unknown`) |
| S → C | `#<seq> <line>` | Every replayable message |
| C → S | `PING\|<lastSeq>` | Heartbeat (replaces bare `PING`) |
| S → C | `SUPERSEDED` | Connection retired (unchanged from step 4) |
| C → S | `BYE` | Explicit close: ends the session immediately, no grace |

---

## Redis data model

| Key | Type | Contents | Expiry |
|---|---|---|---|
| `session:<sessionId>` | Hash | `username`, `tokenHash`, `connectionId`, `instanceId`, `endpointId`, `relayUrl`, `state`, `lastSeq` | Refreshed by PING while attached; set to grace on detach |
| `client:<username>` | Hash | `sessionId` (owner), `instanceId`, `endpointId`, `relayUrl` | Same as the session |
| `online_sessions` | Sorted set | member = username, score = last-seen time | Swept |
| `chat_log` | Stream | Replayable messages | `MAXLEN ~ N` |

Atomic Lua scripts:

- **`create_session`**: create the session, take ownership of the username (newest wins, see open decisions), return the previous owner session to retire.
- **`attach`**: check the token hash and that the session isn't ended, set `connectionId` and `instanceId`, refresh expiry, return the previous `connectionId` and `instanceId` (to supersede).
- **`detach_if_current`**: only if `connectionId` still matches, set DETACHED and the grace expiry.
- **`heartbeat_if_current`**: only if `connectionId` matches, refresh expiry, update `lastSeq` and the last-seen score.
- **`claim_expired`**: atomically claim one expired session for the sweeper.
- **`end_session`**: on `BYE` or a successful claim, remove the records and return whether LEAVE should be announced.

---

## Interaction with what's already built

| Existing piece | What changes |
|---|---|
| Step 2: ownership check-and-delete | Ownership stays keyed by `sessionId`. A dying connection checks `connectionId` and never touches the session if it isn't the current connection. |
| Step 3: client reconnect and backoff | Unchanged. The reconnect sends `RESUME` instead of a fresh handshake. |
| Step 4: timeouts and PING/PONG | Unchanged timings. A timeout now **detaches** instead of ending. |
| Step 4: supersede (same pod and 10s check) | Moves to the connection level: a connection retires when the session's `connectionId` is no longer its own. |
| Step 4: Redis expiry | Becomes the grace-period mechanism plus the sweeper. |
| Step 5: online list on join | Still sent for `NEW` sessions and on `GAP`. A normal resume doesn't need it. |
| Two windows, same name | A second process has no token, so it's a `NEW` session. Resolved by the newest-wins / first-wins decision below. A reconnect is always clearly identified by its token. |
| Mesh dashboard | Unchanged. Transfer events are not replayed. |

---

## Edge cases and failure modes

| Case | Expected behavior |
|---|---|
| Resume lands on the other pod | `attach` succeeds; the old pod's connection is superseded by its 10s check |
| Resume while the old connection is still alive | New connection attached; old one told `SUPERSEDED` |
| Resume after grace expired | `RESUME_FAILED expired` → new session, JOIN announced; the old session's LEAVE was already announced |
| Wrong or missing token | `RESUME_FAILED bad_token` → new session |
| Gap larger than `chat_log` retention | `RESUMED ... GAP` → client does a full resync |
| Client killed (`kill -9`), restarted | Token lost with the process → new session; the old one ends at grace expiry (one LEAVE) |
| Ctrl+C / normal exit | Client sends `BYE` → immediate end, no grace |
| Server pod crashes | Sessions detach implicitly (no PINGs); clients resume on the other pod within grace |
| Redis restarts (data lost) | All resumes fail → clients create new sessions; acceptable |
| Two resumes race with the same token | `attach` is atomic: last one wins, the earlier one is superseded |
| Pods' clocks differ | All expiry and score math uses Redis `TIME` inside the scripts |

---

## Security of the resume token

- 128-bit random value from `SecureRandom`, sent to the client once.
- Redis stores only a **hash** of the token, and comparison is constant-time.
- The client keeps the token **in memory only**, never on disk, so a session belongs to one running process.
- **Limitation:** the chat channel is plain TCP, so anyone who can see the traffic can read the token. The token is only as safe as the channel. The real fix is TLS on the chat connection, or moving chat onto Iroh/QUIC (already listed as a future plan).

---

## Rollout order

1. **Prerequisites:** reconnect plan step 4 (Redis expiry) and step 5 complete and verified.
2. **Server, internal only:** split `sessionId` / `connectionId` and log both. No protocol change.
3. **Grace period and sweeper:** a connection loss detaches and LEAVE is delayed. Still no protocol change, and testable on its own.
4. **Protocol:** `NEW` / `RESUME` handshake, `SESSION` / `RESUMED` / `RESUME_FAILED`, `BYE`. Server, admin JAR and bot image deploy **together**, since the handshake changes.
5. **Replay:** `chat_log` stream, `#<seq>` prefix, `PING|<lastSeq>`.
6. **Optional:** a client-side outbox for messages typed while disconnected (needs client sequence numbers for de-duplication).

---

## Test plan

| Test | Pass condition |
|---|---|
| WiFi off 30s / 50s / 90s | Same `sessionId` before and after; no LEAVE or JOIN; chat sent during the gap is replayed in order |
| Resume across pods (port-forward pinned) | Same `sessionId`; old pod's connection superseded within ~15s |
| WiFi off 3 min (longer than grace) | Exactly one LEAVE at grace expiry; reconnect gets `RESUME_FAILED expired` and a new session |
| Ctrl+C | `BYE` → immediate LEAVE, no grace delay |
| `kill -9` client, restart | New session immediately; old session ends at grace expiry with one LEAVE |
| Delete a server pod | Its clients resume on the other pod within grace; no LEAVE/JOIN |
| Delete the Redis pod | Clients fall back to new sessions; no crash, no stuck state |
| Gap longer than stream retention | `GAP` → full resync; online list correct |
| Bad token | `RESUME_FAILED bad_token`; the real owner unaffected |
| Two windows, same name | Behaves per the chosen decision; no back-and-forth |

**Always check afterwards:** Redis owner and session state correct, online gauge correct, chat broadcast and one small file transfer succeed.

---

## Open decisions

| Question | Options | Leaning |
|---|---|---|
| Grace length | 60s / 120s / 300s | 120s |
| Show a DETACHED user as online? | Yes (stable list) / no (accurate list) | Yes |
| Tokenless second process, same name | Newest wins (old told `SUPERSEDED`, must stop) / first wins (`NAME_IN_USE`) | Decide before rollout step 4 |
| Which messages to replay | CHAT/JOIN/LEAVE only / everything | CHAT/JOIN/LEAVE only |
| `chat_log` retention | Count-based / time-based | Count-based (`MAXLEN ~ 1000`) |
| Client outbox for messages typed offline | Now / later | Later (rollout step 6) |
| Support old clients during rollout | Yes (legacy handshake path) / no (deploy together) | No |

## Out of scope

- TLS on the chat channel
- Moving chat onto Iroh/QUIC (separate future plan; would give connection migration natively)
- The mesh failure threshold and re-sending finished-transfer events
- Persisting sessions across client process restarts (the token is memory-only by design)

## Relationship to other documents

- Builds on the Chat Reconnect Redesign (`docs/fixes/chat-reconnect/`), especially step 4's Redis expiry and step 5's online list.
- Would remove the reconnect plan's known limit, "messages sent during a disconnect are lost."
- Related future plan: moving chat onto Iroh (`docs/future-plans/`).
