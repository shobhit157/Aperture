# Slow Transfers

## Problem

Aperture successfully transferred files between peers, but some cross-network transfers were significantly slower than expected.

An early cross-network test transferred approximately 5 MB but took more than 40 seconds.

A later test successfully transferred a 50 MB file.

The goal is to understand what controls transfer performance and identify the actual bottleneck instead of assuming that the P2P layer itself is slow.

---

## Observed Behavior

An early experiment produced:

```text id="x4n8dm"
File size: ~5 MB
Network: Cross-network
Result: Transfer succeeded
Duration: >40 seconds
```

A later experiment produced:

```text id="q2v9sa"
File size: 50 MB
Result: Transfer succeeded
Integrity: Verified
```

The successful 50 MB transfer demonstrated that the transfer pipeline was functional.

However, the earlier slow transfer showed that **successful connectivity does not necessarily imply good throughput**.

---

## Performance Model

A file transfer can be viewed as several stages:

```text id="w0j7ek"
Connection establishment
        │
        ▼
Path selection
        │
        ▼
Data transfer
        │
        ▼
Buffering / processing
        │
        ▼
Disk write
        │
        ▼
Integrity verification
```

The total observed time may therefore contain more than network transmission time.

---

## Possible Causes

Several variables can affect transfer speed.

### Network Path

The connection may be:

```text id="x1z8qd"
Peer A ─────────────► Peer B
       Direct
```

or:

```text id="y8w2nc"
Peer A ───► Relay ───► Peer B
```

A relay can add additional latency and forwarding overhead.

### Latency

Longer physical or network paths can increase round-trip time.

### Relay Capacity

If traffic uses a relay, available relay bandwidth and load can affect throughput.

### Transport Behavior

QUIC transport behavior, congestion control, buffering, and packet loss can affect the effective transfer rate.

### Chunking

Future chunked transfers may change the performance characteristics of the system.

Poorly chosen chunk sizes or excessive per-chunk overhead could reduce efficiency.

### Disk I/O

Reading the source file or writing the destination file can become a bottleneck.

### CPU / Processing

Hashing, serialization, encryption, or other processing can consume CPU resources.

### Application Implementation

The Rust peer application may introduce its own buffering, synchronization, or scheduling overhead.

---

## First Principle: Measure Before Optimizing

The correct response to a slow transfer is not immediately to change the implementation.

First determine where the time is being spent.

For example:

```text id="v7z3ca"
Total transfer time
        │
        ├── Connection setup
        ├── Network transfer
        ├── Application processing
        ├── Disk I/O
        └── Verification
```

Without these measurements, changing the code may optimize the wrong component.

---

## Direct vs Relay Comparison

One of the most important measurements is the connection path.

The same file should ideally be tested using:

```text id="5g7x1n"
Test A
Direct P2P

Test B
Relay

Test C
Different relay region
```

Then compare:

* connection time
* transfer duration
* throughput
* retries
* packet loss where observable
* CPU usage
* disk throughput

This helps determine whether the network path is a significant contributor.

---

## Throughput

A simple measurement is:

```text id="p7v1cs"
throughput = file_size / transfer_time
```

For example, if a 50 MB file takes 10 seconds:

```text
50 MB / 10 s = 5 MB/s
```

For consistent comparisons, Aperture should record both:

* bytes transferred
* elapsed time

and report throughput using a consistent unit.

---

## Connection Establishment vs Transfer Time

A transfer can be slow because connection establishment itself is slow, or because the actual data path is slow.

These should be measured separately.

```text id="f0e8ny"
Start
 │
 ├──── Connection establishment ────┐
 │                                  │
 │                             Connected
 │                                  │
 └────── Actual file transfer ──────┘
```

For example:

```text id="4f3w6n"
Connection setup: 8 s
File transfer:     2 s
Total:            10 s
```

In this case, optimizing the file-transfer loop would not address the largest delay.

---

## Disk I/O Test

Network performance should also be separated from local storage performance.

A useful experiment is:

```text id="x5h3bn"
Peer A
  │
  ▼
Network transfer
  │
  ▼
Memory / discard destination
```

compared with:

```text id="v3j8ab"
Peer A
  │
  ▼
Network transfer
  │
  ▼
Disk write
```

If the first test is significantly faster, disk I/O may be contributing to the bottleneck.

---

## Local vs Cross-Network

A useful baseline is to compare:

