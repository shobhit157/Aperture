use anyhow::{anyhow, Result};
use iroh::{
    endpoint::{presets, PathEvent, RecvStream},
    Endpoint, EndpointAddr, EndpointId, RelayUrl, TransportAddr,
};
use std::path::PathBuf;
use std::sync::OnceLock;
use std::time::Instant;
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::time::{timeout, Duration};
use tokio_stream::StreamExt;

const ALPN: &[u8] = b"p2papp/file/0";

// Phase A2: limits for what a remote peer may send us.
const MAX_HEADER_BYTES: usize = 1024;
const MAX_NAME_CHARS: usize = 100;
const MAX_ID_CHARS: usize = 64;
const STALL_TIMEOUT_SECS: u64 = 40;
// Phase A4: default read/write chunk. Can be changed for speed tests with
// PEER_CHUNK_KB (see chunk_size()).
const DEFAULT_CHUNK_KB: usize = 64;
// Phase A4: progress lines — at most one per 1% step or per second.
const PROGRESS_MIN_INTERVAL_MS: u128 = 1000;
// Phase A3: the hash line after the data is tiny; anything longer is wrong.
const MAX_TRAILER_BYTES: usize = 100;
const TRAILER_TIMEOUT_SECS: u64 = 10;
// Phase A3: header/trailer version. Both sides must run the same build.
const HEADER_PREFIX: &str = "META3|";

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt::init();

    let username = std::env::args().nth(1).unwrap_or_else(|| "peer".to_string());

    let endpoint = Endpoint::builder(presets::N0)
        .alpns(vec![ALPN.to_vec()])
        .bind()
        .await?;
    endpoint.online().await;
    let my_endpoint_id = endpoint.id();
    println!("EVENT:ENDPOINT_READY:{my_endpoint_id}");

    let my_addr = endpoint.addr();
    if let Some(relay_url) = my_addr.relay_urls().next() {
        println!("EVENT:RELAY_READY:{relay_url}");
    } else {
        println!("EVENT:ERROR:no relay url available yet");
    }
    // Phase A4: the direct (IP) addresses this endpoint knows about. Helps
    // explain why some pairs stay on the relay (e.g. a pod that only knows
    // its 10.1.x.x address). Java ignores this event.
    let ips: Vec<String> = my_addr.ip_addrs().map(|a| a.to_string()).collect();
    println!("EVENT:ENDPOINT_ADDRS:{}", if ips.is_empty() { "none".to_string() } else { ips.join(",") });
    println!("EVENT:CHUNK_SIZE:{}", chunk_size());

    let recv_endpoint = endpoint.clone();
    let recv_username = username.clone();
    tokio::spawn(async move {
        loop {
            let Some(incoming) = recv_endpoint.accept().await else { break };
            let recv_username = recv_username.clone();
            tokio::spawn(async move {
                if let Err(e) = handle_incoming(incoming, &recv_username).await {
                    // Phase A2: a connection that failed before any transfer
                    // started (e.g. QUIC handshake errors). Tagged so it is no
                    // longer confused with transfer errors.
                    println!("EVENT:INCOMING_REJECTED:{}", clean(&e.to_string()));
                }
            });
        }
    });

    println!("EVENT:READY_FOR_COMMANDS:");

    let stdin = BufReader::new(tokio::io::stdin());
    let mut lines = stdin.lines();

    while let Ok(Some(line)) = lines.next_line().await {
        let line = line.trim();

        if line == "quit" {
            break;
        } else if let Some(rest) = line.strip_prefix("sendto ") {
            // format: sendto <transfer_id> <endpoint_id> <relay_url|-> <file_path>
            let parts: Vec<&str> = rest.splitn(4, ' ').collect();
            if parts.len() != 4 {
                println!("EVENT:ERROR:usage: sendto <transfer_id> <endpoint_id> <relay_url|-> <file_path>");
                continue;
            }
            let transfer_id = parts[0].to_string();
            let endpoint_id_str = parts[1];
            let relay_url_str = parts[2];
            let file_path = PathBuf::from(parts[3]);

            // Phase A2: the receiver uses the ID in a filename, so only
            // safe IDs are allowed (Java's UUIDs pass).
            if !valid_transfer_id(&transfer_id) {
                println!("EVENT:ERROR:bad transfer id (allowed: A-Z a-z 0-9 - _, max {MAX_ID_CHARS})");
                continue;
            }
            if !file_path.is_file() {
                println!("EVENT:TRANSFER_FAILED:{transfer_id}:sender: file not found: {}", clean(&file_path.display().to_string()));
                continue;
            }

            let parsed_id = endpoint_id_str.parse::<EndpointId>();
            let parsed_relay = relay_url_str.parse::<RelayUrl>();

            // Phase A4 (bug 10): the relay URL is only a hint — discovery finds
            // the peer by its ID even with a wrong URL (test 10). "-" means
            // "no hint, dial by ID only".
            let parsed_relay = if relay_url_str == "-" { Ok(None) } else { parsed_relay.map(Some) };

            match (parsed_id, parsed_relay) {
                (Ok(target_id), Ok(relay_hint)) => {
                    let addr = match relay_hint {
                        Some(relay_url) => EndpointAddr::from_parts(
                            target_id,
                            std::iter::once(TransportAddr::Relay(relay_url)),
                        ),
                        None => EndpointAddr::new(target_id),
                    };
                    let endpoint = endpoint.clone();
                    tokio::spawn(async move {
                        // Phase A1: three distinct outcomes. Only failures the
                        // receiver can't know about are reported as
                        // TRANSFER_FAILED here — if the receiver itself failed,
                        // it already reported that (exactly once).
                        match send_file(&endpoint, addr, &file_path, &transfer_id).await {
                            Ok(SendOutcome::Delivered) => {
                                println!("EVENT:FILE_SENT:{transfer_id}:{}", file_path.display());
                            }
                            Ok(SendOutcome::ReceiverFailed(reason)) => {
                                println!("EVENT:FILE_SEND_FAILED:{transfer_id}:{}", clean(&reason));
                            }
                            Err(e) => {
                                println!("EVENT:TRANSFER_FAILED:{transfer_id}:sender: {}", clean(&e.to_string()));
                            }
                        }
                    });
                }
                (Err(e), _) => println!("EVENT:ERROR:bad endpoint id: {e}"),
                (_, Err(e)) => println!("EVENT:ERROR:bad relay url: {e}"),
            }
        } else {
            println!("EVENT:ERROR:unknown command");
        }
    }

    Ok(())
}

