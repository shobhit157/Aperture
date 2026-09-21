# Aperture Networking Architecture

## 1. Purpose

Aperture is built around peer-to-peer communication across different networks.

The networking layer exists to answer a fundamental question:

> **How can two peers communicate when they may be located behind different private networks, NAT devices, firewalls, or cloud networks?**

The project therefore treats networking as a separate engineering concern from application-level signaling.

---

## 2. Network Model

A typical peer does not necessarily have a publicly reachable IP address.

For example:

```text
                 Internet
                    │
          ┌─────────┴─────────┐
          │                   │
        NAT A               NAT B
          │                   │
      Private LAN         Private LAN
          │                   │
        Alice                 Bob
```

Alice and Bob may have private addresses such as:

```text
Alice → 192.168.x.x
Bob   → 10.x.x.x
```

Those addresses are meaningful inside their respective networks but are not directly routable across the public Internet.

A P2P system therefore needs additional mechanisms for discovery and connectivity.

---

## 3. Four Different Networking Problems

Aperture separates four related but different problems.

```text
Discovery
    │
    ▼
Connectivity
    │
    ▼
Transport
    │
    ▼
Application Data
```

### Discovery

Find information about a peer.

### Connectivity

Determine whether a usable path can be established.

### Transport

Provide a reliable communication channel once connectivity exists.

### Application Data

Move the actual file or message data.

Confusing these layers makes P2P problems difficult to diagnose.

---

## 4. Peer Discovery

Discovery answers:

> **Where can I find information about this peer?**

Aperture can use Iroh's discovery mechanisms to obtain information associated with a peer identity.

Conceptually:

```text
Alice
  │
  │ "Where is Bob?"
  ▼
Discovery
  │
  ▼
Bob's endpoint information
```

Discovery does not guarantee that Alice can establish a direct connection to Bob.

Finding an endpoint and reaching that endpoint are separate problems.

---

## 5. Signaling vs Discovery

Aperture also has application-level signaling through the Java backend.

These mechanisms have different responsibilities.

### Java signaling

Coordinates application participants and exchanges information required by the transfer workflow.

### Iroh discovery

Helps locate information associated with a peer.

Conceptually:

```text
Application signaling
        │
        ▼
Who wants to communicate?
        │
        ▼
Peer information
        │
        ▼
Iroh connectivity
```

The Java backend is therefore not an Iroh relay and is not the same thing as peer discovery.

---

## 6. NAT

Network Address Translation allows multiple private hosts to communicate through a shared public address.

For example:

```text
Private Network

Alice
10.0.0.10
   │
   ▼
NAT
203.x.x.x
   │
   ▼
Internet
```

The NAT device maintains mappings between internal connections and external connections.

This creates a challenge for inbound P2P communication because another peer may not be able to simply connect to Alice's private address.

---

## 7. NAT Traversal

Aperture uses Iroh's connectivity mechanisms to attempt direct peer-to-peer communication across network boundaries.

Conceptually:

```text
Alice Network                  Bob Network

   Alice                          Bob
     │                              │
     ▼                              ▼
    NAT A                         NAT B
     │                              │
     └────────── Internet ──────────┘
```

The goal is:

```text
Alice ═══════════════════════► Bob
             Direct P2P
```

If the network conditions allow it, the peers communicate directly.

---

## 8. Hole Punching

One mechanism used by P2P systems to establish direct connectivity through NAT is hole punching.

A simplified model is:

```text
Alice                         Bob
  │                             │
  │──── outbound traffic ──────►│
  │                             │
  │◄──── outbound traffic ──────│
  │                             │
  └──────── direct path ────────┘
```

The exact behavior depends on the NAT implementation and network environment.

Different NAT behaviors can produce very different results.

Therefore, a successful local experiment does not automatically prove that the same approach will work across arbitrary Internet networks.

---

## 9. Linux Network Namespace Laboratory

Aperture's networking experiments have used Linux network namespaces to simulate independent hosts.

For example:

```text
┌─────────────┐             ┌─────────────┐
│  ns-alice   │             │   ns-bob    │
│             │             │             │
│ Java/Rust   │             │ Java/Rust   │
└──────┬──────┘             └──────┬──────┘
       │                            │
       └──────── Linux network ─────┘
```

Network namespaces provide isolated:

* Network interfaces
* Routing tables
* IP addresses
* Network stacks
* Firewall configuration

This makes them useful for testing networking behavior without requiring multiple physical machines.

---

## 10. Simulating Multiple Networks

The lab can be extended to represent separate networks:

```text
Network A                       Network B

Alice                           Bob
  │                               │
  ▼                               ▼
br0                             br1
  │                               │
  └────────── Router ─────────────┘
```

For example:

```text
Network A → 10.0.0.0/24
Network B → 192.168.1.0/24
```

A router namespace can connect the networks and provide routing between them.

This allows routing and NAT behavior to be studied independently of the application.

---

## 11. Router and NAT

A router namespace can be used to simulate an intermediate network device.

Conceptually:

```text
Alice
  │
  ▼
Network A
  │
  ▼
Router
  │
  ▼
Network B
  │
  ▼
Bob
```

Adding NAT changes the problem:

```text
Alice
  │
  ▼
Private Network
  │
  ▼
NAT Router
  │
  ▼
Public Network
  │
  ▼
Bob
```

This allows Aperture's P2P behavior to be tested under controlled network conditions.

