# Transfer State vs Mesh State

> [!WARNING]
> **Incident:** A P2P transfer completed successfully, but the Aperture mesh displayed `timed out`.
>
> **Lesson:** The state of the real transfer and the state observed by the mesh must be treated as two different things.

---

## 1. Incident

The mesh displayed:

```text
19:34:03 — dummy-client-74f7d9945f-6mrwf to admin, starting
19:35:04 — dummy-client-74f7d9945f-6mrwf to admin, timed out
```

However, the server logs showed a completion event for the same transfer:

```text
MESH|COMPLETE|...|relay
```

There was therefore a discrepancy:

```text
             Actual Transfer              Mesh

                 RUNNING                 RUNNING
                    │                       │
                    │ progress              │
                    ▼                       │
                COMPLETE                    │
                                            │
                                            ▼
                                         TIMEOUT
```

The transfer itself was not necessarily failing. The mesh had lost synchronization with the transfer's actual state.

---

## 2. Root Cause

The mesh was effectively using **absence of a recent event** as evidence that the transfer had failed.

That is unsafe.

A transfer can be:

- slow
- temporarily quiet
- changing network paths
- processing data
- still completing successfully

Therefore:

```text
No COMPLETE event yet
        ≠
Transfer has failed
```

The correct model is:

```text
Actual transfer
      │
      ├── progress
      ├── complete
      └── failure
      │
      ▼
Authoritative events
      │
      ▼
MeshEventServer
      │
      ▼
Mesh UI
```

> [!IMPORTANT]
> The mesh should **observe transfer state**, not independently invent it.

---

## 3. Event Flow

Aperture has several layers between the Iroh client and the mesh:

```text
┌──────────────────────┐
│     Rust / Iroh      │
│                      │
│ transfer is running  │
│ progress / failure   │
│ completion           │
└──────────┬───────────┘
           │
           ▼
┌──────────────────────┐
│     Client.java      │
│  event translation   │
└──────────┬───────────┘
           │
           ▼
┌──────────────────────┐
│     Java Server      │
│  transfer coordination│
└──────────┬───────────┘
           │
           ▼
┌──────────────────────┐
│   MeshEventServer    │
│    mesh state        │
└──────────┬───────────┘
           │ WebSocket
           ▼
┌──────────────────────┐
│      mesh.html       │
│     visualization    │
└──────────────────────┘
```

A failure or delayed event anywhere in this chain can make the mesh's view stale.

---

# 4. Correct Transfer State Model

The transfer lifecycle should be explicit.

```text
                  ┌─────────────┐
                  │  REQUESTED  │
                  └──────┬──────┘
                         │
                         ▼
                  ┌─────────────┐
                  │ CONNECTING  │
                  └──────┬──────┘
                         │
                         ▼
                ┌─────────────────┐
                │  TRANSFERRING   │◄─────────────┐
                └───────┬─────────┘              │
                        │                        │
              ┌─────────┼──────────┐             │
              │         │          │             │
          progress   no progress  error          │
              │         │          │             │
              │         ▼          ▼             │
              │      STALLED     FAILED          │
              │         │                        │
              │    ┌────┴────┐                   │
              │    │         │                   │
              │ recovery   timeout               │
              │    │         │                   │
              └────┘         ▼                   │
                         ┌─────────┐              │
                         │ FAILED  │              │
                         └─────────┘              │
                                                 │
                successful transfer              │
                         │                       │
                         ▼                       │
                    ┌──────────┐                 │
                    │ VERIFYING│                 │
                    └────┬─────┘                 │
                         │                       │
                         ▼                       │
                    ┌───────────┐                │
                    │ COMPLETED │                │
                    └───────────┘                │
```

### Important distinction

`STALLED` and `FAILED` are not the same thing.

| State | Meaning |
|---|---|
| `TRANSFERRING` | Data is actively moving |
| `STALLED` | No progress has been observed recently |
| `FAILED` | The transfer has actually failed |
| `VERIFYING` | Transfer finished; integrity is being checked |
| `COMPLETED` | Transfer and verification succeeded |

---

# 5. Progress-Aware Timeout

The old approach is effectively:

```text
START
  │
  │ wait 60 seconds
  │
  ▼
TIMEOUT
```

This creates false positives for slow transfers.

Instead, track:

```text
last_progress_timestamp
bytes_transferred
total_bytes
```

For example:

```text
19:34:03   START
19:34:10   10 MB
19:34:20   20 MB
19:34:30   30 MB
19:34:40   40 MB
```

Even though the transfer has been running for more than a short timeout window, it is healthy because progress is continuing.

The correct question is:

> **How long has it been since the last progress event?**

Not:

> **How long has the transfer existed?**

---

## Recommended Behavior

```text
                 bytes arriving
                      │
                      ▼
              ┌──────────────┐
              │ TRANSFERRING │
              └──────┬───────┘
                     │
                     │ no progress
                     ▼
                ┌─────────┐
                │ STALLED │
                └────┬────┘
                     │
              ┌──────┴──────┐
              │             │
          progress        timeout
              │             │
              ▼             ▼
       TRANSFERRING       FAILED
```

> [!NOTE]
> A short period without progress should not immediately produce `FAILED`.

---

# 6. Authoritative Failure Events

The Rust client already has explicit failure events such as:

```text
EVENT:TRANSFER_FAILED:<transfer_id>:<reason>
```

