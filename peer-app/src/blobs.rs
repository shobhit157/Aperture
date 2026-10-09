//! B2: file transfer with iroh-blobs ("share, then fetch").
//!
//! Sender:   share <id> <file>  -> hash the file in place, keep it as an offer
//!           allow <id> <peer>  -> only that peer may fetch it
//! Receiver: fetch <id> <hash> <size> <sender> <name>
//!                              -> pull the missing chunks, each one verified,
//!                                 then save as received_<id8>_<name>
//!
//! B2b-1: offers and fetches are saved (state.rs), so both sides continue
//! after a restart. A failed fetch keeps its chunks.
//!
//! B2b-2: a failed fetch first tries again by itself 3 times (5 s, 10 s,
//! 20 s: short hiccups). If it still fails it WAITS (FETCH_WAITING):
//!   retry <id> / retry all  -> try again now (Java sends this when the
//!                              server says the sender is back, B3)
//!   cancel <id>             -> stop and delete the partial data
//! Every share and fetch lives PEER_TRANSFER_TTL_SECS (default 30 min) from
//! when it was created; then it expires and is cleaned up.

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
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{Duration, Instant};
use tokio::task::AbortHandle;
use tokio_stream::StreamExt;

use crate::state::{modified_ms, now_ms, FetchRecord, OfferRecord, State};
use crate::util::{clean, percent, safe_filename, save_partial, valid_hash_text, valid_transfer_id, ProgressGate};

/// Unused blob data (no tag) is deleted by the store's garbage collector.
const GC_INTERVAL_SECS: u64 = 60;
/// How often the receiver checks relay vs direct while fetching.
const PATH_POLL_MS: u64 = 500;
/// B2b-2: how long a share or fetch lives, unless PEER_TRANSFER_TTL_SECS says otherwise.
const DEFAULT_TTL_SECS: u64 = 30 * 60;
/// B2b-2: how often expired shares and fetches are cleaned up.
const EXPIRY_CHECK_SECS: u64 = 5;
/// B2b-2: after a failed try, wait this long and try again by itself
/// (short hiccups: just started, network blip). Then FETCH_WAITING.
const QUICK_RETRY_SECS: [u64; 3] = [5, 10, 20];

// ---------------------------------------------------------- expiry (B2b-2)

/// Transfer lifetime in seconds (read once from PEER_TRANSFER_TTL_SECS).
pub fn transfer_ttl_secs() -> u64 {
    static TTL: OnceLock<u64> = OnceLock::new();
    *TTL.get_or_init(|| {
        std::env::var("PEER_TRANSFER_TTL_SECS")
            .ok()
            .and_then(|v| v.trim().parse::<u64>().ok())
            .filter(|s| *s > 0)
            .unwrap_or(DEFAULT_TTL_SECS)
    })
}

fn ttl_ms() -> u64 {
    transfer_ttl_secs().saturating_mul(1000)
}

fn is_expired(created_ms: u64, now: u64, ttl: u64) -> bool {
    now >= created_ms.saturating_add(ttl)
}

fn secs_left(created_ms: u64, now: u64, ttl: u64) -> u64 {
    created_ms.saturating_add(ttl).saturating_sub(now) / 1000
}

fn expired_now(created_ms: u64) -> bool {
    is_expired(created_ms, now_ms(), ttl_ms())
}

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

impl FetchRequest {
    /// B2b-1: rebuild a fetch from fetches.json (checked, never trusted).
    pub fn from_record(id: &str, rec: &FetchRecord) -> std::result::Result<Self, String> {
        if !valid_transfer_id(id) {
            return Err("bad saved transfer id".into());
        }
        if !valid_hash_text(&rec.hash) {
            return Err("bad saved hash".into());
        }
        let hash = rec.hash.parse::<Hash>().map_err(|e| format!("bad saved hash: {e}"))?;
        let sender = rec.sender.parse::<EndpointId>().map_err(|e| format!("bad saved sender: {e}"))?;
        Ok(FetchRequest { transfer_id: id.to_string(), hash, size: rec.size, sender, name: rec.name.clone() })
    }
}

