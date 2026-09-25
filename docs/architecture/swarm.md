# Swarm Architecture

## Purpose

Aperture currently supports one-to-one P2P file transfer:

```text
Alice ───────────────► Bob
          Iroh
```

The future goal is to support **distributed file distribution**, where multiple peers exchange different pieces of the same file.

Instead of requiring the original sender to upload the entire file to every receiver, peers can become providers for pieces they have already received.

```text
                    Distribution Job
                           │
                        Manifest
                           │
                    Initial Seeding
                           │
             ┌─────────────┴─────────────┐
             ▼                           ▼
           Bot1                        Bot2
        P1, P4, P7                  P2, P5, P8
          │   \                       /   │
          ▼    ▼                     ▼    ▼
        Bot3  Bot4                 Bot5  Bot6
```

The swarm architecture is planned functionality and is separate from the currently working one-to-one transfer path.

---

## Current vs Future

### Current

```text
Sender
   │
   │ Iroh
   ▼
Receiver
```

The sender and receiver are the only participants in the transfer.

### Future

```text
                 File
                  │
             Distribution Job
                  │
          ┌───────┴───────┐
          ▼               ▼
        Peer A           Peer B
        P1 P2            P3 P4
          │ \             / │
          ▼  ▼           ▼  ▼
        Peer C          Peer D
```

Peers can download missing pieces from other peers that already possess them.

---

## Distribution Job

A swarm should only exist when a user explicitly creates a **distribution job**.

A distribution job defines:

* the file being distributed
* participating peers
* file manifest
* chunk information
* authorization
* availability state
* transfer policies

This prevents a private transfer such as:

```text
Alice → Singapore
```

from accidentally becoming available to every bot in the system.

Private transfers and distribution jobs must remain separate application-level workflows.

---

## File Manifest

Before distribution begins, Aperture creates a manifest describing the file.

Conceptually:

```text
file_id
file_size
chunk_size
chunk_count
whole_file_hash
chunk_hashes[]
```

Example:

```text
File: dataset.bin
Size: 1 GB

Chunks:
P1
P2
P3
...
P15
```

Each piece should have its own cryptographic hash.

The receiver can therefore verify a piece before accepting it into local storage.

The complete file can also be verified using the whole-file hash.

---

## Pieces / Chunks

A large file is divided into smaller pieces.

For example:

```text
1 GB File

┌────┬────┬────┬────┬────┬────┬────┬────┐
│ P1 │ P2 │ P3 │ P4 │ P5 │ P6 │ ...│ P15│
└────┴────┴────┴────┴────┴────┴────┴────┘
```

The initial chunk count is an experimental parameter.

Aperture should measure different chunk sizes rather than assuming that one fixed size is optimal.

Chunk size affects:

* metadata overhead
* scheduling granularity
* parallelism
* retry cost
* storage behavior
* hashing overhead
* transfer efficiency

---

## Initial Seeding

The original sender initially provides pieces to a limited number of peers.

For example:

```text
Admin
 │
 ├── P1  → Bot1
 ├── P2  → Bot2
 ├── P3  → Bot3
 ├── P4  → Bot4
 └── ...
```

After receiving a piece, a bot can become a provider for that piece.

This creates additional sources for future transfers.

The goal is to move from:

```text
Admin → 50 Bots
```

toward:

```text
             Admin
          /    |    \
       Bot1  Bot2  Bot3
        / \    |    / \
      Bot4 ... Bot20 ...
```

The actual efficiency must be measured experimentally.

---

## Piece Availability

The system needs to know which peers possess which pieces.

Conceptually:

```text
P1 → [Bot1, Bot17, Bot32]
P4 → [Bot1, Bot8]
P7 → [Bot1, Bot23]
```

This availability information is part of the swarm's control state.

It answers:

> Which peer can provide the piece that I need?

Availability information does not contain the actual file bytes.

---

## Peer Roles

A peer can have multiple roles during a distribution job.

### Seeder

