//! META3: Aperture's own push protocol (Phase A). The sender connects and
//! streams the file; the receiver checks size + BLAKE3 hash and replies.
//! Kept working beside iroh-blobs until B5 removes it.

use anyhow::{anyhow, Result};
use iroh::{
    endpoint::{Accepting, Connection, RecvStream},
    protocol::{AcceptError, ProtocolHandler},
    Endpoint, EndpointAddr, EndpointId, RelayUrl, TransportAddr,
};
use std::path::PathBuf;
use std::time::Instant;
use tokio::io::AsyncWriteExt;
use tokio::time::{timeout, Duration};
use tokio_stream::StreamExt;

use crate::util::{chunk_size, clean, percent, safe_filename, save_partial, short, valid_transfer_id, ProgressGate, MAX_ID_CHARS};

pub const ALPN: &[u8] = b"p2papp/file/0";

// Phase A2: limits for what a remote peer may send us.
const MAX_HEADER_BYTES: usize = 1024;
const STALL_TIMEOUT_SECS: u64 = 40;
// Phase A3: the hash line after the data is tiny; anything longer is wrong.
const MAX_TRAILER_BYTES: usize = 100;
const TRAILER_TIMEOUT_SECS: u64 = 10;
// Phase A3: header/trailer version. Both sides must run the same build.
const HEADER_PREFIX: &str = "META3|";
const CONNECT_TIMEOUT_SECS: u64 = 30;
const REPLY_TIMEOUT_SECS: u64 = 60;

/// B0: serves META3 through the Router. The transfer logic itself is
/// unchanged (handle_incoming).
#[derive(Debug, Clone)]
pub struct Meta3Handler;

impl ProtocolHandler for Meta3Handler {
    /// Handshake errors keep the old event name (Phase A2), so they are
    /// still not confused with transfer errors.
    async fn on_accepting(&self, accepting: Accepting) -> std::result::Result<Connection, AcceptError> {
        match accepting.await {
            Ok(conn) => Ok(conn),
            Err(e) => {
                println!("EVENT:INCOMING_REJECTED:{}", clean(&e.to_string()));
                Err(e.into())
            }
        }
    }

    async fn accept(&self, conn: Connection) -> std::result::Result<(), AcceptError> {
        if let Err(e) = handle_incoming(conn).await {
            // Phase A2: failed before a transfer started (e.g. no stream).
            println!("EVENT:INCOMING_REJECTED:{}", clean(&e.to_string()));
        }
        Ok(())
    }
}

/// The `sendto` command:
///   sendto <transfer_id> <endpoint_id> <relay_url|-> <file_path>
pub fn handle_sendto(endpoint: &Endpoint, rest: &str) {
    let parts: Vec<&str> = rest.splitn(4, ' ').collect();
    if parts.len() != 4 {
        println!("EVENT:ERROR:usage: sendto <transfer_id> <endpoint_id> <relay_url|-> <file_path>");
        return;
    }
    let transfer_id = parts[0].to_string();
    let endpoint_id_str = parts[1];
    let relay_url_str = parts[2];
    let file_path = PathBuf::from(parts[3]);

    // Phase A2: the receiver uses the ID in a filename, so only
    // safe IDs are allowed (Java's UUIDs pass).
    if !valid_transfer_id(&transfer_id) {
        println!("EVENT:ERROR:bad transfer id (allowed: A-Z a-z 0-9 - _, max {MAX_ID_CHARS})");
        return;
    }
    if !file_path.is_file() {
        println!("EVENT:TRANSFER_FAILED:{transfer_id}:sender: file not found: {}", clean(&file_path.display().to_string()));
        return;
    }

    let parsed_id = endpoint_id_str.parse::<EndpointId>();
    // Phase A4 (bug 10): the relay URL is only a hint — discovery finds
    // the peer by its ID even with a wrong URL. "-" means "no hint".
    let parsed_relay = if relay_url_str == "-" {
        Ok(None)
    } else {
        relay_url_str.parse::<RelayUrl>().map(Some)
    };

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
                // receiver can't know about are reported as TRANSFER_FAILED
                // here — if the receiver failed, it already reported that.
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
}

