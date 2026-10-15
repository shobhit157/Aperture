# Restart Sync: Findings (before B3c)

**Status:** Found 15 Oct 2026 on `main` (c51a00e, B3b) · fix: [B3c restart sync](../fixes/PhaseB3c-restart-sync-paln.md)
Related: [File transfer bugs](File-transfer-bugs.md) · [Mesh v2](../fixes/mesh-v2.md) · [peer-app protocol](../peer-app-protocol.md)

---

## Summary

After a restart, **peer-app remembers** its transfers (on disk), **the Java client forgets** them (memory only), and **the server uses different time rules** than peer-app. The file usually arrives and is always correct, but the terminals, the mesh and Grafana tell the wrong story, and some dead transfers wait 30 min for nothing.

| Part | Remembers after restart? | Gives a receiver how long? |
|---|---|---|
| peer-app (`offers.json`, `fetches.json`) | ✅ yes | 30 min (TTL) |
| Java client (`byId` map) | ❌ no | — |
| Server (Redis, mesh, Grafana) | ✅ yes | **30 s**, then "failed · peer left" |

The same endpoint ID (B0) only means the same *name on the network*. It does not bring back Java's list.

---

## Test setup

- **admin** = sender, **admin2** = receiver, both on one laptop (WSL), separate folders (`~/b3c-tests/admin`, `~/b3c-tests/admin2`) and data folders (`~/.aperture/admin`, `~/.aperture/admin2`)
- Signaling server on k3s (EC2, 2 pods), clients via NodePort 30000; mesh page on EC2:8080
- Build: `main` c51a00e (B3b), peer-app from 11 Oct
- One random 500 MB file per case (`case1.bin` … `case7.bin`), checked with `sha256sum`
- "Offline" = Ctrl+C on the client (peer-app gets SIGTERM, saves state, `EVENT:SHUTDOWN:signal`)

---

## Results

| # | Case | File | Sender terminal | Receiver terminal | Server / mesh |
|---|---|---|---|---|---|
| 1 | Short blip (sender frozen 20 s) | ✅ hash ✅ | ✅ done | ✅ done | ✅ direct → relay → direct, done |
| 2 | Sender offline, back (B3b test C) | ✅ | ❌ silent | ✅ resumed | ✅ waiting → done |
| 3 | Receiver offline 1 min | ✅ hash ✅ | ✅ done | ❌ silent | ❌ failed · peer left |
| 4 | Both offline | ✅ hash ✅ | ❌ silent | ❌ silent | ❌ failed · peer left |
| 5 | Offline too long (TTL 120 s) | ✅ cleaned up | ❌ silent | ✅ expired | ✅ failed · expired |
| 6 | Receiver lost its data | skipped: result clear (no file); test after B3c | | | |
| 7 | Sender's file changed while offline | ✅ never a wrong file | ❌ silent | ❌ waits until TTL | ❌ waiting, "0% kept" |

### Case 1: short blip

Sender's peer-app frozen with `kill -STOP` for 20 s at ~25%.

| Time | Event |
|---|---|
| 17:45:04 | started, direct |
| 17:45:22 | path direct → relay |
| 17:45:27 | path relay → direct |
| 17:45:37 | done (direct) on both sides, hash ✅ |

The connection never broke: 20 s is shorter than iroh's idle timeout (~30 s). iroh switched to the relay and back; no quick tries were needed.

### Case 2: sender offline, back

Tested in B3b (test C, and a sender down 8 min): the receiver waits, then resumes and finishes. After its restart the **sender terminal printed nothing** about the transfer.

### Case 3: receiver offline 1 min

| Time | Event |
|---|---|
| 16:31:54 | started, direct |
| ~16:32:00 | admin2 Ctrl+C at 50% |
| 16:32:30 | server: **failed · peer left (admin2)**, counted as failed |
| ~16:33 | admin2 restarts: `RESTORED:0:1`, the fetch resumes **by itself** |
| ~16:33 | admin: `done OK (direct)` · admin2: **nothing** · mesh: **failed** |

File 524288000 bytes, hash ✅ (`74edd214…5810`).

### Case 4: both offline

| Time | Event |
|---|---|
| 16:42:31 | started |
| 16:42:39 | admin closed first; admin2 at 0%: `retrying 1/3 … Unable to download` |
| | admin2 closed during `retrying 2/3` |
| 16:43:19 | server: **failed · peer left (admin2)** |
| restart | admin: `RESTORED:1:0` (offer back) · admin2: `RESTORED:0:1` (fetch back) |
| 16:45 | file arrived, hash ✅ (`24b76c39…3ad8`); `offers.json` / `fetches.json` empty |