```text id="0p5f1k"
Test 1
ns-alice ─────► ns-bob
```

with:

```text id="3t5b6r"
Test 2
Local machine ─────► AWS
```

and eventually:

```text id="w7q2ne"
Test 3
AWS Region A ─────► AWS Region B
```

These tests progressively introduce more realistic network conditions.

---

## File Size Comparison

Different file sizes should also be tested.

For example:

```text id="2x6b8p"
1 MB
5 MB
10 MB
50 MB
100 MB
500 MB
1 GB
```

This can reveal whether the problem is:

* fixed connection overhead
* throughput limitation
* per-chunk overhead
* memory usage
* disk behavior
* scaling behavior

A fixed startup cost becomes less important as the file gets larger, while a throughput bottleneck becomes more visible.

---

## Chunk Size Experiments

The future swarm architecture will divide files into pieces.

Chunk size should therefore eventually become a measured parameter.

For example:

```text id="7g9w1q"
1 MB chunks
5 MB chunks
10 MB chunks
25 MB chunks
```

Measure:

* total transfer time
* throughput
* CPU usage
* memory usage
* retry cost
* number of transfers
* scheduling overhead

The goal is to find behavior that works well for Aperture's expected workloads.

---

## Transfer Instrumentation

The Rust data plane should eventually expose detailed timing information.

A useful transfer record could look like:

```text id="p3b5sy"
Transfer
--------
File ID: abc123
Peer: Bot17
Size: 50 MB

Connection:
  Path: Direct
  Setup: 420 ms

Transfer:
  Start: ...
  End: ...
  Duration: 8.2 s
  Throughput: 6.1 MB/s

Integrity:
  SHA-256: PASS
```

For relay transfers:

```text id="k6c2ra"
Transfer
--------
File ID: abc123
Peer: Bot17
Size: 50 MB

Connection:
  Path: Relay
  Setup: 1.4 s

Transfer:
  Duration: 21.7 s
  Throughput: 2.3 MB/s

Integrity:
  SHA-256: PASS
```

The exact numbers above are illustrative; real measurements should come from experiments.

---

## Experimental Matrix

Future performance testing can use a matrix such as:

| Variable    | Values                                  |
| ----------- | --------------------------------------- |
| File size   | 1 MB, 5 MB, 50 MB, 100 MB, 500 MB, 1 GB |
| Path        | Direct, relay                           |
| Network     | Local, AWS, cross-region                |
| Destination | Memory, disk                            |
| Chunk size  | Multiple experimental values            |
| Concurrency | 1, 2, 4, 8...                           |

Only one major variable should be changed at a time when trying to identify a bottleneck.

---

## Important Distinction: Connectivity vs Throughput

A connection can be completely successful while still performing poorly.

```text id="k2s8qa"
Connectivity
    │
    ▼
Connection established ✓
    │
    ▼
Throughput
    │
    └── Can still be poor
```

Therefore:

> **"The transfer works" and "the transfer is fast" are separate engineering properties.**

Aperture should track both.

---

## What We Learned

### 1. Successful transfer does not prove good performance

A file can arrive correctly while the transfer remains inefficient.

### 2. Connection path matters

Direct and relay transfers should be measured separately.

### 3. Startup time matters

Connection establishment should not be mixed with actual transfer throughput.

### 4. The bottleneck may not be networking

Disk I/O, CPU, buffering, and application logic can all affect performance.

### 5. File size matters

Small transfers can be dominated by fixed overhead, while large transfers expose sustained throughput.

### 6. Optimization should follow measurement

Performance improvements should target a measured bottleneck.

---

## Current Status

Aperture has successfully transferred files across both local and cross-network environments.

The performance investigation remains ongoing.

The next useful experiments are:

1. Local direct transfer baseline
2. Local relay transfer
3. Local ↔ AWS direct transfer
4. Local ↔ AWS relay transfer
5. Different file sizes
6. Connection setup vs transfer timing
7. Disk vs memory destination
8. Chunk-size experiments
9. Multi-peer transfer benchmarks

The objective is to build a measurable performance profile before optimizing the swarm implementation.

---

## Related Documentation

* `architecture/data-plane.md` — data-plane implementation
* `architecture/networking.md` — network model
* `architecture/swarm.md` — future distributed transfer
* `problems/direct-connection-failure.md` — connectivity failures
* `problems/relay-fallback.md` — relay behavior
* `experiments/cross-network.md` — transfer experiments
