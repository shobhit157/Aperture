# Future Plan: DHT-Based Discovery Alongside DNS

> [!NOTE]
> This is a forward-looking design doc, not a built feature. It records
> the reasoning for *why* a DHT is worth introducing later, and *where*
> its underlying ideas — bucket-based routing specifically — could be
> reused elsewhere in Aperture before the DHT itself exists.

---

## 1. The problem this solves

Aperture's `peer-app` currently uses `dns.iroh.link` (n0's centralized
discovery service) as its default. Over the course of this project, that
service has gone down independently on two separate networks (home ISP
and mobile carrier), confirming it as a real, external single point of
failure — not something in Aperture's own code.

```text
Today:

  peer-app ──────► dns.iroh.link (n0, centralized)
                         │
                    single organization,
                    single point of failure
```

> [!IMPORTANT]
> Aperture's actual connection-establishment flow already bypasses this —
> the Java signaling server hands each peer the other's relay URL (and,
> once implemented, its direct IP addresses) directly. `dns.iroh.link` is
> not currently load-bearing for connections; it only affects background
> pkarr-publishing noise. This plan is about removing that remaining
> dependency, not fixing a functional outage.

---

## 2. What a DHT actually is

A **Distributed Hash Table** spreads "who knows what" across many
independent nodes instead of one central server. No single node going
offline breaks the whole lookup system, because responsibility for any
given piece of data is shared among several nodes, not concentrated in one.

> [!WARNING]
> A DHT does not, by itself, make *application data* recoverable if the
> one peer holding it disappears. It only makes *finding* peers resilient.
> Data itself is only as recoverable as its actual redundancy — how many
> peers really hold a copy. This distinction matters for the swarm plan
> in Section 5.

Most real DHTs (BitTorrent's Mainline DHT included) are built on
**Kademlia**.

---

## 3. Kademlia, briefly

Every node gets a random ID. "Distance" between two IDs is their XOR —
purely mathematical, with **no relationship to physical location**.

```text
India peer ID:     11010100
Singapore peer ID: 11010111
XOR:               00000011  → distance 3 (mathematically "close")

Two peers next door to each other could just as easily XOR to a
LARGE distance. Distance here is about ID similarity, not geography.
```

This distance metric isn't there to reflect the real world — it exists so
every node can independently, consistently compute "who is responsible for
this piece of data," without asking a central authority.

---

## 4. BitTorrent's bucket optimization

Standard Kademlia pre-allocates ~160 buckets (one per possible distance
range). BitTorrent's routing table instead starts with **one bucket**
covering the whole ID space, and only splits it when needed:

```text
Start:  [ one bucket — the whole ID space ]

Bucket fills, MY id falls inside it → split:
        [ bucket A — half the space, includes my id ]
        [ bucket B — other half, far from me, stays coarse ]

Bucket A fills again, my id still inside → split again:
        [ bucket A1 — quarter, includes my id ]
        [ bucket A2 — another quarter, farther from me ]
        [ bucket B  — untouched, still one coarse bucket ]
```

If a full bucket does **not** contain the node's own ID, it isn't split —
the oldest entries are pinged instead (same as standard Kademlia), and
dead ones are evicted.

### The two benefits

1. **Less memory** — fine-grained buckets only ever form near your own
   ID, where you actually need precision. Everything far away stays as
   one coarse bucket instead of 160 mostly-empty ones.
2. **No cross-bucket lookups needed** — a bucket only ever splits once
   it's full, so whichever bucket a query lands in is guaranteed to
   already have enough entries to answer from.

---

## 5. Where this reasoning already applies inside Aperture, before any DHT exists

The "detailed nearby, coarse far away" principle doesn't require a DHT to
be useful. It directly informs how `knownUsers` should evolve as the bot
count grows.

**Today:**

```java
private static final List<String> knownUsers = new CopyOnWriteArrayList<>();
```

A flat list, scanned linearly by `!send all all` and similar. Fine at
small scale; not the shape to keep once the bot count grows toward the
project's own 50–100 bot target.

**Planned:** organize known peers by relevance, not just presence —
peers recently transferred with, kept detailed and fresh; everyone else,
kept coarse and refreshed less often. This is the same "close vs. far"
split as the Kademlia bucket idea, applied without needing an actual DHT.

---

## 6. Where a real DHT fits the swarm plan specifically

For genuine swarm piece-distribution (see the separate swarm planning
notes), "who has piece 7" is exactly the kind of lookup a DHT is built
for — and BitTorrent's own bucket optimization is the correct routing
structure to use for it, not a naive flat table.

```text
Swarm piece lookup, DHT-shaped:

  "who has piece 7?"
         │
         ▼
  ask nodes whose ID is close (XOR-distance) to piece 7's ID
         │
         ▼
  they either know, or point toward nodes even closer
         │
         ▼
  a few hops later: the actual current holders of piece 7
```

> [!WARNING]
> This only tells you *who claims to have* piece 7. If that's the one and
> only copy in the swarm and that peer disappears, the DHT cannot recover
> the data — only genuine replication across multiple peers can. A future
> scheduler should track replication count per piece, not just location,
> and prioritize under-replicated pieces (BitTorrent's own
> rarest-piece-first strategy exists partly for this reason).

---

## 7. Current status of this plan

- [ ] Confirm whether `iroh` (current version in use) exposes any
      pluggable/multi-source discovery mechanism at all — as of this
      writing, a direct source search for `discovery` in `iroh`'s own
      crate came back empty. A DHT option, if used, will likely require
      either a newer `iroh` release or a separate, standalone crate wired
      in manually.
- [ ] Confirm whether disabling `dns.iroh.link` pkarr-publishing outright
      is possible in the meantime, given it is not currently load-bearing
      for actual connections.
- [ ] Apply the "close vs. far" bucket principle to `knownUsers` before
      bot count growth makes the flat list a real bottleneck.
- [ ] Design swarm piece-lookup using Kademlia-style bucket routing, once
      the swarm feature itself moves out of research and into design.
- [ ] Design replication-count tracking per piece, independent of the
      lookup mechanism, so data loss is prevented at the source rather
      than assumed away by the DHT.

---

## 8. One-line summary

> [!TIP]
> A DHT makes *finding* peers resilient to any one node disappearing. It
> does not make *data* resilient — that still requires real replication,
> designed in deliberately. Aperture's path here is: keep DNS as the
> default for now, plan a DHT (or DHT-shaped bucket routing) specifically
> for swarm piece lookup, and treat data redundancy as a separate,
> equally necessary decision.
