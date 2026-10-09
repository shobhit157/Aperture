//! peer-app: Aperture's data plane. Java talks to it with lines on stdin
//! (commands) and reads lines on stdout (EVENT:…).
//!
//! One Iroh endpoint, one identity (B0), two protocols on one Router:
//!   - META3 (p2papp/file/0): the old push protocol, until B5
//!   - iroh-blobs: "share, then fetch" (B2)
//! B2b-1: offers and fetches are saved, so transfers survive restarts.

mod blobs;
mod identity;
mod meta3;
mod state;
mod util;

use anyhow::Result;
use iroh::{endpoint::presets, protocol::Router, Endpoint, EndpointId};
use iroh_blobs::Hash;
use std::io::Write;
use std::path::PathBuf;
use std::sync::Arc;
use tokio::io::{AsyncBufReadExt, BufReader};

use blobs::{Blobs, FetchRequest};
use util::{chunk_size, clean, valid_hash_text, valid_transfer_id, MAX_ID_CHARS};

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt::init();

    let username = std::env::args().nth(1).unwrap_or_else(|| "peer".to_string());

    // B0: same key every start -> same endpoint ID.
    let (secret_key, identity) = match identity::load_identity(&username) {
        Ok(found) => found,
        Err(e) => fail(&format!("identity: {e}")),
    };
    println!("EVENT:IDENTITY:{identity}");
    let data_dir = match identity::data_dir(&username) {
        Ok(d) => d,
        Err(e) => fail(&format!("data folder: {e}")),
    };

    let endpoint = Endpoint::builder(presets::N0)
        .secret_key(secret_key)
        .bind()
        .await?;

    // B2b-1: the memory files (offers.json, fetches.json). Nothing writes
    // them until the blob store below is open, and the store's lock stops a
    // second copy of the same user, so only one process ever writes them.
    let (state, warnings) = state::State::load(&data_dir);
    let state = Arc::new(state);

    // B2: the blob store lives in <data folder>/blobs/.
    let (blobs, blobs_protocol, store_dir) = match Blobs::open(&data_dir, &endpoint, state.clone()).await {
        Ok(b) => b,
        Err(e) => fail(&format!("blob store: {e}")),
    };
    println!("EVENT:BLOB_STORE:{}", store_dir.display());
    for w in warnings {
        println!("EVENT:ERROR:state: {}", clean(&w));
    }

    // B2b-1: offers come back before anyone can connect.
    let offers_restored = blobs.restore_offers().await;

    // One endpoint, two protocols. The Router sets the endpoint's ALPNs.
    let router = Router::builder(endpoint.clone())
        .accept(meta3::ALPN, meta3::Meta3Handler)
        .accept(iroh_blobs::ALPN, blobs_protocol)
        .spawn();
    endpoint.online().await;
    println!("EVENT:ENDPOINT_READY:{}", endpoint.id());

    let my_addr = endpoint.addr();
    if let Some(relay_url) = my_addr.relay_urls().next() {
        println!("EVENT:RELAY_READY:{relay_url}");
    } else {
        println!("EVENT:ERROR:no relay url available yet");
    }
    // Phase A4: direct (IP) addresses this endpoint knows about. Java ignores it.
    let ips: Vec<String> = my_addr.ip_addrs().map(|a| a.to_string()).collect();
    println!("EVENT:ENDPOINT_ADDRS:{}", if ips.is_empty() { "none".to_string() } else { ips.join(",") });
    println!("EVENT:CHUNK_SIZE:{}", chunk_size());

    // B2b-1: unfinished fetches continue once we are online.
    let fetches_restored = blobs.restore_fetches();
    println!("EVENT:RESTORED:{offers_restored}:{fetches_restored}");

    println!("EVENT:READY_FOR_COMMANDS:");

    let stdin = BufReader::new(tokio::io::stdin());
    let mut lines = stdin.lines();
    let mut stop_signal = Box::pin(shutdown_signal());

    loop {
        tokio::select! {
            line = lines.next_line() => match line {
                Ok(Some(line)) => {
                    if !handle_line(&endpoint, &blobs, line.trim()) {
                        break; // quit
                    }
                }
                _ => break, // Java closed stdin
            },
            // B2b-1: Ctrl+C or SIGTERM (Kubernetes, Java's destroy()) = quit
            _ = &mut stop_signal => {
                println!("EVENT:SHUTDOWN:signal");
                break;
            }
        }
    }

    // Stop accepting, let handlers finish (the blobs handler saves and
    // closes the store), close the endpoint.
    blobs.begin_shutdown();
    if let Err(e) = router.shutdown().await {
        println!("EVENT:ERROR:shutdown: {}", clean(&e.to_string()));
    }

    // B2b-1 fix: exit right here. tokio reads stdin on a helper thread that
    // keeps waiting for the next command line, and a normal return would wait
    // for that thread forever when we were stopped by a signal (Ctrl+C,
    // Kubernetes) instead of `quit`. Everything is saved and closed by now.
    let _ = std::io::stdout().flush();
    std::process::exit(0);
}