/// Reports whether the connection's currently-SELECTED path (the one actually
/// used for data transmission) is direct (IP) or relay, tagged with the
/// transfer ID so the caller can attribute it to the correct transfer.
/// Called ONCE, by the receiver, only on success — this is what tells
/// MeshEventServer the transfer is DONE.
fn report_path_from_conn(conn: &iroh::endpoint::Connection, transfer_id: &str) {
    let paths = conn.paths();
    let selected = paths.iter().find(|p| p.is_selected());

    let path = match selected {
        Some(p) if p.is_ip() => "direct",
        Some(p) if p.is_relay() => "relay",
        Some(_) => "custom",
        None => "none",
    };
    println!("EVENT:TRANSFER_PATH:{transfer_id}:{path}");
}

/// Fix 4: live indicator for the mesh. Prints CONNECTION_PATH every time
/// Iroh selects a path (first selection, and any later migration). It never
/// marks anything complete.
fn spawn_path_watcher(conn: &iroh::endpoint::Connection, transfer_id: &str) {
    let mut path_events = conn.path_events();
    let transfer_id = transfer_id.to_string();
    tokio::spawn(async move {
        while let Some(event) = path_events.next().await {
            if let PathEvent::Selected { remote_addr, .. } = event {
                let path = match remote_addr {
                    TransportAddr::Ip(_) => "direct",
                    TransportAddr::Relay(_) => "relay",
                    _ => "custom",
                };
                println!("EVENT:CONNECTION_PATH:{transfer_id}:{path}");
            }
        }
    });
}

/// Phase A1: what the sender learned about a transfer that it managed to
/// start. Local failures (can't connect, stream broke, no reply) and
/// rejections (receiver refused the header) are returned as Err instead.
enum SendOutcome {
    /// Receiver replied OK: file fully received and saved.
    Delivered,
    /// The receiver failed, or will report the failure itself (e.g. after
    /// we reset the stream), so the sender must NOT report it again.
    ReceiverFailed(String),
}

