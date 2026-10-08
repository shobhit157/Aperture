//! B2a: file transfer with iroh-blobs ("share, then fetch").
//!
//! Sender:   share <id> <file>  -> hash the file in place, keep it as an offer
//!           allow <id> <peer>  -> only that peer may fetch it
//! Receiver: fetch <id> <hash> <size> <sender> <name>
//!                              -> pull the missing chunks, each one verified,
//!                                 then save as received_<id8>_<name>
//!
//! The receiver pulls, the sender only serves. Data lives in a blob store in
//! the data folder, so chunks that already arrived survive a restart.

use anyhow::{anyhow, Result};
use iroh::{
    endpoint::{RemoteInfo, TransportAddrUsage},
    Endpoint, EndpointId, TransportAddr,
};
use iroh_blobs::{
    api::{
        blobs::{AddPathOptions, BlobStatus, ImportMode},
        downloader::{DownloadProgressItem, Downloader},
    },
    provider::events::{
        AbortReason, ConnectMode, EventMask, EventSender, ObserveMode, ProviderMessage, RequestMode,
        RequestUpdate, ThrottleMode,
    },
    store::{
        fs::{options::Options, FsStore},
        GcConfig,
    },
    BlobFormat, BlobsProtocol, Hash, HashAndFormat,
};
use std::collections::{HashMap, HashSet};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use tokio_stream::StreamExt;

use crate::util::{clean, percent, safe_filename, save_partial, ProgressGate};

/// Unused blob data (no tag) is deleted by the store's garbage collector.
const GC_INTERVAL_SECS: u64 = 60;
/// How often the receiver checks relay vs direct while fetching.
const PATH_POLL_MS: u64 = 500;

/// One file the sender is offering.
#[derive(Debug, Clone)]
struct Offer {
    hash: Hash,
    allowed: HashSet<EndpointId>,
}

/// What the provider (sender side) needs to decide who may fetch what.
#[derive(Debug, Default)]
struct Access {
    /// transfer_id -> offer
    offers: HashMap<String, Offer>,
    /// QUIC connection id -> the peer on it (filled on connect)
    conns: HashMap<u64, EndpointId>,
}

impl Access {
    /// Is this peer allowed for at least one offer? (checked on connect)
    fn knows(&self, peer: &EndpointId) -> bool {
        self.offers.values().any(|o| o.allowed.contains(peer))
    }

    /// Which offer lets this peer fetch this hash? (checked per request)
    fn offer_for(&self, peer: &EndpointId, hash: &Hash) -> Option<String> {
        self.offers
            .iter()
            .find(|(_, o)| &o.hash == hash && o.allowed.contains(peer))
            .map(|(id, _)| id.clone())
    }
}

/// A `fetch` command, already parsed and checked.
pub struct FetchRequest {
    pub transfer_id: String,
    pub hash: Hash,
    pub size: u64,
    pub sender: EndpointId,
    pub name: String,
}

#[derive(Clone)]
pub struct Blobs {
    store: FsStore,
    endpoint: Endpoint,
    downloader: Downloader,
    access: Arc<Mutex<Access>>,
    /// transfer ids being fetched right now (no double fetch of one id)
    fetching: Arc<Mutex<HashSet<String>>>,
}

fn tag_out(id: &str) -> String {
    format!("out-{id}")
}

fn tag_in(id: &str) -> String {
    format!("in-{id}")
}

