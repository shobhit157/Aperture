# Aperture Roadmap

Aperture is being developed incrementally, with each stage building on the previous networking and distributed-systems work.

The roadmap is intentionally experimental: features are implemented, measured, and refined rather than assumed to work at scale.

---

## Phase 1 — Core P2P Foundation

* [x] Java signaling server
* [x] Peer registration and presence
* [x] Redis-based shared presence
* [x] Cross-instance messaging with SNS/SQS
* [x] Rust peer application
* [x] Iroh-based peer connectivity
* [x] Direct P2P connectivity testing
* [x] Relay fallback testing
* [x] End-to-end file transfer
* [x] File integrity verification

---

## Phase 2 — Distributed Backend

* [x] Containerized backend instances
* [x] Shared presence across instances
* [x] Cross-instance routing
* [x] Prometheus metrics
* [x] Grafana monitoring
* [ ] Kubernetes deployment
* [ ] Kubernetes service discovery
* [ ] Health checks and readiness probes
* [ ] Horizontal scaling tests
* [ ] Failure and recovery testing

---

## Phase 3 — P2P Networking Experiments

* [x] Linux namespace networking
* [x] NAT traversal experiments
* [x] Hole-punching experiments
* [x] Local P2P testing
* [x] Cross-network P2P testing
* [x] AWS-based connectivity testing
* [ ] Measure direct vs relay performance
* [ ] Test multiple geographic regions
* [ ] Deploy self-hosted relay
* [ ] Compare regional relay performance
* [ ] Improve transfer diagnostics

---

## Phase 4 — Reliable File Transfer

* [x] Whole-file integrity verification
* [x] Transfer progress
* [ ] Chunked file transfer
* [ ] Per-chunk integrity verification
* [ ] Parallel piece transfers
* [ ] Transfer retry mechanisms
* [ ] Resumable transfers
* [ ] Interrupted-transfer recovery
* [ ] Better throughput measurement
* [ ] Large-file testing

---

## Phase 5 — Distributed File Swarm

The next major architectural step is to move from:

```text
Alice ─────────► Bob
```

towards:

```text
                 ┌──► Bot 1
                 │
Admin ─► Swarm ──┼──► Bot 2
                 │
                 ├──► Bot 3
                 │
                 └──► ...
```

Planned work:

* [ ] Define distribution jobs
* [ ] Define participant sets
* [ ] Create file manifests
* [ ] Split files into pieces
* [ ] Generate piece hashes
* [ ] Track piece availability
* [ ] Introduce shared swarm metadata
* [ ] Evaluate Iroh Documents for metadata synchronization
* [ ] Implement provider discovery
* [ ] Implement piece scheduling
* [ ] Implement parallel transfers
* [ ] Make receivers become providers
* [ ] Add retry and failure handling
* [ ] Prevent duplicate piece downloads
* [ ] Test distributed propagation

### Initial Experiment

A first swarm experiment will use a relatively small number of pieces and bots before increasing the scale.

Example:

```text
1 GB file
   │
   ├── P1
   ├── P2
   ├── P3
   ├── ...
   └── P10–P15
```

Initial pieces can be distributed to a subset of bots.

Those bots can then redistribute their pieces to other participants.

The system should measure how the distribution pattern changes as the number of participating peers increases.

---

## Phase 6 — Swarm Scheduling and Optimization

Once basic swarm distribution works:

* [ ] Rarest-piece-first scheduling
* [ ] Provider selection
* [ ] Provider load awareness
* [ ] Direct-path preference
* [ ] Relay fallback
* [ ] Adaptive parallelism
* [ ] Bandwidth-aware scheduling
* [ ] Retry backoff
* [ ] Piece availability optimization
* [ ] Transfer prioritization
* [ ] Swarm performance analysis

The scheduler will remain application logic rather than being delegated entirely to the underlying P2P library.

---

## Phase 7 — Observability

Expand monitoring across the complete system.

### Control Plane

* [ ] Active users
* [ ] Active backend instances
* [ ] Signaling requests
* [ ] Cross-instance messages
* [ ] Redis availability
* [ ] SNS/SQS delivery failures

### Data Plane

* [ ] Active P2P connections
* [ ] Direct vs relay connections
* [ ] Connection establishment time
* [ ] Transfer duration
* [ ] Throughput
* [ ] Transfer failures
* [ ] Retries
* [ ] Piece availability
* [ ] Piece transfer rates

### Swarm

* [ ] Number of participating peers
* [ ] Pieces available per peer
* [ ] Distribution progress
* [ ] Replication rate
* [ ] Missing pieces
* [ ] Provider utilization
* [ ] Completion time

---

## Phase 8 — Security and Isolation

Before treating Aperture as a production-oriented system:

* [ ] Strong application authentication
* [ ] Authorization for transfers
* [ ] Distribution-job isolation
* [ ] Secure peer registration
* [ ] Validate file and piece metadata
* [ ] Protect swarm metadata
* [ ] Prevent unauthorized piece requests
* [ ] Secure secrets and credentials
* [ ] Review relay exposure
* [ ] Security testing

A private transfer must remain isolated from unrelated swarm participants.

---

## Phase 9 — User-Facing Platform

After the networking and distributed-system foundations are stable:

* [ ] Production-oriented web interface
* [ ] Persistent user accounts
* [ ] File management
* [ ] Transfer history
* [ ] Active-transfer status
* [ ] Distribution-job management
* [ ] Peer management
* [ ] Error reporting
* [ ] User consent and privacy controls

The user interface should sit above the existing control and data planes rather than becoming responsible for networking logic.

---

# Long-Term Direction

The long-term goal is to evolve Aperture from a P2P file-transfer experiment into a **distributed data-distribution platform**.

The architectural direction is:

```text
                    APERTURE
                       │
          ┌────────────┴────────────┐
          │                         │
     Control Plane              Data Plane
          │                         │
     Java + Redis             Rust + Iroh
     SNS / SQS                     │
          │                         │
          └──────────┬──────────────┘
                     │
              Swarm Scheduler
                     │
          ┌──────────┼──────────┐
          ▼          ▼          ▼
        Bot A      Bot B      Bot C
          │          │          │
          └────── P2P ──────────┘
```

The fundamental principle remains:

> **The control plane decides what should happen; the data plane moves the data.**

Aperture will use this architecture to investigate how files can be distributed efficiently across geographically separated peers while maintaining reliability, integrity, observability, and controlled participation.