/// Phase A1: keep reasons on one line and free of '|' so they can't break
/// the EVENT line or the TRANSFER_METRIC|failed|peer|reason message in Java.
fn clean(reason: &str) -> String {
    reason.replace(['\n', '\r', '|'], " ")
}

/// Phase A2: transfer IDs end up in filenames and EVENT lines.
fn valid_transfer_id(id: &str) -> bool {
    !id.is_empty()
        && id.len() <= MAX_ID_CHARS
        && id.chars().all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_')
}

/// Phase A2 (bug 8 + path safety): turn any name a peer sends into a plain,
/// safe filename. Used by the sender (for the header and progress lines) and
/// again by the receiver (never trust the other side).
fn safe_filename(raw: &str) -> String {
    // Keep only the last path component: "../../etc/x" -> "x".
    let base = raw.rsplit(['/', '\\']).next().unwrap_or("");
    let replaced: String = base
        .chars()
        .map(|c| if c.is_control() || "<>:\"/\\|?*".contains(c) { '_' } else { c })
        .collect();
    // No hidden/relative names, no trailing dots or spaces (Windows).
    let trimmed = replaced.trim().trim_start_matches('.').trim_end_matches(['.', ' ']);
    let short: String = trimmed.chars().take(MAX_NAME_CHARS).collect();
    if short.is_empty() { "file".to_string() } else { short }
}

/// Phase A4: chunk size in bytes. PEER_CHUNK_KB (4..=4096) overrides the
/// default, read once. Used for speed tests; both sides may differ.
fn chunk_size() -> usize {
    static SIZE: OnceLock<usize> = OnceLock::new();
    *SIZE.get_or_init(|| {
        let kb = std::env::var("PEER_CHUNK_KB")
            .ok()
            .and_then(|v| v.parse::<usize>().ok())
            .filter(|kb| (4..=4096).contains(kb))
            .unwrap_or(DEFAULT_CHUNK_KB);
        kb * 1024
    })
}

/// Phase A4 (bug 11): decides when a progress line is worth printing —
/// when the percentage changes or a second has passed, and always at 100%.
/// About 100 lines per transfer instead of one per chunk.
struct ProgressGate {
    last_pct: Option<u64>,
    last_print: Instant,
}

impl ProgressGate {
    fn new() -> Self {
        ProgressGate { last_pct: None, last_print: Instant::now() }
    }

    fn should_print(&mut self, pct: u64) -> bool {
        if pct >= 100 && self.last_pct == Some(100) {
            return false;
        }
        let changed = self.last_pct != Some(pct);
        let slow = self.last_print.elapsed().as_millis() >= PROGRESS_MIN_INTERVAL_MS;
        if changed || slow {
            self.last_pct = Some(pct);
            self.last_print = Instant::now();
            true
        } else {
            false
        }
    }
}

const CONNECT_TIMEOUT_SECS: u64 = 30;
const REPLY_TIMEOUT_SECS: u64 = 60;