Initially provides pieces to other peers.

```text
Seeder → P1, P2, P3
```

### Provider

A peer that has a piece and can upload it to another participant.

```text
Bot17 → P4
```

### Receiver

A peer currently downloading missing pieces.

```text
Bot50 → needs P4
```

A peer can simultaneously be a receiver and provider:

```text
Bot17
 ├── downloading P8
 └── uploading P4
```

This is fundamental to distributed distribution.

---

## Provider Discovery

When a peer needs a piece, it first needs to discover which participating peers have it.

Example:

```text
Bot50 needs P4
       │
       ▼
Availability state
       │
       ▼
P4 → [Bot17, Bot23]
       │
       ▼
Choose provider
       │
       ▼
Transfer P4
```

Provider discovery is a control-plane concern.

The actual piece transfer remains on the data plane.

---

## Piece Scheduling

Finding a provider is not enough.

A scheduler eventually needs to decide:

* which piece to request
* which provider to use
* how many transfers to run concurrently
* when to retry
* whether to prefer a direct connection
* when to use relay connectivity
* how to avoid duplicate requests
* how to account for provider reliability
* how to handle slow providers

A future scheduler may use strategies such as **rarest-piece-first**.

For example:

```text
P1 → 20 providers
P2 → 15 providers
P3 → 2 providers
```

The scheduler may prioritize P3 because fewer providers have it.

This logic belongs to Aperture's application layer, not to Iroh itself.

---

## Transfer Lifecycle

A typical piece transfer can follow:

```text
Receiver
   │
   │ 1. Identify missing piece
   ▼
Availability State
   │
   │ 2. Find providers
   ▼
Provider Selection
   │
   │ 3. Request piece
   ▼
Iroh P2P Connection
   │
   │ 4. Transfer piece
   ▼
Hash Verification
   │
   │ 5. Store piece
   ▼
Update Availability
   │
   ▼
Peer becomes provider
```

The Java control plane coordinates the state.

Rust + Iroh handles the actual data transfer.

---

## Control Plane vs Data Plane

The swarm architecture continues Aperture's fundamental separation.

### Control Plane

Responsible for:

* distribution jobs
* participant membership
* manifests
* availability information
* provider discovery
* transfer requests
* scheduling decisions
* authorization
* swarm state

### Data Plane

Responsible for:

* peer connectivity
* NAT traversal
* direct P2P connections
* relay fallback
* piece transfer
* integrity verification

```text
              CONTROL PLANE
        Java + Redis + Messaging
                  │
        "Bot17 has P4"
                  │
                  ▼
              DATA PLANE
             Rust + Iroh
                  │
             P4 bytes
                  │
                  ▼
                Bot50
```

**Signaling and scheduling are centralized application concerns; file data remains decentralized.**

---

## Authorization and Isolation

Knowing that a peer possesses a piece must not automatically give every other peer permission to download it.

Aperture needs application-level authorization.

Conceptually:

```text
Distribution Job
      │
      ├── Participant list
      │
      ├── File manifest
      │
      └── Authorization
             │
             ▼
       Allowed peers only
```

Iroh peer identity establishes who is communicating, but Aperture's application logic must determine whether that peer is authorized to access a particular distribution job and piece.

---

## Shared Swarm State

A shared state mechanism can maintain information such as:

```text
File ID
Participant IDs
Manifest
Piece availability
Transfer state
```

Iroh Documents may be useful for parts of this shared state because the swarm needs distributed knowledge about file availability.

However, the document state and the actual file bytes are separate concerns.

```text
Shared State
     │
     ├── Manifest
     ├── Availability
     └── Metadata

Actual Data
     │
     └── Iroh data transfer
```

The exact state-storage implementation remains an area for experimentation.

---

## Receiver Becomes Provider

One of the most important properties of the swarm is that receiving a piece increases the number of possible providers.

Example:

```text
Initially:

P4 → Bot1


Bot17 downloads P4:

P4 → Bot1, Bot17


Bot32 downloads P4:

P4 → Bot1, Bot17, Bot32
```

