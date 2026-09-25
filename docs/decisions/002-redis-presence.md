# Decision 002 — Redis for User Presence

## Context

Aperture needs to track which users or peers are currently online.

A single backend instance could maintain this information in local memory, but Aperture is designed to support multiple backend instances.

For example:

```text id="p5x3nf"
             Clients
                │
        ┌───────┴───────┐
        ▼               ▼
    Backend A       Backend B
```

If presence is stored only inside Backend A's memory, Backend B cannot reliably know which users are connected to Backend A.

---

## Problem

Local in-memory presence creates a consistency problem.

Example:

```text id="x7c4ma"
Alice connected to Backend A

Backend A:
Alice → ONLINE

Backend B:
Alice → UNKNOWN
```

A distributed control plane needs shared presence information.

---

## Decision

Redis was chosen as the shared presence store.

Conceptually:

```text id="d8m2vq"
Backend A ──┐
            │
Backend B ──┼──► Redis
            │
Backend C ──┘
```

All backend instances can access the same logical presence state.

---

## Presence Model

Conceptually, Aperture can maintain information such as:

```text id="z6k8wp"
Alice → ONLINE
Bob   → ONLINE
Carol → OFFLINE
```

The stored information can also associate a user with the backend instance currently handling its connection.

For example:

```text id="c2n9rx"
Alice → Backend A
Bob   → Backend B
```

This becomes useful when routing messages between backend instances.

---

## Why Redis?

Redis provides:

* fast in-memory access
* shared state across backend instances
* key-value data structures
* expiration mechanisms
* atomic operations
* mature client libraries

Presence is also naturally suited to data that changes frequently and does not need to be treated as permanent application data.

---

## Presence vs Persistent Data

Redis presence should not be confused with the application's permanent user database.

Conceptually:

```text id="r8w4hz"
Persistent Data
      │
      ▼
PostgreSQL / Database
      │
      └── Accounts, configuration, etc.


Ephemeral State
      │
      ▼
Redis
      │
      └── Online presence
```

The exact persistent-data architecture can evolve independently.

---

## Expiration and Failure

Presence is inherently temporary.

A backend can disappear without explicitly telling Redis that a user went offline.

Therefore, presence entries should have an expiration strategy.

Conceptually:

```text id="n7q2vk"
User connects
    │
    ▼
Presence registered
    │
    ▼
Expiration refreshed
    │
    ├── Connection alive
    │      ↓
    │   refresh
    │
    └── Connection lost
           ↓
       expiration
           ↓
       user offline
```

This reduces the chance of stale online users remaining indefinitely.

The exact expiration and heartbeat values should be treated as implementation parameters and measured under failure scenarios.

---

## Message Routing

Shared presence becomes particularly useful when multiple backend instances are running.

Example:

```text id="j3c8qs"
Alice → Backend A

Bob → Backend B
```

If Alice wants to communicate with Bob:

```text id="m6r4vz"
Alice
  │
  ▼
Backend A
  │
  │ lookup Bob
  ▼
Redis
  │
  │ Bob → Backend B
  ▼
Backend A
  │
  ▼
Cross-instance messaging
  │
  ▼
Backend B
  │
  ▼
Bob
```

Redis therefore provides the routing information, while the messaging system transports the message between backend instances.

---

## Alternatives

Possible alternatives included:

* local in-memory maps
* PostgreSQL
* dedicated service discovery
* distributed caches
* Redis

### Local memory

Simple, but does not provide shared state across backend instances.

### PostgreSQL

Can store presence, but frequent ephemeral updates are not the primary workload this component is intended to handle.

### Dedicated service discovery

Could solve some discovery problems, but introduces infrastructure intended for a broader service-discovery use case.

### Redis

Provides a simple shared, fast, expiring state store that fits the presence requirement.

---

## Trade-offs

### Benefits

* Shared state between backend instances
* Fast lookups
* Expiration support
* Simple data model
* Supports horizontal scaling

### Costs

* Additional infrastructure
* Another dependency that can fail
* Presence consistency must be designed carefully
* Redis availability becomes part of the control-plane dependency chain

---

## Failure Behavior

Aperture should not assume Redis is always available.

Conceptually:

```text id="k1d7sx"
Client
  │
  ▼
Backend
  │
  X Redis unavailable
  │
  ▼
Controlled degradation
```

The system should distinguish between:

* user actually offline
* presence information unavailable
* backend disconnected
* Redis unavailable

These are different states.

---

## Why Redis Does Not Carry File Data

Redis is part of the control plane.

It should not be used as the P2P file-transfer path.

```text id="a9p5ct"
CONTROL PLANE

Java
 │
 └── Redis
       │
       └── Presence / control state


DATA PLANE

Rust + Iroh
 │
 └── File bytes
```

This preserves the separation between coordination and data transfer.

---

## Result

Redis became the shared presence layer for Aperture's distributed Java backend.

The resulting model is:

```text id="s3q8nb"
              Clients
                 │
          ┌──────┴──────┐
          ▼             ▼
      Backend A     Backend B
          │             │
          └──────┬──────┘
                 ▼
               Redis
          shared presence
```

This allows the control plane to scale beyond a single backend instance while keeping presence state centralized at the appropriate control-plane layer.

---

## Future Considerations

As Aperture evolves, the presence model may need to account for:

* large numbers of connected peers
* heartbeat frequency
* stale-session cleanup
* backend failures
* Redis failures
* multiple regions
* regional presence
* distributed availability state for swarm transfers

These should be evaluated through experiments as the system grows.

---

## Architectural Principle

> **Redis stores shared control-plane state; it does not become part of the P2P data path.**

---

## Related Documentation

* `architecture/control-plane.md`
* `architecture/swarm.md`
* `decisions/001-java-signaling.md`
* `problems/docker-networking.md`
