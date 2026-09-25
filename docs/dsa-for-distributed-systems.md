# DSA for Distributed Systems — Study Roadmap

> [!NOTE]
> This is a personal study roadmap, not a feature of Aperture. It exists in
> the repo because every topic on it maps directly to something already
> built, or something planned, in this project.

---

## Why this list, not a generic DSA list

Generic interview-prep DSA (two pointers, dynamic programming, etc.) is
still worth doing separately for job interviews — but it isn't what makes
someone good at building systems like Aperture. This list is scoped
specifically to what shows up in P2P networking, swarm distribution, and
multi-node coordination — the actual domain this project lives in.

---

## 1. Hashing

### Hash tables (foundation)
Already used implicitly everywhere in this project — Redis itself is a
hash table service. Understand load factors, collision handling, and why
lookups are O(1) on average.

### Consistent hashing
The real target. Solves: *"how do you distribute keys evenly across a set
of nodes that can grow or shrink, without reshuffling everything every
time a node joins or leaves?"*

```text
Naive:  key % N nodes
             │
        N changes → almost every key remaps → total reshuffle

Consistent hashing:
        nodes and keys placed on a ring
             │
        N changes → only keys near the changed node remap
```

**Relevance:** this is the mechanism behind Kademlia/DHT node responsibility,
and would be directly relevant to deciding which peer in a swarm is
responsible for tracking which file piece.

---

## 2. Graphs

Aperture's own mesh view is a live graph — nodes are peers, edges are
active transfers.

- **BFS / DFS** — the basic traversal toolkit. Needed to reason about
  reachability in a swarm ("can piece 7 reach every peer that needs it?").
- **Shortest path (Dijkstra)** — relevant if a future scheduler needs to
  reason about which provider is "closest" (lowest latency) for a piece.
- **Trees / tries** — Kademlia's routing table is a binary trie keyed on
  ID prefixes. Understanding tries generally makes DHT internals click
  much faster.

---

## 3. Distributed systems fundamentals

These are not classic "DSA," but are usually taught alongside it and are
the actual core of this domain.

### Consensus (Raft, Paxos)
*"How do multiple machines agree on one truth, even when some fail or
messages arrive late or out of order?"*

> [!TIP]
> Start with **Raft** — it was explicitly designed to be more
> understandable than Paxos, without sacrificing correctness.

**Relevance:** directly applicable to a future swarm scheduler that needs
every peer to agree on piece ownership, even as peers join, leave, or
disappear mid-transfer.

### Logical / vector clocks
*"How do you know which of two events happened first, across machines with
no shared, perfectly synchronized clock?"*

```text
Real incident from this project:

  Mesh showed:  STARTING → TIMED OUT
  Reality was:  STARTING → COMPLETED (successfully)

  The mesh's flat 60s timer inferred failure from elapsed time
  alone — it had no way to know "is this transfer still making
  real progress" versus "has it gone silent."
```

This is exactly the class of problem vector clocks and progress-aware
event ordering exist to solve — reasoning correctly about state and
ordering across machines that don't share a clock.

### Gossip protocols
*"How does information spread through a network of peers with no central
broadcaster?"*

**Relevance:** directly comparable to Aperture's own SNS/SQS cross-pod
sync (`MESH|START`, `MESH|COMPLETE` broadcast to every server pod) — and
to how piece-availability would need to propagate through a swarm.

---

## 4. Scheduling and selection strategies

### Greedy algorithms
BitTorrent's **rarest-piece-first** strategy is a greedy algorithm:
always fetch the piece fewest peers currently have, to keep the swarm
healthy and avoid any single piece becoming a bottleneck.

```text
Piece availability across swarm:

  Piece 1: ████████ (8 peers have it)
  Piece 2: ██ (2 peers have it)      ← fetch this one first
  Piece 3: █████ (5 peers have it)
```

### Priority queues / heaps
The natural data structure for "always process the most urgent/rarest
thing next" — needed to implement rarest-piece-first efficiently rather
than rescanning availability from scratch every time.

---

## 5. How this maps to Aperture's own roadmap

| Topic | Where it applies in Aperture |
|---|---|
| Hashing / consistent hashing | Piece-to-provider assignment in a future swarm |
| Graphs (BFS/DFS, trees) | Mesh visualization, DHT routing tables |
| Consensus (Raft) | Swarm-wide agreement on piece ownership |
| Vector clocks | Correct transfer-state ordering (the mesh timeout bug) |
| Gossip protocols | Cross-pod state sync (already built, via SNS/SQS) |
| Greedy + priority queues | Rarest-piece-first scheduling |

---

## Suggested order

1. Hash tables → consistent hashing
2. Graphs: BFS/DFS → trees/tries
3. Raft (read the original paper's introduction, or a good visual guide,
   before the full paper)
4. Gossip protocols
5. Greedy algorithms + heaps, applied specifically to rarest-piece-first

> [!IMPORTANT]
> The goal isn't to memorize these for an interview. It's to be able to
> look at Aperture's own future swarm scheduler design and recognize
> *which* well-studied problem it actually is, instead of reinventing a
> weaker version of something already well understood.
