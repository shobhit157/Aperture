# Future Plan: Transfer Resume

> [!NOTE]
> This is a forward-looking design doc, not a built feature. It records
> the gap between Aperture's current "clean failure" behavior and true
> resume, and what would actually be required to close it.

---

## 1. Current behavior: clean failure, not resume

The atomicity fix in `peer-app` guarantees a transfer either completes
correctly or leaves nothing corrupted behind — but it does not preserve
partial progress.

```text
Today:

  100 MB transfer
         │
        60 MB received
         │
    connection fails
         │
         ▼
  .partial file deleted
  EVENT:TRANSFER_FAILED
         │
         ▼
  next attempt starts from 0 MB again
```

This was a deliberate, explicit choice when the atomicity fix was built —
correctness (never a corrupted file) was prioritized over convenience
(not losing progress), and resume was scoped out as separate, larger work.

---

## 2. What real resume would look like

```text
Planned:

  100 MB transfer
         │
        60 MB received
         │
    connection fails
         │
         ▼
  .partial file KEPT (60 MB, not deleted)
         │
         ▼
    reconnect
         │
         ▼
  resume request: "I already have the first 60 MB"
         │
         ▼
  sender seeks to byte 60,000,000 and continues from there
         │
         ▼
      100 MB total
         │
         ▼
  verify complete file
         │
         ▼
      COMPLETED
```

---

## 3. What this genuinely requires, beyond today's implementation

1. **Receiver**: keep the `.partial` file instead of deleting it on
   failure, and record how many bytes it actually contains.
2. **Fresh signaling for resume specifically**: since the original QUIC
   connection is gone, a *new* connection needs to be established, and
   both sides need to agree "this is a resume of transfer X, starting at
   byte N" — not a fresh `FILE_REQUEST` from scratch.
3. **Sender**: ability to seek to an arbitrary byte offset in the source
   file and begin streaming from there, rather than always starting at
   byte 0.
4. **Integrity verification on resume**: before trusting the existing
   `.partial` bytes, some way to confirm they're genuinely still correct
   (e.g., a hash of the already-received portion) — a resume that blindly
   trusts a possibly-corrupted partial file is worse than starting over.
5. **A cutoff for how long a `.partial` file is kept** — an unresolved
   partial transfer can't be kept forever; some expiry/cleanup policy is
   needed so failed transfers that are never actually resumed don't
   accumulate as orphaned disk usage indefinitely.

---

## 4. The simpler, already-discussed alternative: auto-retry (not resume)

Rather than true byte-level resume, a smaller and much simpler
improvement: when a transfer is marked failed, automatically re-issue the
*entire* `FILE_REQUEST` from scratch, once, without the user needing to
manually retype `!send` or `/send` again.

```text
Simple auto-retry:

  transfer fails
        │
        ▼
  automatically re-request the WHOLE file again
        │
        ▼
  (no partial-byte tracking, no resume signaling needed)
```

This was directly motivated by a real test session: a transfer
interrupted by a genuine network drop required manually retyping the
`!send` command once the connection recovered, even though the surrounding
chat connection itself reconnected automatically.

> [!TIP]
> Auto-retry is meaningfully less work than true resume and solves the
> most common real annoyance (having to notice a failure and manually
> retry) without needing sender-side seek support or resume-specific
> signaling at all. Worth building before true resume, not instead of it.

---

## 5. Why this becomes more valuable once the swarm feature exists

A failed **piece** (in the planned swarm model — see the swarm
distribution future-plan doc) is cheap to retry from scratch, since
pieces are small by design. A failed **90%-complete single-stream
transfer** in today's one-to-one model is expensive to lose and restart.

This means: true resume is more urgently valuable for **today's
one-to-one, whole-file transfers** than it will be once files are
piece-based — worth building resume (or at minimum auto-retry) before the
swarm work, not after, since the swarm architecture itself reduces how
much resume actually matters.

---

## 6. Implementation checklist

- [ ] Build auto-retry first (simpler, addresses the more common
      real-world annoyance already observed in testing).
- [ ] Change `peer-app`'s failure-cleanup logic to optionally preserve
      `.partial` files instead of always deleting them.
- [ ] Design the resume-specific signaling message (transfer ID + byte
      offset) through `Client.java` and the server.
- [ ] Add sender-side file seek support.
- [ ] Add partial-file integrity verification before trusting a resume.
- [ ] Add an expiry policy for orphaned `.partial` files that are never
      resumed.
- [ ] Re-test the mid-transfer-kill scenarios already used to verify the
      atomicity fix, this time confirming resume completes correctly
      rather than just confirming clean failure.
