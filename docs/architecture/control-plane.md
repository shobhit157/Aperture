# Aperture Control Plane

## 1. Purpose

The Aperture control plane is responsible for **coordination between peers and backend instances**.

It handles signaling, user presence, transfer coordination, and communication between independently running backend instances.

The control plane does **not** carry the actual file data.

> **The control plane decides and coordinates. The data plane transfers.**

---

## 2. High-Level Architecture

```text
                         CONTROL PLANE

                         Java Backend
                              │
              ┌───────────────┼───────────────┐
              │               │               │
           Presence        Signaling       Messaging
              │               │               │
            Redis         Peer Info        SNS/SQS
                                              │
                                    ┌─────────┴─────────┐
                                    ▼                   ▼
                              Backend A            Backend B
```

The main components are:

* **Java backend** — application-level coordination
* **Redis** — shared online-user presence
* **AWS SNS/SQS** — communication between backend instances
* **Peer signaling** — exchange of information required to establish the data-plane connection

---

## 3. Java Backend

The Java backend acts as the central coordination point for the application.

Its responsibilities include:

* Managing connected users
* Tracking online presence
* Handling signaling messages
* Coordinating file-transfer requests
* Routing messages between backend instances
* Providing the application-level communication layer

The backend does not need to receive, store, or forward the actual file being transferred.

For example:

```text
Alice ──► Java Backend
          │
          │ "Alice wants to send
          │  a file to Bob"
          ▼
        Signaling
          │
          ▼
Bob
```

Once the peers have the information required to communicate, the actual transfer happens through the data plane.

---

## 4. User Presence

Aperture needs to know which users are currently online.

Redis provides shared state for this purpose.

A simplified representation is:

```text
Redis

Alice  → online
Bob    → online
Bot17  → online
Bot32  → online
```

This becomes important when multiple backend instances are running.

Without shared presence information, one backend instance would only know about users connected directly to itself.

Redis provides a common place for backend instances to query this information.

---

## 5. Multiple Backend Instances

Aperture is designed to allow more than one backend instance.

For example:

```text
                    Redis
                      │
              shared presence
                      │
          ┌───────────┴───────────┐
          │                       │
      Backend A               Backend B
          │                       │
       Alice                    Bob
```

Alice may be connected to Backend A while Bob is connected to Backend B.

A transfer request therefore cannot always be handled entirely inside one backend instance.

The system needs a mechanism for communicating between backend instances.

---

## 6. Cross-Instance Messaging

AWS SNS/SQS is used to transport control-plane messages between backend instances.

A simplified flow is:

```text
Alice
  │
  ▼
Backend A
  │
  │ control message
  ▼
SNS
  │
  ▼
SQS
  │
  ▼
Backend B
  │
  ▼
Bob
```

This allows backend instances to remain independently deployable while still participating in the same application.

SNS/SQS carries **control messages**, not file contents.

---

## 7. Signaling

Signaling is the process of exchanging information required for peers to establish a connection.

In Aperture, the Java backend coordinates this process.

A simplified flow is:

```text
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

The signaling layer does not establish the actual file-data path itself.

Instead, it helps the peers obtain the information necessary for the data plane to establish communication.

This distinction is important:

```text
Signaling
    │
    ▼
Helps peers find/connect to each other
    │
    ▼
Data plane
    │
    ▼
Transfers the actual bytes
```

---

## 8. Control Plane vs Data Plane

The two planes have different responsibilities.

| Control Plane          | Data Plane          |
| ---------------------- | ------------------- |
| Java                   | Rust + Iroh         |
| Redis                  | Iroh                |
| SNS/SQS                | QUIC/P2P transport  |
| Presence               | Peer connectivity   |
| Signaling              | NAT traversal       |
| Transfer coordination  | File transfer       |
| Cross-server messaging | File/chunk data     |
| No file payload        | Actual file payload |

The separation prevents the Java backend from becoming the path through which every file must travel.

---

## 9. Example: Alice Sends a File to Bob

### Step 1 — Alice connects

```text
Alice → Backend A
```

The backend registers Alice as online.

```text
Redis
Alice → online
```

### Step 2 — Bob is online

Bob may be connected to another backend instance.

```text
Backend B
   │
   └── Bob
```

Redis allows the system to determine that Bob is online.

### Step 3 — Alice requests a transfer

```text
Alice
  │
  │ send file to Bob
  ▼
Backend A
```

### Step 4 — Backend instances coordinate

If Bob belongs to Backend B:

```text
Backend A
    │
    │ control message
    ▼
 SNS/SQS
    │
    ▼
Backend B
```

### Step 5 — Signaling information is exchanged

The relevant peer information is passed between the participants.

```text
Alice ◄──── signaling ────► Bob
```

### Step 6 — Data-plane connection

The Rust/Iroh components attempt to establish a P2P connection.

```text
Alice ═══════════════════► Bob
          Iroh
```

If direct connectivity fails, Iroh can use a relay.

```text
Alice ═════► Relay ═════► Bob
```

### Step 7 — File transfer

The file bytes travel through the data plane.

The Java backend is no longer the file-data path.

---

## 10. Why This Separation Matters

A centralized file server would look like:

```text
Alice ──► Server ──► Bob
              ▲
              │
        file data passes here
```

With Aperture's architecture:

```text
             CONTROL PLANE
Alice ───────► Java ───────► Bob
                 │
              signaling


              DATA PLANE
Alice ═════════════════════► Bob
              Iroh
```

The backend therefore coordinates the transfer without becoming the transport layer for the file itself.

This is the core architectural distinction between Aperture's control plane and data plane.

---

## 11. Failure Boundaries

The separation also creates different failure domains.

### Control-plane failure

Examples:

* Java backend unavailable
* Redis unavailable
* SNS/SQS delivery problem

These can prevent peers from coordinating or starting a transfer.

### Data-plane failure

Examples:

* Direct P2P connection cannot be established
* NAT traversal fails
* Relay connection fails
* Peer disconnects during transfer

These affect the actual peer connection or transfer.

Treating these as separate failure domains makes troubleshooting easier.

---

## 12. Scaling Direction

The control plane is designed to support multiple backend instances:

```text
                    Redis
                      │
              ┌───────┴───────┐
              │               │
          Backend A       Backend B
              │               │
            Alice             Bob
              │               │
              └──── SNS/SQS ──┘
```

As the project grows, the control plane can be extended with:

* More backend instances
* Better routing
* Persistent transfer state
* Authentication and authorization
* Distributed job coordination
* Swarm metadata

The actual file-transfer workload should remain primarily in the P2P data plane.

---

## 13. Architectural Principle

The control plane exists to answer:

> **Who is available, who wants to communicate, and what information is required to establish the connection?**

The data plane answers:

> **How do the peers actually move the data?**

This separation is the foundation for Aperture's transition from simple one-to-one P2P transfers toward a larger distributed file-sharing system.
