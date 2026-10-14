# Aperture: Control Plane & User App Design

Status: design only (no code yet) · mockups updated Oct 2026 (v2: B3b states)
Build order: B3 (✅ B3a + B3b · B3c/B3d next) → D1 → B4 → D2–D3 → B5 → D4 → CI/CD → Phase U

---

## 1. Overview

Aperture gets two screens. Both use the same peer-app, the same signaling server and the same events, but they are for different people.

| | Control plane (dashboard) | User app |
|---|---|---|
| Who | me (operator), demo videos | any user |
| Runs where | page served by the Java server (k3s) | user's own laptop, `localhost:8080` |
| Shows | the whole system: all peers, all transfers, server health | only my chats, my files, my path |
| Can do | log bots out/in, scale bots, make a bot send a file, chat | chat, send files directly, check the path |
| Login | none (port-forward or one password) | username + key from B0 (no accounts) |

```
                  ┌──────────── signaling server (Java, k3s) ────────────┐
                  │  who's online · chat · transfer info · events        │
                  └───▲──────────────▲──────────────────▲────────────────┘
                      │ WebSocket    │                  │
          ┌───────────┴───┐   ┌──────┴──────┐    ┌──────┴──────┐
          │ Control plane │   │  user1 app  │    │  bot-3      │
          │ (my browser)  │   │ browser ↔   │    │ Java client │
          └───────────────┘   │ peer-app    │    │ + peer-app  │
                              └──────┬──────┘    └──────┬──────┘
                                     └── files: DIRECT P2P (blobs) ──┘
```

The server never carries file data. Files go peer to peer (direct, or through the n0 relay, encrypted).

### Colours and line styles (same on both screens)

