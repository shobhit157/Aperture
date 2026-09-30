# Chat Reconnect Fix — Step 3

Client-side reconnect hygiene. Fixes the two remaining client-side gaps
identified in the original plan: a flat, capped retry policy, and a slow
first failure on an unreachable server.

---

## What was wrong

- The retry delay was a flat 5 seconds between every attempt, with no
  growth for a sustained bad stretch.
- After 5 consecutive failed attempts, the client gave up entirely and
  stopped trying — a bot that hit this was dead until someone manually
  restarted it.
- The attempt counter reset the instant a socket connected, not once the
  connection had actually proven itself healthy. A connection that died
  again within a second or two of connecting still counted as "recovered,"
  which could produce a fast retry storm on a genuinely flaky link.
- `new Socket(host, port)` had no connect timeout. Against an unreachable
  server, this left the very first connection attempt waiting on the
  operating system's own internal TCP retry cycle, which could take far
  longer than a deliberate, application-level timeout would — confirmed
  directly, see below.

---

## What was built

**File:** `Client.java`. No server-side changes, no protocol changes.
Rebuild required for both the bot image and the admin jar, since both are
built from this same file.

- The retry delay now grows on each consecutive failure: 1s, 2s, 4s, 8s,
  16s, capped at 30s. It stays at the 30s cap for every attempt after
  that, rather than continuing to grow.
- The 5-attempt give-up limit is removed. The client retries indefinitely
  for as long as the process is running.
- The delay only resets back to the starting value once a connection has
  stayed up for at least 60 seconds. A connection that fails sooner than
  that keeps the current, larger delay rather than restarting from 1s.
- The connection attempt itself now uses an explicit 5-second connect
  timeout, via `Socket.connect(SocketAddress, timeout)` instead of the
  single-argument constructor. A `SocketTimeoutException` from this is a
  subclass of `IOException` and flows into the same retry handling as any
  other connection failure, with no other code path changes needed.
- The connection's setup and message loop are wrapped in a `try/finally`
  that always closes the socket and clears the shared writer reference,
  regardless of which path causes the method to exit — a clean server
  close, a read failure, or any other exception.

---

## Evidence

### Growing backoff and no give-up

Observed directly, pointing the client at a port with nothing listening:

```text
attempt 1  → Reconnecting in 1s
attempt 2  → Reconnecting in 2s
attempt 3  → Reconnecting in 4s
attempt 4  → Reconnecting in 8s
attempt 5  → Reconnecting in 16s
attempt 6  → Reconnecting in 30s
attempt 7  through attempt 13 → Reconnecting in 30s (capped, holding steady)
```

The client kept retrying past attempt 13, well beyond the old 5-attempt
limit, with the delay correctly capped rather than continuing to grow
unbounded.

### Reset only after a genuinely healthy connection

Observed on a real, live network episode:

```text
Connection lost (attempt 1, was connected for 72s): Connection reset
Reconnecting in 1s...
Connection lost (attempt 2, was connected for 0s): Connection timed out
Reconnecting in 2s...
Connection lost (attempt 3, was connected for 0s): Connection timed out
Reconnecting in 4s...
Connected to server! [stayed up for 172s]
Connection lost (attempt 4, was connected for 172s): Connection reset
Reconnecting in 1s...
```

The connection that lasted 172 seconds — well past the 60-second
threshold — correctly reset the delay back to 1s on its next failure. The
connections that lasted 0 seconds correctly kept the delay growing rather
than resetting.

### Connect timeout, before and after

Before the fix, against an unreachable server:

```text
[peer-app] EVENT:READY_FOR_COMMANDS:
Connection lost (attempt 1, was connected for 0s): Connection timed out
```

The gap between the process starting and this first line appearing was
long — several times longer than any of the deliberate backoff delays
themselves — because it was waiting on the operating system's own TCP
retry cycle, not on anything the client controlled.

After the fix, the same test:

```text
[peer-app] EVENT:READY_FOR_COMMANDS:
Connection lost (attempt 1, was connected for 0s): Connect timed out
Reconnecting in 1s...
```

The first failure now appears within about 5 seconds, and the error
message itself changed from "Connection timed out" to "Connect timed
out" — confirming this is now the client's own deliberate timeout firing,
not the operating system's.

---

## What step 3 does not fix

A connection that goes silent without any error — for example, a client's
network dropping entirely, with no packet ever telling either side
anything went wrong — is not detected by anything built so far. The
client's `readLine()` inside the message loop has no read timeout, so it
can sit blocked indefinitely on a connection that is already dead in
every practical sense. Observed directly: disabling the local network
connection produced no reconnect attempt, no error, and no output at all
— the client simply had no way to notice anything had happened.

This is a client-side version of the same gap step 2 found on the server
side (a session that doesn't notice it's dead can sit there for a very
long time). Closing it is step 4: a read timeout on both sides, paired
with the existing heartbeat, so a genuinely dead connection is noticed on
a predictable schedule rather than only when an actual error eventually
surfaces.

---

## Relationship to other project documents

- Part of the reconnect redesign — see `chat-reconnect-plan.md` and
  `chat-reconnect-steps-1-2.md` in `docs/fixes/` for the server-side work
  this builds on.
- The silent-disconnect gap found while testing this step is the reason
  step 4 is the next piece of work, not an optional follow-up.