impl Blobs {
    /// Opens (or creates) the blob store in `<data_dir>/blobs/` and returns
    /// the protocol handler for the Router.
    pub async fn open(data_dir: &Path, endpoint: &Endpoint) -> Result<(Self, BlobsProtocol, PathBuf)> {
        let root = data_dir.join("blobs");
        std::fs::create_dir_all(&root).map_err(|e| anyhow!("cannot create {}: {e}", root.display()))?;
        let mut options = Options::new(&root);
        options.gc = Some(GcConfig {
            interval: Duration::from_secs(GC_INTERVAL_SECS),
            add_protected: None,
        });
        let store = FsStore::load_with_opts(root.join("blobs.db"), options)
            .await
            .map_err(|e| anyhow!("cannot open blob store {}: {e}", root.display()))?;

        let access = Arc::new(Mutex::new(Access::default()));

        // Intercept = we decide for every connection and every request.
        // Push (writing into OUR store) and get_many stay disabled.
        let mask = EventMask {
            connected: ConnectMode::Intercept,
            get: RequestMode::InterceptLog,
            get_many: RequestMode::Disabled,
            push: RequestMode::Disabled,
            observe: ObserveMode::Intercept,
            throttle: ThrottleMode::None,
        };
        let (events, rx) = EventSender::channel(64, mask);
        tokio::spawn(provider_events(rx, access.clone(), endpoint.clone()));

        let protocol = BlobsProtocol::new(&store, Some(events));
        let downloader = store.downloader(endpoint);

        let blobs = Blobs {
            store,
            endpoint: endpoint.clone(),
            downloader,
            access,
            fetching: Arc::new(Mutex::new(HashSet::new())),
        };
        Ok((blobs, protocol, root))
    }

    // ---------------------------------------------------------------- sender

    /// `share <id> <file>`: hash the file in place (no copy) and keep it as
    /// an offer. Prints SHARED when ready.
    pub fn share(&self, transfer_id: String, path: PathBuf) {
        let this = self.clone();
        tokio::spawn(async move {
            match this.do_share(&transfer_id, &path).await {
                Ok((hash, size, ms)) => {
                    println!("EVENT:SHARED:{transfer_id}:{hash}:{size}");
                    println!("EVENT:SHARE_TIMING:{transfer_id}:hash_ms={ms}");
                }
                Err(e) => println!("EVENT:TRANSFER_FAILED:{transfer_id}:sender: {}", clean(&e.to_string())),
            }
        });
    }

    async fn do_share(&self, transfer_id: &str, path: &Path) -> Result<(Hash, u64, u128)> {
        if self.access.lock().unwrap().offers.contains_key(transfer_id) {
            return Err(anyhow!("already shared"));
        }
        let abs = std::fs::canonicalize(path)
            .map_err(|_| anyhow!("file not found: {}", path.display()))?;
        let meta = std::fs::metadata(&abs)?;
        if !meta.is_file() {
            return Err(anyhow!("not a file: {}", abs.display()));
        }

        // TryReference: hash the file where it is (~1 s for 400 MB in the
        // B1 spike) instead of copying it into the store. If the file
        // changes later, verification fails, so a changed file can never
        // be delivered as correct.
        let started = Instant::now();
        let haf = self
            .store
            .blobs()
            .add_path_with_opts(AddPathOptions {
                path: abs,
                format: BlobFormat::Raw,
                mode: ImportMode::TryReference,
            })
            .with_named_tag(tag_out(transfer_id))
            .await
            .map_err(|e| anyhow!("could not hash file: {e}"))?;
        let ms = started.elapsed().as_millis();

        self.access.lock().unwrap().offers.insert(
            transfer_id.to_string(),
            Offer { hash: haf.hash, allowed: HashSet::new() },
        );
        Ok((haf.hash, meta.len(), ms))
    }

    /// `allow <id> <peer>`: from now on this peer may fetch the offer.
    pub fn allow(&self, transfer_id: &str, peer: EndpointId) -> Result<()> {
        let mut access = self.access.lock().unwrap();
        let offer = access
            .offers
            .get_mut(transfer_id)
            .ok_or_else(|| anyhow!("no offer {transfer_id} (share first)"))?;
        offer.allowed.insert(peer);
        Ok(())
    }

    /// `unshare <id>`: stop serving. The store deletes its own copy of the
    /// hash data later (garbage collection); the original file is never
    /// touched.
    pub fn unshare(&self, transfer_id: String) {
        let this = self.clone();
        tokio::spawn(async move {
            let removed = this.access.lock().unwrap().offers.remove(&transfer_id);
            if removed.is_none() {
                println!("EVENT:ERROR:unshare: no offer {transfer_id}");
                return;
            }
            if let Err(e) = this.store.tags().delete(tag_out(&transfer_id)).await {
                println!("EVENT:ERROR:unshare {transfer_id}: {}", clean(&e.to_string()));
                return;
            }
            println!("EVENT:UNSHARED:{transfer_id}");
        });
    }

