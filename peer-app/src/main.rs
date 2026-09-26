use anyhow::{Context, Result};
use iroh::{
    endpoint::presets, Endpoint, EndpointAddr, EndpointId, RelayUrl, TransportAddr,
};
use std::path::PathBuf;
use tokio::io::{AsyncBufReadExt, BufReader};

const ALPN: &[u8] = b"p2papp/file/0";

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

    let recv_endpoint = endpoint.clone();
    let recv_username = username.clone();
    tokio::spawn(async move {
        loop {
            let Some(incoming) = recv_endpoint.accept().await else { break };
            let recv_username = recv_username.clone();
            tokio::spawn(async move {
                if let Err(e) = handle_incoming(incoming, &recv_username).await {
                    println!("EVENT:ERROR:{e}");
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
            // format: sendto <transfer_id> <endpoint_id> <relay_url> <file_path>
            let parts: Vec<&str> = rest.splitn(4, ' ').collect();
            if parts.len() != 4 {
                println!("EVENT:ERROR:usage: sendto <transfer_id> <endpoint_id> <relay_url> <file_path>");
                continue;
            }
            let transfer_id = parts[0].to_string();
            let endpoint_id_str = parts[1];
            let relay_url_str = parts[2];
            let file_path = PathBuf::from(parts[3]);

            if !file_path.exists() {
                println!("EVENT:ERROR:file not found: {}", file_path.display());
                continue;
            }

            let parsed_id = endpoint_id_str.parse::<EndpointId>();
            let parsed_relay = relay_url_str.parse::<RelayUrl>();

            match (parsed_id, parsed_relay) {
                (Ok(target_id), Ok(relay_url)) => {
                    let addr = EndpointAddr::from_parts(
                        target_id,
                        std::iter::once(TransportAddr::Relay(relay_url)),
                    );
                    let endpoint = endpoint.clone();
                    tokio::spawn(async move {
                        if let Err(e) = send_file(&endpoint, addr, &file_path, &transfer_id).await {
                            println!("EVENT:ERROR:{transfer_id}:{e}");
                        } else {
                            println!("EVENT:FILE_SENT:{transfer_id}:{}", file_path.display());
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

async fn send_file(endpoint: &Endpoint, target: EndpointAddr, file_path: &PathBuf, transfer_id: &str) -> Result<()> {
    use tokio::io::AsyncReadExt;

    let conn = endpoint.connect(target, ALPN).await?;
    let (mut send, mut recv) = conn.open_bi().await?;

    let filename = file_path
        .file_name()
        .and_then(|n| n.to_str())
        .unwrap_or("file")
        .to_string();
    let total_size = tokio::fs::metadata(file_path).await?.len();

    // META now carries the transfer ID too, so the receiver can tag its own report with it.
    let meta = format!("{}|{}|{}", filename, total_size, transfer_id);
    send.write_all(format!("META|{meta}\n").as_bytes()).await?;

    const CHUNK_SIZE: usize = 64 * 1024;
    let mut file = tokio::fs::File::open(file_path).await?;
    let mut buf = vec![0u8; CHUNK_SIZE];
    let mut sent: u64 = 0;

    loop {
        let n = file.read(&mut buf).await?;
        if n == 0 {
            break;
        }
        send.write_all(&buf[..n]).await?;
        sent += n as u64;
        let pct = if total_size == 0 { 100 } else { (sent * 100 / total_size).min(100) };
        // Fix 2+3 (unified): transfer_id appended as a 6th field so
        // Client.java can forward this progress update to the server,
        // tagged with the right transfer — needed so the mesh can reset
        // its "last-seen" clock for THIS transfer specifically, not just
        // render a local progress bar.
        println!("EVENT:PROGRESS:sending|{filename}|{pct}|{sent}|{total_size}|{transfer_id}");
    }

    send.finish()?;
    let _ = recv.read_to_end(1024).await;

    report_path_from_conn(&conn, transfer_id);

    conn.close(0u32.into(), b"done");
    Ok(())
}

async fn handle_incoming(incoming: iroh::endpoint::Incoming, _my_username: &str) -> Result<()> {
    use tokio::io::AsyncReadExt;
    use tokio::time::Duration;

    const STALL_TIMEOUT_SECS: u64 = 40;

    let conn = incoming.await?;
    let (mut send, mut recv) = conn.accept_bi().await?;

    let mut header_bytes = Vec::new();
    let mut byte = [0u8; 1];
    loop {
        let read_result = tokio::time::timeout(
            Duration::from_secs(STALL_TIMEOUT_SECS),
            recv.read_exact(&mut byte),
        ).await;

        match read_result {
            Ok(Ok(())) => {
                if byte[0] == b'\n' {
                    break;
                }
                header_bytes.push(byte[0]);
            }
            Ok(Err(e)) => return Err(e.into()),
            Err(_) => {
                println!("EVENT:TRANSFER_FAILED:unknown:stalled while reading header");
                return Ok(());
            }
        }
    }

    let header = std::str::from_utf8(&header_bytes)?
        .strip_prefix("META|")
        .context("missing META prefix")?;
    let mut header_parts = header.splitn(3, '|');
    let filename = header_parts.next().unwrap_or("received_file").to_string();
    let total_size: u64 = header_parts.next().unwrap_or("0").parse().unwrap_or(0);
    let transfer_id = header_parts.next().unwrap_or("unknown").to_string();

    let final_path = format!("received_{filename}");
    let partial_path = format!("{final_path}.partial");
    let mut out_file = tokio::fs::File::create(&partial_path).await?;

    const CHUNK_SIZE: usize = 64 * 1024;
    let mut buf = vec![0u8; CHUNK_SIZE];
    let mut received: u64 = 0;
    let mut stalled = false;
    let mut connection_dead = false;

    if total_size > 0 {
        loop {
            tokio::select! {
                read_result = recv.read(&mut buf) => {
                    match read_result {
                        Ok(Some(n)) if n > 0 => {
                            tokio::io::AsyncWriteExt::write_all(&mut out_file, &buf[..n]).await?;
                            received += n as u64;
                            let pct = (received * 100 / total_size).min(100);
                            // Fix 2+3 (unified): same 6th field addition as
                            // send_file's progress line above.
                            println!("EVENT:PROGRESS:receiving|{filename}|{pct}|{received}|{total_size}|{transfer_id}");
                            if received >= total_size {
                                break;
                            }
                        }
                        Ok(Some(_)) => {}
                        Ok(None) => break,
                        Err(e) => {
                            let _ = tokio::fs::remove_file(&partial_path).await;
                            println!("EVENT:TRANSFER_FAILED:{transfer_id}:read error: {e}");
                            return Err(e.into());
                        }
                    }
                }
                _ = conn.closed() => {
                    stalled = true;
                    connection_dead = true;
                    break;
                }
                _ = tokio::time::sleep(Duration::from_secs(STALL_TIMEOUT_SECS)) => {
                    stalled = true;
                    break;
                }
            }
        }
    }

    if received == total_size && !stalled {
        tokio::fs::rename(&partial_path, &final_path).await?;
        println!("EVENT:FILE_RECEIVED:{transfer_id}:{final_path}|{received}");
    } else {
        let _ = tokio::fs::remove_file(&partial_path).await;
        let reason = if stalled { "stalled (no activity)" } else { "incomplete" };
        println!("EVENT:TRANSFER_FAILED:{transfer_id}:{reason} ({received}/{total_size} bytes)");
    }

    report_path_from_conn(&conn, &transfer_id);

    if !connection_dead {
        let _ = send.write_all(b"ACK").await;
        let _ = send.finish();
        conn.closed().await;
    }

    Ok(())
}