#[derive(Clone)]
pub struct Blobs {
    store: FsStore,
    endpoint: Endpoint,
    downloader: Downloader,
    access: Arc<Mutex<Access>>,
    /// Transfer ids being fetched right now (no double fetch of one id).
    /// B2b-2: with a handle, so `cancel` can stop the running task.
    fetching: Arc<Mutex<HashMap<String, Option<AbortHandle>>>>,
    /// B2b-1: offers.json / fetches.json
    state: Arc<State>,
    /// B2b-1: set when peer-app is shutting down, so interrupted fetches
    /// are reported as paused (they resume on restart), not failed.
    stopping: Arc<AtomicBool>,
}

fn tag_out(id: &str) -> String {
    format!("out-{id}")
}

fn tag_in(id: &str) -> String {
    format!("in-{id}")
}

fn report_state_error(e: anyhow::Error) {
    println!("EVENT:ERROR:state: {}", clean(&e.to_string()));
}

impl Blobs {
    /// Opens (or creates) the blob store in `<data_dir>/blobs/` and returns
    /// the protocol handler for the Router.
    pub async fn open(
        data_dir: &Path,
        endpoint: &Endpoint,
        state: Arc<State>,
    ) -> Result<(Self, BlobsProtocol, PathBuf)> {
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
            fetching: Arc::new(Mutex::new(HashMap::new())),
            state,
            stopping: Arc::new(AtomicBool::new(false)),
        };
        Ok((blobs, protocol, root))
    }

    /// Called just before shutdown.
    pub fn begin_shutdown(&self) {
        self.stopping.store(true, Ordering::SeqCst);
    }

    fn is_fetching(&self, id: &str) -> bool {
        self.fetching.lock().unwrap().contains_key(id)
    }

    // ------------------------------------------------------- restart (B2b-1)

    /// Sender side, at startup: bring saved offers back. An offer whose file
    /// changed or disappeared while peer-app was off is dropped. Expired
    /// offers are skipped (the expiry check removes them a moment later).
    pub async fn restore_offers(&self) -> usize {
        let mut restored = 0;
        for (id, rec) in self.state.offers() {
            if expired_now(rec.created_ms) {
                continue;
            }
            match self.check_offer(&id, &rec).await {
                Ok(hash) => {
                    let allowed: HashSet<EndpointId> =
                        rec.allowed.iter().filter_map(|p| p.parse::<EndpointId>().ok()).collect();
                    self.access.lock().unwrap().offers.insert(id.clone(), Offer { hash, allowed });
                    restored += 1;
                }
                Err(reason) => {
                    println!("EVENT:OFFER_DROPPED:{id}:{}", clean(&reason));
                    if let Err(e) = self.state.remove_offer(&id) {
                        report_state_error(e);
                    }
                    let _ = self.store.tags().delete(tag_out(&id)).await;
                }
            }
        }
        restored
    }

    async fn check_offer(&self, id: &str, rec: &OfferRecord) -> std::result::Result<Hash, String> {
        if !valid_transfer_id(id) {
            return Err("bad saved transfer id".into());
        }
        if !valid_hash_text(&rec.hash) {
            return Err("bad saved hash".into());
        }
        let hash = rec.hash.parse::<Hash>().map_err(|e| format!("bad saved hash: {e}"))?;
        let meta = std::fs::metadata(&rec.path).map_err(|_| "file is gone".to_string())?;
        if !meta.is_file() {
            return Err("not a file any more".into());
        }
        if meta.len() != rec.size || modified_ms(&meta) != rec.modified_ms {
            return Err("file changed while peer-app was off".into());
        }
        // Normally the store still has it. If not, hash the file again
        // (in place) and make sure it is still the same content.
        let complete = matches!(self.store.blobs().status(hash).await, Ok(BlobStatus::Complete { .. }));
        if !complete {
            let haf = self
                .store
                .blobs()
                .add_path_with_opts(AddPathOptions {
                    path: rec.path.clone(),
                    format: BlobFormat::Raw,
                    mode: ImportMode::TryReference,
                })
                .with_named_tag(tag_out(id))
                .await
                .map_err(|e| format!("could not hash file again: {e}"))?;
            if haf.hash != hash {
                return Err("file content changed".into());
            }
        }
        // Make sure the tag that protects it from cleanup exists.
        self.store
            .tags()
            .set(tag_out(id), HashAndFormat::raw(hash))
            .await
            .map_err(|e| format!("store error: {e}"))?;
        Ok(hash)
    }

    /// Receiver side, at startup: continue every saved, unfinished fetch.
    /// Expired ones are skipped (the expiry check removes them).
    pub fn restore_fetches(&self) -> usize {
        let mut restored = 0;
        for (id, rec) in self.state.fetches() {
            if expired_now(rec.created_ms) {
                continue;
            }
            match FetchRequest::from_record(&id, &rec) {
                Ok(req) => {
                    self.fetch(req);
                    restored += 1;
                }
                Err(reason) => {
                    println!("EVENT:ERROR:fetch {id}: saved fetch dropped: {}", clean(&reason));
                    if let Err(e) = self.state.remove_fetch(&id) {
                        report_state_error(e);
                    }
                }
            }
        }
        restored
    }

    // -------------------------------------------------------- expiry (B2b-2)

    /// Starts the background check that removes expired shares and fetches.
    pub fn start_expiry(&self) {
        let this = self.clone();
        tokio::spawn(async move {
            loop {
                tokio::time::sleep(Duration::from_secs(EXPIRY_CHECK_SECS)).await;
                if this.stopping.load(Ordering::SeqCst) {
                    break;
                }
                this.expire_old().await;
            }
        });
    }

    async fn expire_old(&self) {
        for (id, rec) in self.state.offers() {
            if expired_now(rec.created_ms) {
                self.expire_offer(&id).await;
            }
        }
        // A fetch that is running (or doing its quick tries) is left alone;
        // it stops trying once expired and is cleaned up then.
        for (id, rec) in self.state.fetches() {
            if expired_now(rec.created_ms) && !self.is_fetching(&id) {
                self.expire_fetch(&id).await;
            }
        }
    }

    async fn expire_offer(&self, id: &str) {
        self.access.lock().unwrap().offers.remove(id);
        if let Err(e) = self.state.remove_offer(id) {
            report_state_error(e);
        }
        let _ = self.store.tags().delete(tag_out(id)).await;
        println!("EVENT:OFFER_EXPIRED:{id}");
    }

    async fn expire_fetch(&self, id: &str) {
        if let Err(e) = self.state.remove_fetch(id) {
            report_state_error(e);
        }
        self.drop_fetch_data(id).await;
        println!("EVENT:FETCH_EXPIRED:{id}");
        println!(
            "EVENT:TRANSFER_FAILED:{id}:expired after {}s without finishing",
            transfer_ttl_secs()
        );
    }

    /// Removes the tag (so garbage collection deletes the chunks) and any
    /// leftover partial file.
    async fn drop_fetch_data(&self, id: &str) {
        let _ = self.store.tags().delete(tag_in(id)).await;
        let _ = tokio::fs::remove_file(format!("received_{id}.partial")).await;
    }

    // ---------------------------------------------------------------- sender

    /// `share <id> <file>`: hash the file in place (no copy) and keep it as
    /// an offer. Prints SHARED when ready.
    pub fn share(&self, transfer_id: String, path: PathBuf) {
        if self.access.lock().unwrap().offers.contains_key(&transfer_id) {
            // A mistake in the command, not a failed transfer.
            println!("EVENT:ERROR:share {transfer_id}: already shared");
            return;
        }
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
        let abs = std::fs::canonicalize(path)
            .map_err(|_| anyhow!("file not found: {}", path.display()))?;
        let meta = std::fs::metadata(&abs)?;
        if !meta.is_file() {
            return Err(anyhow!("not a file: {}", abs.display()));
        }

        // TryReference: hash the file where it is instead of copying it into
        // the store. If the file changes later, verification fails, so a
        // changed file can never be delivered as correct.
        let started = Instant::now();
        let haf = self
            .store
            .blobs()
            .add_path_with_opts(AddPathOptions {
                path: abs.clone(),
                format: BlobFormat::Raw,
                mode: ImportMode::TryReference,
            })
            .with_named_tag(tag_out(transfer_id))
            .await
            .map_err(|e| anyhow!("could not hash file: {e}"))?;
        let ms = started.elapsed().as_millis();
        {
            let mut access = self.access.lock().unwrap();
            if access.offers.contains_key(transfer_id) {
                return Err(anyhow!("already shared"));
            }
            access
                .offers
                .insert(transfer_id.to_string(), Offer { hash: haf.hash, allowed: HashSet::new() });
        }

        // B2b-1: remember it, so it survives a restart.
        let record = OfferRecord {
            hash: haf.hash.to_string(),
            path: abs,
            size: meta.len(),
            modified_ms: modified_ms(&meta),
            allowed: Vec::new(),
            created_ms: now_ms(),
        };
        if let Err(e) = self.state.put_offer(transfer_id, record) {
            report_state_error(e);
        }
        Ok((haf.hash, meta.len(), ms))
    }

    /// `allow <id> <peer>`: from now on this peer may fetch the offer.
    pub fn allow(&self, transfer_id: &str, peer: EndpointId) -> Result<()> {
        {
            let mut access = self.access.lock().unwrap();
            let offer = access
                .offers
                .get_mut(transfer_id)
                .ok_or_else(|| anyhow!("no offer {transfer_id} (share first)"))?;
            offer.allowed.insert(peer);
        }
        if let Err(e) = self.state.allow(transfer_id, &peer.to_string()) {
            report_state_error(e);
        }
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
            if let Err(e) = this.state.remove_offer(&transfer_id) {
                report_state_error(e);
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
    /// Ends with FILE_RECEIVED (+ TRANSFER_PATH). On failure: up to 3 quick
    /// tries by itself (FETCH_RETRYING), then FETCH_WAITING (chunks kept,
    /// `retry` continues it).
    pub fn fetch(&self, req: FetchRequest) {
        let id = req.transfer_id.clone();
        let saved = self.state.fetch(&id);
        if let Some(old) = &saved {
            if old.hash != req.hash.to_string() {
                println!("EVENT:ERROR:fetch {id}: this transfer id is already used for a different file");
                return;
            }
        }
        {
            let mut fetching = self.fetching.lock().unwrap();
            if fetching.contains_key(&id) {
                println!("EVENT:ERROR:fetch {id}: already fetching");
                return;
            }
            fetching.insert(id.clone(), None);
        }

        // B2b-1: remember it before starting, so a restart continues it.
        // A retry keeps the original created time, so the 30 min limit
        // counts from the first fetch, not from the last retry.
        let record = FetchRecord {
            hash: req.hash.to_string(),
            size: req.size,
            sender: req.sender.to_string(),
            name: req.name.clone(),
            created_ms: saved.map(|s| s.created_ms).unwrap_or_else(now_ms),
        };
        if let Err(e) = self.state.put_fetch(&id, record) {
            report_state_error(e);
        }

        let this = self.clone();
        let task_id = id.clone();
        let task = tokio::spawn(async move {
            let id = task_id;
            let mut result = this.do_fetch(&req).await;

            // B2b-2: quick tries for short hiccups. Each one resumes from
            // the chunks already received. Stops early if peer-app is
            // stopping, the fetch was cancelled, or its time is over.
            for (n, secs) in QUICK_RETRY_SECS.iter().enumerate() {
                let Err(reason) = &result else { break };
                if this.stopping.load(Ordering::SeqCst) {
                    break;
                }
                match this.state.fetch(&id) {
                    Some(rec) if !expired_now(rec.created_ms) => {}
                    _ => break, // cancelled or expired
                }
                println!(
                    "EVENT:FETCH_RETRYING:{id}:{}:{secs}:{}",
                    n + 1,
                    clean(reason)
                );
                tokio::time::sleep(Duration::from_secs(*secs)).await;
                if this.stopping.load(Ordering::SeqCst) {
                    break;
                }
                result = this.do_fetch(&req).await;
            }

            this.fetching.lock().unwrap().remove(&id);
            match result {
                Ok(()) => {
                    if let Err(e) = this.state.remove_fetch(&id) {
                        report_state_error(e);
                    }
                }
                Err(_) if this.stopping.load(Ordering::SeqCst) => {
                    // Interrupted by shutdown: kept, resumes on restart.
                    println!("EVENT:FETCH_PAUSED:{id}:peer-app is stopping, resumes on restart");
                }
                Err(reason) => this.wait_or_expire(&id, &reason).await,
            }
        });
        // Keep the handle so `cancel` can stop it (only if it is still running).
        if let Some(slot) = self.fetching.lock().unwrap().get_mut(&id) {
            *slot = Some(task.abort_handle());
        }
    }

    /// B2b-2: after the quick tries failed, wait for a `retry` instead of
    /// giving up, unless its time is already over.
    async fn wait_or_expire(&self, id: &str, reason: &str) {
        let Some(rec) = self.state.fetch(id) else {
            return; // cancelled meanwhile
        };
        let now = now_ms();
        if is_expired(rec.created_ms, now, ttl_ms()) {
            self.expire_fetch(id).await;
        } else {
            println!(
                "EVENT:FETCH_WAITING:{id}:{}:{} (partial data kept; 'retry {id}' continues it)",
                secs_left(rec.created_ms, now, ttl_ms()),
                clean(reason)
            );
        }
    }

    /// `retry <id>`: start a waiting fetch again; it resumes from the chunks
    /// it already has.
    pub fn retry(&self, id: &str) {
        if self.is_fetching(id) {
            println!("EVENT:ERROR:retry {id}: already fetching");
            return;
        }
        let Some(rec) = self.state.fetch(id) else {
            println!("EVENT:ERROR:retry {id}: no waiting fetch with this id");
            return;
        };
        if expired_now(rec.created_ms) {
            println!("EVENT:ERROR:retry {id}: expired");
            return;
        }
        match FetchRequest::from_record(id, &rec) {
            Ok(req) => {
                println!("EVENT:RETRY:{id}");
                self.fetch(req);
            }
            Err(reason) => println!("EVENT:ERROR:retry {id}: {}", clean(&reason)),
        }
    }

    /// `retry all`: start every waiting (not running, not expired) fetch.
    pub fn retry_all(&self) {
        let mut started = 0;
        for (id, rec) in self.state.fetches() {
            if self.is_fetching(&id) || expired_now(rec.created_ms) {
                continue;
            }
            if let Ok(req) = FetchRequest::from_record(&id, &rec) {
                println!("EVENT:RETRY:{id}");
                self.fetch(req);
                started += 1;
            }
        }
        println!("EVENT:RETRY_ALL:{started}");
    }

    /// `cancel <id>`: stop a running or waiting fetch and delete its data.
    pub fn cancel(&self, id: String) {
        let this = self.clone();
        tokio::spawn(async move {
            let running = this.fetching.lock().unwrap().remove(&id);
            let saved = this.state.fetch(&id).is_some();
            if running.is_none() && !saved {
                println!("EVENT:ERROR:cancel {id}: no fetch with this id");
                return;
            }
            if let Some(Some(handle)) = running {
                handle.abort();
            }
            if let Err(e) = this.state.remove_fetch(&id) {
                report_state_error(e);
            }
            this.drop_fetch_data(&id).await;
            println!("EVENT:FETCH_CANCELLED:{id}");
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

        // Already complete in the store? (e.g. stopped after downloading but
        // before saving the file) Then skip straight to saving.
        let complete = matches!(
            self.store.blobs().status(r.hash).await,
            Ok(BlobStatus::Complete { size }) if size == r.size
        );

        // Chunks already in the store (from an earlier, stopped fetch).
        let already = if complete {
            r.size
        } else {
            self.store
                .remote()
                .local(r.hash)
                .await
                .map(|info| info.local_bytes())
                .unwrap_or(0)
        };
        if already > 0 {
            println!("EVENT:RESUMED:{id}:{already}");
        }

        let started = Instant::now();
        let mut final_path = None;
        if !complete {
            let path = PathWatch::start(self.endpoint.clone(), r.sender, id.to_string());
            let outcome = self.download(r, &name).await;
            final_path = path.stop().await;
            outcome?;
        }
        let data_done = Instant::now();

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
        // it its final name (same naming as META3). A leftover partial from
        // an earlier, interrupted run is ours (one fetch per id) -> replace.
        let partial = format!("received_{id}.partial");
        let _ = tokio::fs::remove_file(&partial).await;
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
                    last_error = Some(
                        "could not get data from the sender (offline, unreachable, or this peer is not allowed)".into(),
                    );
                }
                DownloadProgressItem::Error(e) => last_error = Some(format!("download error: {e}")),
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

/// Polls relay vs direct during a fetch and prints CONNECTION_PATH on every
/// change, like META3 does.
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
        let last = *self.last.lock().unwrap();
        now.or(last)
    }
}

/// B2b-2: if a fetch is cancelled mid-download, the watcher is dropped
/// without stop(); this makes sure its polling task ends too.
impl Drop for PathWatch {
    fn drop(&mut self) {
        self.task.abort();
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

        assert!(a.knows(&peer(1)));
        assert_eq!(a.offer_for(&peer(1), &h1).as_deref(), Some("t1"));
        assert_eq!(a.offer_for(&peer(1), &h2), None);
        assert!(!a.knows(&peer(2)));
        assert_eq!(a.offer_for(&peer(2), &h1), None);
    }

    #[test]
    fn tags_are_per_transfer() {
        assert_eq!(tag_out("t1"), "out-t1");
        assert_eq!(tag_in("t1"), "in-t1");
    }

    #[test]
    fn saved_fetch_is_checked() {
        let good = FetchRecord {
            hash: Hash::new(b"x").to_string(),
            size: 5,
            sender: peer(1).to_string(),
            name: "a.bin".into(),
            created_ms: 0,
        };
        assert!(FetchRequest::from_record("t1", &good).is_ok());

        let mut bad_hash = good.clone();
        bad_hash.hash = format!(":{}", good.hash); // would crash the parser
        assert!(FetchRequest::from_record("t1", &bad_hash).is_err());

        let mut bad_sender = good.clone();
        bad_sender.sender = "nope".into();
        assert!(FetchRequest::from_record("t1", &bad_sender).is_err());

        assert!(FetchRequest::from_record("../x", &good).is_err());
    }

    #[test]
    fn expiry_math() {
        let ttl = 30_000; // 30 s
        assert!(!is_expired(1_000, 1_000, ttl));
        assert!(!is_expired(1_000, 30_999, ttl));
        assert!(is_expired(1_000, 31_000, ttl));
        assert_eq!(secs_left(1_000, 11_000, ttl), 20);
        assert_eq!(secs_left(1_000, 99_000, ttl), 0);
    }
}
