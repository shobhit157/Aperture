# Aperture Roadmap

> One place for all phases, in order. Details live in the linked docs.
> Update the status here whenever a step finishes.

**Legend:** ✅ done · 🔄 in progress · ⏳ next · 💡 planned · 💤 parked

## Overview

```mermaid
flowchart LR
  R[Chat reconnect ✅] --> A[Phase A<br/>honest transfers ✅] --> M[Mesh v2 ✅] --> B0[B0 identity ✅] --> B2[B2 blobs ✅] --> B3[B3 Java on blobs ⏳] --> D1[D1 dashboard] --> B4[B4 bots StatefulSet] --> D23[D2–D3 health + controls] --> B5[B5 remove META3] --> D4[D4 polish] --> CI[CI/CD 💡] --> U[Phase U<br/>user app 💡]
```

| # | Phase | Goal | Status | Details |
|---|---|---|---|---|
| 1 | Chat reconnect | Sessions survive drops; newest login wins; no zombies | ✅ | `docs/fixes/chat-reconnect/` |
| 2 | Phase A | File transfer is correct and honest about failures | ✅ PR #2 | `docs/future-plans/File-transfer-improvement-plan.md` |
| 3 | Mesh v2 | Transfer state in Redis, one event type per transfer ID, 1 send + 1 receive per client | ✅ PR #2 (scale tests in B4) | `docs/fixes/mesh-v2.md` |
| 4 | **Phase B** | iroh-blobs: verified chunks, resume, receiver fetches | 🔄 B0, B1, B2 done · **B3 next** | `File-transfer-improvement-plan.md` → Phase B |
| 5 | Dashboard | Live control plane: peers, mesh, transfers, health, safe controls, chat | 💡 designed | `docs/Dashboard and User App Design/control-plane-and-user-app.md` |
| 6 | CI/CD | Build + test on every push | 💡 | `docs/future-plans/CI-CD_plan.md` |
| 7 | Phase U | User app: local peer-app + browser screen, direct transfers with proof | 💡 designed | `docs/Dashboard and User App Design/control-plane-and-user-app.md` |

**Order:** dashboard steps are built as soon as their data exists:
B3 → D1 → B4 → D2–D3 → B5 → D4 → CI/CD → Phase U.

**Focus rule:** until CI/CD is done, only Phase B, Dashboard and CI/CD. Everything else is parked (see the end of this file).

---

## 1. Chat reconnect ✅

- [x] Steps 1–5: session IDs, PING/PONG, timeouts, supersede (same pod + cross pod), Redis presence with TTL
- [x] Process ownership: SUPERSEDED → client exits, peer-app stopped

## 2. Phase A: honest file transfer ✅

Evidence: `docs/problems/evidence/` · Bugs: `docs/problems/File-transfer-bugs.md` · Protocol: `docs/peer-app-protocol.md`

