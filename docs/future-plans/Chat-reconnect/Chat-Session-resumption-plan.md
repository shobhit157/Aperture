# Session Resumption (parked idea)

**Status: Parked.** Not on the roadmap. Revisit only if chat stays on the Java server long-term
and short-disconnect noise becomes a real problem. If chat moves onto Iroh/QUIC, most of this
comes built in.

## Problem

Today a session is one TCP connection. A short Wi-Fi drop creates a new session, announces
LEAVE/JOIN, and loses chat messages sent during the gap. This is noisy but correct: the reconnect
redesign and [process ownership](../fixes/chat-reconnect/process-ownership.md) keep ownership and
cleanup right, and [Mesh v2](../fixes/mesh-v2/mesh-v2-transfer-events.md) keeps transfers correct.

## Core idea

- **Session ≠ connection.** A session outlives individual TCP connections.
- On first connect the server gives the client a **secret resume token** (kept in memory only).
- After a drop the client sends `RESUME|sessionId|token|lastSeq`, and the server re-attaches the
  same session, with no LEAVE/JOIN.
- A disconnected session waits a **grace period** (~120 s) before it really ends; a sweeper ends
  expired sessions exactly once (atomic Redis script, like Mesh v2's "counted once").
- Missed chat messages are **replayed** from a capped Redis Stream (`chat_log`), using sequence
  numbers; if the gap is too old, the client does a full resync.

## Things to remember if this is ever built

- `SUPERSEDED` would need a reason (`process` vs `connection`), so a client exits only when
  another *process* took the name, not when its own newer connection did.
- Mesh v2's 30 s "peer left" grace should then use the session grace instead.
- The token is only as safe as the channel: plain TCP today, so TLS or Iroh first.
