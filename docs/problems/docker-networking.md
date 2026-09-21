# Docker Networking

## Problem

Aperture uses Docker to run parts of its infrastructure and peer applications.

Docker provides convenient process isolation and networking, but container networking can make P2P experiments difficult to interpret.

A container may appear to be a separate peer while still sharing the host's underlying network infrastructure.

Therefore, Aperture needs to distinguish:

```text
Application isolation
        ≠
Network isolation
        ≠
Internet-level isolation
```

---

## Docker Network Model

A simplified Docker setup can look like:

```text id="x5c4qy"
                    Linux Host
                       │
             Docker networking
                       │
          ┌────────────┴────────────┐
          ▼                         ▼
      Container A               Container B
          │                         │
        Peer A                    Peer B
```

The containers are isolated processes, but their network path depends on the Docker networking configuration.

For example, with a bridge network:

```text id="r8s1jc"
Container A
    │
    ▼
Docker Bridge
    │
    ▼
Container B
```

This is useful for creating repeatable local experiments.

---

## Container Networking Is Not Automatically Internet NAT Simulation

Running two peers in separate containers does not automatically reproduce the networking conditions of two users on the Internet.

For example:

```text id="j7y2mt"
Container A ── Docker bridge ── Container B
```

is very different from:

```text id="b1q6vd"
Home Network A ── NAT ── Internet ── NAT ── Home Network B
```

The second environment contains independent:

* routers
* NAT devices
* public addresses
* firewall policies
* Internet paths

Therefore, Docker experiments should be treated as controlled local networking tests unless the topology deliberately reproduces additional network layers.

---

## Docker Bridge

A Docker bridge provides a virtual Layer-2 network for containers.

Conceptually:

```text id="n6t0bx"
        Docker Bridge
       /      |      \
      /       |       \
   Peer A   Peer B   Peer C
```

Each container can receive an address on that network.

For example:

```text id="m1z4kf"
Peer A → 172.x.x.2
Peer B → 172.x.x.3
Peer C → 172.x.x.4
```

The exact addresses depend on the configured network.

The important concept is that these addresses are normally private addresses inside the Docker networking environment.

---

## Container-to-Container Communication

When two containers are attached to the same Docker network, they can generally communicate through that network.

```text id="h9d2sv"
Peer A
  │
  ▼
Docker Network
  │
  ▼
Peer B
```

This is useful for testing:

* application protocols
* peer discovery
* signaling
* file transfer
* concurrent connections
* service-to-service communication

However, it does not by itself test difficult NAT traversal scenarios.

---

## Multiple Docker Networks

A more realistic laboratory can place peers on different networks.

```text id="q8z6wa"
Network A                         Network B

Peer A                            Peer B
  │                                  │
  ▼                                  ▼
Bridge A                          Bridge B
  │                                  │
  └──────────── Router ──────────────┘
```

This introduces routing between separate subnets.

For example:

```text id="d2f6mt"
Network A → 10.0.1.0/24
Network B → 10.0.2.0/24
```

A router can connect the two networks.

This provides a better environment for learning routing before introducing NAT.

---

## Linux Network Namespaces

Linux network namespaces provide another way to build controlled networking environments.

A namespace can contain its own:

* network interfaces
* routing table
* ARP/neighbor state
* firewall rules
* sockets

Conceptually:

```text id="w4n1hx"
Linux Host

┌──────────────┐
│ ns-alice     │
│ Peer A       │
└──────┬───────┘
       │
     veth
       │
     bridge
       │
     veth
       │
┌──────┴───────┐
│ ns-bob       │
│ Peer B       │
└──────────────┘
```

This makes namespaces useful for Aperture's networking laboratory.

---

## Docker vs Network Namespaces

Docker itself relies heavily on Linux networking primitives, including network namespaces.

The distinction is mainly in how the environment is managed.

```text id="5e3v4n"
Docker
  │
  └── Convenient container management
        │
        └── Linux networking underneath


Manual namespaces
  │
  └── Fine-grained networking laboratory
```

For Aperture:

* Docker is useful for repeatable application deployment.
* Linux namespaces are useful for understanding and controlling networking behavior.
* Both can be used together.

---

## Router Namespace

A router namespace can connect two simulated networks.

```text id="m0q4ye"
        Network A
            │
            ▼
       ┌─────────┐
       │ Router  │
       └────┬────┘
            │
            ▼
        Network B
```

The router has an interface in each network.

Conceptually:

```text id="q5n8cr"
10.0.1.1/24
     │
   Router
     │
10.0.2.1/24
```

Hosts then use the router as their default gateway when communicating outside their local subnet.

---

## NAT

NAT can be added at the router boundary.

```text id="v6t9kx"
Private Network
      │
      ▼
   NAT Router
      │
      ▼
 External Network
```

This creates a more realistic environment for studying P2P connectivity.

The experiment can then examine:

```text id="s2x7mw"
Private IP
    ↓
NAT mapping
    ↓
External endpoint
    ↓
Peer connectivity
```

This is more representative of Internet-style networking than a simple shared Docker bridge.

---

## Why This Matters to Iroh

