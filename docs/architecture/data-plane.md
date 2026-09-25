# Aperture Data Plane

## 1. Purpose

The Aperture data plane is responsible for the **actual movement of file data between peers**.

It is implemented using **Rust and Iroh**.

The data plane is intentionally separated from the Java control plane.

> **The control plane coordinates the transfer. The data plane performs the transfer.**

---

## 2. High-Level Architecture

```text
                         DATA PLANE

             ┌─────────────────────────────┐
             │        Rust + Iroh          │
             └──────────────┬──────────────┘
                            │
                  Connectivity layer
                            │
                 ┌──────────┴──────────┐
                 │                     │
            Direct P2P              Relay
                 │                     │
                 ▼                     ▼
              Alice ◄───────────────► Bob
```

Aperture prefers a direct peer-to-peer connection.

When direct connectivity is not possible, Iroh can use a relay to provide a path between the peers.

---

## 3. Responsibilities

The data plane is responsible for:

* Peer identity
* Peer connectivity
* NAT traversal
* Direct P2P connections
* Relay fallback
* File transfer
* Chunk transfer
* Transfer progress
* File integrity verification

The data plane does not decide:

* Which users are allowed to communicate
* Which users are online
* Which backend instance owns a connection
* Which distribution job a peer belongs to

Those responsibilities belong to the control plane and application logic.

---

## 4. Rust Peer Application

Each peer runs a Rust-based component responsible for the P2P data path.

Conceptually:

```text
                Peer
                 │
        ┌────────┴────────┐
        │                 │
   Application        Iroh layer
        │                 │
        └────────┬────────┘
                 │
              Network
```

The Rust component handles the networking required to communicate with another peer.

The Java application can therefore remain focused on application-level coordination while Rust/Iroh handles the P2P data path.

---

## 5. Peer Identity

P2P communication requires peers to have stable identities.

A peer identity allows one peer to distinguish another peer from an arbitrary network endpoint.

Conceptually:

```text
Alice
  │
  └── Peer Identity A

Bob
  │
  └── Peer Identity B
```

The identity is different from an IP address.

An IP address can change because of:

* DHCP
* Network changes
* NAT
* Mobile networks
* Cloud infrastructure

A peer identity provides a more stable way of identifying the participant.

---

## 6. Discovery, Connectivity and Transfer

These are separate problems.

### Discovery

Answers:

> Where can I find information about this peer?

### Connectivity

Answers:

> Can the two peers establish a network connection?

### Transfer

Answers:

> How do we move the file data once the connection exists?

Conceptually:

```text
Discovery
    │
    ▼
Find peer information
    │
    ▼
Connectivity
    │
    ├── Direct connection
    │
    └── Relay fallback
    │
    ▼
Transport
    │
    ▼
File transfer
```

Keeping these concepts separate is important when diagnosing P2P failures.

---

## 7. Direct P2P Connectivity

The preferred path is:

```text
Alice ═════════════════════► Bob
             Direct P2P
```

The peers communicate directly without sending the file through the Aperture Java backend.

This is the desired path because the data does not need to traverse the application server.

---

## 8. NAT Traversal

Peers are often located behind private networks.

For example:

```text
Alice Network                    Bob Network

10.0.0.10                        192.168.1.20
     │                                │
     ▼                                ▼
   NAT A                            NAT B
     │                                │
     └────────── Internet ────────────┘
```

The private addresses are not directly routable across the public Internet.

P2P systems therefore need mechanisms that allow peers to establish connectivity despite NAT.

Iroh provides the connectivity mechanisms required to attempt direct communication.

---

## 9. Direct Connection vs Relay

Aperture follows the general strategy:

```text
                 Start connection
                       │
                       ▼
              Attempt direct P2P
                       │
                ┌──────┴──────┐
                │             │
              Works         Fails
                │             │
                ▼             ▼
           Direct P2P       Relay
                │             │
                └──────┬──────┘
                       ▼
                    Transfer
```

A relay is therefore a fallback connectivity mechanism.

It does not replace the control plane.

It also does not mean that the Java server is carrying the file.

---

## 10. Relay Path

When direct connectivity fails, the data path can become:

```text
Alice
  │
  │ encrypted connection
  ▼
Iroh Relay
  │
  │ encrypted connection
  ▼
Bob
```

The relay provides a network path between peers that cannot establish a direct connection.

