# B3c: Agree After a Restart

**Status:** 🔄 Design done · code next on branch `phase-b3cd-cleanup`
Problem and test results: [Restart sync findings](../problems/restart-sync-findings.md)
Related: [File transfer improvement plan](../future-plans/File-transfer-improvement-plan.md) · [Mesh v2](mesh-v2.md) · [peer-app protocol](../peer-app-protocol.md)

---

## Rule

**peer-app never acts alone after a restart. The server stays the boss.**

| Finding | Fixed by |
|---|---|
| R2 Java forgets | 3.2 + 3.3: Java rebuilds its list from peer-app + server |
| R3 peer-app acts alone | 3.1: lock on restore |
| R4 30 s vs 30 min | 3.4: receiver gets 30 min too |
| R5 different answers | 3.3: one decision per transfer, the server's state wins |
| R6, R7 dead transfer waits | 3.3: "report failed" at once |
| R8 sender not told | 3.3: Java knows restored items, so it prints dropped / expired |
| R9, R10 mesh % and minutes | B3d |

---

## 3.1 Lock on restore (peer-app)

- Restored **offers** are **locked**: every fetch is refused until Java sends `release <id>`.
- Restored **fetches** are **not started**: they wait for `retry <id>` (go) or `cancel <id>` (delete).
- One line per restored item, then the summary (Java treats the summary as "list complete"):

```
EVENT:RESTORED_OFFER:<id>:<hash>:<size>:<file name>
EVENT:RESTORED_FETCH:<id>:<hash>:<size>:<bytes_on_disk>:<file name>
EVENT:RESTORED:<offers>:<fetches>
```

- New command `release <id>` → `EVENT:RELEASED:<id>`.
- Locked items still expire with the normal TTL, so nothing stays stuck if Java never answers.

## 3.2 Server tells the client its transfers on login

```
MY_TRANSFERS|<id>:<state>:<from>:<to>:<pct>,…
```

Active transfers of that user (started / moving / retrying / waiting), from Redis. For now a scan of `transfers:active`; later the per-user index `transfers:user:<name>` (also needed by the user app).

## 3.3 Java compares both lists

| peer-app has it | Server: active | Java does |
|---|---|---|
| ✅ | ✅ | **go**: `release` (offer) or `retry` (fetch); back in the list; prints "restored · resumed", later "done ✓" |
| ✅ | ❌ (failed / done / unknown) | **delete**: `unshare` (offer) or `cancel` (fetch, partial data removed); prints why |
| ❌ | ✅ | **report failed**: "lost local data", or "file changed" if peer-app printed `OFFER_DROPPED`; the other side stops waiting at once |

Java saves nothing new: peer-app knows **what** (file, hash, bytes on disk), the server knows **who and how far** (from, to, state, %).

```mermaid
sequenceDiagram
  participant P as peer-app
  participant J as Java client
  participant S as Server (Redis)
  P->>P: read offers.json / fetches.json → LOCKED
  P-->>J: RESTORED_OFFER / RESTORED_FETCH … RESTORED
  J->>S: login
  S-->>J: MY_TRANSFERS (active list)
  J->>J: compare both lists
  alt both have it, active
    J->>P: release <id> / retry <id>
    J-->>J: "restored · resumed" → "done ✓"
  else only peer-app has it
    J->>P: unshare <id> / cancel <id>
  else only server has it
    J->>S: TRANSFER_EVENT failed (lost local data / file changed)
  end
```

## 3.4 Receiver gets 30 min too (server)

Today a receiver who leaves is failed after 30 s ("peer left"). With B3c it becomes **waiting for receiver** until the TTL, the same as a sender who leaves.

| Who goes offline | Server shows | Deadline |
|---|---|---|
| Sender | waiting for sender | transfer start + TTL |
| Receiver *(new)* | waiting for receiver | transfer start + TTL |

