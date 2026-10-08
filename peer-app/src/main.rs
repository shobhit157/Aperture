//! peer-app: Aperture's data plane. Java talks to it with lines on stdin
//! (commands) and reads lines on stdout (EVENT:…).
//!
//! One Iroh endpoint, one identity (B0), two protocols on one Router:
//!   - META3 (p2papp/file/0): the old push protocol, until B5
//!   - iroh-blobs: "share, then fetch" (B2)

mod blobs;
mod identity;
mod meta3;
mod util;

use anyhow::Result;
use iroh::{endpoint::presets, protocol::Router, Endpoint, EndpointId};
use iroh_blobs::Hash;
use std::path::PathBuf;
use tokio::io::{AsyncBufReadExt, BufReader};

use blobs::{Blobs, FetchRequest};
use util::{chunk_size, clean, valid_transfer_id, MAX_ID_CHARS};

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt::init();

    let username = std::env::args().nth(1).unwrap_or_else(|| "peer".to_string());

    // B0: same key every start -> same endpoint ID. A bad key file or seed
    // stops peer-app with a clear error instead of quietly using a new ID.
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

    // B2: the blob store lives in <data folder>/blobs/.
    let (blobs, blobs_protocol, store_dir) = match Blobs::open(&data_dir, &endpoint).await {
        Ok(b) => b,
        Err(e) => fail(&format!("blob store: {e}")),
    };
    println!("EVENT:BLOB_STORE:{}", store_dir.display());

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

    println!("EVENT:READY_FOR_COMMANDS:");

    let stdin = BufReader::new(tokio::io::stdin());
    let mut lines = stdin.lines();

    while let Ok(Some(line)) = lines.next_line().await {
        let line = line.trim();
        if line == "quit" {
            break;
        } else if let Some(rest) = line.strip_prefix("sendto ") {
            meta3::handle_sendto(&endpoint, rest);
        } else if let Some(rest) = line.strip_prefix("share ") {
            cmd_share(&blobs, rest);
        } else if let Some(rest) = line.strip_prefix("allow ") {
            cmd_allow(&blobs, rest);
        } else if let Some(rest) = line.strip_prefix("unshare ") {
            cmd_unshare(&blobs, rest);
        } else if let Some(rest) = line.strip_prefix("fetch ") {
            cmd_fetch(&blobs, rest);
        } else if !line.is_empty() {
            println!("EVENT:ERROR:unknown command");
        }
    }

    // Stop accepting, let handlers finish (the blobs handler saves and
    // closes the store), close the endpoint.
    if let Err(e) = router.shutdown().await {
        println!("EVENT:ERROR:shutdown: {}", clean(&e.to_string()));
    }
    Ok(())
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

/// B2: a blob hash is exactly 64 hex characters. Checked here because
/// iroh-blobs' parser panics (crashes peer-app) on a wrong length instead
/// of returning an error. A bad command must never kill peer-app.
fn valid_hash_text(hash: &str) -> bool {
    hash.len() == 64 && hash.chars().all(|c| c.is_ascii_hexdigit())
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

#[cfg(test)]
mod tests {
    use super::valid_hash_text;

    #[test]
    fn hash_text_is_checked() {
        let good = "a38cf1b054a8ac627eaed9136dd4b6e3ec183cbdcf156eabb5c49a61dff2593d";
        assert!(valid_hash_text(good));
        // the copy-paste mistake that crashed peer-app
        assert!(!valid_hash_text(&format!(":{good}")));
        assert!(!valid_hash_text(&good[..63]));
        assert!(!valid_hash_text(&good.replace('a', "z")));
        assert!(!valid_hash_text(""));
    }
}