---

## 12. Local vs Internet Experiments

A critical distinction is:

```text
Local namespace test
        ≠
Real Internet NAT test
```

A Linux namespace environment can reproduce many networking concepts, but it does not automatically reproduce every behavior of residential or enterprise NAT devices.

For this reason, Aperture uses both:

### Controlled local experiments

Useful for:

* Routing
* IP addressing
* NAT concepts
* Packet inspection
* Application behavior
* Reproducible testing

### Real network experiments

Useful for:

* Internet NAT behavior
* Firewall behavior
* NAT traversal
* Direct P2P connectivity
* Relay fallback
* Real-world latency and throughput

---

## 13. Direct vs Relay

The desired connection path is:

```text
Alice ═════════════════════► Bob
             Direct
```

If direct connectivity fails:

```text
Alice ═════► Iroh Relay ═════► Bob
```

This gives Aperture a fallback path.

The important observation is:

> A relay solves connectivity, not application signaling.

The Java backend can coordinate the transfer while Iroh independently determines whether the data path is direct or relayed.

---

## 14. Connection Path as Experimental Data

A successful file transfer alone does not tell us enough about the network.

For every important experiment, the connection path should be recorded.

For example:

```text
Transfer
├── Peer A: Alice
├── Peer B: Bob
├── File size: 50 MB
├── Connection path: Direct P2P
├── Duration: measured
├── Throughput: measured
└── Integrity: verified
```

Or:

```text
Transfer
├── Peer A: India
├── Peer B: Singapore
├── Connection path: Relay
├── Duration: measured
├── Throughput: measured
└── Integrity: verified
```

This prevents us from incorrectly attributing performance differences to the wrong part of the system.

---

## 15. Slow Transfer Investigation

Aperture has encountered slow cross-network transfers.

For example, a transfer can succeed while still performing poorly.

That means:

```text
Connectivity succeeded
        ≠
Performance is good
```

Possible causes include:

* Relay path
* Network latency
* Limited relay bandwidth
* Connection establishment behavior
* Application-level buffering
* Chunking strategy
* Disk I/O
* CPU overhead
* TCP/QUIC behavior
* Network conditions

The correct approach is to measure each stage rather than assume the cause.

---

## 16. Same-Host NAT and Hairpin Behavior

Testing multiple network namespaces on the same physical host can produce behavior that differs from two independent Internet hosts.

For example:

```text
Host
 │
 ├── ns-alice
 │
 └── ns-bob
```

Traffic between these namespaces may remain within the same host or use local networking paths.

This means a successful same-host test does not necessarily prove that two peers behind independent NAT devices will establish direct connectivity.

NAT hairpinning is another case where behavior depends on the network device and topology.

Therefore, Aperture treats same-host P2P tests as controlled experiments rather than complete representations of Internet P2P behavior.

---

## 17. AWS as a Real Network Experiment

Cloud instances provide another environment for testing connectivity.

For example:

```text
Local Machine
      │
      │ Internet
      ▼
AWS EC2
      │
      ▼
Rust / Iroh Peer
```

Multiple cloud regions can later be used to test:

```text
Mumbai
   │
   │ Internet
   │
Singapore
```

and eventually:

```text
Mumbai ───── Singapore
   │             │
   └──── Frankfurt
```

These experiments can measure how geographical distance, network routing, NAT conditions, and relay placement affect P2P performance.

---

## 18. Networking Failure Model

Aperture treats connectivity as something that can fail at multiple stages.

```text
Discovery
   │
   ├── Failure
   │
   ▼
Endpoint information
   │
   ├── Failure
   │
   ▼
Direct connectivity
   │
   ├── Failure
   │
   ▼
Relay fallback
   │
   ├── Failure
   │
   ▼
Transfer
   │
   ├── Failure
   │
   ▼
Integrity verification
```

This gives each failure a different debugging target.

For example:

* Cannot find peer → investigate discovery
* Peer found but cannot connect → investigate connectivity/NAT
* Relay connection works but transfer is slow → investigate data path/performance
* Transfer completes but hash differs → investigate data integrity

---

## 19. Networking Principles

### Discovery is not connectivity

Finding a peer does not mean that a connection can be established.

### Connectivity is not transfer

Establishing a connection does not guarantee successful file transfer.

### Local testing is not Internet testing

A controlled namespace topology cannot represent every real-world NAT.

### Direct and relay paths must be measured separately

Their latency and throughput characteristics can be very different.

### Network topology matters

The same application can behave differently depending on:

* NAT type
* Firewall rules
* Routing
* Geographic location
* Relay location
* Network congestion

### Experiments should isolate variables

When investigating a failure, avoid changing discovery, topology, NAT conditions, and application behavior simultaneously.

Change one major variable at a time and record the result.

---

## 20. Relationship to Aperture

The networking layer connects the control plane and data plane:

```text
                 APERTURE

          CONTROL PLANE
               │
               │ signaling
               ▼
          Peer information
               │
               ▼
          NETWORK LAYER
               │
       ┌───────┴────────┐
       │                │
   Direct P2P         Relay
       │                │
       └───────┬────────┘
               ▼
           DATA PLANE
               │
               ▼
         File Transfer
```

The goal is not simply to make two peers exchange bytes.

The networking experiments are intended to understand **why a P2P connection succeeds or fails, which path the data takes, and how that path affects the distributed system as it scales**.
