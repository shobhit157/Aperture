# Aperture Roadmap

> One place for all phases, in order. Details live in the linked docs.
> Update the status here whenever a step finishes.

**Legend:** ✅ done · 🔄 in progress · ⏳ next · 💡 idea

## Overview

```mermaid
flowchart LR
  R[Chat reconnect ✅] --> A[Phase A<br/>honest transfers 🔄] --> M[Mesh v2<br/>transfer events ⏳] --> B[Phase B<br/>iroh-blobs 💡] --> C[Phase C<br/>retry, limits, gossip 💡] --> D[Phase D<br/>iroh-docs 💡]
```

| # | Phase | Goal | Status | Details |
|---|---|---|---|---|
| 1 | Chat reconnect | Sessions survive drops; newest login wins; no zombies | ✅ | `docs/Chat-reconnect/` |
| 2 | **Phase A** | File transfer is correct and honest about failures | 🔄 final checks | `docs/future-plans/file-transfer-improvement-plan.md` |
| 3 | **Mesh v2** | One event type keyed by transfer ID, state machine, Redis pub/sub | ⏳ | `docs/future-plans/mesh-v2-transfer-events.md` |
| 4 | **Phase B** | iroh-blobs: verified chunks, resume, receiver fetches | 💡 | `file-transfer-improvement-plan.md` → Phase B |
| 5 | Phase C | Retry, limits, metrics, gossip experiments | 💡 | `file-transfer-improvement-plan.md` |
| 6 | Phase D | iroh-docs: shared incident log without a server | 💡 | `file-transfer-improvement-plan.md` |

---

## 1. Chat reconnect ✅

- [x] Steps 1–5: session IDs, PING/PONG, timeouts, supersede (same pod + cross pod), Redis presence with TTL
- [x] Process ownership: SUPERSEDED → client exits, peer-app stopped

## 2. Phase A: honest file transfer 🔄

Evidence: `docs/problems/evidence/` · Bugs: `docs/problems/file-transfer-bugs.md` · Protocol: `docs/peer-app-protocol.md`

- [x] **A1** one result per transfer; receiver replies OK/FAIL; sender errors visible (bugs 1–4)
- [x] **A2** safe saving: per-transfer files, sync before rename, clean names, header limit (5, 6, 8, 12)
- [x] **A3** BLAKE3 hash check (7)
- [x] **A4** progress throttled (11), stop if file changes, timing + addresses; bug 10 not a bug
- [x] Deployed (server + bots), admin ↔ bot (relay) and admin ↔ admin2 (direct) tested, hashes match
- [ ] **Step 0** live path on mesh: `paths_stream()`, ID in `TRANSFER_METRIC`, `livePath` — see mesh v2 doc, "Step 0"
- [ ] Failure test: delete bot mid-transfer → mesh shows failed, not counted
- [ ] Grafana: +1 per successful transfer
- [ ] Bot → admin transfer
- [ ] Docs: bug log, protocol doc, findings, runbook, tools README
- [ ] Merge `file-transfer-phase-a` → `main`

## 3. Mesh v2: transfer events ⏳

Details: `docs/future-plans/mesh-v2-transfer-events.md`

- [ ] One `TRANSFER_EVENT|id|state|path|pct|reason` message, always with the transfer ID
- [ ] Per-transfer state machine on the server (`started → moving → done / failed`, stalled as backup)
- [ ] Redis pub/sub between pods (instead of SNS/SQS for mesh)
- [ ] Deltas to the browser; finished lines stay ~10 s
- [ ] Prometheus metrics driven by the same state machine (one source of truth)
- [ ] Tests: state-machine unit tests, two parallel transfers same pair, two-pod update < 1 s

**Rule:** events describe **states only** (`started`, `path`, `progress`, `done`,
`failed`) — never "who pushes". Then Phase B (receiver fetches) reuses the same
events without redesign.

## 4. Phase B: iroh-blobs 💡

Details: `file-transfer-improvement-plan.md` → Phase B

- [ ] **B0** persistent identity (key file) + `Router`
- [ ] **B1** spike: share, fetch, kill and resume (separate small program)
- [ ] **B2** peer-app `share` / `fetch` commands
- [ ] **B3** Java: ticket in the offer, receiver fetches — reports via mesh v2 `TRANSFER_EVENT`
- [ ] **B4** test script: resume test + Phase A checks
- [ ] **B5** remove the old protocol

## 5. Phase C 💡

- [ ] Retry with backoff (e.g. one retry after connect timeout)
- [ ] Limits: max file size, max parallel transfers
- [ ] iroh-gossip experiment: broadcast an alert to all bots without the server

## 6. Phase D: iroh-docs 💡

- [ ] Check current iroh-docs status and API
- [ ] Shared incident log: bots write, one goes offline, catches up on return

---

## Side tracks (any time)

| Track | Status | Details |
|---|---|---|
| Why bots use the relay (same public IP, no hairpin, separate networks) | ✅ explained | `docs/learning-notes/finding-vs-reaching-peers.md` |
| Laptop ↔ EC2 peer test (real hole punching) | ⏳ | — |
| WSL mirrored networking / `hostNetwork` experiment | 💡 | same note |
| Elastic IP for EC2 (stop IP changing on restart) | 💡 | — |
| CI/CD (build + test on push) | 💡 | CI/CD plan doc |
| Dashboard (online users, transfer stats) | 💡 | `Aperture-Dashboard.md` |

## How to use this file

- Start each session here: pick the first unchecked item.
- New idea → add it as 💡 in the right phase (or side tracks), with details in its own doc.
- Finished → tick it, update the status icon, link the evidence.
