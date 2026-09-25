# 005 — Self-Hosted Iroh Relay

## Context

Aperture prefers direct peer-to-peer connections.

However, direct connectivity can fail because of NAT behavior, firewall restrictions, or network topology. In these cases, Iroh can use a relay to provide a connectivity fallback.

During development, public Iroh infrastructure has been useful for testing. As Aperture evolves, self-hosting a relay provides more control over the networking environment.

## Decision

Aperture will keep public Iroh discovery infrastructure for now and experiment with a **self-hosted Iroh relay** as a fallback connectivity path.

The intended connection strategy is:

```text
Direct P2P succeeds
        │
        ▼
   Direct transfer
```

If direct connectivity fails:

```text
Direct P2P fails
        │
        ▼
 Self-hosted relay
        │
        ▼
   P2P data path
```

The relay is a fallback, not the default data path.

## Why Self-Host a Relay?

Self-hosting provides control over:

* Relay infrastructure
* Geographic placement
* Available bandwidth
* Capacity
* Network configuration
* Logging and observability
* Experimental networking conditions

It also allows Aperture to compare different relay locations and configurations.

For example:

```text
             ┌── Mumbai Relay
             │
Peers ───────┼── Singapore Relay
             │
             └── Frankfurt Relay
```

Regional relay experiments can later measure how relay location affects connection establishment and transfer performance.

## Relay vs Signaling Server

The relay and Java signaling server have different responsibilities.

### Java signaling server

```text
Peer information
Presence
Transfer requests
Coordination
```

### Iroh relay

```text
Connectivity fallback
Encrypted P2P traffic forwarding
```

The Java server does not become a relay simply because it coordinates peers.

Similarly, the relay does not become Aperture's application-level signaling server.

## Relay vs File Storage

A relay should not be treated as centralized file storage.

Conceptually:

```text
Alice ──────► Relay ──────► Bob
                 │
                 │
             forwards traffic
                 │
                 ✕
          does not become
          file storage
```

The relay exists to help peers communicate when a direct path cannot be established.

## Current Discovery Strategy

Aperture will continue using Iroh's existing public discovery mechanisms for now.

Discovery and relay are separate concerns:

```text
Discovery:
"Where can I find this peer?"

Connectivity:
"How can these two peers communicate?"

Transfer:
"How do they exchange the data?"
```

Self-hosting the relay does not require immediately self-hosting discovery infrastructure.

## Future Regional Testing

A later experiment can deploy relays in multiple regions:

```text
                    Aperture
                       │
        ┌──────────────┼──────────────┐
        ▼              ▼              ▼
     Mumbai        Singapore       Frankfurt
      Relay           Relay           Relay
```

Measurements should include:

* Direct vs relay path
* Connection establishment time
* Transfer duration
* Throughput
* Latency
* Retry frequency
* Relay utilization
* File size
* Network location

The purpose is to measure the effect of relay placement rather than assume that a particular region will always perform better.

## Consequences

### Benefits

* Greater infrastructure control
* Controlled networking experiments
* Ability to test regional relay placement
* Potentially predictable relay capacity
* Better visibility into fallback connectivity

### Costs

* Additional infrastructure
* Bandwidth and hosting costs
* Operational maintenance
* Relay capacity becomes an engineering concern
* A relay can become a bottleneck if too much traffic uses it

## Principle

> **Direct P2P is preferred; the relay exists to provide connectivity when a direct path is unavailable.**

Self-hosting the relay gives Aperture control over the fallback path without changing the fundamental decentralized data-plane architecture.
