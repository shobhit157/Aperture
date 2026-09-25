# Decision 003 — Rust + Iroh for the Data Plane

## Context

Aperture needs to transfer file data directly between peers whenever the network allows it.

The control plane already handles:

* peer coordination
* presence
* endpoint exchange
* transfer signaling
* cross-instance messaging

The actual file bytes should remain outside that centralized backend.

This creates a separate **data plane** requirement.

---

## Problem

Aperture needs to solve several networking problems that are difficult to implement correctly from scratch:

* peer identity
* endpoint discovery
* NAT traversal
* direct connectivity
* relay fallback
* encrypted transport
* reliable data transfer

Implementing all of these independently would significantly increase the project's networking complexity.

---

## Decision

Rust + Iroh was chosen for the Aperture data plane.

The resulting separation is:

```text id="j8x4ps"
             APERTURE
                 │
       ┌─────────┴─────────┐
       │                   │
   Control Plane        Data Plane
       │                   │
      Java              Rust + Iroh
       │                   │
   Signaling          P2P Connectivity
   Presence           File Transfer
   Messaging
```

---

## Why Iroh?

Iroh provides networking primitives that align closely with Aperture's P2P requirements.

The data plane needs to handle:

```text id="x3q9nb"
Peer Identity
      │
      ▼
Discovery
      │
      ▼
Connectivity
      │
      ├── Direct P2P
      │
      └── Relay fallback
              │
              ▼
          Data Transfer
```

This allows Aperture to concentrate on application-level behavior instead of implementing the entire connectivity stack itself.

---

## Why Rust?

Rust was chosen for the peer-side data-transfer application.

The data plane performs network-intensive work and needs predictable resource usage.

Rust also provides:

* strong memory-safety guarantees
* explicit control over I/O
* efficient native execution
* good support for asynchronous networking
* a natural environment for building a dedicated peer process

The Rust application therefore acts as the data-plane component that integrates with Iroh.

---

## Responsibilities of the Rust Data Plane

The Rust peer application is responsible for:

```text id="u4c7ma"
Rust Peer
│
├── Peer identity
├── Iroh endpoint
├── Connectivity
├── NAT traversal
├── Direct P2P connections
├── Relay fallback
├── File transfer
├── Transfer progress
└── Integrity verification
```

The Java backend does not need to implement these responsibilities.

---

## Direct P2P

When two peers can establish a direct path:

```text id="v5m8sz"
Peer A ═══════════════════► Peer B
             Iroh
```

The actual file data travels between the peers.

This avoids making the Java backend a bottleneck for large file transfers.

---

## Relay Fallback

Direct connectivity is not guaranteed.

When direct connectivity fails:

```text id="a7p3kc"
Peer A ─────► Relay ─────► Peer B
```

Iroh can use a relay path as a connectivity fallback.

The higher-level Aperture transfer workflow can continue without requiring a separate centralized file-transfer server.

---

## Why Not Transfer Files Through Java?

An alternative design would be:

```text id="r6w2yt"
Alice
  │
  ▼
Java Backend
  │
  ▼
Bob
```

This would make the backend responsible for the file data path.

For large files and many simultaneous transfers, this would increase:

* server bandwidth requirements
* server CPU/I/O workload
* infrastructure cost
* scaling complexity
* dependence on backend availability

Aperture instead uses:

```text id="c9n4hx"
Alice ═══════════════► Bob
          Iroh
```

The Java backend only coordinates the transfer.

---

## Discovery, Connectivity and Transfer

An important architectural distinction is that these are separate concerns.

### Discovery

Answers:

> Where can I find information about this peer?

### Connectivity

Answers:

> Can I establish a usable network path to this peer?

### Transfer

Answers:

> How do I move the file or piece of data?

Conceptually:

```text id="h2r7mv"
Discovery
   ↓
Connectivity
   ↓
Transport
   ↓
Application Data
```

Iroh participates in the networking and data-transfer layers.

Aperture's Java control plane handles application-level coordination.

---

## File Integrity