- [x] **A1** one result per transfer; receiver replies OK/FAIL; sender errors visible (bugs 1–4)
- [x] **A2** safe saving: per-transfer files, sync before rename, clean names, header limit (5, 6, 8, 12)
- [x] **A3** BLAKE3 hash check (7)
- [x] **A4** progress throttled (11), stop if file changes, timing + addresses; bug 10 not a bug
- [x] Deployed (server + bots); admin ↔ bot (relay) and admin ↔ admin2 (direct) tested, hashes match
- [x] Live path on the mesh (done as part of Mesh v2)
- [x] Merged `file-transfer-phase-a` → `main` (PR #2)

## 3. Mesh v2: transfer state in Redis ✅

Details: `docs/fixes/mesh-v2.md`

- [x] One `TRANSFER_EVENT|id=…|state=…|path=…|pct=…|reason=…` message, always with the transfer ID
- [x] Per-transfer state in Redis (atomic Lua), `mesh:changed` pub/sub between pods
- [x] Updates to the browser batched every 250 ms; full re-read every 10 s; finished lines fade after ~10 s
- [x] Grafana counts each transfer once (only when it becomes final)
- [x] Client limits: at most 1 sending + 1 receiving; extras wait in a queue
- [x] S6: old v1 mesh path removed
- [ ] Pending tests → done in **B4**: two files to the same peer, admin → bot, delete bot mid-transfer, 3 server pods, 20 bots `!send all all`, delete a server pod

## 4. Phase B: iroh-blobs 🔄

Details: `docs/future-plans/File-transfer-improvement-plan.md` → Phase B · Commands and events: `docs/peer-app-protocol.md` → Part 4

- [x] **B1** spike: share, fetch, resume, path, allow-list, speed → `docs/Experiments/iroh-blobs-phase-b1-spike.md`
- [x] **B0** persistent identity (key file in a data folder, or seed-derived key for bots) + one Iroh `Router` serving META3 (PR #3)
- [x] **B2a** `share` / `allow` / `fetch` on iroh-blobs beside META3; allow-list per peer + hash (PR #4)
- [x] **B2b-1** survive restarts: offers/fetches saved (`offers.json`, `fetches.json`), resume after restart or `kill -9`, clean shutdown on Ctrl+C / SIGTERM (exit fix for the stdin thread) (PR #4)
- [x] **B2b-2** wait smartly: 3 quick tries (5/10/20 s) → `FETCH_WAITING`; `retry <id>` / `retry all`; `cancel <id>`; shares and fetches expire after `PEER_TRANSFER_TTL_SECS` (default 30 min) (PR #4)
- [x] Tests: `tools/blobs-test.sh` — basic, receiver INT/KILL, sender, changed, wait, expire, cancel → **8/8 pass** (use `SIZE_MB=300` while the laptop disk is nearly full)
- [x] B2 binary (`8b386b…`) deployed to admin (`~/peer-app`) and bots (`shobhit3718/dummy-client:b2`); bots now reach the server by Service name (`signaling-server:5000`)
- [ ] **B3** Java: hash in the offer, allow on `PEER_INFO`, receiver fetches; server announces "sender back online" → client sends `retry`; mesh "waiting for sender"; configurable limits; clear error instead of `endpoint: null`
- [ ] **B4** tests + bots as StatefulSet with volumes and seeds; kill / restart / logout cases (`!logout`, `!login`); 20-bot scale test (also closes the Mesh v2 tests)
- [ ] **B5** remove META3 + one "Phase B code changes" doc

## 5. Dashboard 💡

Design + mockups: `docs/Dashboard and User App Design/control-plane-and-user-app.md`

- [ ] **D1** (after B3) page + WebSocket: peers, live mesh, transfers, events, chat
- [ ] **D2** P2P health + server health (+ Grafana link)
- [ ] **D3** (after B4) controls: log bots out/in, scale (RBAC, least privilege), "bot A sends file to B"
- [ ] **D4** polish: phone width, dark mode, empty states, errors
- [ ] Optional view: 3D mesh → `docs/future-plans/Mesh-3D-view-plan.md`
- [ ] Optional view: Kademlia ID space (simulated) → `docs/future-plans/Kademlia-DHT-id-space-mesh.md`

## 6. CI/CD 💡

Details: `docs/future-plans/CI-CD_plan.md`

- [ ] Build + test on push (GitHub Actions, free for public repos)
- [ ] Build and push Docker images (later: multi-arch amd64 + arm64)
- [ ] Deploy to k3s

## 7. Phase U: user app 💡

Design + mockups: `docs/Dashboard and User App Design/control-plane-and-user-app.md`

- [ ] **U1** web server inside peer-app (`localhost` only) + page: login, online list, chat, send/receive
- [ ] **U2** "Where your file goes" mesh per user
- [ ] **U3** proof card (+ bytes per path, server byte count)
- [ ] **U4** "Check it yourself" panel
- [ ] **U5** desktop app (Tauri)

---

## Running costs (AWS)

- Account on the **paid plan** since 2026-10-10 (Free Plan ended); credits **~$101 left, valid until 2027-04-12**.
- Main cost: EC2 `m7i-flex.large` (~$0.10 per running hour) + disk + public IPv4. **Stop the instance after every session.**
- Budgets: `monthly-safety-budget` ($5, real charges) and `credit-burn` ($30, usage before credits).
- EC2 gets a **new public IP after every start**: update admin's server IP and the mesh URL. Bots use the Service name, so they don't care.

---

## Parked 💤

Not touched until Phase B, Dashboard and CI/CD are done.

| Item | Details |
|---|---|
| Phase C: limits, richer metrics, iroh-gossip experiment | `File-transfer-improvement-plan.md` → Phase C |
| Phase D: iroh-docs shared incident log | `File-transfer-improvement-plan.md` → Phase D |
| Swarm distribution (server as tracker first, iroh-docs later) | `docs/future-plans/swarm-distribution-plan.md` |
| Own DHT (Kademlia) | `docs/future-plans/dht-future-plan.md` |
| Self-hosted relay | `docs/future-plans/self-hosted-relay-plan.md` |
| Chat session resumption | `docs/future-plans/Chat-reconnect/Chat-Session-resumption-plan.md` |
| Light bots (peer-app only) + simulated regions (`tc netem`, NAT namespaces) for 200-bot tests | — |
| Multi-region bots on free/cheap VMs (Oracle Always Free is ARM: needs arm64 images) | — |
| Laptop ↔ EC2 peer test (real hole punching) | — |
| WSL mirrored networking / `hostNetwork` experiment | `docs/learning-notes/Kademlia-DHT_routing_algorithm/finding-vs-reaching-peers.md` |
| Elastic IP for EC2 (less needed now: bots use the Service name) | — |
| Read iroh-blobs error codes (permission / limit) instead of the Downloader's "Unable to download" | — |

Done side track: why bots use the relay (same public IP, no hairpin NAT) → explained in `finding-vs-reaching-peers.md`.

**Next project after Aperture:** Island mode, a region that loses internet works as its own local network (kept in the `decentralization` repo).

---

## How to use this file

- Start each session here: pick the first unchecked item.
- New idea → add it to **Parked** with details in its own doc (unless it belongs to the current phase).
- Finished → tick it, update the status icon, link the evidence.
