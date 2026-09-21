# Relay Fallback

## Problem

Aperture prefers direct P2P connections between peers.

However, direct connectivity cannot be guaranteed across every network configuration.

Peers may be behind:

* restrictive NATs
* firewalls
* corporate networks
* cloud networking boundaries
* networks that do not permit successful hole punching

When direct connectivity fails, the system needs another way to establish communication.

---

## Expected Behavior

The preferred connection strategy is:

```text id="v2c5rx"
Peer A
   │
   ▼
Attempt direct P2P
   │
   ├── Success ──► Direct transfer
   │
   └── Failure
          │
          ▼
      Relay fallback
          │
          ▼
      P2P transfer
```

The relay should therefore be treated as a **fallback path**, rather than the default data path.

---

## Direct Path

When NAT traversal succeeds:

```text id="xqwlzi"
Peer A ═══════════════════► Peer B
             Direct
```

The file data travels between the peers without requiring a relay for the data path.

This is the preferred path for experiments where direct connectivity is possible.

---

## Relay Path

When a direct connection cannot be established:

```text id="kpf8ds"
Peer A ─────────► Relay ─────────► Peer B
```

The relay provides a reachable intermediary through which the peers can communicate.

The application does not need to redesign the transfer protocol simply because the underlying network path changed.

---

## Relay vs Signaling Server

Aperture contains both signaling infrastructure and relay infrastructure, but they have different responsibilities.

### Java Signaling Server

The Java backend handles control-plane operations such as:

* peer coordination
* endpoint exchange
* user presence
* transfer signaling
* cross-instance messaging

It does not carry the actual file contents.

### Iroh Relay

The relay provides a network path when direct peer-to-peer connectivity cannot be established.

```text id="g2j6jm"
CONTROL PLANE

Alice ──► Java Backend ──► Bob
              │
          coordination


DATA PLANE

Alice ═══════════════════► Bob
           direct

or

Alice ─────► Relay ─────► Bob
             fallback
```

The two systems solve different problems.

---

## Relay Does Not Mean Centralized File Storage

A relay should not be confused with a traditional file server.

A traditional centralized architecture might look like:

```text id="h2hy0s"
Alice ──► Server ──► Bob
          │
       File data
          │
       Storage
```

A relay-based P2P architecture is different:

```text id="7yrj6f"
Alice ──► Relay ──► Bob
          │
       Forwarding
       infrastructure
```

The relay exists to provide connectivity.

It is not the application's permanent file-storage layer.

---

## Why Direct Connectivity Can Fail

A direct connection can fail even when both peers know each other's endpoints.

For example:

```text id="5u6i7f"
Peer A                    Peer B
  │                          │
  │     endpoint known       │
  │◄────────────────────────►│
  │                          │
  X direct path unavailable  X
```

Possible causes include:

* incompatible NAT behavior
* restrictive firewall rules
* unavailable inbound mappings
* symmetric or otherwise restrictive NAT behavior
* network policy
* routing limitations

The exact cause should be established through experiments rather than assumed from a single failure.

---

## Relay as Graceful Degradation

Aperture's networking model is therefore:

```text id="a5m8dk"
                  Connection
                      │
             Attempt direct path
                      │
             ┌────────┴────────┐
             │                 │
          Success             Fail
             │                 │
             ▼                 ▼
        Direct P2P            Relay
             │                 │
             └────────┬────────┘
                      ▼
                  Transfer
```

This allows the application to remain usable across a wider range of network environments.

The trade-off is that relay paths can introduce additional network distance and infrastructure dependency.

---

## Impact on Transfer Performance

A direct path and a relay path can have different performance characteristics.

A relay may introduce:

* additional latency
* additional network hops
* relay bandwidth constraints
* additional infrastructure load

Therefore, a slow transfer should not immediately be attributed to the application itself.

A useful investigation separates:

```text id="2e2s8h"
Connection establishment
          +
Network path
          +
Transfer throughput
          +
Disk I/O
          +
CPU / processing
```

For every significant transfer experiment, Aperture should record whether the path was direct or relayed.

---

## Slow Transfer Investigation

An earlier cross-network experiment showed that a small file transfer could take significantly longer than expected.

The first hypothesis was that the network path might be contributing to the problem.

However, several variables can affect transfer speed:

```text id="3d7y2f"
Possible causes

Relay path
Network latency
Relay bandwidth
QUIC behavior
Buffering
Chunking
Disk I/O
CPU
Application implementation
```

Therefore, the correct approach is to measure each stage rather than assuming the relay is responsible.

---

## Self-Hosted Relay

Aperture can eventually operate its own relay infrastructure.

Conceptually:

```text id="7d8m1v"
                Aperture
                    │
             Self-hosted relay
                    │
             ┌──────┴──────┐
             ▼             ▼
          Peer A         Peer B
```

Potential reasons to experiment with self-hosted relays include:

* infrastructure control
* predictable deployment
* capacity testing
* regional placement
* observability
* reduced dependence on external infrastructure
* experimenting with relay selection

The decision should be based on measured requirements rather than assuming that self-hosting is automatically better.

---

## Regional Relay Experiments

A future experiment could deploy relays in different regions.

For example:

```text id="h7l5fj"
          Mumbai Relay
               │
               │
Delhi ─────────┼──────── Singapore
               │
          Singapore Relay
```

The experiment could compare:

* direct connection
* public relay
* self-hosted relay
* different relay regions

Measurements should include:

* connection establishment time
* transfer duration
* throughput
* latency
* relay usage
* failure rate

This would help determine how network geography affects Aperture's P2P performance.

---

## Relay Selection

If multiple self-hosted relays are eventually deployed, Aperture may need a relay-selection strategy.

Possible factors include:

* geographic distance
* observed latency
* availability
* relay capacity
* historical performance
* current load

This should remain an application/infrastructure concern rather than being mixed with file-transfer scheduling.

---

## Failure Model

A relay itself can fail.

```text id="3q5d4u"
Direct connection
      │
      X

Relay A
      │
      X

Possible Relay B
      │
      ▼
Transfer
```

A resilient deployment could eventually provide multiple relay options.

However, this introduces additional infrastructure complexity and should be added only when experiments demonstrate the need.

---

## What We Learned

### 1. Direct P2P should be preferred

When a direct path is available, it avoids unnecessary relay infrastructure.

### 2. Relay is a connectivity fallback

The relay provides an alternative path when direct connectivity fails.

### 3. Signaling and relay are different

The Java backend coordinates peers; the relay provides network connectivity.

### 4. Relay does not turn Aperture into centralized storage

The relay is infrastructure for forwarding traffic, not the application's file repository.

### 5. Performance must be measured

Relay usage may affect latency and throughput, but the effect needs to be measured under controlled conditions.

### 6. Self-hosting is an experiment

Running a private relay provides more control, but also introduces operational responsibility.

---

## Current Direction

For the current stage of Aperture:

```text id="9f1kqz"
Discovery
   │
   ▼
Public DNS/Pkarr
   │
   ▼
Iroh connectivity
   │
   ├── Direct P2P ──► preferred
   │
   └── Relay ───────► fallback
```

The immediate goal is to understand and measure direct versus relay connectivity.

Self-hosted relay infrastructure can then be evaluated experimentally, including the possibility of regional relays.

---

## Related Documentation

* `architecture/networking.md` — networking concepts
* `architecture/data-plane.md` — Iroh data-plane architecture
* `problems/direct-connection-failure.md` — direct connectivity investigation
* `experiments/cross-network.md` — cross-network testing
* `decisions/005-self-hosted-relay.md` — self-hosted relay decision
