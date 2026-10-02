# peer-app "authentication failed" Errors

Status: **Open — partly understood.** The errors come from **incoming connections failing during the QUIC handshake**, not from Aperture's own transfers. Who is connecting, and why the handshake fails, is not yet known.

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
- "Protocol compliance … authentication failed" is a QUIC **protocol violation** error: a handshake or packet could not be verified, so that connection was rejected.
- It comes from the **data plane only**. It is unrelated to the chat reconnect / session work (signaling server, Redis, sessions).

---

## Where the error is printed

Code review of `peer-app/src/main.rs` shows the line comes from the accept loop:

```rust
tokio::spawn(async move {
    if let Err(e) = handle_incoming(incoming, &recv_username).await {
        println!("EVENT:ERROR:{e}");
    }
});
```

and the first fallible step inside `handle_incoming` is the handshake:

```rust
let conn = incoming.await?;
```

What this tells us:

- The line has **no transfer ID**. Every error from a real transfer includes one (`EVENT:TRANSFER_FAILED:<id>:…`), so this isn't one of Aperture's transfers failing.
- These are **incoming connection attempts that fail during the QUIC handshake**, before any stream is opened and before any file data moves.
- They're printed as a generic `EVENT:ERROR`, which makes them look more serious than they may be.

---

## When it was observed

| # | Context | What happened just before | Transfers afterwards |
|---|---|---|---|
| 1 | Admin client, after several WiFi on/off tests (chat reconnect step 4 testing) | Network changed repeatedly while `peer-app` kept running | **Worked**: a 1 MB `bot_medium.bin` transfer completed (1,048,576 bytes, `FILE_RECEIVED`) |
| 2 | Admin client, during the Redis-expiry pod-crash test | `admin2`'s Java process was frozen (`kill -STOP 1793`) and later killed (`kill -9 1793`); its server pod was force-deleted | Not yet checked |

The bursts were not correlated with any `!chat` or `!send` command.

---

## Hypotheses

Ranked by how well they fit the evidence so far.

1. **Leftover connection attempts after a network change (most likely).**
   Both occurrences followed network disruption: repeated WiFi toggles, and a frozen client. Peers or the relay may keep retrying or delivering packets for connections whose state no longer matches (old addresses, old connection state). On the receiving side, these show up as incoming handshakes that can't be verified.

2. **A peer reaching us with stale or mismatched identity information.**
   Someone dials an endpoint ID / address combination that no longer matches the process answering there (for example after a bot restart produced a new key while old info was still cached).

3. **Orphaned `peer-app` processes (now unlikely).**
   Originally suspected because a killed client might leave its `peer-app` running with the same identity. Since then:
   - `peer-app` exits by itself when stdin closes (its command loop ends when the Java parent dies), and
   - the client now stops `peer-app` on normal exit and Ctrl+C (shutdown hook, process ownership fix),
   - and a test after `kill` showed no leftover `peer-app`.

---

## Impact

- **Known:** none on chat or sessions.
- **Known:** after occurrence 1, file transfer still worked.
- **Likely:** harmless noise from failed incoming handshakes, since they happen before any transfer starts.
- **Unknown:** whether a burst ever coincides with, or blocks, a real incoming transfer.

---

## How to investigate

1. **Log who is connecting.** In the accept loop, before `incoming.await`, print the remote address of the incoming connection, and print the error with a clear tag:
```text
   EVENT:INCOMING_REJECTED:<remote addr>:<error>
```
   This shows whether failures come from known bots, the relay, or unknown sources.

2. **Run with debug logging** to see the handshake details:
```bash
   RUST_LOG=iroh=debug java -cp target/Network_lab-0.0.1-SNAPSHOT.jar com.shobhit.Network_lab.Client <server-ip> 30000
```

3. **Record context each time it happens:** which client, time, and what happened just before (network change, crash test, transfer, bot restart).

4. **Check whether transfers are affected:** while errors are appearing, run `!send all all small` and `/send` in both directions.

5. **Reproduce deliberately:**
   - Hypothesis 1: run a transfer, toggle WiFi a few times, watch for bursts without killing anything.
   - Hypothesis 2: restart a bot pod (new key), then watch the clients that had talked to it.

---

## Possible fixes (once the cause is known)

- **Tag it properly:** print failed incoming handshakes as `EVENT:INCOMING_REJECTED` (with the remote address) instead of a generic `EVENT:ERROR`, so it isn't confused with transfer failures. Java can then log it quietly or count it as a metric.
- If confirmed harmless: log it at debug level only.
- If it does affect transfers: investigate the specific peers or paths involved (relay vs direct, stale addresses).
- Moving to `iroh-blobs` (see the file transfer improvement plan) changes the connection pattern (receiver fetches from provider), so re-check after that migration.

---

## Relationship to other documents

- Found during Chat Reconnect Redesign testing (step 4, and the Redis expiry pod-crash test). Not caused by that work.
- Code review findings: `docs/problems/file-transfer-bugs.md`.
- Fix plan for the transfer protocol: `docs/future-plans/file-transfer-improvement-plan.md`.
- Data plane background: `docs/architecture/data-plane.md`, `docs/decisions/003-iroh-data-plane.md`.
