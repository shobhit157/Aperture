# Kademlia — Routing Table

*How a node organizes what it knows about other peers — k-buckets, splitting, and eviction.*

> [!NOTE]
> Companion to `kademlia-distance-metric.md` (XOR distance, the triangle
> inequality, and why XOR qualifies as a valid distance at all). This file
> covers the actual data structure built on top of that distance metric —
> the "how," not the "why."

---

## 1. What a k-bucket is

A node's routing table isn't one flat list of everyone it knows — it's
organized into **buckets**, where each bucket holds contacts within a
particular XOR-distance range from the node's own ID.

**k** is simply the *maximum number of contacts a single bucket is
allowed to hold* (the original Kademlia paper uses k = 20 as a typical
value — chosen for redundancy, not derived from anything specific to a
particular deployment).

```text
A k-bucket (k = 4, for illustration):

  [ contact 1, contact 2, contact 3, contact 4 ]
                                          ▲
                                       full
```

### Why keep more than one contact per distance range at all

If a bucket only ever stored a single contact per range, losing that one
contact (it goes offline) would leave the node with **no way at all** to
route queries into that part of the ID space until it rediscovers someone
new there. Keeping k contacts per range means a bucket can tolerate
several of its entries going offline before that range becomes
unreachable.

---

## 2. How a k-bucket is maintained — least-recently-seen, not random

Kademlia treats a k-bucket as a **least-recently-seen list**, not an
arbitrary collection:

```text
Bucket, ordered oldest-seen → newest-seen:

  [ oldest ][      ][      ][ newest ]
      │
      whenever this node is heard from again
      (via any interaction, not just a lookup),
      it moves to the "newest" end
```

When a new contact is discovered and the bucket is **already full**:

```text
New contact discovered, bucket full
            │
            ▼
   ping the OLDEST contact in the bucket
            │
      ┌─────┴─────┐
      │           │
  responds      doesn't respond
      │           │
      ▼           ▼
 keep it,     evict it,
 move to      insert the
 "newest"     new contact
 end;
 discard
 the new
 contact
```

> [!IMPORTANT]
> This is a deliberate design choice: **long-lived, frequently-responsive
> nodes are preferred over brand-new, unproven ones.** Nodes that have
> already been online a long time are statistically more likely to *stay*
> online than one that just appeared — so the bucket actively favors
> proven stability over novelty, rather than simply keeping whichever
> contact was seen most recently.

---

## 3. BitTorrent's bucket-splitting optimization

Standard Kademlia pre-allocates roughly 160 buckets (one per possible
distance range, matching the 160-bit ID length). BitTorrent's variant
instead starts with a **single bucket** covering the whole ID space, and
only splits a bucket when it's full **and** contains the node's own ID.

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
the oldest entries are pinged (same mechanism as Section 2), and dead
ones are evicted instead.

### The two benefits

1. **Less memory** — fine-grained buckets only ever form near your own
   ID, where you actually need precision. Everything far away stays as
   one coarse bucket instead of 160 mostly-empty ones.
2. **No cross-bucket lookups needed** — a bucket only ever splits once
   it's full, so whichever bucket a query lands in is guaranteed to
   already have enough entries to answer from directly.

---

## 4. Worked lookup, using a routing table by hand (4-bit IDs)

Six nodes in a tiny network:

```text
Node A: 0001
Node B: 0110
Node C: 1010
Node D: 1100
Node E: 0011
Node F: 1001
```

**Scenario:** you are node A, looking for whoever is responsible for data
with ID `1011`. Your routing table only knows about E and C.

Step 1 — distance from your known contacts to the target:

```text
E (0011) XOR target (1011) = 1000 → 8
C (1010) XOR target (1011) = 0001 → 1
```

C is much closer (1 vs 8) → forward the query to C.

Step 2 — C checks its own routing table (it knows D):

```text
D (1100) XOR target (1011) = 0111 → 7
```

That's *farther* than C itself (distance 1) — C is already the closest
node it knows of. Lookup terminates: C is the answer, reached in a
single hop.

> [!NOTE]
> In a real network (thousands of nodes, 160-bit IDs) this same process
> typically takes only a handful of hops — each hop roughly halves the
> remaining distance, which is the actual source of Kademlia's lookup
> efficiency.

---

## 5. Collisions — a non-issue at real scale

Real Kademlia uses 160-bit IDs: `2^160 ≈ 1.46 × 10^48` possible values —
larger than the estimated number of grains of sand on Earth by roughly
10^28–10^30 times. Two independently-generated random IDs colliding is
treated as effectively impossible by design — the ID space is made
deliberately large enough that this isn't worth building explicit
collision-detection machinery for.

> [!WARNING]
> If a genuine collision *did* somehow occur, the network would have no
> reliable way to distinguish the two nodes, and token-based
> authentication (see below) could get confused about ownership. The
> correct engineering response, as Kademlia itself demonstrates, is to
> make the ID space large enough that this stays negligible — not to add
> collision-resolution logic after the fact.

Aperture's own peer identities (`EndpointId`, Iroh's public keys) come
from an even stronger source than random IDs — real cryptographic
keypairs — where "collision" would mean breaking the underlying
cryptography itself, a different and more severe concern than a simple
random-ID clash.

---

## 6. Node vs peer, and tokens — related BitTorrent DHT vocabulary

- **Node**: a participant in the DHT itself. Its job is routing and
  lookup — storing pieces of the "who has what" map, answering and
  forwarding queries. A node does not necessarily hold the actual data
  being looked up.
- **Peer**: a participant actually uploading/downloading the real
  content. A single machine can be both at once, but they are separate
  *roles*, not synonyms.
- **Token**: a short-lived, opaque value one node hands out to prove "you
  genuinely queried me recently." Announcing that you're downloading
  something requires presenting that exact token back, checked against
  the requester's IP address too — preventing forged announcements.

> [!TIP]
> Mapped onto Aperture today: the Java signaling server plays the "node"
> role (helps peers find each other, holds no file data). The bots
> running `peer-app` play the "peer" role (they actually exchange file
> bytes).

---

## 7. What a DHT does and does NOT solve

> [!WARNING]
> A DHT makes **finding** peers resilient to any single node disappearing
> — it does not make the underlying **data** resilient. If a piece of
> data exists on only one peer and that peer vanishes, no DHT lookup
> mechanism can recover it. Only genuine replication (multiple real
> copies existing across multiple peers) prevents data loss. A future
> scheduler needs to track *how many copies* of each piece exist, not
> just *where* they currently are.

---

## 8. Where to apply this in Aperture

- **`knownUsers` in `Client.java`** — currently a flat list. The
  "detailed nearby, coarse far away" bucket principle could organize this
  by relevance (recent transfer partners kept fresh, everyone else
  coarse) once bot count grows, without needing an actual DHT.
- **Swarm piece lookup** (future) — "who has piece 7" is exactly the
  shape of problem this routing table structure was designed for, if a
  decentralized (non-server-coordinated) swarm model is chosen.