- The deadline counts from the **transfer start** (same clock as peer-app's `created_ms`), not from the disconnect.
- Mesh: the same purple dashed line, label "waiting for receiver".

### All cases after B3c

| # | Case | Result |
|---|---|---|
| 1 | Short blip | continues by itself (iroh / quick tries) ✅ |
| 2 | Sender offline, back in time | resumes ✅ |
| 3 | Receiver offline, back in time | resumes ✅ |
| 4 | Both offline, back in time | resumes ✅ |
| 5 | Someone offline past the TTL | expires, both sides clean up, clear message ❌ |
| 6 | Data lost | reported failed at once ❌ |
| 7 | Sender's file changed | reported failed at once, never a wrong file ❌ |

Cases 1–4 always end in a resume; 5–7 end in a clean, fast failure with a clear message.

## 3.5 Sweeper (server backup)

| State | Failed after |
|---|---|
| started / moving / retrying | 120 s without any event (unchanged) |
| waiting for sender | deadline + 2 min (unchanged) |
| waiting for receiver *(new)* | deadline + 2 min; here the sweeper **is** the one that expires it |
| check interval | every 15 s (unchanged) |

Server and peer-app read the same `PEER_TRANSFER_TTL_SECS` (default 1800 s).

## 3.6 Decisions

- **Receiver back after the server said failed** (only after the TTL now): **cancel**, partial data deleted, clear message.
- **Queued transfers** (not started yet) are memory only and are **not** restored; the user sends again.
- **Sender back, receiver tries too early:** a locked offer refuses the fetch; the receiver's next quick try (5–10 s) works. No extra code.
- **TTL counts from the transfer start**, not from each disconnect, so a transfer can't live forever.

## 3.7 Edge cases

| # | Case | Rule |
|---|---|---|
| E1 | Reconnect without restart (Java and peer-app kept running) | Compare only IDs Java doesn't already know; the next progress moves waiting → moving |
| E2 | Restart before the transfer began (state `started`, no fetch / offer yet) | Report failed "restarted before the transfer began", not "lost local data" |
| E3 | Server not reachable at startup | Print "restored N transfers · waiting for server"; compare when connected; TTL is the safety net |
| E4 | Redis lost its data | `MY_TRANSFERS` empty → restored items deleted; check Redis persistence (AOF); accepted for now |
| E5 | Double retry (B3b "sender back" + B3c "go") | `already fetching` = OK, not shown |
| E6 | Laptop clock differs from Redis clock | 2 min margin; server state wins |
| E7 | Bots on the old build | Rebuild client image with the server (`:b3c`); test in B4 |
| E8 | Same user started twice | Blob store lock + "superseded" already stop it; keep as a test |

---

## 4. Changes

| Step | Where | Change |
|---|---|---|
| 1 | peer-app `blobs.rs`, `main.rs` | `Offer.locked`; locked offers refused; restored fetches not started; `RESTORED_OFFER` / `RESTORED_FETCH`; `release <id>`; unit tests |
| 2 | Server `TransferTracker`, `TransferStore`, `ClientHandler` | `MY_TRANSFERS` on login; receiver left → waiting (deadline = start + TTL); sweeper rule for waiting-for-receiver |
| 3 | Java `Client.java` | collect both lists, compare, go / delete / report failed; handle `OFFER_DROPPED` / `OFFER_EXPIRED` for restored items; terminal messages; `/status` shows restored transfers |
| 4 | `mesh.html` | "waiting for receiver" label |
| 5 | Docs | peer-app protocol (new events, `release`), mesh-v2 (`MY_TRANSFERS`, receiver waiting), results after B3c in the findings doc |

---

## 5. Tests after B3c

Same setup and cases as the [findings](../problems/restart-sync-findings.md#test-setup):

| # | Case | Expected |
|---|---|---|
| 1 | Short blip | unchanged: continues, done on both sides |
| 2 | Sender offline, back | sender: "restored · resumed" → "done ✓"; receiver resumes |
| 3 | Receiver offline 1 min | server: **waiting for receiver**; receiver: "restored · resumed from X MB" → "done ✓"; counted once as done |
| 4 | Both offline | both print restored → done; server: done |
| 5 | Offline too long | expired, cleaned up; the side coming back late prints "old transfer closed: expired" |
| 6 | Receiver lost its data | receiver reports failed ("lost local data") at once; sender stops sharing at once |
| 7 | Sender's file changed | sender reports failed ("file changed") at once; receiver cancels instead of waiting |

Plus: delete a server pod during a waiting transfer; the mesh recovers from Redis.

---

## 6. Left for B3d

- Mesh "% kept" (R9): send `pct` with retrying / waiting.
- "already had it" instead of `done (none)`.
- Fade the offline peer's node on the mesh while its transfer is waiting.
