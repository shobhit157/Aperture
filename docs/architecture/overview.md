# Aperture Architecture Overview

## 1. Purpose

Aperture is a distributed peer-to-peer file-sharing and communication platform built to explore:

* Distributed systems
* Peer-to-peer networking
* NAT traversal
* Distributed coordination
* Cloud infrastructure
* Observability
* Scalable file distribution

The main architectural principle is:

> **Signaling is centralized; file transfer is decentralized.**

Aperture separates coordination from the actual movement of file data.

## 2. High-Level Architecture

```text
                         APERTURE
                            │
              ┌─────────────┴─────────────┐
              │                           │
        CONTROL PLANE                DATA PLANE
              │                           │
        Java Backend                 Rust + Iroh
              │                           │
       ┌──────┴──────┐             ┌──────┴──────┐
       │             │             │             │
     Redis        SNS/SQS       Direct P2P     Relay
       │             │             │             │
       └──────┬──────┘             └──────┬──────┘
              │                           │
              └─────────────┬─────────────┘
                            │
                       File Transfer
                            │
                       Alice ↔ Bob
```

### Control Plane

The Java backend handles coordination between peers.

Its responsibilities include:

* User/session management
* Online-user presence
* Signaling
* Endpoint exchange
* Transfer coordination
* Cross-server message routing
* Distributed server coordination

The control plane does **not** carry the actual file contents.

### Data Plane

The Rust/Iroh component handles peer-to-peer communication.

Its responsibilities include:

* Peer identity
* Connectivity establishment
* NAT traversal
* Direct P2P connections
* Relay fallback
* File/chunk transfer
* Transfer progress
* Integrity verification

The actual file bytes travel through the data plane.

## 3. Major Components

### Java Backend

The Java backend is the application's coordination layer.

It allows independently running backend instances to coordinate users and transfer requests.

It is responsible for answering questions such as:

* Which peer is online?
* How can Alice initiate a transfer to Bob?
* Which server instance currently knows about this peer?

The backend should not become a bottleneck for file data.

### Redis

Redis is used primarily for **online-user presence and fast shared state**.

For example:

```text
Alice → online
Bob   → online
Bot17 → online
Bot32 → online
```

Redis allows multiple backend instances to share information about currently connected users.

### AWS SNS/SQS

SNS/SQS provides communication between independent backend instances.

For example:

```text
Backend Mumbai
      │
      │ message
      ▼
    SNS
      │
      ▼
    SQS
      │
      ▼
Backend Singapore
```

This allows a request involving users connected to different backend instances to be routed between those instances.

SNS/SQS therefore belongs to the **control plane**, not the file-transfer path.

### Rust + Iroh

The Rust component provides the P2P data plane.

Iroh handles the networking problems involved in establishing peer connections across different networks.

The preferred connection path is:

```text
Alice ───────────────► Bob
       Direct P2P
```

If a direct connection cannot be established:

```text
Alice ──► Iroh Relay ──► Bob
```

The relay provides connectivity when direct P2P is unavailable.

## 4. Normal File Transfer

A simplified transfer looks like this:

```text
        CONTROL PLANE

Alice
  │
  │ transfer request
  ▼
Java Backend
  │
  │ signaling / endpoint information
  ▼
Bob
```

After the peers have enough information to establish the connection:

```text
        DATA PLANE

Alice
  │
  │
  │   Direct P2P
  ▼
Bob
```

The Java backend is therefore involved in **coordination**, but the file itself does not need to pass through the Java server.

## 5. Direct Connection and Relay Fallback

Aperture prefers direct peer-to-peer communication.

The connection decision can be viewed as:

```text
              Start transfer
                    │
                    ▼
          Attempt P2P connectivity
                    │
             ┌──────┴──────┐
             │             │
           Success        Fail
             │             │
             ▼             ▼
        Direct P2P       Relay
             │             │
             └──────┬──────┘
                    ▼
               Transfer
```

Discovery, signaling, connectivity, and data transfer are treated as separate problems.

For example:

* **Discovery:** Where can I find this peer?
* **Signaling:** How do the peers exchange information?
* **NAT traversal:** Can these peers establish a direct path?
* **Relay:** Can they communicate when direct connectivity fails?
* **Transport:** How are bytes transported between peers?
* **Application logic:** What should actually be transferred?

## 6. Current Architecture

The current system primarily supports one-to-one transfers:

```text
                ┌─────────────┐
                │    Alice    │
                └──────┬──────┘
                       │
                 Control Plane
                       │
                ┌──────▼──────┐
                │ Java Server │
                └──────┬──────┘
                       │
                   Signaling
                       │
                ┌──────▼──────┐
                │     Bob     │
                └─────────────┘

                 Data Plane

              Alice ◄──────► Bob
                    Iroh
```

The system has been tested with:

* Local P2P connections
* Cross-network transfers
* NAT traversal
* Relay fallback
* File integrity verification
* Multiple backend instances
* Redis-based presence
* Cross-instance messaging

## 7. Future Architecture: Distributed File Sharing

The next architectural stage is to move beyond one-to-one transfers.

Instead of:

```text
Admin ─────────► Bot1
      ─────────► Bot2
      ─────────► Bot3
      ...
      ─────────► Bot50
```

A distributed transfer can eventually become:

```text
                 Admin
                   │
             Initial seeding
                   │
          ┌────────┴────────┐
          ▼                 ▼
        Bot1              Bot2
        /  \              /  \
       ▼    ▼            ▼    ▼
     Bot3  Bot4        Bot5  Bot6
       \     \          /     /
        └─────► ... ◄──┘
```

The file would be divided into pieces.

Peers would store different pieces and exchange them directly.

A future swarm system therefore needs more than P2P connectivity. It also needs:

* File manifests
* Chunk metadata
* Piece hashes
* Availability tracking
* Provider discovery
* Piece scheduling
* Parallel transfers
* Retry handling
* Authorization
* Receivers becoming providers

This functionality is **future architecture** and should not be confused with the current one-to-one transfer implementation.

## 8. Architectural Principles

### Separate coordination from data

The backend coordinates transfers but should not become the file-data pipeline.

### Prefer direct P2P

Direct connections reduce dependence on infrastructure and allow peers to communicate directly.

### Use relays as fallback

A relay exists to solve connectivity problems, not to replace P2P.

### Keep distributed state explicit

Presence, transfer state, and future swarm availability should have clearly defined ownership and consistency models.

### Measure before optimizing

Networking decisions should be supported by experiments involving:

* Connection path
* Network topology
* File size
* Transfer duration
* Throughput
* Retries
* Direct vs relay connectivity

### Document engineering decisions

Aperture is also a networking and distributed-systems learning laboratory.

Problems, experiments, design decisions, and their results should therefore be recorded instead of relying on memory.

## 9. Documentation Structure

```text
docs/
│
├── architecture/
│   ├── overview.md
│   ├── control-plane.md
│   ├── data-plane.md
│   ├── networking.md
│   └── swarm.md
│
├── decisions/
├── problems/
├── experiments/
│
├── roadmap.md
└── learning-notes.md
```

Each document should focus on one architectural concern rather than putting the entire project's history into the README.

## 10. Current Architectural Direction

Aperture is evolving from:

```text
P2P file transfer
        │
        ▼
Distributed coordination
        │
        ▼
Multi-peer file distribution
        │
        ▼
Distributed swarm
```

The long-term goal is to understand how a system can combine **centralized coordination with decentralized data movement** while remaining observable, scalable, and resilient.