The piece becomes increasingly distributed across the swarm.

This creates the foundation for decentralized propagation.

---

## Failure and Retry

Individual transfers can fail.

Possible causes include:

* peer going offline
* connection failure
* NAT traversal failure
* relay fallback
* timeout
* insufficient bandwidth
* corrupted or incomplete data
* provider becoming unavailable

The swarm should therefore avoid depending on a single provider.

Example:

```text
Bot50 needs P4

Bot17
  │
  X connection failed
  │
  ▼
Bot23
  │
  ▼
P4 transferred
```

The scheduler can retry using another provider when one becomes unavailable.

---

## Example: 1 GB Distributed to 50 Bots

Consider:

```text
File size: 1 GB
Participants: 50 bots
Initial pieces: 10–15
```

The initial seed might distribute different pieces to different bots:

```text
Bot1  → P1
Bot2  → P2
Bot3  → P3
...
Bot10 → P10
```

Those bots then become providers.

Other bots can obtain missing pieces from them:

```text
Bot20 needs P3
       │
       ▼
P3 available from Bot3
       │
       ▼
Iroh transfer
       │
       ▼
Bot20 now owns P3
```

Eventually:

```text
P1 → many providers
P2 → many providers
...
P10 → many providers
```

Additional pieces can then propagate through the swarm.

If all 50 participants require the complete 1 GB file, every participant will eventually need every piece.

The objective is not to assume that this is automatically faster than centralized distribution, but to measure whether distributing the upload workload improves scalability under realistic network conditions.

---

## Private Transfer vs Distribution Job

These are two different workflows.

### Private Transfer

```text
Alice ─────────► Bob
```

Only Alice and Bob participate.

The file should not enter the swarm.

### Distribution Job

```text
Admin
 │
 ▼
Distribution Job
 │
 ├── Bot1
 ├── Bot2
 ├── Bot3
 ├── ...
 └── Bot50
```

Only explicitly selected participants belong to the swarm.

This distinction is essential for both security and predictable system behavior.

---

## Observability

Swarm experiments should collect measurable data.

Useful metrics include:

* file size
* chunk size
* chunk count
* pieces transferred
* transfer duration
* throughput
* number of providers per piece
* direct vs relay connections
* failed transfers
* retry count
* concurrent transfers
* provider utilization
* time to first piece
* time to complete file
* original sender upload volume
* total swarm upload volume

The purpose is to compare different swarm strategies using measurements rather than assumptions.

---

## Future Scaling Problems

As the swarm grows, several problems may appear.

### Availability State

A single global availability structure could become a bottleneck.

### Scheduling

Choosing providers efficiently becomes harder as the number of peers increases.

### Hot Providers

Some peers may become overloaded because many receivers request the same piece.

### Rare Pieces

Pieces with very few providers may become critical points of failure.

### Network Asymmetry

Peers may have very different upload and download capacities.

### Relay Capacity

Some peers may require relay connectivity, creating additional infrastructure load.

These problems will be investigated through experiments as the swarm implementation develops.

---

## Relationship to Iroh

Iroh provides the underlying P2P connectivity and data-transfer capabilities.

Aperture provides the application-level swarm logic.

```text
Aperture
   │
   ├── Which file?
   ├── Which pieces?
   ├── Who participates?
   ├── Who has which piece?
   ├── Who should provide it?
   └── When should it be transferred?
             │
             ▼
          Iroh
   ├── Peer identity
   ├── Connectivity
   ├── NAT traversal
   ├── Relay fallback
   └── Data transfer
```

The scheduler, availability model, authorization, and distribution policy are Aperture's responsibility.

---

## Design Principle

The swarm architecture follows the same principle as the rest of Aperture:

> **The control plane decides what should happen; the data plane moves the data.**

The long-term goal is to turn Aperture from a one-to-one P2P transfer system into a distributed system where peers can collectively provide the infrastructure required to distribute large files.