/// Reports the connection's currently-SELECTED path. Called ONCE, by the
/// receiver, only on success — this is what tells the mesh it is DONE.
fn report_path_from_conn(conn: &Connection, transfer_id: &str) {
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

/// Mesh v2 S1: live path indicator. paths_stream() yields the CURRENT path
/// list first, then a new snapshot whenever the selected path changes.
/// Prints CONNECTION_PATH at the start and on every switch.
fn spawn_path_watcher(conn: &Connection, transfer_id: &str) {
    let conn = conn.clone();
    let transfer_id = transfer_id.to_string();
    tokio::spawn(async move {
        let mut snapshots = conn.paths_stream();
        let mut last: Option<&'static str> = None;
        while let Some(list) = snapshots.next().await {
            let selected = list.iter().find(|p| p.is_selected()).map(|p| {
                if p.is_ip() {
                    "direct"
                } else if p.is_relay() {
                    "relay"
                } else {
                    "custom"
                }
            });
            if let Some(path) = selected {
                if last != Some(path) {
                    println!("EVENT:CONNECTION_PATH:{transfer_id}:{path}");
                    last = Some(path);
                }
            }
        }
        // Stream ends when the connection closes.
    });
}

/// Phase A1: what the sender learned about a transfer that it managed to
/// start. Local failures and rejections are returned as Err instead.
enum SendOutcome {
    /// Receiver replied OK: file fully received and saved.
    Delivered,
    /// The receiver failed, or will report the failure itself, so the
    /// sender must NOT report it again.
    ReceiverFailed(String),
}

async fn send_file(endpoint: &Endpoint, target: EndpointAddr, file_path: &PathBuf, transfer_id: &str) -> Result<SendOutcome> {
    use tokio::io::AsyncReadExt;

    // Phase A1: bounded connect — an unreachable peer becomes a clear
    // failure instead of a transfer stuck "in progress" forever (bug 4).
    let conn = timeout(Duration::from_secs(CONNECT_TIMEOUT_SECS), endpoint.connect(target, ALPN))
        .await
        .map_err(|_| anyhow!("connect timed out after {CONNECT_TIMEOUT_SECS}s"))??;
    let (mut send, mut recv) = conn.open_bi().await?;

    spawn_path_watcher(&conn, transfer_id);

    let filename = safe_filename(
        file_path.file_name().and_then(|n| n.to_str()).unwrap_or("file"),
    );
    let total_size = tokio::fs::metadata(file_path).await?.len();

    //   META3|<transfer_id>|<size>|<name>\n   (name LAST, so '|' can't split it)
    send.write_all(format!("{HEADER_PREFIX}{transfer_id}|{total_size}|{filename}\n").as_bytes()).await?;

    // Phase A3: TEST ONLY. Flip one byte after hashing it.
    let corrupt_for_test = std::env::var("PEER_TEST_CORRUPT_BYTE").as_deref() == Ok("1");

    let mut file = tokio::fs::File::open(file_path).await?;
    let mut buf = vec![0u8; chunk_size()];
    let mut sent: u64 = 0;
    let mut hasher = blake3::Hasher::new();
    let mut progress = ProgressGate::new();

    // Phase A4: send exactly the announced size.
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
        let pct = percent(sent, total_size);
        if progress.should_print(pct) {
            println!("EVENT:PROGRESS:sending|{filename}|{pct}|{sent}|{total_size}|{transfer_id}");
        }
    }
    if total_size == 0 {
        println!("EVENT:PROGRESS:sending|{filename}|100|0|0|{transfer_id}");
    }

    // Phase A4: the file shrank while we were sending. Abort the stream;
    // the receiver sees the reset and reports the failure (once).
    if sent < total_size {
        let _ = send.reset(1u32.into());
        conn.close(1u32.into(), b"file changed");
        return Ok(SendOutcome::ReceiverFailed(format!(
            "file changed while sending (sent {sent} of {total_size} bytes)"
        )));
    }

    //   HASH|blake3|<64 hex chars>\n   (after the data: only known now)
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
/// and ONE timeout for the whole line.
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

// B0: the Router already finished the handshake and hands us the connection.
async fn handle_incoming(conn: Connection) -> Result<()> {
    let (mut send, mut recv) = conn.accept_bi().await?;

    // Phase A2: a bad header is rejected; the sender (who has a valid
    // transfer ID) reports it.
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
                Ok(()) => save_partial(&partial_path, &h.transfer_id, &h.name).await,
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

/// Phase A2/A3/A4: receive exactly `h.size` bytes, check the sender's
/// BLAKE3 hash, sync to disk, report timing. Every failure becomes an Err
/// reason. The bool is true when the connection is already gone.
async fn receive_body(
    conn: &Connection,
    recv: &mut RecvStream,
    out_file: &mut tokio::fs::File,
    h: &Header,
) -> (std::result::Result<(), String>, bool) {
    let total = h.size;
    let mut buf = vec![0u8; chunk_size()];
    let mut received: u64 = 0;
    let mut hasher = blake3::Hasher::new();
    let mut progress = ProgressGate::new();
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
                    let pct = percent(received, total);
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

    // Phase A2 (bug 6): data really on disk before the final name.
    if let Err(e) = out_file.flush().await {
        return (Err(format!("could not flush file: {e}")), false);
    }
    if let Err(e) = out_file.sync_all().await {
        return (Err(format!("could not sync file to disk: {e}")), false);
    }
    let synced = Instant::now();

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

#[cfg(test)]
mod tests {
    use crate::util::DEFAULT_CHUNK_KB;

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
    }
}