| Line | Meaning |
|---|---|
| Blue solid | direct (file goes laptop to laptop) |
| Orange dashed | relay (encrypted; the relay can't read it) |
| Amber dotted | reconnecting n/3 (quick tries) |
| Purple dashed | waiting for sender · N min left |
| Grey dotted | signaling only (server: chat, who's online) |
| Faded, dotted circle | peer offline |

---

## 2. Is this possible? Yes. What we already have

Most of the hard parts are already built and tested.

| Needed | Status |
|---|---|
| P2P connection between peers (Iroh, direct + relay fallback) | ✅ done (Phase A) |
| Same ID on every start (key file / seed) | ✅ done (B0) |
| File transfer with hash check (iroh-blobs share / allow / fetch) | ✅ done (B2a) |
| Survive restarts and crashes, resume from where it stopped | ✅ done (B2b-1) |
| Quick tries, waiting, `retry`, `cancel`, 30-min expiry | ✅ done (B2b-2) |
| Reports path (direct/relay), speed, fingerprint | ✅ done (`CONNECTION_PATH`, `TRANSFER_PATH`, `TRANSFER_TIMING`, `FILE_HASH`) |
| Signaling server: who's online, chat, Redis, SNS/SQS, on k3s | ✅ done |
| Prometheus + Grafana | ✅ done |
| Live mesh view | ✅ done (`mesh.html`, incl. reconnecting / waiting since B3b) |
| Java uses blobs instead of META3 | ✅ done (B3a + B3b, PR #5) · ⏳ B3c restart cleanup, B3d small fixes |
| Bots with stable names + logout/login | ⏳ B4 |
| Dashboard page + controls | 🆕 D1–D4 |
| Web server inside peer-app + user page | 🆕 U1 |
| Bytes-per-path counter, server byte count | 🆕 U3 (small) |
| Desktop installers (Tauri) | 🆕 U5 |

The new work is mostly screens and connecting existing pieces, not new networking.

---

## 3. Control plane (dashboard)

![Control plane dashboard mockup](images/dashboard-v2.png)

*Mockup, example data. KPI row on top; server in the middle of the mesh (grey dotted = signaling only); blue = direct, orange dashed = relay, amber dotted = reconnecting, purple dashed = waiting for sender; offline peers faded (bot-7 shows "offline · others waiting"). Yellow dashed = controls (scale, test transfer, kill sender mid-transfer).*

### 3.1 Goal
One live screen to watch the whole system and run the B4 tests (log a bot out, scale, test transfer) without kubectl, plus chat and "make a bot send a file" for demos.

### 3.2 Decisions

| Question | Decision |
|---|---|
| Scope | watch everything + safe controls + chat + bot sends file |
| Tech | plain HTML + JS page served by the Java server; live data over WebSocket |
| History graphs | stay in Grafana (linked from the page) |
| mesh.html | becomes the "Live mesh" panel |
| Access | only me: `kubectl port-forward` or one shared password; not public |
| Not included | uploading files from the browser, restarting pods, editing settings (these stay in kubectl / config) |

Why no browser upload: a browser can't run peer-app, so an uploaded file would have to pass through a helper on the server. That breaks "the server never touches file data". Bots and users send their own files instead.

### 3.3 Options considered

| | 1. Watch + few controls (chosen) | 2. Watch only | 3. Full control |
|---|---|---|---|
| See everything live | ✔ | ✔ | ✔ |
| Log bots out/in, scale, test transfer | ✔ | — | ✔ |
| Chat, bot sends file | ✔ (added) | — | ✔ |
| Upload from browser, settings, restart pods | — | — | ✔ |
| Login system needed | no | no | yes |
| Effort | medium | low | high |

### 3.4 Panels

| Panel | Shows | Actions |
|---|---|---|
| KPI row | users online, active transfers (moving / retrying / waiting), direct rate, resumed (data not re-sent), failed today, signaling pods | none |
| Peers | every user and bot: online / **offline · others waiting** / logged out, endpoint ID | Log out / Log in (bots) |
| Live mesh | server in the middle; direct (blue), relay (orange dashed), reconnecting (amber dotted), waiting (purple dashed), signaling only (grey dotted); %, `retry n/3` or `N min` on each line | none |
| Transfers | active %, speed, path; waiting for sender (time left, "% kept on disk"); reconnecting n/3; done; expired; cancelled | Cancel (later) |
| P2P health | success %, median speed, setup time, direct vs relay share, bytes through server = 0 (last 15 min) | none |
| Server health | signaling pods ready (+ users per pod), Redis, SNS/SQS, errors, uptime | link to Grafana |
| Controls | bots running (− N +); "bot A sends file F to B"; kill sender mid-transfer (resume test) | Scale, Send, Run |
| Chat | chat as `web-admin` with everyone | Send message |
| Recent events | joins, leaves, transfers, retrying, waiting, resumed, failures, expiries, control actions | none |

### 3.5 Where the data comes from

| Data | Source |
|---|---|
| peers, transfers, events, chat | Java server (it already knows), pushed over WebSocket |
| transfer states (waiting / retrying / expired) | peer-app events (B2b-2) → Java client → server → Redis (✅ B3b) |
| numbers over time | Prometheus |
| scaling bots | Kubernetes API, called by the server with a ServiceAccount that may only scale the bot StatefulSet (RBAC) |

### 3.6 Messages (proposal)

Server → page:
- `peer_update {name, state, endpointId}`
- `transfer_update {id, from, to, size, pct, speed, path, state, retry, secondsLeft}`
- `event {time, text}`
- `chat {from, text}`
- `health {pods, redis, queue, errors}`

Page → server:
- `bot_logout {bot}` / `bot_login {bot}`
- `scale_bots {replicas}`
- `bot_send {from, to, file}`
- `chat {text}`

### 3.7 What earlier steps must deliver
- **B3 (✅ B3a + B3b):** client reports transfer states to the server (`retrying`, `waiting` with time left); server tracks "waiting for sender"; the receiver sees "sender joined" and sends `retry`; `TRANSFER_END` stops the sender serving. Left: B3c (agree after a restart), B3d (% in retrying/waiting events and small fixes).
- **B4:** bots as a StatefulSet with stable names (bot-0, bot-1, …), seed + volume per bot, real `logout` / `login` commands.

### 3.8 Build steps

| Step | Content | After |
|---|---|---|
| D1 | page + WebSocket; KPI row, Peers, Live mesh, Transfers, Events, Chat | B3 |
| D2 | P2P health + Server health (+ Grafana link) | D1 |
| D3 | Controls: log out/in, scale (RBAC), bot sends file, kill sender | B4 |
| D4 | polish: phone width, dark mode, empty states, error messages | B5 |

### 3.9 Security
- Not exposed publicly; reached through port-forward or behind one password.
- The server's Kubernetes permission covers only "scale the bot StatefulSet" (least privilege).
- Every control action appears in Recent events (what and when).

---

## 4. User app ("Aperture for users", Phase U)

![User app: direct transfer finished, with proof card](images/user-app-direct-v2.png)

*Mockup, example data. Left: user1's screen (sender). Right: user2's screen (receiver). The file went directly laptop to laptop; the sidebar shows "Your transfers" and the 2 + 2 limits.*

### 4.1 Goal
Anyone downloads one file, opens the browser, chats with other users and bots, and sends files **directly laptop to laptop**, and can see and check for themselves that it went direct.

### 4.2 Options considered

| | Local app + browser screen (chosen first) | Desktop app (Tauri) | Browser-only (WebAssembly) |
|---|---|---|---|
| Direct transfers | ✔ | ✔ | ✘ always relay (browsers can't open UDP) |
| Install needed | one file | installer | none |
| Resume after restart | ✔ | ✔ | ✘ (tab closed = lost) |
| Big files | ✔ | ✔ | limited (held in browser memory) |
| Reuses peer-app | almost all | almost all | needs a WebAssembly build |
| When | U1 | U5 | maybe later |

### 4.3 How it works

```
user1's laptop                                   user2's laptop
┌───────────────────────┐                        ┌───────────────────────┐
│ browser (localhost)   │                        │ browser (localhost)   │
│        │              │      DIRECT P2P        │        ▲              │
│ peer-app + web page ──┼───────────────────────►┼── peer-app + web page │
└───────────┬───────────┘ (relay only if direct  └──────────┬────────────┘
            │              is blocked)                       │
            └──── chat + who's online via the server ────────┘
```

- peer-app gets a small built-in web server that serves the page and a local API.
- It listens on **127.0.0.1 only**, so nobody else on the network can control it.
- Identity: username + key file (B0), same ID every start.
- Transfers: blobs with resume, quick tries, waiting, expiry (B2).
- Works with bots and Java users, because it's the same peer-app.

### 4.4 What the user does
1. Download `aperture-peer` (one file) and run it. The browser opens `localhost:8080`.
2. Pick a username. The ID stays the same every time.
3. See who's online (users and bots) and chat.
4. Drag a file onto a person. It goes directly, and the screen shows direct or relay and the speed.
5. If the Wi-Fi drops or the other side closes the app, the transfer waits and resumes from where it stopped.

### 4.5 Screens

| Area | Content |
|---|---|
| Sidebar | my name + ID; online list (users, bots; an away peer is faded), each name shown with its short ID; **Your transfers** (mini list, queued items) and limits 2 + 2 |
| Header | the other person's name + path pill (direct / relay / waiting / offline) |
| Chat | messages with the selected person; the file card sits inside the chat |
| "Where your file goes" | small mesh: you, the other person, server (chat only), relay; a moving line shows the real path |
| File card | progress, speed, direct / relay; reconnecting / waiting / resuming / expired; Cancel |
| Proof card | after a transfer: path, other side's address, bytes through relay, bytes through server, not sent again (after a resume), blake3 match, time, speed |
| Check it yourself | `b3sum` on both sides; compare the address; watch traffic with OS tools; relay-blocked test; Wireshark tip |

### 4.6 States the user sees

| State | Screen |
|---|---|
| direct | blue line between the laptops, "direct · 88 MB/s" |
| relay | orange path through the relay, "relay (encrypted, can't read it)" + why |
| reconnecting | amber dotted line, "user1 dropped · reconnecting 2/3…" (3 quick tries, 5 / 10 / 20 s) |
| other side offline | purple dashed line, "waiting for user1 · 30 min left", "58% is safe on your disk" |
| back online | "continues from 58%"; proof card: "resumed from 58% · 174 MB not sent again" |
| expired / cancelled | clear message, partial data removed |

**When the sender goes away (3 steps):**

![User app: sender closed the app, receiver reconnecting](images/user-app-reconnecting-v2.png)

*1. user1 closed Aperture at 58%. user2's line turns amber dotted: "user1 dropped · reconnecting 2/3…" (peer-app's 3 quick tries).*

![User app: sender offline, receiver waiting](images/user-app-waiting-v2.png)

*2. After 3 tries: purple dashed, "waiting for user1 · 30 min left". The 174 MB already received stays on disk; user2 doesn't need to do anything.*

![User app: sender back, transfer resumed and finished](images/user-app-resumed-v2.png)

*3. user1 opens Aperture again: the transfer continues from 58%. The proof card shows "resumed from 58%", "direct (paused once)" and "174 MB not sent again".*

**Strict network:**

![User app: strict network, relay fallback](images/user-app-relay-v2.png)

*Direct is blocked, so the file goes through the relay (orange). The app says so honestly; the relay only forwards encrypted data, and it may switch to direct by itself.*

### 4.7 How a user can trust "direct"

![Proof card with Check it yourself panel](images/user-app-check-v2.png)

*user2's screen after the transfer: proof card plus "Check it yourself" open (`b3sum` gives the same hash on both laptops, `ss -tnu` shows the connection goes straight to the other laptop).*

1. **Live mesh:** while sending, the moving line goes straight from laptop to laptop; server dotted (chat only), relay grey.
2. **Proof card:** path, the other laptop's address, bytes through relay = 0, bytes through server = 0.
3. **Hash check:** blake3 fingerprint matches on both sides (`b3sum <file>`).
4. **Check it yourself** (no need to trust the app):
   - Compare the address: the other user opens a "what is my IP" site; it must match.
   - Watch traffic: Windows Resource Monitor → Network; Mac `nettop -p aperture-peer`; Linux `sudo nethogs` or `ss -tnu`. Expect almost all bytes to the other user's address.
   - Block the relay in the firewall and send again: if it still works, it was direct.
   - Wireshark: packets go to the other user's address (content encrypted).
   - Server's own count of file bytes handled: 0.
5. **Honest fallback:** on strict networks it says "relay", and that the relay only forwarded encrypted data.

"Direct" means no relay or server in the middle; the data still crosses normal internet routers.

### 4.8 Proof data: where it comes from

| Item | Source |
|---|---|
| path (direct / relay) | `CONNECTION_PATH`, `TRANSFER_PATH` (exists) |
| speed, time | `TRANSFER_TIMING` (exists) |
| fingerprint | `FILE_HASH` (exists) |
| resumed from / not sent again | chunks already on disk at `retry` (B2b-1; needs a byte count in the event) |
| other side's address | iroh remote info (exists, needs exposing) |
| bytes through relay | **new**: per-path byte counts from iroh |
| bytes through server | **new**: server reports file bytes it handled (always 0) |

### 4.9 Local API (proposal)
- `GET /` → the page
- `WS /events` → live peer-app events (progress, path, retrying, waiting, chat, presence)
- `POST /send {to, path}`, `POST /retry {id}`, `POST /cancel {id}`
- `POST /chat {to, text}`

### 4.10 Build steps

| Step | Content |
|---|---|
| U1 | web server in peer-app + page: login, online list, chat, send/receive |
| U2 | "Where your file goes" mesh |
| U3 | proof card (+ per-path bytes, server byte count, resumed bytes) |
| U4 | "Check it yourself" panel |
| U5 | desktop app with Tauri (Windows/Mac/Linux installers) |
| maybe later | browser-only peer (WebAssembly): no install, but always relay |

### 4.11 Honest limits
- Direct isn't always possible (strict office/mobile networks). Then it uses the relay, and the app says so.
- The user must download and run one program (U1); the Tauri app (U5) makes this feel normal.

### 4.12 What data is kept

No accounts, passwords, emails or user database.

| Data | Where | How long |
|---|---|---|
| Key (the user's identity) | user's laptop (`~/.aperture/<name>/secret.key`, B0) | until the user deletes it |
| Transfers in progress | user's laptop (`offers.json`, `fetches.json`, B2b) | until done or expired (30 min) |
| Received files | user's laptop | the user's |
| Who's online (name + endpoint ID) | server (Redis) | only while connected; removed when the user leaves |
| Transfer info (hash, size, sender, receiver, state) | server (Redis) | until the transfer finishes or expires |
| Chat | server passes it on | live only, not stored |
| Files | never on the server | — |

**Names aren't protected (no accounts).** Anyone could pick the name "user2". So:
- The app shows every name with its short ID (e.g. `user2 · 076c…063e`). A real user's ID never changes (B0), so an impostor with a different ID is visible.
- Optional later: the server locks a name to the first public key that used it (`user2 → 076c…063e`) and refuses other keys. This stores only a public key, no personal data.

---

## 5. Timeline

At 6–8 focused hours a day (~2 sessions/day):

| Days | Work |
|---|---|
| ✅ | B3a + B3b: Java uses blobs, retrying / waiting / resume |
| 1–2 | B3c + B3d: restart cleanup, small fixes |
| 3–4 | D1: first live dashboard |
| 5–7 | B4: bots StatefulSet, kill/logout tests |
| 8–9 | D2 + D3: health + controls |
| 10–11 | B5 + D4 |
| next ~1 week | CI/CD: build, test, Docker push, deploy |
| next ~2 weeks | Phase U: U1–U4 |
| next ~1–1.5 weeks | U5: Tauri desktop app |

| Milestone | About |
|---|---|
| First live dashboard | ~4 days |
| Phase B + full dashboard | ~2 weeks |
| + CI/CD (good point to show recruiters) | ~3 weeks |
| + user app (U1–U4) | ~5 weeks |
| + desktop app | ~6–6.5 weeks |

Risks: Kubernetes debugging in B4 (volumes, 20 bots), low disk space on the laptop, new tools (WebSocket UI, Kubernetes RBAC, Tauri).

---

## 6. Open questions
- Dashboard: password, or port-forward only?
- Keep chat history (Redis) or live only?
- User app: default folder for received files (`~/Aperture/received`)?
- Should users see bots in their online list?
- Lock names to the first key that used them, or only show short IDs?
- Phase U before or after CI/CD?
