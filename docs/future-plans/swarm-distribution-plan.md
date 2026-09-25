# Future Plan: Swarm-Based File Distribution

> [!NOTE]
> This is a forward-looking design doc, not a built feature. It pulls
> together the swarm idea, the technology research, and the open design
> questions into one place, since they were previously scattered across
> discussion without a single reference.

---

## 1. The problem this solves

Aperture currently performs strictly one-to-one transfers. One sender,
one direct (or relayed) connection per recipient.

```text
Today — one sender to many recipients:

              ┌──► Bot B (full file)
  Bot A ──────┼──► Bot C (full file)
  (sender)    ├──► Bot D (full file)
              └──► Bot E (full file)

  Bot A's own upload bandwidth is used N times, once per recipient.
```

For distribution to many peers, the original sender's upload bandwidth is
the bottleneck — the same shape of problem BitTorrent was built to solve.

---

## 2. The target shape

```text
Planned — swarm redistribution:

  Bot A ──► Bot B ──► Bot D
    │                   │
    └──► Bot C ◄────────┘
              │
              └──► Bot E

  Once any bot has a piece, it can share that piece with others too.
  Total upload load spreads across the swarm instead of concentrating
  on the original source.
```

This requires three things Aperture does not currently have:

1. Files split into independently transferable, verifiable **pieces**.
2. A way for peers to know **who currently has which piece**.
3. A **scheduler** deciding who should fetch which piece from whom next.

---

## 3. Technology research: what Iroh actually offers here

Confirmed by inspecting the actual dependency in use
(`iroh = "1.0.2"` in `peer-app/Cargo.toml`):

- **`iroh-blobs`** — a companion crate for content-addressed blob storage
  and transfer, with built-in piece verification via hashing. **Not
  currently a dependency.** Would need evaluating for compatibility with
  the current `iroh` version before adoption.
- **`iroh-docs`** — a companion crate for decentralized, multi-writer
  synchronized state across peers. Could plausibly replace a centralized
  "who has what" scheduler with something genuinely peer-to-peer. **Not
  currently a dependency.** Same evaluation needed.

> [!IMPORTANT]
> Neither crate is in use today. Aperture's current file transfer is a
> hand-rolled protocol built directly on raw `iroh` — one whole file per
> `open_bi()` stream, a custom `META|filename|size|transferId` header, no
> content addressing, no piece concept at all. This is confirmed correct
> and working for one-to-one transfers, but has none of the swarm
> primitives built in.

---

## 4. Two coordination models, and the tradeoff

### Model A — centrally scheduled (simpler, builds on what exists)

The Java signaling server (already the coordination point for peer
discovery) is extended to also track piece ownership per file, and
decides who fetches what from whom.

```text
Bot ──reports pieces held──► Java server (extended registry)
Bot ──asks "who has piece 7"──► Java server ──► answer
```

Pro: reuses existing infrastructure (Redis already tracks presence).
Con: the server becomes a bottleneck and a single point of coordination
failure again — the same shape of problem discovery already had.

### Model B — decentralized via `iroh-docs` (more "true" P2P)

Peers read/write a shared, automatically-synchronizing document
describing current piece ownership, with no central coordinator needed.

Pro: genuinely removes the server as a coordination bottleneck.
Con: unproven for this Iroh version; more research needed before
committing to it.

> [!TIP]
> Recommended path: prototype Model A first, since it can be built with
> tools already proven to work in this project (Redis, the existing
> signaling server). Revisit Model B once `iroh-docs` compatibility is
> confirmed directly, the same way every other Iroh capability in this
> project has been verified against the actual source before being relied
> on.

---

## 5. Scheduler design: informed by BitTorrent, not reinvented

### Rarest-piece-first

Always fetch the piece fewest peers currently have, to avoid any single
piece becoming a bottleneck as the swarm grows.

```text
Piece 1: ████████ (8 peers have it)
Piece 2: ██ (2 peers have it)        ← fetch this one first
Piece 3: █████ (5 peers have it)
```

### Piece lookup, DHT-shaped (if Model B is eventually chosen)

"Who has piece 7" is exactly the kind of lookup Kademlia-style
bucket-based routing was designed for — see the separate DHT future-plan
doc for the full mechanism. The same "detailed nearby, coarse far away"
bucket-splitting idea from BitTorrent's own DHT implementation would be
the right structure for this, not a flat table.

---

## 6. The redundancy problem — a DHT-shaped lookup does not solve this

> [!WARNING]
> A lookup mechanism (centralized or DHT-based) only tells you *who
> claims to have* a piece. If a piece exists on only one peer and that
> peer disappears, no lookup mechanism recovers the missing data — only
> genuine replication does. The scheduler must track **how many copies**
> of each piece currently exist across the swarm, not just their
> location, and prioritize spreading under-replicated pieces before they
> become a single point of failure.

---

## 7. Open questions to resolve before implementation begins

- [ ] Confirm `iroh-blobs` / `iroh-docs` version compatibility with the
      current `iroh = "1.0.2"` dependency, or identify the minimum Iroh
      version upgrade required.
- [ ] Decide piece size (BitTorrent typically uses 256KB–4MB pieces —
      needs benchmarking against Aperture's own 64KB chunk size already
      used in the one-to-one transfer path).
- [ ] Decide Model A vs. Model B for the initial prototype.
- [ ] Design how a bot announces "I now have piece N" — a new message
      type through the existing signaling path (Model A) or a document
      write (Model B).
- [ ] Design replication-count tracking, independent of whichever lookup
      model is chosen.
- [ ] Decide how a provider that goes mid-transfer is detected and
      replaced — this connects directly to the existing transfer-failure
      detection work (see the transfer-state-vs-mesh-state doc) and the
      explicit `EVENT:TRANSFER_FAILED` event `peer-app` already emits.
- [ ] Prototype with a small, fixed swarm (4-5 bots, one file) before
      attempting the project's stated 50+ bot target.

---

## 8. Relationship to other future plans

- Depends on the transfer-state accuracy work (explicit failure
  reporting, progress-aware timeout) being solid first — a swarm
  scheduler making provider-replacement decisions needs to trust that
  failure signals are genuine, not a guess from a flat timeout.
- The DHT future-plan doc's bucket-routing content is the direct
  technical foundation for Model B's piece lookup, if chosen.
- Transfer resume (separate doc) becomes significantly more valuable once
  files are pieces rather than one monolithic stream — a failed piece is
  cheap to retry from scratch; a failed 90%-complete single-stream
  transfer is not.