The receiver pulled the file by the sender's endpoint ID; the sender's restored allow-list let it in. **Both terminals silent**, server **failed**.

### Case 5: offline too long (TTL 120 s)

| Time | Event |
|---|---|
| 17:59:53 | started (`PEER_TRANSFER_TTL_SECS=120` on both) |
| ~17:59:56 | admin Ctrl+C at 19% (97.8 MB); admin2 retrying 1/3 → 3/3 |
| 18:00:36 | admin2: waiting for admin |
| 18:01:57 | admin2: **FAILED expired after 120s**; mesh: failed · expired |
| restart | admin: `RESTORED:0:0` (old offer not restored); state files empty; no partial file |

Clean end ✅. The sender never printed that its send expired. The old case 7 fetch was cleaned up at the same start ✅.

### Case 7: sender's file changed while offline

| Time | Event |
|---|---|
| 17:49:44 | started, direct |
| 17:49:46 | admin Ctrl+C; admin2 retrying 1/3 → 3/3 (73.5 MB kept) |
| 17:50:26 | admin2: waiting for admin (30 min) |
| | file changed: `echo x >> case7.bin` |
| restart | admin: `RESTORED:0:0`, offer **dropped** (file changed); nothing printed |
| 17:52:32 | "admin is back" → admin2 resumes → admin's peer-app: `PEER_REJECTED` ×5 |
| 17:53:11 | admin2 back to **waiting (27 min left)**, with no way to finish |

The changed file was **never served** ✅, but nobody told the receiver.

---

## Findings

| # | Finding | Seen in | Effect |
|---|---|---|---|
| R1 | **The transfer layer is solid.** peer-app resumes across restarts and always delivers the right file (BLAKE3 per chunk; offer dropped if the file changed; expired data cleaned up) | 1, 3, 4, 5, 7 | ✅ good |
| R2 | **Java forgets its transfers after a restart.** `byId` is memory only; events for unknown IDs (`SERVED`, `FETCH_*`, `RESUMED`, `OFFER_DROPPED`, `OFFER_EXPIRED`, `TRANSFER_END`) are ignored without a message | 2, 3, 4, 5, 7 | terminals silent in 5 of 7 cases; `/status` empty |
| R3 | **peer-app acts alone.** Restored fetches start by themselves and restored offers serve at once, without Java or the server knowing | 3, 4 | a transfer finishes that the server already marked failed |
| R4 | **Server and peer-app disagree on time.** A receiver gets 30 s on the server ("peer left") but 30 min in peer-app | 3, 4 | mesh and Grafana say **failed** while the file arrives; counted as failed, not done |
| R5 | **Different answers on each side.** In case 3: sender "done OK", receiver silent, server "failed" | 3 | nobody can trust one view |
| R6 | **Nobody reports a dead transfer early.** A dropped offer or lost data leaves the other side waiting until the TTL | 7 (6) | receiver waits up to 30 min for nothing |
| R7 | **The receiver retries every time the sender rejoins**, even when the offer is gone; the sender rejects each try (`PEER_REJECTED`) | 7 | useless traffic and noisy logs |
| R8 | **The sender is never told** that its offer expired or was dropped | 5, 7 | sender thinks nothing happened |
| R9 | **Mesh "% kept" is wrong** while waiting: shows 0% while the terminal shows 14–19% (progress is not sent with retrying / waiting) | 5, 7 | misleading mesh (already on the B3d list) |
| R10 | **"N min left" is rounded up**: "2 min left" when ~1 min 20 s was left | 5 | small; fine as long as it never shows 0 too early |
| R11 | **A short freeze doesn't break the connection** (iroh waits ~30 s and switches to the relay), so quick tries only start after longer outages | 1 | ✅ good; to test quick tries, freeze longer than ~30 s |
| R12 | **The sender cleans up anyway**: on `SERVED` for an unknown ID, Java still sends `unshare` | 4 | ✅ good; no offer left behind |

---

## Root causes

1. **Java keeps transfer state only in memory** and has no way to rebuild it.
2. **peer-app restores and continues on its own** instead of waiting for Java and the server.
3. **The server's "receiver left" rule is from Mesh v2** (before blobs), when a broken transfer couldn't resume; with blobs it can, so 30 s is outdated.
4. **No one compares** what peer-app has with what the server has, so missing or dead transfers are never noticed.