    // -------------------------------------------------------------- receiver

    /// `fetch …`: pull the blob from the sender, then save it as a file.
    /// Ends with FILE_RECEIVED (+ TRANSFER_PATH) or TRANSFER_FAILED, the
    /// same result events as META3.
    pub fn fetch(&self, req: FetchRequest) {
        {
            let mut fetching = self.fetching.lock().unwrap();
            if !fetching.insert(req.transfer_id.clone()) {
                println!("EVENT:ERROR:fetch {}: already fetching", req.transfer_id);
                return;
            }
        }
        let this = self.clone();
        tokio::spawn(async move {
            let result = this.do_fetch(&req).await;
            this.fetching.lock().unwrap().remove(&req.transfer_id);
            if let Err(reason) = result {
                // B2a: no retries yet (B2b). Drop the tag so the store can
                // clean up the partial data.
                let _ = this.store.tags().delete(tag_in(&req.transfer_id)).await;
                println!("EVENT:TRANSFER_FAILED:{}:{}", req.transfer_id, clean(&reason));
            }
        });
    }

    async fn do_fetch(&self, r: &FetchRequest) -> std::result::Result<(), String> {
        let id = r.transfer_id.as_str();
        let name = safe_filename(&r.name);

        // A tag protects the data from garbage collection while we fetch.
        self.store
            .tags()
            .set(tag_in(id), HashAndFormat::raw(r.hash))
            .await
            .map_err(|e| format!("store error: {e}"))?;

        // Chunks already in the store (from an earlier, stopped fetch).
        let already = self
            .store
            .remote()
            .local(r.hash)
            .await
            .map(|info| info.local_bytes())
            .unwrap_or(0);
        if already > 0 {
            println!("EVENT:RESUMED:{id}:{already}");
        }

        let path = PathWatch::start(self.endpoint.clone(), r.sender, id.to_string());
        let started = Instant::now();
        let outcome = self.download(r, &name).await;
        let data_done = Instant::now();
        let final_path = path.stop().await;
        outcome?;

        // Complete and the right size? (every chunk was already verified
        // against the hash while it arrived)
        match self.store.blobs().status(r.hash).await {
            Ok(BlobStatus::Complete { size }) if size == r.size => {}
            Ok(BlobStatus::Complete { size }) => {
                return Err(format!("size mismatch (expected {}, got {size})", r.size));
            }
            Ok(_) => return Err("incomplete".into()),
            Err(e) => return Err(format!("store error: {e}")),
        }
        println!("EVENT:FILE_HASH:{id}:blake3:{}", r.hash);

        // Copy out of the store into a per-transfer partial file, then give
        // it its final name (same naming as META3).
        let partial = format!("received_{id}.partial");
        if tokio::fs::try_exists(&partial).await.unwrap_or(false) {
            return Err(format!("{partial} already exists"));
        }
        let target = std::env::current_dir()
            .map_err(|e| format!("no working folder: {e}"))?
            .join(&partial);
        if let Err(e) = self.store.blobs().export(r.hash, &target).await {
            let _ = tokio::fs::remove_file(&target).await;
            return Err(format!("could not save file: {e}"));
        }
        let exported = Instant::now();
        let saved = match save_partial(&partial, id, &name).await {
            Ok(p) => p,
            Err(e) => {
                let _ = tokio::fs::remove_file(&partial).await;
                return Err(e);
            }
        };

        // Decision B2-1: the store copy is not kept after export. Dropping
        // the tag lets garbage collection delete it.
        let _ = self.store.tags().delete(tag_in(id)).await;

        println!("EVENT:FILE_RECEIVED:{id}:{saved}|{}", r.size);
        println!("EVENT:TRANSFER_PATH:{id}:{}", final_path.unwrap_or("none"));
        let data_ms = (data_done - started).as_millis();
        let fetched = r.size.saturating_sub(already);
        let mbps = if data_ms > 0 { fetched as f64 / 1_048_576.0 / (data_ms as f64 / 1000.0) } else { 0.0 };
        println!(
            "EVENT:TRANSFER_TIMING:{id}:data_ms={data_ms} export_ms={} total_ms={} data_mb_s={mbps:.1} resumed_bytes={already} protocol=blobs",
            (exported - data_done).as_millis(),
            (exported - started).as_millis(),
        );
        Ok(())
    }