async fn send_file(endpoint: &Endpoint, target: EndpointAddr, file_path: &PathBuf, transfer_id: &str) -> Result<SendOutcome> {
    use tokio::io::AsyncReadExt;

    // Phase A1: bounded connect — an unreachable peer becomes a clear
    // failure instead of a transfer stuck "in progress" forever (bug 4).
    let conn = timeout(Duration::from_secs(CONNECT_TIMEOUT_SECS), endpoint.connect(target, ALPN))
        .await
        .map_err(|_| anyhow!("connect timed out after {CONNECT_TIMEOUT_SECS}s"))??;
    let (mut send, mut recv) = conn.open_bi().await?;

    spawn_path_watcher(&conn, transfer_id);

    // Phase A2: cleaned name, so '|' etc. can't break our header or the
    // progress lines that Client.java splits on '|'.
    let filename = safe_filename(
        file_path.file_name().and_then(|n| n.to_str()).unwrap_or("file"),
    );
    let total_size = tokio::fs::metadata(file_path).await?.len();

    // Phase A2: the name is LAST, so even if it contained '|' the
    // receiver's splitn(3) would keep it in one piece.
    // Phase A3: same fields, new version tag:
    //   META3|<transfer_id>|<size>|<name>\n
    send.write_all(format!("{HEADER_PREFIX}{transfer_id}|{total_size}|{filename}\n").as_bytes()).await?;

    // Phase A3: TEST ONLY. Flip one byte after hashing it, so the script can
    // check that the receiver rejects corrupted data. Off unless the
    // environment variable is set to 1.
    let corrupt_for_test = std::env::var("PEER_TEST_CORRUPT_BYTE").as_deref() == Ok("1");

    let mut file = tokio::fs::File::open(file_path).await?;
    let mut buf = vec![0u8; chunk_size()];
    let mut sent: u64 = 0;
    // Phase A3: hash while sending, so the file is read only once.
    let mut hasher = blake3::Hasher::new();
    let mut progress = ProgressGate::new();

    // Phase A4: send exactly the announced size. If the file grows, the
    // extra bytes are ignored; if it shrinks, we stop (below).
    while sent < total_size {
        let want = (total_size - sent).min(buf.len() as u64) as usize;
        let n = file.read(&mut buf[..want]).await?;
        if n == 0 {
            break;
        }
        hasher.update(&buf[..n]);
        if corrupt_for_test && sent == 0 {
            buf[0] ^= 0xFF;
        }
        send.write_all(&buf[..n]).await?;
        sent += n as u64;
        let pct = (sent * 100 / total_size).min(100);
        if progress.should_print(pct) {
            println!("EVENT:PROGRESS:sending|{filename}|{pct}|{sent}|{total_size}|{transfer_id}");
        }
    }
    if total_size == 0 {
        println!("EVENT:PROGRESS:sending|{filename}|100|0|0|{transfer_id}");
    }

    // Phase A4: the file shrank while we were sending. Abort the stream
    // instead of sending a hash for a shorter file. The receiver sees the
    // reset and reports the failure (once); we only note it locally.
    if sent < total_size {
        let _ = send.reset(1u32.into());
        conn.close(1u32.into(), b"file changed");
        return Ok(SendOutcome::ReceiverFailed(format!(
            "file changed while sending (sent {sent} of {total_size} bytes)"
        )));
    }

    // Phase A3: the hash goes AFTER the data (it is only known now).
    //   HASH|blake3|<64 hex chars>\n
    let hash = hasher.finalize().to_hex();
    println!("EVENT:FILE_HASH:{transfer_id}:blake3:{hash}");
    send.write_all(format!("HASH|blake3|{hash}\n").as_bytes()).await?;

    send.finish()?;

    // Phase A1: read the receiver's verdict (bug 1).
    let reply = timeout(Duration::from_secs(REPLY_TIMEOUT_SECS), recv.read_to_end(1024))
        .await
        .map_err(|_| anyhow!("no reply from receiver after {REPLY_TIMEOUT_SECS}s"))?
        .map_err(|e| anyhow!("reading receiver reply failed: {e}"))?;
    let reply = String::from_utf8_lossy(&reply).trim().to_string();

    conn.close(0u32.into(), b"done");

    if reply == "OK" || reply == "ACK" {
        Ok(SendOutcome::Delivered)
    } else if let Some(reason) = reply.strip_prefix("FAIL|") {
        Ok(SendOutcome::ReceiverFailed(reason.to_string()))
    } else if let Some(reason) = reply.strip_prefix("REJECTED|") {
        // Phase A2: the receiver refused our header and could not report it
        // (it had no valid transfer ID) — so the sender reports it.
        Err(anyhow!("receiver rejected: {reason}"))
    } else {
        Err(anyhow!("unexpected reply from receiver: {reply:?}"))
    }
}

/// Phase A2: the parsed, already-cleaned header.
struct Header {
    transfer_id: String,
    size: u64,
    name: String,
}