Iroh's connectivity behavior depends on the network environment.

For example:

```text id="x1d8pk"
Simple Docker network
        │
        ▼
Direct connection likely

Simulated NAT
        │
        ▼
NAT traversal becomes relevant

Real Internet NAT
        │
        ▼
Real-world connectivity behavior
```

Therefore, Iroh results should always be interpreted together with the network topology in which they were obtained.

---

## Host Networking

Docker can also use host networking.

Conceptually:

```text id="a7r3yp"
Container
    │
    ▼
Host network stack
```

This removes some of the normal container network isolation.

It can be useful for specific infrastructure components, but it changes the networking model significantly.

A host-networked peer should therefore not be compared directly with a peer running behind an isolated Docker bridge without accounting for the difference.

---

## P2P Testing Pitfall

A common experimental mistake is:

```text id="p8n2cs"
Container A
     │
     └──── Docker network ──── Container B

"Direct P2P works!"
```

and then concluding:

> P2P NAT traversal works on the Internet.

That conclusion is too strong.

The test demonstrated connectivity in that particular network environment.

A meaningful Internet NAT experiment requires a topology containing the relevant routing and NAT boundaries.

---

## Recommended Test Progression

Aperture's networking experiments can progress from simple to complex.

### Level 1 — Same Docker Network

```text id="h4p1ks"
A ── Docker bridge ── B
```

Purpose:

* application connectivity
* basic P2P transfer
* protocol debugging

### Level 2 — Separate Networks

```text id="r7d0ma"
A ── Network A ── Router ── Network B ── B
```

Purpose:

* routing
* default gateways
* multiple subnets

### Level 3 — Simulated NAT

```text id="c3y6fn"
A ── Private Network ── NAT ── Network ── B
```

Purpose:

* NAT behavior
* endpoint mapping
* traversal experiments

### Level 4 — Real Network

```text id="q9x2ls"
Local Peer ── Internet ── AWS Peer
```

Purpose:

* real NAT
* real routing
* real latency
* real firewall behavior

Each level answers different questions.

---

## Docker and Aperture Infrastructure

Docker is particularly useful for running the control-plane infrastructure.

For example:

```text id="b6v3wa"
Docker Compose

┌───────────────┐
│ Java Backend  │
└───────┬───────┘
        │
   ┌────┴────┐
   ▼         ▼
 Redis     Metrics
```

Multiple backend instances can also be created:

```text id="e2q8jc"
             Load Balancer
                  │
          ┌───────┴───────┐
          ▼               ▼
      Backend A       Backend B
          │               │
          └───────┬───────┘
                  ▼
                Redis
```

This is useful for testing the control-plane scaling model.

---

## Docker and the Data Plane

Peer containers can run the Rust/Iroh application.

```text id="p1j7wd"
Container
    │
    └── Rust Peer
          │
          ▼
        Iroh
```

However, peer containers should be deployed with a clearly understood network configuration.

The test documentation should record:

* Docker network type
* subnet
* gateway
* published ports
* container IP
* host IP
* NAT configuration
* routing configuration

Without this information, reproducing a networking result becomes difficult.

---

## Observability

Docker networking experiments should be observed at multiple layers.

### Application

```text id="c9x2ap"
Peer logs
Connection state
Transfer status
```

### Transport

```text id="u8f3ns"
Iroh connection path
QUIC behavior
Connection timing
```

### Network

```text id="m7r5dy"
IP addresses
Routes
Interfaces
NAT mappings
```

### Packet Level

Tools such as packet capture can help inspect:

```text id="n3w8vk"
Packet
  ↓
Interface
  ↓
Bridge
  ↓
Router/NAT
```

The goal is to connect what the application reports with what the Linux network is actually doing.

---

## What We Learned

### 1. Containers are not automatically independent Internet clients

Multiple containers can share the host's networking infrastructure.

### 2. Docker bridges are useful controlled environments

They are excellent for application and basic P2P testing.

### 3. Linux namespaces provide deeper control

Namespaces allow the networking laboratory to explicitly construct interfaces, bridges, routes, routers, and NAT.

### 4. Topology determines what an experiment proves

A successful local container test should not automatically be generalized to real Internet NAT behavior.

### 5. Networking should be documented with the application

The P2P logs alone are not enough.

The underlying topology must also be recorded.

---

## Current Direction

Aperture uses Docker for reproducible deployment and Linux networking environments for controlled networking experiments.

The intended progression is:

```text id="w2j6se"
Docker
   │
   ▼
Basic P2P
   │
   ▼
Linux namespaces
   │
   ▼
Multiple networks
   │
   ▼
Routing
   │
   ▼
NAT
   │
   ▼
Real Internet
   │
   ▼
AWS / Multi-region experiments
```

This progression allows networking problems to be isolated instead of treating every P2P failure as an Iroh problem.

---

## Related Documentation

* `architecture/networking.md` — Aperture networking model
* `architecture/data-plane.md` — P2P data plane
* `problems/direct-connection-failure.md` — NAT traversal investigation
* `experiments/local-p2p.md` — local namespace experiments
* `experiments/cross-network.md` — real network experiments
