# Phase B3d: cleanup after the B3c tests

**Status:** ✅ done, merged with B3c (PR #6) · **Branch:** `phase-b3cd-cleanup`
**Images:** `shobhit3718/signaling-server:b3d`, `shobhit3718/dummy-client:b3d` (also `:latest`)

B3c (restart sync) worked in the EC2 tests, but the tests showed rough edges:
the first fetch almost always failed once, messages were unclear, a waiting
transfer looked broken, and some older to-do items were still open. B3d fixes
those, and adds handling for the server being down.

---

## What changed

### Step 1 – peer-app (`peer-app/src/blobs.rs`)

| Change | Why |
|---|---|
| Downloader connect timeout **1 s → 10 s** (`Downloader::new_with_opts`, `connection_pool::Options`) | iroh-blobs' default 1 s was shorter than finding + reaching the sender (~0.7 s DNS alone), so the **first try almost always failed** and the 5 s retry worked |
| Quick-retry counter resets when data moved since the last try | a long transfer that broke twice used to start at "retry 2/3" |
| `OFFER_DROPPED:<id>:<reason>\|<name>` | the client can say **which** file was dropped |
| Offers that expired while peer-app was off are reported (`OFFER_DROPPED … expired while peer-app was off`) | they used to disappear silently |

### Step 2 – Java client (`Client.java`)

**2a – messages**
- JOIN / LEFT line printed **first**, then "is back" and resume events (they used to come out in the wrong order).
- Dropped shares show the file name.
- Sender sees `admin2 left the chat — still sharing (up to N min)` and `admin2 is back`.
- Waiting transfers are shown as **🔒 locked**: `🔒 locked: 84% kept, waiting for admin (29 min left) — resumes when they're back`, also in `/status`.

**2b – input and connection**
- Input is trimmed; unknown `/commands` print `unknown command` instead of going out as chat; new `/help`.
- **Startup watchdog:** if peer-app does not report endpoint + relay within 60 s (or crashes), the client prints the reason (last 5 peer-app lines) and exits with code **4**, instead of logging in with `endpoint: null` or hanging forever. Child processes are killed too.
- **Reconnect give-up:** backoff 1 → 2 → 4 … 30 s as before, but after **10 min** offline (`RECONNECT_GIVE_UP_SECS`) the client exits with code **5**. Safe because peer-app keeps transfers on disk and B3c resumes them on the next start.

**2c – server lost**
- **Mesh lag:** the receiver sends its latest % right before `retrying` / `waiting` (progress is otherwise sent at most every 3 s, so the mesh showed less than was really kept).
- **Outbox:** final events (`done`, `failed`, `TRANSFER_END`) made while offline are kept and sent after the next login. Running receives are **re-announced** (current %, or waiting + time left).
- `⚠ server lost · N transfer(s) continue directly (peer-to-peer)` and, after login, `[sync] back online · in sync with the server (N result(s) from while it was away sent)`.
- **Server** (`TransferTracker.java`): the sweeper's **first** run is 3 min after the server starts (`START_GRACE_MS`). Without it, every transfer that was active during an outage longer than 2 min would be failed ("no response") before the clients could reconnect and report.

**2d – polish from the EC2 tests**
- Restart failures show the file name (from `OFFER_DROPPED`) instead of `transfer c0409cec`.
- `EVENT:PEER_REJECTED` and `EVENT:SHUTDOWN` are quiet events (no raw lines).
- `resuming from N MB` printed once, not on every quick retry.

### Step 4 – mesh (`mesh.html`)
- The user a transfer **waits for** is drawn faded and dashed, labelled `(offline)` (unless another transfer shows them active).
- Receiver already had the file (path `none`) → grey line "already had it", log `done (already had it)`; no more `path none` / `done (none)`.
- When the **mesh page itself** loses the server, it shows the last known state dimmed with "mesh offline · server unreachable" instead of marking every transfer "stalled".

**Client exit codes:** `3` username taken by another session · `4` peer-app never got ready · `5` server unreachable too long.

---

## Tests (EC2, k3s, 2 server pods, admin ↔ admin2 over the internet)

| # | Test | Result |
|---|---|---|
| T1 | First try works | ✅ 2 of 3 first-try; once "retrying 1/3" then success after 5 s |
| T2 | Input (spaces, typo command, `/help`, empty line) | ✅ |
| T3 | Sender closed at 84 % → retries → 🔒 locked → back → resume | ✅ mesh % = kept %, sender faded, JOIN first, sha256 match |
| T4 | Receiver closed at 71 % → sender "still sharing" → back → restore + resume | ✅ receiver faded, sha256 match |
| T5 | File changed while the sender was off | ✅ `saved share t5_500m.bin dropped: file changed…`, receiver told why, mesh "failed" |
| T6 | Server scaled to 0 during a 1 GB transfer | ✅ finished peer-to-peer, outbox sent after reconnect, mesh got `done`, sha256 match |
| T6b | Server down > 2 min while a transfer waits (start grace) | ⏭ not run (deployed, not live-tested) |
| T7 | Give-up with `RECONNECT_GIVE_UP_SECS=60` | ✅ exit 5 |
| B3c E1 | Plain reconnect does not re-run the restart comparison | ✅ (seen in T6) |
| B3c Case 4 | Hash from the B3c run | ✅ |

Local: `cargo test` 19 passed; `tools/blobs-test.sh sender` (retries count 1/2/3) and `changed` (`OFFER_DROPPED …|test.bin`) pass; startup watchdog tested with a crashing (`/bin/false`) and a hanging fake peer-app (exit 4 after 60 s).

---

## Known issues

| Issue | Notes |
|---|---|
| **First try sometimes still fails once** (1 in 3 on EC2) | Recovers by itself after 5 s. Not the timeout any more (failed after ~4 s). The logs show the laptop's relay connection dropping (`Lost connection to relay server: Ping timeout`) and the home relay switching `aps1-1` → `euc1-1` → `aps1-1` within 30 s, so a relay hiccup is likely. A slow address lookup right after start is another candidate. **Idea:** the server already sends the sender's relay URL in `FILE_REQUEST`; Java could pass it to peer-app as a hint **for the first fetch only**. Not for resumes: relay URLs change (seen above), and the lookup always has the current one. |
| Mesh % when the **receiver** is killed | Up to 3 s of progress is lost (a dying process can't report). Corrected on resume. |
| Finished records live 30 s in Redis | The D1 dashboard needs its own history / counters. |
| Server dies without closing the socket | The client notices after ≤ 45 s (read timeout); events written in that window can be lost. |
| "In sync" has no server ACK | It means "everything in the outbox was sent". |
| Give-up can overshoot | by up to one backoff wait (≤ 30 s). |
| Leftovers from the old B3d list | command list reprinted on every reconnect; `k8s-server.yaml` update. Parked in the roadmap. |

## Ideas that came up (parked)
- Auto-relaunch peer-app with hints when it fails at startup, and relaunch if it crashes mid-session.
- Duplicate / changed-file check before accepting → `docs/ideas/duplicate-file-check.md`.