/// Phase A2 (bug 12) / A3: read one '\n'-terminated line with a size limit
/// and ONE timeout for the whole line. Used for the header and the hash line.
async fn read_line_limited(
    recv: &mut RecvStream,
    what: &str,
    max_bytes: usize,
    secs: u64,
) -> std::result::Result<String, String> {
    let mut bytes = Vec::new();
    let read_line = async {
        let mut byte = [0u8; 1];
        loop {
            recv.read_exact(&mut byte).await.map_err(|e| format!("{what} read failed: {e}"))?;
            if byte[0] == b'\n' {
                return Ok(());
            }
            if bytes.len() >= max_bytes {
                return Err(format!("{what} longer than {max_bytes} bytes"));
            }
            bytes.push(byte[0]);
        }
    };
    timeout(Duration::from_secs(secs), read_line)
        .await
        .map_err(|_| format!("stalled while reading {what}"))??;
    String::from_utf8(bytes).map_err(|_| format!("{what} is not UTF-8"))
}

/// Phase A2: read and check the header. Err = reason to reject.
async fn read_header(recv: &mut RecvStream) -> std::result::Result<Header, String> {
    let text = read_line_limited(recv, "header", MAX_HEADER_BYTES, STALL_TIMEOUT_SECS).await?;
    let rest = match text.strip_prefix(HEADER_PREFIX) {
        Some(r) => r,
        None if text.starts_with("META") => return Err("different header version (both sides must run the same peer-app build)".into()),
        None => return Err("missing META header".into()),
    };

    let mut parts = rest.splitn(3, '|');
    let transfer_id = parts
        .next()
        .filter(|id| valid_transfer_id(id))
        .ok_or("bad transfer id")?
        .to_string();
    let size = parts
        .next()
        .and_then(|s| s.parse::<u64>().ok())
        .ok_or("bad size")?;
    let name = safe_filename(parts.next().ok_or("missing filename")?);

    Ok(Header { transfer_id, size, name })
}

async fn handle_incoming(incoming: iroh::endpoint::Incoming, _my_username: &str) -> Result<()> {
    let conn = incoming.await?;
    let (mut send, mut recv) = conn.accept_bi().await?;

    // Phase A2: a bad header is rejected. We have no valid transfer ID to
    // report with, so we tell the sender (who has one) and it reports.
    let h = match read_header(&mut recv).await {
        Ok(h) => h,
        Err(reason) => {
            let reason = clean(&reason);
            println!("EVENT:INCOMING_REJECTED:{reason}");
            let _ = send.write_all(format!("REJECTED|{reason}\n").as_bytes()).await;
            let _ = send.finish();
            let _ = timeout(Duration::from_secs(5), conn.closed()).await;
            return Ok(());
        }
    };

    spawn_path_watcher(&conn, &h.transfer_id);

    // Phase A2 (bug 5): one partial file per transfer, created with
    // create_new so two transfers can never write into the same file.
    let partial_path = format!("received_{}.partial", h.transfer_id);
    let opened = tokio::fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(&partial_path)
        .await;

    let (verdict, connection_dead) = match opened {
        // Not our file (same transfer ID already in progress) — don't delete it.
        Err(e) => (Err(format!("could not create {partial_path}: {e}")), false),
        Ok(mut out_file) => {
            let (body, dead) = receive_body(&conn, &mut recv, &mut out_file, &h).await;
            drop(out_file);
            let verdict = match body {
                Ok(()) => save_partial(&partial_path, &h).await,
                Err(reason) => Err(reason),
            };
            if verdict.is_err() {
                let _ = tokio::fs::remove_file(&partial_path).await;
            }
            (verdict, dead)
        }
    };

    // Phase A1: decide once, report once, tell the sender the truth.
    match &verdict {
        Ok(final_path) => {
            println!("EVENT:FILE_RECEIVED:{}:{final_path}|{}", h.transfer_id, h.size);
            report_path_from_conn(&conn, &h.transfer_id);
        }
        Err(reason) => {
            println!("EVENT:TRANSFER_FAILED:{}:{}", h.transfer_id, clean(reason));
        }
    }

    if !connection_dead {
        let reply = match &verdict {
            Ok(_) => "OK\n".to_string(),
            Err(reason) => format!("FAIL|{}\n", clean(reason)),
        };
        let _ = send.write_all(reply.as_bytes()).await;
        let _ = send.finish();
        let _ = timeout(Duration::from_secs(10), conn.closed()).await;
    }

    Ok(())
}