    /// Runs the download and prints progress. Err = why it failed.
    async fn download(&self, r: &FetchRequest, name: &str) -> std::result::Result<(), String> {
        let id = r.transfer_id.as_str();
        let mut stream = self
            .downloader
            .download(r.hash, vec![r.sender])
            .stream()
            .await
            .map_err(|e| format!("download could not start: {e}"))?;

        let mut gate = ProgressGate::new();
        let mut last_error: Option<String> = None;
        let mut complete = false;
        while let Some(item) = stream.next().await {
            match item {
                // Cumulative: includes bytes that were already on disk.
                DownloadProgressItem::Progress(bytes) => {
                    let pct = percent(bytes, r.size);
                    if gate.should_print(pct) {
                        println!("EVENT:PROGRESS:receiving|{name}|{pct}|{bytes}|{}|{id}", r.size);
                    }
                }
                DownloadProgressItem::PartComplete { .. } => complete = true,
                DownloadProgressItem::ProviderFailed { .. } => {
                    last_error = Some("sender unreachable or refused the request".into());
                }
                DownloadProgressItem::Error(e) => last_error = Some(e.to_string()),
                DownloadProgressItem::DownloadError => {
                    last_error.get_or_insert_with(|| "download failed".into());
                }
                DownloadProgressItem::TryProvider { .. } => {}
            }
        }
        if complete {
            if r.size == 0 || gate.should_print(100) {
                println!("EVENT:PROGRESS:receiving|{name}|100|{}|{}|{id}", r.size, r.size);
            }
            Ok(())
        } else {
            Err(last_error.unwrap_or_else(|| "download ended without data".into()))
        }
    }
}

// ------------------------------------------------------------ path watching

/// "direct" if an IP path is in active use, else "relay" if a relay is.
fn path_of(info: &RemoteInfo) -> Option<&'static str> {
    let active: Vec<&TransportAddr> = info
        .addrs()
        .filter(|a| matches!(a.usage(), TransportAddrUsage::Active))
        .map(|a| a.addr())
        .collect();
    if active.iter().any(|a| matches!(a, TransportAddr::Ip(_))) {
        Some("direct")
    } else if active.iter().any(|a| matches!(a, TransportAddr::Relay(_))) {
        Some("relay")
    } else {
        None
    }
}

async fn current_path(endpoint: &Endpoint, peer: EndpointId) -> Option<&'static str> {
    endpoint.remote_info(peer).await.as_ref().and_then(path_of)
}

/// Polls relay vs direct during a fetch (B1 spike: visible within ~1 s)
/// and prints CONNECTION_PATH on every change, like META3 does.
struct PathWatch {
    task: tokio::task::JoinHandle<()>,
    last: Arc<Mutex<Option<&'static str>>>,
    endpoint: Endpoint,
    peer: EndpointId,
}

impl PathWatch {
    fn start(endpoint: Endpoint, peer: EndpointId, transfer_id: String) -> Self {
        let last = Arc::new(Mutex::new(None));
        let seen = last.clone();
        let ep = endpoint.clone();
        let task = tokio::spawn(async move {
            loop {
                if let Some(path) = current_path(&ep, peer).await {
                    let changed = {
                        let mut seen = seen.lock().unwrap();
                        let changed = *seen != Some(path);
                        *seen = Some(path);
                        changed
                    };
                    if changed {
                        println!("EVENT:CONNECTION_PATH:{transfer_id}:{path}");
                    }
                }
                tokio::time::sleep(Duration::from_millis(PATH_POLL_MS)).await;
            }
        });
        PathWatch { task, last, endpoint, peer }
    }

    /// Stops polling and returns the path used at the end.
    async fn stop(self) -> Option<&'static str> {
        self.task.abort();
        let now = current_path(&self.endpoint, self.peer).await;
        now.or(*self.last.lock().unwrap())
    }
}

// ----------------------------------------------------- sender-side access

