# Aperture

**A distributed P2P file-sharing and communication platform built to explore scalable networking, peer-to-peer data transfer, and distributed systems.**

Aperture separates the **control plane** from the **data plane**:

* **Java backend** — signaling, user presence, chat, and transfer coordination.
* **Rust + Iroh** — peer-to-peer connectivity and actual file transfer.
* **Redis** — online-user registry.
* **AWS SNS/SQS** — communication between backend instances.
* **Docker & Kubernetes** — deployment and scaling.
* **Prometheus & Grafana** — observability.

### Architecture

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

The backend coordinates peers but does **not** carry the actual file data.

```text
Alice ──► Java Backend ◄── Bob
  │                         │
  └──────── Iroh P2P ───────┘
             File
```

## Current Status

* [x] Distributed Java signaling server
* [x] Redis-based online-user registry
* [x] Cross-instance messaging with SNS/SQS
* [x] P2P file transfer using Rust/Iroh
* [x] Direct NAT traversal tested
* [x] Relay fallback tested
* [x] SHA-256 file integrity verification
* [x] Containerized deployment
* [x] Prometheus/Grafana monitoring
* [ ] Kubernetes deployment
* [ ] Distributed file/swarm transfer
* [ ] Resumable and chunked transfers
* [ ] Production-ready user interface

## Project Direction

Aperture is evolving from **one-to-one P2P file transfer** toward a distributed file distribution system where peers can exchange file pieces with each other instead of relying entirely on a central sender.

The distributed transfer architecture is still under development.

## Documentation

Detailed engineering decisions, experiments, architecture, networking research, and problems encountered during development are documented in [`docs/`](docs/).

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