/// Phase A2: receive exactly `h.size` bytes into `out_file` and make sure
/// they are on disk. Phase A3: also check the sender's BLAKE3 hash.
/// Phase A4: throttled progress + timing breakdown.
/// Every failure (read, write, stall, disk full, bad hash, extra data)
/// becomes an Err reason — no `?` that would skip the report.
/// The bool is true when the connection is already gone (no reply possible).
async fn receive_body(
    conn: &iroh::endpoint::Connection,
    recv: &mut RecvStream,
    out_file: &mut tokio::fs::File,
    h: &Header,
) -> (std::result::Result<(), String>, bool) {
    let total = h.size;
    let mut buf = vec![0u8; chunk_size()];
    let mut received: u64 = 0;
    // Phase A3: hash exactly what we write to disk.
    let mut hasher = blake3::Hasher::new();
    let mut progress = ProgressGate::new();
    // Phase A4: where does the time go? data / hash line / sync to disk.
    let started = Instant::now();

    while received < total {
        // Never read past the announced size.
        let want = (total - received).min(buf.len() as u64) as usize;
        tokio::select! {
            read_result = recv.read(&mut buf[..want]) => match read_result {
                Ok(Some(n)) if n > 0 => {
                    if let Err(e) = out_file.write_all(&buf[..n]).await {
                        return (Err(format!("could not write file: {e}")), false);
                    }
                    hasher.update(&buf[..n]);
                    received += n as u64;
                    let pct = (received * 100 / total).min(100);
                    if progress.should_print(pct) {
                        println!("EVENT:PROGRESS:receiving|{}|{pct}|{received}|{total}|{}", h.name, h.transfer_id);
                    }
                }
                Ok(Some(_)) => {}
                Ok(None) => return (Err(format!("incomplete ({received}/{total} bytes)")), false),
                Err(e) => return (Err(format!("read error at {received}/{total} bytes: {e}")), false),
            },
            _ = conn.closed() => {
                return (Err(format!("connection lost ({received}/{total} bytes)")), true);
            }
            _ = tokio::time::sleep(Duration::from_secs(STALL_TIMEOUT_SECS)) => {
                return (Err(format!("stalled (no activity) ({received}/{total} bytes)")), false);
            }
        }
    }
    if total == 0 {
        println!("EVENT:PROGRESS:receiving|{}|100|0|0|{}", h.name, h.transfer_id);
    }
    let data_done = Instant::now();

    // Phase A3 (bug 7): right after the data comes the sender's hash line.
    // Anything else (more data, garbage, nothing) is a failure.
    let ours = hasher.finalize().to_hex();
    println!("EVENT:FILE_HASH:{}:blake3:{ours}", h.transfer_id);
    let line = match read_line_limited(recv, "hash line", MAX_TRAILER_BYTES, TRAILER_TIMEOUT_SECS).await {
        Ok(l) => l,
        Err(e) => return (Err(format!("no valid hash from sender: {e}")), false),
    };
    let Some(theirs) = line.strip_prefix("HASH|blake3|") else {
        return (Err("no valid hash from sender (sent more data than announced?)".into()), false);
    };
    if !theirs.eq_ignore_ascii_case(ours.as_str()) {
        return (Err(format!("hash mismatch (sender {}…, received {}…)", short(theirs), short(&ours))), false);
    }

    // Phase A2: nothing may follow the hash line.
    let mut extra = [0u8; 1];
    if let Ok(Ok(Some(n))) = timeout(Duration::from_secs(5), recv.read(&mut extra)).await {
        if n > 0 {
            return (Err("sender sent data after the hash".into()), false);
        }
    }
    let checked = Instant::now();

    // Phase A2 (bug 6): make sure the data is really on disk before the
    // file gets its final name.
    if let Err(e) = out_file.flush().await {
        return (Err(format!("could not flush file: {e}")), false);
    }
    if let Err(e) = out_file.sync_all().await {
        return (Err(format!("could not sync file to disk: {e}")), false);
    }
    let synced = Instant::now();

    // Phase A4: timing breakdown (Java ignores this event).
    let data_ms = (data_done - started).as_millis();
    let mbps = if data_ms > 0 { total as f64 / 1_048_576.0 / (data_ms as f64 / 1000.0) } else { 0.0 };
    println!(
        "EVENT:TRANSFER_TIMING:{}:data_ms={data_ms} hash_wait_ms={} sync_ms={} total_ms={} data_mb_s={mbps:.1} chunk_kb={}",
        h.transfer_id,
        (checked - data_done).as_millis(),
        (synced - checked).as_millis(),
        (synced - started).as_millis(),
        chunk_size() / 1024,
    );

    (Ok(()), false)
}