The relay should be considered part of the **data-plane connectivity infrastructure**, not the application control plane.

---

## 11. File Transfer

Once connectivity has been established, the peer application transfers the file through the P2P connection.

Conceptually:

```text
Alice
  │
  │ file
  ▼
Rust / Iroh
  │
  │ P2P connection
  ▼
Rust / Iroh
  │
  ▼
Bob
```

The Java backend does not need to receive the file and forward it to Bob.

This is the central reason for separating the data plane from the control plane.

---

## 12. File Integrity

A transfer is not considered successful simply because the expected number of bytes arrived.

The received file must also be verified.

Aperture uses cryptographic hashing to verify file integrity.

Conceptually:

```text
Sender

File
 │
 ▼
SHA-256
 │
 ▼
Expected Hash
```

The receiver independently calculates the hash:

```text
Receiver

Received File
 │
 ▼
SHA-256
 │
 ▼
Calculated Hash
```

Then:

```text
Expected Hash == Calculated Hash
              │
             YES
              │
              ▼
       Transfer verified
```

A mismatch indicates that the received content does not match the expected content.

---

## 13. Chunked Transfers

Aperture's current architecture is being extended toward chunk-based file distribution.

Instead of treating a large file as one indivisible object:

```text
1 GB File
```

the future system can represent it as:

```text
1 GB File
   │
   ├── Chunk 1
   ├── Chunk 2
   ├── Chunk 3
   ├── ...
   └── Chunk N
```

Each chunk can have its own integrity information.

This becomes important for:

* Resumable transfers
* Parallel transfers
* Distributed file sharing
* Swarm-based distribution
* Partial availability

The chunk scheduler is application logic and is separate from Iroh's underlying connectivity mechanisms.

---

## 14. One-to-One Transfer

The current primary use case is:

```text
Alice ───────────────────► Bob
             Iroh
```

The control plane coordinates the participants.

The data plane transfers the file.

This can be summarized as:

```text
             CONTROL PLANE

Alice ─────► Java Backend ─────► Bob
                  │
               signaling


             DATA PLANE

Alice ═════════════════════════► Bob
                 Iroh
```

---

## 15. Future Swarm Data Plane

The future distributed architecture changes the data-plane topology.

Instead of:

```text
Admin ─────────► Bot1
      ─────────► Bot2
      ─────────► Bot3
      ─────────► Bot4
```

peers can exchange pieces:

```text
              Admin
             /     \
            ▼       ▼
          Bot1     Bot2
          /  \     /  \
         ▼    ▼   ▼    ▼
       Bot3 Bot4 Bot5 Bot6
```

For example:

```text
Bot50 needs Chunk 4
        │
        ▼
Find a peer that owns Chunk 4
        │
        ▼
Bot17
        │
        │ P2P transfer
        ▼
Bot50
```

After receiving and verifying the chunk, Bot50 can become another provider.

This is the foundation for a future distributed file swarm.

---

## 16. Failure Boundaries

Data-plane failures are different from control-plane failures.

Examples include:

### Connectivity failure

```text
Peer A ──X── Peer B
```

Possible causes:

* NAT restrictions
* Firewall rules
* Network failure
* Connectivity timeout

### Relay failure

```text
Peer A ──X── Relay ──X── Peer B
```

The fallback path itself can also fail.

### Transfer failure

A connection may succeed but the transfer can still fail because of:

* Peer disconnection
* Interrupted transfer
* Corrupted data
* Application-level errors

These failures should be recorded separately when running experiments.

---

## 17. Observability

P2P networking is difficult to debug without knowing what path the connection actually took.

Aperture therefore treats connection-path information and transfer measurements as important experimental data.

Useful measurements include:

* Direct vs relay connection
* Connection establishment time
* File size
* Transfer duration
* Throughput
* Retries
* Transfer failures
* Integrity verification result

For example:

```text
Transfer
├── File: 50 MB
├── Path: Direct P2P
├── Duration: measured
├── Throughput: measured
└── Integrity: SHA-256 verified
```

These measurements allow networking changes to be evaluated using evidence rather than assumptions.

---

## 18. Architectural Principle

The data plane exists to answer:

> **How do peers establish connectivity and move data directly between themselves?**

The control plane exists to answer:

> **Who should communicate and how should the transfer be coordinated?**

Keeping those responsibilities separate allows Aperture to experiment with different networking mechanisms without redesigning the entire application.
