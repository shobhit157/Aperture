# Mesh Evolution Plan

How Aperture's live view of transfers should grow, from today's 20 bots to 1000+ users.
Written after Mesh v2 (Redis as the single source of truth) was finished and tested.

---

## 1. Where we are today (Mesh v2)

```
client --TRANSFER_EVENT--> server pod --Lua--> Redis (transfer:<id>, transfers:active)
                                              |
                                              +-- PUBLISH mesh:changed <id>
                                                        |
every pod's MeshEventServer <--------------------------+
        |  snapshot on connect, updates every 250 ms, resync every 10 s
        v
   mesh.html (ring of users, one line per transfer)
```

What Mesh v2 already gets right, and what never needs redoing:

- **One source of truth.** Every pod writes transfer state to Redis with atomic Lua scripts, so any pod shows the same picture.
- **Counted once.** A transfer is counted in Prometheus exactly once, no matter how many pods or reporters there are. Tested: 6 done and 1 failed matched Grafana exactly.
- **Live path.** relay → direct switches show while they happen (`paths_stream()`).
- **No user labels in metrics.** Counters are totals by type (`direct`, `relay`, `failed`), never per user.

What does **not** scale: the **drawing** (a ring of dots) and the **bots** (a JVM plus peer-app each).

---

## 2. Rules we follow at any size

| Rule | Why |
|---|---|
| **Record every transfer** (Redis, logs) | Needed to investigate any single transfer later. Cheap. |
| **Count in totals, never per user** in Prometheus | One series per user or peer blows up Prometheus (the "cardinality explosion"). |
| **Show groups and problems, not every user** once there are more than ~50 | People can't watch 1000 dots; 5 failures get lost among 995 healthy ones. |
| **Alerts notice, live views investigate** | Nobody should have to stare at a screen to find a problem. |

---

## 3. Stages by size

### Stage 1: up to ~50 users (now)

- Keep the **ring** view.
- Small change: dots and labels shrink as users increase, and labels point outward from the ring.
- Test target: 20 bots with `!send all all small`.

### Stage 2: 200 to 1000 users

- Default view becomes **group bubbles**: one bubble per server pod or bot group. A line between bubbles shows the total transfers between them; line colour shows healthy, relay-heavy or failing.
- An always-on **problems list** sits next to it: failed first, then stalled.
- **Click a bubble** to open that group as today's ring (≤ 50 users).
- Optional **clustered dots** view: every user as a small dot packed in its group, with lines only for problems.

### Stage 3: 1000+ users

- Add a **group × group matrix** (sender group by receiver group, shaded by count, red dot for failures). It can't tangle and shows patterns like "one pod's whole row is red".
- Draw with **Canvas** instead of SVG, so redrawing thousands of shapes stays fast.
- Server changes (see section 5).

| View | 20 | 200 | 500 | 1000 | 5000 |
|---|---|---|---|---|---|
| Ring (today) | works | breaks | breaks | breaks | breaks |
| Clustered dots | works | works | works | strained | breaks |
| Group bubbles | more than needed | works | works | works | works |
| Matrix | more than needed | works | works | works | works |
| Problems + stats | works | works | works | works | works |

---

## 4. Beyond the mesh: other kinds of live tracking

The mesh answers "how are users connected right now". Other questions need other tools:

| Tool | Question it answers | Status |
|---|---|---|
| **Alerts** (Prometheus rules + Alertmanager) | Is something wrong right now? | Next: needs no new code |
| **Live metric graphs** (Grafana) | How healthy is everything overall? | Have it; add panels for failure rate and direct % |
| **Live table** ("top" for transfers) | What's running, and which is slowest? | Later; like `/status` for all users |
| **Event stream** (mesh log, `kubectl logs -f`) | What just happened, in order? | Have it |
| **Timeline / swimlanes** | When did each transfer start, switch path and end? | Later; good for queue and path-switch behaviour |
| **Tracing** (OpenTelemetry + Jaeger or Grafana Tempo) | Where did the time go in one transfer? | After Phase B; strongest learning step |
| **Synthetic probe** (canary bot) | Does a transfer work at all right now? | Easy; one small test file every minute |

First alert rule, which needs only the existing counter:

```yaml
groups:
- name: aperture-transfers
  rules:
  - alert: TransfersFailing
    expr: increase(chat_file_transfers_failed_total[5m]) > 3
    for: 1m
    labels: { severity: warning }
    annotations:
      summary: "More than 3 transfers failed in the last 5 minutes"
```

Why live tracking matters at all:

- some behaviour leaves no trace afterwards (the relay → direct switch);
- problems are cheapest to fix in the first minute;
- testing needs it;
- people waiting on a transfer want progress.

---

## 5. Server changes needed for 1000+ users

| Part | Today | Change |
|---|---|---|
| Redis writes (Lua) | fine | none: ~300 updates/s is light for Redis |
| Pub/sub `mesh:changed` | sends only the id | none |
| 10 s snapshot | one Redis call per transfer | **pipeline** all reads into one round trip |
| 250 ms flush | one WebSocket message per changed transfer | **one batched message** per flush |
| Mesh page | SVG, full redraw | Canvas; groups by default |
| Bots | JVM + peer-app (~200 MB each) | lightweight bot mode in peer-app (no JVM), spread over several machines |

---

## 6. Order of work

1. **Stage 1 polish:** adaptive dot and label size; run the 20-bot test.
2. **First alert rule**, plus Grafana panels for failure rate and direct %.
3. *(Phase B: iroh-blobs. The mesh doesn't block it.)*
4. **Stage 2:** group bubbles, problems list, click-to-drill-down.
5. **Synthetic probe bot.**
6. **Tracing** with OpenTelemetry.
7. **Stage 3:** matrix, Canvas, server batching, lightweight bots.

---

## 7. Open questions

- What is a "group": a server pod, a bot deployment, or a region? Pods are the easiest to start with, but bot groups may say more about the network.
- Should the problems list show only active problems, or also the last N minutes of finished failures?
- How long should Redis keep finished transfers if we want a short history (now 30 s)?

---

Related: `docs/mesh-v2-transfer-events.md`, `docs/decisions/003-redis-transfer-state.md`, `docs/ROADMAP.md`.
Interactive comparison of the views: the "Mesh at Scale" page (simulated data).