/// Phase A2 (bug 5): give the finished file its final name without ever
/// overwriting an existing file: received_<id8>_<name>, then _2, _3, ...
async fn save_partial(partial_path: &str, h: &Header) -> std::result::Result<String, String> {
    let short_id: String = h.transfer_id.chars().take(8).collect();
    let base = format!("received_{short_id}_{}", h.name);

    let mut final_path = base.clone();
    let mut n = 2;
    while tokio::fs::try_exists(&final_path).await.unwrap_or(false) {
        if n > 1000 {
            return Err("too many files with the same name".into());
        }
        final_path = with_counter(&base, n);
        n += 1;
    }

    tokio::fs::rename(partial_path, &final_path)
        .await
        .map_err(|e| format!("could not save file: {e}"))?;
    Ok(final_path)
}

/// Phase A3: first 12 chars of a hash, for readable error messages.
fn short(hash: &str) -> &str {
    hash.get(..12).unwrap_or(hash)
}

/// "received_ab_photo.jpg", 2 -> "received_ab_photo_2.jpg"
fn with_counter(name: &str, n: u32) -> String {
    match name.rfind('.') {
        Some(dot) if dot > 0 => format!("{}_{n}{}", &name[..dot], &name[dot..]),
        _ => format!("{name}_{n}"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn filenames_are_cleaned() {
        assert_eq!(safe_filename("we|ird.txt"), "we_ird.txt");
        assert_eq!(safe_filename("../../etc/passwd"), "passwd");
        assert_eq!(safe_filename("C:\\Users\\x\\a.txt"), "a.txt");
        assert_eq!(safe_filename(".."), "file");
        assert_eq!(safe_filename(".bashrc"), "bashrc");
        assert_eq!(safe_filename("a:b?.txt. "), "a_b_.txt");
        assert_eq!(safe_filename(""), "file");
        assert_eq!(safe_filename(&"x".repeat(300)).chars().count(), MAX_NAME_CHARS);
    }

    #[test]
    fn transfer_ids() {
        assert!(valid_transfer_id("3f2b9c1e-7a1d-4c2e-9f00-1234567890ab"));
        assert!(valid_transfer_id("n1"));
        assert!(!valid_transfer_id(""));
        assert!(!valid_transfer_id("../x"));
        assert!(!valid_transfer_id("a|b"));
        assert!(!valid_transfer_id(&"a".repeat(65)));
    }

    #[test]
    fn hash_is_chunking_independent() {
        // Hashing in 64 KB pieces (as we do while streaming) must give the
        // same result as hashing the whole file at once.
        let data: Vec<u8> = (0..300_000u32).map(|i| (i % 251) as u8).collect();
        let mut h = blake3::Hasher::new();
        for chunk in data.chunks(DEFAULT_CHUNK_KB * 1024) {
            h.update(chunk);
        }
        assert_eq!(h.finalize(), blake3::hash(&data));
        assert_eq!(short("0123456789abcdef"), "0123456789ab");
    }

    #[test]
    fn progress_is_throttled() {
        let mut g = ProgressGate::new();
        // 10,000 chunks of the same transfer -> at most ~101 lines
        let printed = (0..=10_000u64).filter(|i| g.should_print(i * 100 / 10_000)).count();
        assert!(printed <= 101, "printed {printed}");
        assert!(printed >= 100);
        // 100% is printed once only
        assert!(!g.should_print(100));
    }

    #[test]
    fn counter_names() {
        assert_eq!(with_counter("received_ab_photo.jpg", 2), "received_ab_photo_2.jpg");
        assert_eq!(with_counter("received_ab_noext", 3), "received_ab_noext_3");
    }
}
