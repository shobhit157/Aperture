# Direct Connection Failure

## Problem

Aperture is designed to establish direct P2P connections between peers whenever possible.

During early networking experiments, direct connections did not always succeed.

This was especially noticeable when testing peers inside Linux network namespaces and when testing peers across different networks.

The important question was:

> Why can two peers discover each other but still fail to establish a direct connection?

---

## Expected Behavior

The expected flow was:

```text
Peer A
   │
   │ discover Peer B
   ▼
Peer B endpoint
   │
   ▼
NAT traversal
   │
   ▼
Direct connection
   │
   ▼
P2P data transfer
```

The coordination server should only help peers discover and coordinate with each other.

The actual file data should then travel directly between the peers.

---

## What Actually Happened

Several different behaviors were observed during testing:

```text
Discovery succeeds
       │
       ▼
Endpoints exchanged
       │
       ▼
Direct connection
       │
       X
       │
       ▼
Connection failure / timeout
```

In other experiments:

```text
Discovery succeeds
       │
       ▼
Direct connection fails
       │
       ▼
Relay connection succeeds
       │
       ▼
File transfer succeeds
```

This demonstrated that **discovery and connectivity are separate problems**.

A peer can know where another peer is without being able to establish a direct network path to it.

---

## Linux Namespace Experiments

Linux network namespaces were used to create controlled networking environments.

A simplified setup was:

```text
ns-alice                 ns-bob
   │                        │
   └────────── Network ─────┘
```

More complex experiments introduced separate networks, routers, and NAT behavior.

The namespaces allowed the project to reproduce networking scenarios without requiring many physical machines.

---

## Hole Punching

Aperture's P2P layer uses Iroh's connectivity mechanisms to attempt direct communication.

Conceptually, both peers participate in establishing a path:

```text
Peer A                         Peer B
  │                              │
  │──── outbound traffic ───────►│
  │                              │
  │◄──── outbound traffic ───────│
  │                              │
  └──────── direct path ─────────┘
```

This can work when the network's NAT behavior allows the required mappings to be established.

It is not guaranteed for every NAT configuration.

---

## Same-Host Testing

One important discovery was that testing multiple peers on the same physical host is not equivalent to testing two independent Internet clients.

For example:

```text
                    Host
             ┌───────────────┐
             │               │
        ns-alice          ns-bob
             │               │
             └──── NAT / host ┘
```

Both peers may share networking infrastructure that would not exist between two independent machines.

This can introduce behaviors such as:

* NAT hairpinning
* shared host networking
* unusual routing paths
* namespace-specific forwarding behavior

Therefore, a successful or failed same-host test should not automatically be treated as proof of how arbitrary Internet NATs behave.

---

## NAT Hairpinning

One suspected cause of same-host failures was NAT hairpin behavior.

Hairpinning refers to a situation where a host behind a NAT attempts to reach another internal host through the NAT's external address.

Conceptually:

```text
        Internet-facing address
                 │
                 ▼
              Router
             /      \
            /        \
       Peer A       Peer B
```

A NAT implementation may or may not correctly support this type of path.

Therefore, testing two peers behind the same simulated NAT requires care.

The experiment should distinguish:

```text
Same NAT + same host
```

from:

```text
Different real networks
```

---

## Cross-Network Testing

The next step was to move beyond same-host namespace experiments.

A more realistic topology is:

```text
Local Network                  Cloud Network

Peer A                         Peer B
   │                              │
   ▼                              ▼
NAT / Router                  AWS Network
   │                              │
   └──────── Internet ────────────┘
```

This provides a more realistic test of:

* NAT traversal
* public/private addresses
* routing
* Internet latency
* firewall behavior
* direct connectivity
* relay fallback

---

## Relay Fallback

When direct connectivity cannot be established, Iroh can use a relay path.

Conceptually:

```text
Peer A ─────► Relay ─────► Peer B
```

The relay allows communication even when the peers cannot establish a direct path.

This creates an important distinction:

```text
Direct path
A ─────────────────────► B

Relay path
A ─────► Relay ─────► B
```

The relay is a connectivity fallback.

It is not the Java signaling server.

---

## Important Architectural Lesson

The experiment established that the following are different layers:

```text
Discovery
    ↓
"Where is the peer?"

Connectivity
    ↓
"Can I establish a path?"

Transport
    ↓
"Can I send bytes reliably?"

Application
    ↓
"What data should be transferred?"
```

A failure at one layer does not necessarily indicate a failure at another.

For example:

```text
Discovery ✓
Connectivity ✗
```

means the peer was successfully identified but no usable direct path was established.

---

## Evidence to Record

Future direct-connectivity experiments should record:

* peer identities
* network topology
* private addresses
* observed endpoints
* NAT configuration
* connection path
* direct or relay
* connection establishment time
* failure type
* timeout duration
* retry behavior
* file size
* transfer duration

For example:

```text
Experiment
-----------
Topology: Local namespace → Router/NAT → AWS
Peers: Alice, Bob
File: 50 MB

Discovery: Success
Direct connection: Failed
Relay: Success
Transfer: Success
Integrity: Verified
```

This makes the experiment reproducible instead of relying on memory.

---

## Why a Relay Does Not Mean the P2P Architecture Failed

A relay path does not invalidate the P2P design.

The architecture is:

```text
             Iroh connectivity
                    │
           ┌────────┴────────┐
           │                 │
       Direct path        Relay path
       preferred          fallback
```

The important property is that the application can use either path without changing the higher-level transfer protocol.

The transfer remains peer-oriented even when the network path requires a relay.

---

## Investigation Strategy

When a direct connection fails, changing several components simultaneously makes the result difficult to interpret.

The investigation should therefore isolate variables.

For example:

### Experiment 1

Same host, simple namespaces.

### Experiment 2

Different namespaces with separate networks.

### Experiment 3

Namespaces with simulated routing/NAT.

### Experiment 4

Local machine ↔ AWS instance.

### Experiment 5

Different geographic/cloud regions.

Each experiment changes a limited part of the network environment.

---

## What We Learned

### 1. Discovery is not connectivity

Finding a peer does not guarantee that a network path can be established.

### 2. NAT behavior matters

Different NAT and firewall configurations can produce different connectivity results.

### 3. Same-host tests have limitations

They are useful controlled experiments but do not represent every real-world Internet topology.

### 4. Direct P2P should be measured

The system should record whether a connection was direct or relayed rather than assuming the path.

### 5. Relay is an important fallback

A relay provides connectivity when direct NAT traversal cannot succeed.

### 6. Network experiments need evidence

Topology, endpoints, connection path, timing, and transfer results should be recorded for every significant experiment.

---

## Current Status

Direct P2P connectivity has been successfully demonstrated in some environments, while other network conditions require relay fallback.

The investigation is therefore not treated as a single "working/not working" result.

Instead, Aperture treats connectivity as an experimental property of the network environment.

Future tests will compare:

* local namespaces
* simulated NAT
* real home networks
* AWS instances
* different geographic regions
* direct vs relay paths

The objective is to understand **why** a path succeeds or fails, not simply whether it succeeds.

---

## Related Documentation

* `architecture/networking.md` — networking model and terminology
* `architecture/data-plane.md` — Iroh data-plane responsibilities
* `experiments/local-p2p.md` — controlled local experiments
* `experiments/cross-network.md` — real network experiments
* `decisions/005-self-hosted-relay.md` — relay infrastructure decision
