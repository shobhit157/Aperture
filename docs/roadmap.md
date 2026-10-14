# Aperture Roadmap

> One place for all phases, in order. Details live in the linked docs.
> Update the status here whenever a step finishes.

**Legend:** ✅ done · 🔄 in progress · ⏳ next · 💡 planned · 💤 parked

## Overview

```mermaid
flowchart LR
  R[Chat reconnect ✅] --> A[Phase A<br/>honest transfers ✅] --> M[Mesh v2 ✅] --> B0[B0 identity ✅] --> B2[B2 blobs ✅] --> B3[B3 Java on blobs 🔄<br/>B3a ✅ B3b ✅] --> D1[D1 dashboard] --> B4[B4 bots StatefulSet] --> D23[D2–D3 health + controls] --> B5[B5 remove META3] --> D4[D4 polish] --> CI[CI/CD 💡] --> U[Phase U<br/>user app 💡]
```

| # | Phase | Goal | Status | Details |
|---|---|---|---|---|
| 1 | Chat reconnect | Sessions survive drops; newest login wins; no zombies | ✅ | `docs/fixes/chat-reconnect/` |
| 2 | Phase A | File transfer is correct and honest about failures | ✅ PR #2 | `docs/future-plans/File-transfer-improvement-plan.md` |
| 3 | Mesh v2 | Transfer state in Redis, one event type per transfer ID, send/receive limits per client | ✅ PR #2 (scale tests in B4) | `docs/fixes/mesh-v2.md` |
| 4 | **Phase B** | iroh-blobs: verified chunks, resume, receiver fetches | 🔄 B0, B1, B2, B3a, B3b done (PR #5) · **B3c next** | `File-transfer-improvement-plan.md` → Phase B |
| 5 | Dashboard | Live control plane: peers, mesh, transfers, health, safe controls, chat | 💡 designed | `docs/Dashboard and User App Design/control-plane-and-user-app.md` |
| 6 | CI/CD | Build + test on every push | 💡 | `docs/future-plans/CI-CD_plan.md` |
| 7 | Phase U | User app: local peer-app + browser screen, direct transfers with proof | 💡 designed | `docs/Dashboard and User App Design/control-plane-and-user-app.md` |

**Order:** dashboard steps are built as soon as their data exists:
B3 (c, d) → D1 → B4 → D2–D3 → B5 → D4 → CI/CD → Phase U.

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
- [x] Client limits: at most 1 sending + 1 receiving; extras wait in a queue (B3a: now `MAX_SENDS` / `MAX_RECEIVES`, default 2 + 2)
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
- [x] **B3a** Java on blobs: sender `share` → `FILE_REQUEST` with hash (server adds sender endpoint) → `FILE_ACCEPT` → `PEER_INFO` → `allow` → `FILE_READY` → receiver `fetch`; `TRANSFER_PROTOCOL=blobs|meta3`; `MAX_SENDS` / `MAX_RECEIVES` (default 2 + 2); sender's slot freed after `FILE_READY` (PR #5)
- [x] **B3b** sender drops mid-transfer → receiver reports `retrying` (mesh "reconnecting n/3") → `waiting` (mesh "waiting · N min left", slot freed, chunks kept) → sender back (JOIN / ONLINE list) → `retry` → resumes from the same %; never back → expires after the TTL, line vanishes. Same-file fix: receiver sends `TRANSFER_END … done`, sender unshares (no more stuck `serving`). Server: sender leaving no longer fails the transfer; sweeper skips `waiting` until its deadline (PR #5)
- [x] B3 tests (admin ↔ admin2, images `:b3b`): basic, same file twice, sender drop + resume (71%, 58%), expiry (TTL 120 s), resume **across two server pods** → all pass
- [ ] **B3c** restart cleanup: Java and peer-app agree after a restart (peer-app restores offers/fetches that Java no longer knows → receiver's "done" never reported, sweeper marks it failed)
- [ ] **B3d** small fixes: `%` in `retrying` / `waiting` events (mesh showed 33 % kept, real 64 %); "already had it" instead of `done (none)`; fade the sender's node while `waiting`; trim input; unknown `/…` commands; clear error instead of `endpoint: null`; better `/status`; command list not reprinted on every reconnect; reconnect backoff + give up after N tries; `k8s-dummy-client.yaml` out of git; `k8s-server.yaml` updated; docs/README housekeeping (decision 003 → 006, learning notes)
- [ ] **B4** tests + bots as StatefulSet with volumes and seeds; kill / restart / logout cases (`!logout`, `!login`); resume while both receive slots are busy; 20-bot scale test (also closes the Mesh v2 tests). Logout design (one StatefulSet per bot vs reverse order, logout = scale down) decided at the start of B4
- [ ] **B5** remove META3 + one "Phase B code changes" doc

## 5. Dashboard 💡

Design + mockups: `docs/Dashboard and User App Design/control-plane-and-user-app.md`

- [ ] **D1** (after B3) page + WebSocket: peers (online / offline / waiting), live mesh (offline peers faded), transfers, events, chat
- [ ] **D2** P2P health + server health (+ Grafana link)
- [ ] **D3** (after B4) controls: log bots out/in, scale (RBAC, least privilege), test transfer with file name + size (random content, "same file again" option), admin chat with bots
- [ ] **D4** polish: phone width, dark mode, empty states, errors
- [ ] Extras (D2–D4): transfer timeline, relay → direct switches, slots/queue per peer, EC2 uptime timer, running image/commit, pod resources, chaos buttons
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
- Main cost: EC2 `m7i-flex.large` (~$0.10 per running hour) + disk + public IPv4. **Stop the clients first, then stop the instance after every session.**
- Budgets: `monthly-safety-budget` ($5, real charges) and `credit-burn` ($30, usage before credits).
- EC2 gets a **new public IP after every start**: update admin's server IP and the mesh URL. Bots use the Service name, so they don't care.

---

## Testing tips

- **Pick the server pod for a client** (cross-pod tests): one SSH tunnel that also runs the port-forwards, then clients connect to `localhost 5001` / `5002`:
  `ssh -i <key>.pem -o ServerAliveInterval=30 -L 5001:localhost:5001 -L 5002:localhost:5002 ubuntu@<IP> "export KUBECONFIG=\$HOME/.kube/config; kubectl port-forward pod/<pod-a> 5001:5000 & kubectl port-forward pod/<pod-b> 5002:5000 & wait"`
- **Which pod a client is on:** `kubectl logs --timestamps <pod> | grep -E "SESSION\] (start|end)"` (current = a start without an end)
- **Expiry test without waiting 30 min:** start the receiver with `PEER_TRANSFER_TTL_SECS=120`
- **Same file twice:** only "instant" within ~1 min (blob store GC runs every 60 s); use fresh random files (`head -c 500M /dev/urandom > f.bin`) otherwise
- **ISP IPv4 down (IPv6 still works):** Cloudflare WARP gives IPv4 back through IPv6

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
| EC2 IPv6 address (free, stays the same after stop/start; needs socat for NodePorts + WSL mirrored mode) | — |
| Read iroh-blobs error codes (permission / limit) instead of the Downloader's "Unable to download" | — |
| First fetch of a just-received hash sometimes fails once ("Unable to download"), retry 1/3 succeeds — maybe GC race | — |
| Skip peer-app's quick retries when the server says the sender left (needs a "stop retrying, keep data" command) | — |
| Max files a sender serves at once (today unlimited) | — |

Done side track: why bots use the relay (same public IP, no hairpin NAT) → explained in `finding-vs-reaching-peers.md`.

**Next project after Aperture:** Island mode, a region that loses internet works as its own local network (kept in the `decentralization` repo).

---

## How to use this file

- Start each session here: pick the first unchecked item.
- New idea → add it to **Parked** with details in its own doc (unless it belongs to the current phase).
- Finished → tick it, update the status icon, link the evidence.
- New idea → add it to **Parked** with details in its own doc (unless it belongs to the current phase).
- Finished → tick it, update the status icon, link the evidence.