/// Every incoming blobs connection and request comes through here.
/// Allowed: the peer is on an offer's allow-list AND asks for that offer's
/// hash. Everyone else is rejected before a single byte is sent.
async fn provider_events(
    mut rx: tokio::sync::mpsc::Receiver<ProviderMessage>,
    access: Arc<Mutex<Access>>,
    endpoint: Endpoint,
) {
    while let Some(msg) = rx.recv().await {
        match msg {
            ProviderMessage::ClientConnected(msg) => {
                let conn = msg.inner.connection_id;
                let ok = match msg.inner.endpoint_id {
                    Some(peer) => {
                        let mut a = access.lock().unwrap();
                        let ok = a.knows(&peer);
                        if ok {
                            a.conns.insert(conn, peer);
                        }
                        ok
                    }
                    None => false,
                };
                if !ok {
                    let who = msg.inner.endpoint_id.map(|p| p.to_string()).unwrap_or_else(|| "unknown".into());
                    println!("EVENT:PEER_REJECTED:{who}:-");
                }
                let answer = if ok { Ok(()) } else { Err(AbortReason::Permission) };
                msg.tx.send(answer).await.ok();
            }
            ProviderMessage::ConnectionClosed(msg) => {
                access.lock().unwrap().conns.remove(&msg.inner.connection_id);
            }
            ProviderMessage::GetRequestReceived(msg) => {
                let hash = msg.inner.request.hash;
                let found = {
                    let a = access.lock().unwrap();
                    a.conns
                        .get(&msg.inner.connection_id)
                        .copied()
                        .and_then(|peer| a.offer_for(&peer, &hash).map(|id| (peer, id)))
                };
                match found {
                    Some((peer, id)) => {
                        msg.tx.send(Ok(())).await.ok();
                        // Watch this request; Completed = the receiver got
                        // everything it asked for.
                        let mut updates = msg.rx;
                        let ep = endpoint.clone();
                        tokio::spawn(async move {
                            while let Ok(Some(update)) = updates.recv().await {
                                if let RequestUpdate::Completed(_) = update {
                                    let path = current_path(&ep, peer).await.unwrap_or("none");
                                    println!("EVENT:SERVED:{id}:{peer}:{path}");
                                }
                            }
                        });
                    }
                    None => {
                        let who = access
                            .lock()
                            .unwrap()
                            .conns
                            .get(&msg.inner.connection_id)
                            .map(|p| p.to_string())
                            .unwrap_or_else(|| "unknown".into());
                        println!("EVENT:PEER_REJECTED:{who}:{hash}");
                        msg.tx.send(Err(AbortReason::Permission)).await.ok();
                    }
                }
            }
            ProviderMessage::ObserveRequestReceived(msg) => {
                // Observe = "which chunks do you have?". Same rule as get.
                let hash = msg.inner.request.hash;
                let ok = {
                    let a = access.lock().unwrap();
                    a.conns
                        .get(&msg.inner.connection_id)
                        .map(|peer| a.offer_for(peer, &hash).is_some())
                        .unwrap_or(false)
                };
                let answer = if ok { Ok(()) } else { Err(AbortReason::Permission) };
                msg.tx.send(answer).await.ok();
            }
            // Other request kinds are disabled in the event mask.
            _ => {}
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn peer(n: u8) -> EndpointId {
        iroh::SecretKey::from_bytes(&[n; 32]).public()
    }

    #[test]
    fn allow_list_matches_peer_and_hash() {
        let h1 = Hash::new(b"file one");
        let h2 = Hash::new(b"file two");
        let mut a = Access::default();
        a.offers.insert("t1".into(), Offer { hash: h1, allowed: HashSet::from([peer(1)]) });

        // allowed peer, right hash
        assert!(a.knows(&peer(1)));
        assert_eq!(a.offer_for(&peer(1), &h1).as_deref(), Some("t1"));
        // allowed peer, other hash -> no
        assert_eq!(a.offer_for(&peer(1), &h2), None);
        // stranger -> no
        assert!(!a.knows(&peer(2)));
        assert_eq!(a.offer_for(&peer(2), &h1), None);
    }

    #[test]
    fn tags_are_per_transfer() {
        assert_eq!(tag_out("t1"), "out-t1");
        assert_eq!(tag_in("t1"), "in-t1");
    }
}
