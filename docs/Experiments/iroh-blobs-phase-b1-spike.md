# Phase B · B1 spike: iroh-blobs "share, then fetch"

**Date:** 2026-10-06
**Result:** ✅ all 7 questions answered. Phase B goes ahead with iroh-blobs.
**Plan:** [file-transfer improvement plan → Phase B](../../future-plans/File-transfer-improvement-plan.md)

## Setup

| Item | Value |
|---|---|
| Program | `blobs-spike`: separate small Rust program (not peer-app), commands `share` and `fetch` |
| Versions | `iroh = "=1.0.2"`, `iroh-blobs = "=0.103.1"` (pinned; iroh-blobs is still 0.x) |
| Store | `FsStore` (on disk) on both sides |
| Machines | two WSL terminals on the same laptop (same pair as admin ↔ admin2) |
| Files | 300 MB and 400 MB from `/dev/urandom` |
| Path check | `endpoint.remote_info(peer)` every 500 ms, active address → relay / direct |
| Access control | provider events with `ConnectMode::Intercept` + `--allow <endpoint id>` |

```text
blobs-spike share <file> [--reference] [--allow <endpoint_id>]
blobs-spike fetch <hash> <provider_endpoint_id> <out_file>
```

## Results

| # | Question | Result |
|---|---|---|
| Q1 | Does share + fetch work? | ✅ 300 MB and 400 MB fetched; `sha256sum` matches the original |
| Q2 | Does a stopped fetch resume? | ✅ after a **clean** stop: run 2 started at exactly the bytes run 1 had (72,728,576 of 419,430,400) and fetched only the rest. ⚠️ After a **hard kill**, run 2 started from 0 (see below) |
| Q3 | Can we still see relay vs direct? | ✅ both sides: `unknown → relay → direct+relay` within ~1 s |
| Q4 | Can the sharer reject peers it didn't allow? | ✅ `PEER_REJECTED`; fetcher failed in 0.9 s with 0 bytes and no file |
| Q5 | What progress events exist? | `TryProvider`, `Progress(bytes)` (cumulative, **includes bytes already on disk**), `ProviderFailed`, `PartComplete`, `Error`, `DownloadError` |
| Q6 | Cost of sharing a file | `Copy` (copy into store): 4.8 s, and once **102 s**, for 300 MB. `TryReference` (hash in place): **0.8–1.1 s for 400 MB** |
| Q7 | Speed vs META3 (same pair) | **~80–95 MB/s** (300 MB in 3.5 s; resumed 331 MB in 3.5 s) vs META3 ~37 MB/s one way and ~7–11 MB/s the other |

### Resume, step by step

```text
run 1 (stopped by timeout → Ctrl+C handled → store.shutdown())
SPIKE:ALREADY_HAVE:bytes=0
SPIKE:PROGRESS:bytes=69173248 t_ms=1596
SPIKE:INTERRUPTED after 1653 ms, 72728576 bytes this run, saving partial data

run 2 (same command)
SPIKE:ALREADY_HAVE:bytes=72728576
SPIKE:PROGRESS:bytes=72728576 t_ms=535      ← starts where run 1 stopped
SPIKE:PART_COMPLETE
sha256: original = fetched ✅
```

### What went wrong first (and why)

| Attempt | What happened | Cause |
|---|---|---|
| Stop after 1.5 s | Nothing saved | Stopped before any data arrived (connecting takes ~0.5–1 s) |
| Stop after 3.5 s, no Ctrl+C handling | Run 2 started from 0, although run 1 had received ~70+ MB | `FsStore` writes its "which chunks do I have" records in **batches**; a sudden kill skips the final save. `store.shutdown()` saves it |
| 2 GB file with `Copy` | Very slow, then stopped | Copying 2 GB into the store on a busy WSL disk; `TryReference` avoids the copy |

## Decisions for Phase B

1. **Use iroh-blobs 0.103.1**, pinned exactly; check release notes before any upgrade.
2. **Share with `TryReference`**: no copy, about 1 s even for large files. If the file changes later, verification fails, so a changed file can never be delivered as correct.
3. **The receiver pulls by hash.** The offer carries the hash; the server already knows endpoint IDs.
4. **Allow-list per offer:** after `PEER_INFO`, the sender allows exactly that receiver's endpoint ID. Everyone else is rejected (replaces bug 9's risk).
5. **Path for the mesh:** `endpoint.remote_info(peer)`, polled; `direct` anywhere in the active list → green.
6. **Always shut the store down cleanly** (`store.shutdown()`) on every exit path of peer-app, or resume after a restart is lost.
7. **Progress:** the counter is cumulative (includes resumed bytes), so `pct = bytes / size` works directly.

## Not tested yet

- Two different machines or networks (only WSL ↔ WSL on one laptop).
- WSL ↔ bot pod (relay only, ~0.4 MB/s with META3).
- A real network drop while the process keeps running (expected to continue, because the store stays open).
- Disk use on the receiver: store + exported file = 2× the file size. Decide whether to delete the blob after export, or keep it to serve others (swarm).
- Persistent identity (B0): endpoint IDs still change on every start.

## Spike fixes to remember

- The `bytes_this_run` label is wrong: the progress counter includes bytes already on disk. Real bytes this run = final − `ALREADY_HAVE`.
