# Decision 001 — Java Signaling Server

## Context

Aperture needs a control plane to coordinate peers before they establish a P2P data connection.

The control plane needs to handle:

* user presence
* peer registration
* endpoint exchange
* transfer signaling
* chat messaging
* communication between multiple backend instances

The actual file data should not pass through this server.

---

## Initial Requirement

The architecture needed a backend that could:

```text id="f5n8rd"
Client
  │
  ▼
Signaling Server
  │
  ├── Peer information
  ├── Presence
  ├── Transfer coordination
  └── Messaging
```

while keeping the actual file-transfer path separate:

```text id="d4c7px"
Client A ═══════════════► Client B
             Iroh
```

---

## Decision

Java was chosen for the Aperture control-plane server.

The backend is responsible for coordination rather than carrying file data.

---

## Why Java?

Java was already being used for the project's networking and backend development, making it possible to build the control plane while simultaneously exploring:

* TCP networking
* concurrency
* thread pools
* concurrent collections
* event-driven architecture
* distributed messaging
* observability
* Spring Boot

This also allowed the networking concepts being learned in the project to be applied directly to the production-oriented architecture.

---

## Responsibilities

The Java backend handles:

```text id="q7p2vc"
Java Backend
│
├── User presence
├── Peer registration
├── Endpoint exchange
├── Transfer signaling
├── Chat
├── Cross-instance messaging
└── Control-plane state
```

It does **not** handle:

```text id="j5m8ax"
File bytes
Large file storage
P2P data forwarding
```

Those remain part of the data plane.

---

## Multiple Backend Instances

The design allows multiple Java backend instances.

```text id="n2s4yr"
             Clients
                │
        ┌───────┴───────┐
        ▼               ▼
    Backend A       Backend B
        │               │
        └───────┬───────┘
                ▼
        Distributed messaging
```

This prevents the signaling layer from being tied to a single server instance.

Redis and AWS SNS/SQS are used to support distributed control-plane behavior.

---

## Alternatives

Possible alternatives included:

* Node.js
* Go
* Python
* Rust
* a specialized signaling service

The project could have used any of these for the coordination layer.

The decision was primarily based on the project's existing Java implementation and the opportunity to use Java to explore backend concurrency and distributed systems concepts.

---

## Trade-offs

### Benefits

* Strong Java ecosystem
* Mature concurrency primitives
* Spring Boot ecosystem
* Good observability integrations
* Familiarity with Java networking
* Clear separation from the Rust/Iroh data plane

### Costs

* Additional language/runtime alongside Rust
* More components to maintain
* Java is not responsible for the actual P2P transport

The project accepts this complexity because the control plane and data plane have different responsibilities.

---

## Result

The Java backend became Aperture's control plane.

The resulting architecture is:

```text id="v8s3kc"
              APERTURE
                  │
        ┌─────────┴─────────┐
        │                   │
   Java Control         Rust + Iroh
      Plane              Data Plane
        │                   │
   Signaling             P2P Data
   Presence              Transfer
   Messaging
```

This establishes the core architectural principle:

> **The control plane coordinates the transfer; the data plane performs the transfer.**

---

## Future Consideration

The choice of Java is not intended to imply that Java must remain the control-plane implementation forever.

If future requirements demonstrate a significant advantage from another technology, the control-plane implementation can be reconsidered while preserving the architectural boundary.

The important decision is the **separation of responsibilities**, not the programming language itself.
