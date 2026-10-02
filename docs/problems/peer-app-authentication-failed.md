# peer-app "authentication failed" Errors

Status: **Open — not yet investigated.** Observed twice; cause unconfirmed.

---

## Symptom

The client's `peer-app` (Rust / Iroh data plane) prints repeated errors, forwarded by `Client.java` with the `[peer-app]` prefix:

```text
[peer-app] EVENT:ERROR:detected an error with protocol compliance that was not covered by more specific error codes: authentication failed
```

- Appears in bursts (one or two lines, or nine or more in a row).
- Chat keeps working while it happens. The chat channel is a separate TCP connection to the signaling server and is unaffected.

---

## What the error means

- `peer-app` talks to other peers over **QUIC**, an encrypted transport. Every connection starts with a cryptographic handshake where each side proves its identity (its endpoint key), and every packet after that is authenticated.
- "Protocol compliance … authentication failed" is a QUIC **protocol violation** error. A handshake or packet on some connection could not be verified, so that connection was rejected.
- It comes from the **data plane only**. It is unrelated to the chat reconnect / session work (signaling server, Redis, sessions).

---

## When it was observed

| # | Context | What happened just before | Transfers afterwards |
|---|---|---|---|
| 1 | Admin client, after several WiFi on/off tests (chat reconnect step 4 testing) | Network changed repeatedly while `peer-app` kept running | **Worked**: a 1 MB `bot_medium.bin` transfer completed and was hash-verified |
| 2 | Admin client, during the Redis-expiry pod-crash test | `admin2`'s Java process was frozen (`kill -STOP 1793`) and later killed (`kill -9 1793`); its server pod was force-deleted | Not yet checked |

The bursts were not correlated with any `!chat` or `!send` command.

---

## Hypotheses (unconfirmed)

1. **Orphaned `peer-app` processes.**
   `kill -STOP` / `kill -9` on a client only affects the **Java** process. Its child `peer-app` keeps running with no parent, still holding the same Iroh identity and endpoint. Stale QUIC traffic between a leftover `peer-app` and a live one could fail authentication. This fits occurrence 2 closely.

2. **Stale QUIC connections after a network change.**
   After WiFi drops or the IP changes, connections `peer-app` already had open (from earlier transfers or relay paths) may carry packets that no longer verify on the new path. This fits occurrence 1.

3. **Stale endpoint information.**
   A peer dialing an endpoint ID that no longer matches the process answering at that address (for example after a bot pod restart produced a new key while old info was still cached somewhere).

---

## Impact

- **Known:** none on chat or sessions.
- **Known:** after occurrence 1, file transfer still worked.
- **Unknown:** whether transfers fail while the errors are happening, and whether the errors are just noise from dead connections.

---

## Related problem: orphaned child process

Independent of this error, the client does not stop `peer-app` when the Java process dies abruptly. Leftover `peer-app` processes accumulate, keep an Iroh endpoint alive, and may keep talking to relays or peers. This should be fixed regardless of whether it turns out to cause the error.

---

## How to investigate

1. **Check for orphans when the errors appear:**
```bash
   ps aux | grep "[p]eer"
```
   More `peer` processes than running clients means leftovers. Kill the extras and see whether the errors stop.

2. **Record context each time it happens:**
   - which client prints it (admin, admin2, bot)
   - what happened just before (crash test, reconnect, transfer, network change)
   - time

3. **Run with debug logging** to see which peer and which step fails:
```bash
   RUST_LOG=iroh=debug java -cp target/Network_lab-0.0.1-SNAPSHOT.jar com.shobhit.Network_lab.Client <server-ip> 30000
```
   (Bots already run with `RUST_LOG=iroh=debug`.)

4. **Check whether transfers are affected:** while errors are appearing, run `!send all all small` and `/send` in both directions.

5. **Reproduce deliberately:**
   - Hypothesis 1: start two clients, `kill -9` one, check for an orphaned `peer`, and watch the other client for errors.
   - Hypothesis 2: run one transfer, toggle WiFi, and watch for errors without killing anything.

---

## Possible fixes (once the cause is known)

- **Client:** stop `peer-app` on exit (shutdown hook, `destroy()` then `destroyForcibly()`).
- **peer-app:** exit on its own when its stdin closes (parent gone), so even `kill -9` on Java doesn't leave it running.
- **peer-app:** close or replace stale connections after a network change, and log which peer an authentication failure came from.
- If confirmed as harmless noise: downgrade it to a debug log instead of `EVENT:ERROR`.

---

## Relationship to other documents

- Found during Chat Reconnect Redesign testing (step 4, and the Redis expiry pod-crash test). Not caused by that work.
- Data plane background: `docs/architecture/data-plane.md`, `docs/decisions/003-iroh-data-plane.md`.