The data plane verifies transferred files using cryptographic hashes.

The current transfer workflow uses SHA-256 verification.

Conceptually:

```text id="z4p6ny"
Sender
  │
  │ file
  ▼
Transfer
  │
  ▼
Receiver
  │
  ▼
SHA-256 verification
  │
  ├── Match → accepted
  │
  └── Mismatch → rejected / investigated
```

This separates:

```text
Transfer succeeded
```

from:

```text
Transferred data is verified
```

Both properties matter.

---

## Chunked Transfers

Large files will eventually be divided into pieces for the swarm architecture.

The data plane can therefore become responsible for transferring individual pieces.

```text id="m5w8qd"
File
 │
 ├── P1 ──► Peer
 ├── P2 ──► Peer
 ├── P3 ──► Peer
 └── ...
```

However, the decision about:

* which piece to request
* which provider to select
* transfer priority
* retry strategy

belongs to Aperture's application-level scheduler.

Iroh provides the underlying connectivity and transfer mechanism.

---

## One-to-One First

The project deliberately establishes reliable one-to-one transfer before introducing swarm distribution.

Current:

```text id="t6x1qr"
Alice ─────────► Bob
```

Future:

```text id="n9c3vk"
             Alice
            /  |  \
           ▼   ▼   ▼
         Peer Peer Peer
          │    │    │
          └────┴────┘
          Peer-to-peer
          distribution
```

This separation makes it easier to distinguish basic P2P transport problems from swarm scheduling problems.

---

## Alternatives Considered

Potential alternatives included:

* implementing raw TCP/UDP networking
* implementing custom UDP hole punching
* using another P2P networking library
* transferring files through the Java backend
* building a custom transport protocol

The project chose Iroh because it provides a higher-level foundation for the networking problems Aperture needs to explore.

---

## Trade-offs

### Benefits

* P2P-oriented architecture
* Built-in peer identity concepts
* NAT traversal support
* Direct connectivity
* Relay fallback
* Native Rust data-plane process
* Keeps file data outside the Java backend

### Costs

* Additional language and runtime
* Dependency on Iroh's APIs and ecosystem
* Need to understand Iroh's networking model
* Debugging requires understanding both Aperture and Iroh
* Some advanced behavior remains dependent on the underlying network

---

## Failure Boundaries

The architecture deliberately separates failure domains.

```text id="s8f2km"
Java Control Plane Failure
        │
        └── Signaling / coordination affected


Iroh Data Plane Failure
        │
        └── Connectivity / transfer affected
```

A failure in one component should not automatically imply that the entire architecture has failed.

For example, the signaling server can coordinate a transfer without ever carrying the file bytes.

---

## Observability

The data plane should expose measurements such as:

* connection path
* direct vs relay
* connection establishment time
* transfer duration
* bytes transferred
* throughput
* retries
* integrity result
* transfer failures

This information is necessary for investigating real-world P2P behavior.

---

## Result

Rust + Iroh became the foundation of Aperture's data plane.

The resulting architecture is:

```text id="c4m9ws"
                 APERTURE

       CONTROL PLANE
       Java Backend
             │
       Peer coordination
             │
             ▼
       DATA PLANE
       Rust + Iroh
             │
       ┌─────┴─────┐
       ▼           ▼
    Direct       Relay
       │           │
       └─────┬─────┘
             ▼
         File Data
```

The core principle is:

> **Java coordinates the transfer; Rust + Iroh moves the data.**

---

## Future Considerations

The data plane will eventually need to support the swarm architecture.

Future work includes:

* chunk-level transfer
* resumable transfers
* parallel transfers
* provider selection
* transfer scheduling
* swarm-aware integrity verification
* multi-peer distribution

These features will be built above the existing P2P connectivity layer rather than replacing it.

---

## Related Documentation

* `architecture/data-plane.md`
* `architecture/networking.md`
* `architecture/swarm.md`
* `decisions/001-java-signaling.md`
* `problems/direct-connection-failure.md`
* `problems/relay-fallback.md`
* `problems/slow-transfers.md`
