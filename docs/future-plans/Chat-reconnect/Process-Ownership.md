# Process Ownership — Same Username, Two Processes

Status: **Planned.** Part of the Chat Reconnect Redesign (completes step 4's "older is told to stop" decision).

---

## The problem

Two separate client processes using the same username (e.g. two `admin` windows) take the name from each other forever.

Observed during step 4 testing (pods pinned with port-forward):

```text
f989d0ad → superseded by 1baab78f (13s)
1baab78f → superseded by b4c22ec6 (13s)
b4c22ec6 → superseded by 1f23acf3 (13s)
1f23acf3 → superseded by a7c4e6d7 (11s)
...
```

Why: the server correctly tells the older session `SUPERSEDED` and closes it. The client doesn't recognize `SUPERSEDED`, treats the close as an ordinary disconnect, and step 3's reconnect loop brings it straight back as the newest session, which supersedes the other window.

The server side already works. **The client doesn't obey.**

---

## Decisions

| Question | Decision |
|---|---|
| Who keeps the name when a second process uses it? | **Newest wins.** The new process takes the name. |
| What does the losing process do? | **Exits cleanly.** Prints why, stops `peer-app`, ends the process. No reconnect. |

Rejected alternative: *first wins* (`NAME_IN_USE` for the newcomer). It needs a per-process ID, a join-or-reject Redis script and the Redis expiry, and can lock a user out for ~45–60s after a crash. It can be revisited as part of the session resumption plan, where the resume token identifies the process.

---

## Key guarantee: a reconnect can never receive SUPERSEDED

A single process always closes its old socket (step 3's `finally`) **before** opening a new one. So:

- The old connection of the **same** process is already dead. `SUPERSEDED` sent to it reaches no one.
- `SUPERSEDED` can only reach a process whose connection is **still alive**, which means a **different** process.

So exiting on `SUPERSEDED` never kills a normal reconnecting client.

The server sends `SUPERSEDED` only on positive proof that a different session owns the name. A missing record or a Redis error never triggers it (step 4).

---

## Design

### Server

**No change.** Already built and verified in step 4:

- Same pod: the new session's join supersedes the old one immediately, after registering.
- Other pod: the old session's 10s ownership check notices and supersedes itself.
- Both: send `SUPERSEDED`, then `shutdownOutput()`, then end after a 5s grace. Cleanup is `wasOwner=false`, so no LEAVE is announced.

### Client (`Client.java` only)

1. **Recognize `SUPERSEDED`** in the read loop and throw a dedicated `SupersededException`.
2. **Catch it in `main` before the generic `IOException`**, so the reconnect loop is never entered.
3. **Exit cleanly:**
   - Print: `Username '<name>' is now in use by another session. Exiting.`
   - Stop `peer-app` (`destroy()`, wait briefly, `destroyForcibly()` if needed), so no orphaned process is left.
   - Exit with a distinct code (e.g. `3`), so logs and Kubernetes show *why* it stopped.

---

## Expected behavior

| Case | Result |
|---|---|
| Second window, same pod | First window exits **immediately**; second keeps the name |
| Second window, other pod | First window exits within **~10–15s** |
| Same process reconnecting (WiFi drop) | Unaffected: never sees `SUPERSEDED` |
| Ctrl+C, then restart | Unaffected: normal new session |
| Crash (`kill -9`), then restart | New process takes the name immediately; old zombie cleaned up by step 4 |
| Bots | Unaffected in practice: each pod has a unique name (`HOSTNAME`) |

---

## Rollout

1. Build the client change.
2. Rebuild **both** the admin JAR and the bot image.
3. Server: no rebuild needed.

There's no ordering risk this time: the server already sends `SUPERSEDED`, and only the client changes.

---

## Test plan

| Test | Pass condition |
|---|---|
| Two windows, same pod (port-forward both to one pod) | Window 1 prints the message and exits at once; server log shows `superseded`, `wasOwner=false`, no LEAVE; **no further admin sessions** (no back-and-forth) |
| Two windows, different pods (port-forward to each pod) | Same as above, within ~15s |
| `peer-app` cleanup | After window 1 exits, `ps aux \| grep peer` shows only window 2's `peer-app` |
| WiFi drop on a single window (regression) | Reconnects normally, never prints the superseded message |
| Window 2 after takeover | Chat broadcast and one small file transfer succeed |

---

## Updates to the reconnect plan

- Decisions table: "Same username twice → **newest wins; the older process is told to stop and exits.**"
- Step 4: mark "older is told to stop" as fully built once tests pass.

## Out of scope

- First-wins / `NAME_IN_USE` (see session resumption plan, open decisions)
- Session resumption itself