/// One command line. Returns false for `quit`.
fn handle_line(endpoint: &Endpoint, blobs: &Blobs, line: &str) -> bool {
    if line == "quit" {
        return false;
    } else if let Some(rest) = line.strip_prefix("sendto ") {
        meta3::handle_sendto(endpoint, rest);
    } else if let Some(rest) = line.strip_prefix("share ") {
        cmd_share(blobs, rest);
    } else if let Some(rest) = line.strip_prefix("allow ") {
        cmd_allow(blobs, rest);
    } else if let Some(rest) = line.strip_prefix("unshare ") {
        cmd_unshare(blobs, rest);
    } else if let Some(rest) = line.strip_prefix("fetch ") {
        cmd_fetch(blobs, rest);
    } else if !line.is_empty() {
        println!("EVENT:ERROR:unknown command");
    }
    true
}

/// Resolves on Ctrl+C (SIGINT) or SIGTERM.
async fn shutdown_signal() {
    #[cfg(unix)]
    {
        use tokio::signal::unix::{signal, SignalKind};
        match signal(SignalKind::terminate()) {
            Ok(mut term) => {
                tokio::select! {
                    _ = tokio::signal::ctrl_c() => {}
                    _ = term.recv() => {}
                }
            }
            Err(_) => {
                let _ = tokio::signal::ctrl_c().await;
            }
        }
    }
    #[cfg(not(unix))]
    {
        let _ = tokio::signal::ctrl_c().await;
    }
}

/// Startup problems: one clear line for Java, then exit.
fn fail(reason: &str) -> ! {
    println!("EVENT:ERROR:{}", clean(reason));
    std::process::exit(1);
}

fn check_id(id: &str) -> bool {
    if valid_transfer_id(id) {
        true
    } else {
        println!("EVENT:ERROR:bad transfer id (allowed: A-Z a-z 0-9 - _, max {MAX_ID_CHARS})");
        false
    }
}

/// share <transfer_id> <file_path>
fn cmd_share(blobs: &Blobs, rest: &str) {
    let Some((id, path)) = rest.split_once(' ') else {
        println!("EVENT:ERROR:usage: share <transfer_id> <file_path>");
        return;
    };
    if check_id(id) {
        blobs.share(id.to_string(), PathBuf::from(path));
    }
}

/// allow <transfer_id> <endpoint_id>
fn cmd_allow(blobs: &Blobs, rest: &str) {
    let parts: Vec<&str> = rest.split_whitespace().collect();
    let [id, peer] = parts.as_slice() else {
        println!("EVENT:ERROR:usage: allow <transfer_id> <endpoint_id>");
        return;
    };
    if !check_id(id) {
        return;
    }
    match peer.parse::<EndpointId>() {
        Ok(peer) => match blobs.allow(id, peer) {
            Ok(()) => println!("EVENT:ALLOWED:{id}:{peer}"),
            Err(e) => println!("EVENT:ERROR:allow: {}", clean(&e.to_string())),
        },
        Err(e) => println!("EVENT:ERROR:bad endpoint id: {e}"),
    }
}

/// unshare <transfer_id>
fn cmd_unshare(blobs: &Blobs, rest: &str) {
    let id = rest.trim();
    if check_id(id) {
        blobs.unshare(id.to_string());
    }
}

/// fetch <transfer_id> <hash> <size> <sender_endpoint_id> <name>
/// (name is last, so it may contain spaces)
fn cmd_fetch(blobs: &Blobs, rest: &str) {
    let parts: Vec<&str> = rest.splitn(5, ' ').collect();
    let [id, hash, size, sender, name] = parts.as_slice() else {
        println!("EVENT:ERROR:usage: fetch <transfer_id> <hash> <size> <sender_endpoint_id> <name>");
        return;
    };
    if !check_id(id) {
        return;
    }
    // iroh-blobs' parser crashes on a wrong length, so check first.
    if !valid_hash_text(hash) {
        println!("EVENT:ERROR:bad hash (expected exactly 64 hex characters, got {})", hash.len());
        return;
    }
    let hash = match hash.parse::<Hash>() {
        Ok(h) => h,
        Err(e) => {
            println!("EVENT:ERROR:bad hash: {e}");
            return;
        }
    };
    let Ok(size) = size.parse::<u64>() else {
        println!("EVENT:ERROR:bad size");
        return;
    };
    let sender = match sender.parse::<EndpointId>() {
        Ok(s) => s,
        Err(e) => {
            println!("EVENT:ERROR:bad endpoint id: {e}");
            return;
        }
    };
    blobs.fetch(FetchRequest {
        transfer_id: id.to_string(),
        hash,
        size,
        sender,
        name: name.to_string(),
    });
}
