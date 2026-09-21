# Aperture

**A distributed P2P file-sharing and communication platform built to explore scalable networking, peer-to-peer data transfer, and distributed systems.**

Aperture separates the **control plane** from the **data plane**:

* **Java backend** — signaling, user presence, chat, and transfer coordination.
* **Rust + Iroh** — peer-to-peer connectivity and file transfer.
* **Redis** — online-user registry.
* **AWS SNS/SQS** — communication between backend instances.
* **Docker & Kubernetes** — deployment and scaling.
* **Prometheus & Grafana** — observability.

## Architecture

```text
                    Aperture
                       │
             ┌─────────┴─────────┐
             │                   │
        Control Plane        Data Plane
             │                   │
        Java Backend        Rust + Iroh
             │                   │
       Redis / SNS/SQS       P2P / Relay
             │                   │
             └───────┬───────────┘
                     │
                File Transfer
```

The Java backend coordinates communication between users but does **not carry the actual file data**.

## P2P File Transfer

Aperture uses **Iroh** as its data plane for peer-to-peer file transfer.

When a user sends a file:

1. The Java backend coordinates the transfer between the peers.
2. The peers establish an Iroh connection.
3. Iroh attempts to establish a **direct P2P connection**, including NAT traversal.
4. If a direct connection cannot be established, Iroh can use a **relay** as a fallback.
5. File data is transferred between the peers rather than through the Java backend.
6. The receiver verifies the transferred file using its hash.

```text
Java Backend
     │
     │ signaling
     ▼
Alice ◄──────────────► Bob
          Iroh
       File Data
          │
     ┌────┴────┐
     │         │
 Direct      Relay
   P2P      fallback
```

Detailed Iroh architecture, networking experiments, NAT traversal, relay behavior, and transfer results are documented in [`docs/`](docs/).

## Current Status

* [x] Distributed Java signaling server
* [x] Redis-based online-user registry
* [x] Cross-instance messaging with SNS/SQS
* [x] P2P file transfer using Rust/Iroh
* [x] Direct NAT traversal tested
* [x] Relay fallback tested
* [x] File integrity verification
* [x] Containerized deployment
* [x] Prometheus/Grafana monitoring
* [ ] Kubernetes deployment
* [ ] Distributed file/swarm transfer
* [ ] Resumable and chunked transfers

## Project Direction

Aperture is evolving from **one-to-one P2P file transfer** toward a distributed file distribution system where peers can exchange file pieces with each other instead of relying entirely on a central sender.

The distributed transfer architecture is currently under development.

## Documentation

Detailed engineering decisions, experiments, architecture, networking research, problems, and solutions are documented in [`docs/`](docs/).

```text
docs/
├── architecture/
├── decisions/
├── problems/
├── experiments/
├── roadmap.md
└── learning-notes.md
```

## Tech Stack

**Java 17 · Rust · Iroh · Redis · AWS SNS/SQS · Docker · Kubernetes · Prometheus · Grafana**

---

**Status:** Active research and development