These should become the authoritative source for genuine transfer failures.

Possible reasons include:

```text
CONNECTION_LOST
FILE_NOT_FOUND
DISK_WRITE_ERROR
AUTHORIZATION_FAILED
INTEGRITY_CHECK_FAILED
```

The desired flow is:

```text
Iroh / Rust
    │
    │ actual failure
    ▼
TRANSFER_FAILED
    │
    ▼
Client.java
    │
    ▼
Java Server
    │
    ▼
MeshEventServer
    │
    ▼
Mesh
```

The mesh should show the failure as soon as the real transfer system reports it.

---

# 7. Path State Is Separate From Transfer State

A transfer can change network paths without failing.

For example:

```text
Alice ═══════════════════ Bob
          DIRECT
             │
             │ path changes
             ▼
Alice ───── Relay ────── Bob
             RELAY
```

The transfer may continue normally.

Therefore:

```text
Transfer state:
    TRANSFERRING

Network path:
    RELAY
```

These should be represented independently.

> [!IMPORTANT]
> A path change should **not automatically produce `FAILED`**.

---

# 8. Current Iroh Client Behavior

The Rust client reports progress using events similar to:

```text
EVENT:PROGRESS:sending|<file>|<percent>|<bytes>|<total>
EVENT:PROGRESS:receiving|<file>|<percent>|<bytes>|<total>
```

It also reports the selected Iroh path using:

```rust
conn.paths()
```

and checks whether the selected path is:

```rust
p.is_ip()
p.is_relay()
```

The current implementation therefore already has the foundations for:

- progress monitoring
- transfer identification
- direct/relay reporting
- explicit failure events

The remaining work is to make the mesh's state machine consume these events correctly and continuously.

---

# 9. Why This Matters for Resumable Transfers

The current receiver creates a partial file:

```text
received_<file>.partial
```

If the transfer fails, the current behavior removes that partial file.

That means a failed transfer has to start again from the beginning.

Future behavior:

```text
100 MB transfer

        60 MB
         │
         ▼
┌──────────────────────┐
│ .partial = 60 MB     │
└──────────┬───────────┘
           │
       connection
         failure
           │
           ▼
      keep partial
           │
           ▼
       reconnect
           │
           ▼
      resume at 60 MB
           │
           ▼
        100 MB
           │
           ▼
       VERIFY
           │
           ▼
       COMPLETED
```

This becomes even more useful once files are represented as independently transferable pieces.

---

# 10. Connection to the Future Swarm

This incident is directly relevant to Aperture's future swarm architecture.

Suppose:

```text
Bot A ─────────► Bot B
      Piece 17
```

and the connection fails.

A reliable system should know:

```text
Piece 17
Provider: Bot A
State: FAILED
```

The scheduler can then select another provider:

```text
             Piece 17
                │
        ┌───────┼────────┐
        │       │        │
       B1      B7       B12
        │       │        │
        └───────┼────────┘
                │
          choose provider
                │
                ▼
             Bot B
```

This is why accurate transfer state is not merely a UI concern.

It becomes a prerequisite for:

- retry logic
- resumable transfers
- provider replacement
- piece scheduling
- swarm reliability
- rarest-piece-first strategies

---

# 11. Engineering Principle

> [!TIP]
> **The transfer system should be authoritative. The mesh should be an observer of that state.**

Avoid:

```text
"No COMPLETE event"
        ↓
"TIMEOUT"
        ↓
"Transfer failed"
```

Prefer:

```text
Actual transfer event
        ↓
Authoritative state update
        ↓
Mesh reflects that state
```

And for inactivity:

```text
No progress
    ↓
STALLED
    ↓
still no progress
    ↓
FAILED
```

This separates **what actually happened** from **what the monitoring system last observed**.

---

# 12. Next Engineering Steps

## Immediate

- [ ] Propagate `EVENT:TRANSFER_FAILED` from Rust → Java → mesh.
- [ ] Ensure `COMPLETE` and `FAILED` events update the same transfer record.
- [ ] Replace flat elapsed-time timeout with progress-aware inactivity detection.
- [ ] Add explicit `STALLED` state.
- [ ] Verify that every progress event refreshes `last_progress_timestamp`.
- [ ] Report direct/relay path changes continuously.

## Reliability

- [ ] Preserve `.partial` files after recoverable failures.
- [ ] Add transfer resume.
- [ ] Add integrity verification.
- [ ] Distinguish recoverable and non-recoverable failures.

## Swarm

- [ ] Introduce file manifests.
- [ ] Split files into independently identifiable pieces.
- [ ] Track piece availability.
- [ ] Allow bots to become piece providers.
- [ ] Implement provider selection.
- [ ] Add retry/provider replacement.
- [ ] Prototype rarest-piece-first scheduling.

---

# Final Lesson

This incident exposed a fundamental distributed-systems problem:

```text
                REALITY
                   │
                   ▼
             Rust / Iroh
                   │
                   ▼
            authoritative
                events
                   │
                   ▼
              Java state
                   │
                   ▼
                Mesh
```

A monitoring system can only be as accurate as the state it receives.

> [!IMPORTANT]
> **A transfer taking longer than expected is not a failure.**
>
> **A lack of progress is not immediately a failure.**
>
> **An explicit transfer failure is a failure.**

That distinction will become increasingly important as Aperture moves from one-to-one P2P transfers toward resumable, piece-based, multi-peer file distribution.
