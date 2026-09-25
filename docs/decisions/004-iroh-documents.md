# 004 — Iroh Documents for Shared Swarm Metadata

## Context

Aperture is planned to evolve from one-to-one P2P file transfer into a distributed file distribution system.

In a swarm, peers need shared information such as:

* Which files are being distributed
* How a file is divided into pieces
* Which peers currently have each piece
* Which pieces a peer is missing
* Which peers are participating in a distribution job

This information is different from the actual file data.

Aperture therefore needs a shared state mechanism for **swarm metadata**, while keeping file bytes on the P2P data plane.

## Decision

Use **Iroh Documents as a candidate mechanism for shared, eventually consistent swarm metadata**.

Iroh Documents are not treated as the file-transfer mechanism and do not implement the swarm scheduler by themselves.

The architecture remains:

```text
             Swarm Metadata
                   │
             Iroh Documents
                   │
        ┌──────────┼──────────┐
        │          │          │
      Bot 1      Bot 17     Bot 32
        │          │          │
        └────── P2P Data ────┘
                   │
              Iroh Transfer
                   │
             Actual Pieces
```

The shared document records **state about the file**. Iroh's data-transfer mechanisms move the actual pieces.

## What the Document Represents

For a distribution job, Aperture can maintain metadata such as:

```text
file_id
file_size
chunk_size
chunk_count
whole_file_hash
chunk_hashes
participants
availability
```

An availability map could conceptually look like:

```text
P1 → [Bot1, Bot17, Bot32]
P2 → [Bot4, Bot18]
P3 → [Bot7, Bot21, Bot40]
```

Each bot can also maintain local state:

```text
owned_chunks = {P1, P4, P7}
```

This allows a peer to determine which pieces it needs and where those pieces may currently be available.

## Example Flow

Suppose Bot50 needs piece `P4`.

```text
Bot50
  │
  │ Read swarm metadata
  ▼
Availability state
  │
  │ P4 → Bot17
  ▼
Bot17
  │
  │ Iroh P2P transfer
  ▼
Bot50
  │
  │ Verify piece hash
  ▼
Store P4
  │
  │ Update shared state
  ▼
Availability state
```

The document provides the knowledge required to make the transfer decision.

The actual bytes still travel directly between peers whenever possible.

## Documents vs File Transfer

These responsibilities must remain separate.

| Component                 | Responsibility                                |
| ------------------------- | --------------------------------------------- |
| Iroh Document             | Shared swarm metadata                         |
| Iroh data/blob mechanisms | Transfer piece data                           |
| Java backend              | Control-plane coordination and job management |
| Swarm scheduler           | Decide which piece/provider to request        |
| Bot                       | Store, download, and upload pieces            |

Iroh Documents do **not** automatically provide BitTorrent-like piece scheduling.

The swarm scheduler remains Aperture application logic.

## Distribution Jobs

A shared document must not make every bot part of every transfer.

Aperture will distinguish between:

### Private transfer

```text
Alice ───────────────► Singapore
```

Only the intended recipient participates.

### Distribution job

```text
Admin
  │
  ▼
Distribution Job
  │
  ├── Bot1
  ├── Bot2
  ├── Bot3
  └── ...
```

The participant set and file metadata belong to the specific distribution job.

This prevents a private file transfer from accidentally becoming available to the wider bot network.

## Authorization

Iroh peer identity alone is not sufficient for application-level authorization.

Aperture must determine:

* Which peers belong to a distribution job
* Which peers are allowed to request pieces
* Which peers are allowed to provide pieces
* Which metadata a peer is allowed to read or modify

Authorization therefore remains an Aperture application responsibility.

## Future Swarm Scheduler

The scheduler will eventually use the shared metadata to make decisions such as:

* Which piece should be requested
* Which peer should provide it
* Whether multiple pieces should be downloaded in parallel
* Whether direct P2P or relay connectivity should be preferred
* When to retry a failed transfer
* How to avoid duplicate requests
* How to balance providers
* Whether rare pieces should receive higher priority

For example:

```text
Bot50 needs P4

Availability:
P4 → Bot17, Bot31, Bot42

Scheduler:
    choose provider
         ↓
    request P4
         ↓
    transfer through Iroh
         ↓
    verify hash
         ↓
    store P4
         ↓
    advertise new availability
```

## Scalability Consideration

A single global document could eventually become a coordination hotspot.

If the swarm grows significantly, Aperture may need:

* Metadata sharding
* Per-file or per-job documents
* Local caching
* Batched updates
* Reduced write frequency

These are future scaling concerns and should be measured rather than optimized prematurely.

## Consequences

### Benefits

* Shared multi-peer metadata
* Eventually consistent state suitable for distributed coordination
* Keeps metadata separate from file transport
* Fits naturally with the planned Iroh-based data plane
* Allows receivers to become providers after obtaining pieces

### Costs

* Additional distributed-state complexity
* Eventual consistency must be handled by the application
* Does not provide the scheduler automatically
* Authorization must be implemented by Aperture
* Large-scale metadata design may require additional optimization

## Principle

> **Iroh Documents describe the swarm state; Iroh transfers the data; Aperture decides what the swarm should do.**
