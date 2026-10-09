//! B2b: peer-app's memory on disk, so transfers survive a restart.
//!
//!   offers.json   sender:   what is shared, from which file, who may fetch it
//!   fetches.json  receiver: what is still being downloaded, from whom
//!
//! An entry lives only while its transfer is active. Every change is written
//! at once (temp file -> rename, owner-only), under one lock, so a crash never
//! leaves a half-written file. A damaged file is set aside as *.bad and
//! peer-app starts empty instead of crashing.

use anyhow::Result;
use serde::{de::DeserializeOwned, Deserialize, Serialize};
use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

pub const STATE_VERSION: u32 = 1;
const OFFERS_FILE: &str = "offers.json";
const FETCHES_FILE: &str = "fetches.json";

/// One shared file (sender side).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct OfferRecord {
    pub hash: String,
    pub path: PathBuf,
    pub size: u64,
    /// The file's last-modified time when it was shared. If it differs at
    /// startup, the file changed while peer-app was off.
    pub modified_ms: u64,
    /// Endpoint IDs allowed to fetch.
    pub allowed: Vec<String>,
    pub created_ms: u64,
}

/// One unfinished download (receiver side).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct FetchRecord {
    pub hash: String,
    pub size: u64,
    pub sender: String,
    pub name: String,
    pub created_ms: u64,
}

#[derive(Debug, Default, Serialize, Deserialize)]
struct OffersFile {
    version: u32,
    offers: BTreeMap<String, OfferRecord>,
}

#[derive(Debug, Default, Serialize, Deserialize)]
struct FetchesFile {
    version: u32,
    fetches: BTreeMap<String, FetchRecord>,
}

trait Versioned {
    fn version(&self) -> u32;
}
impl Versioned for OffersFile {
    fn version(&self) -> u32 {
        self.version
    }
}
impl Versioned for FetchesFile {
    fn version(&self) -> u32 {
        self.version
    }
}

#[derive(Default)]
struct Inner {
    offers: BTreeMap<String, OfferRecord>,
    fetches: BTreeMap<String, FetchRecord>,
}

pub struct State {
    dir: PathBuf,
    inner: Mutex<Inner>,
}

impl State {
    /// Reads both files from the data folder. Missing files = empty.
    /// Returns warnings (damaged files that were set aside).
    pub fn load(dir: &Path) -> (State, Vec<String>) {
        let mut warnings = Vec::new();
        let offers: OffersFile = load_file(&dir.join(OFFERS_FILE), &mut warnings);
        let fetches: FetchesFile = load_file(&dir.join(FETCHES_FILE), &mut warnings);
        let state = State {
            dir: dir.to_path_buf(),
            inner: Mutex::new(Inner { offers: offers.offers, fetches: fetches.fetches }),
        };
        (state, warnings)
    }

    pub fn offers(&self) -> BTreeMap<String, OfferRecord> {
        self.inner.lock().unwrap().offers.clone()
    }

    pub fn fetches(&self) -> BTreeMap<String, FetchRecord> {
        self.inner.lock().unwrap().fetches.clone()
    }

    pub fn fetch(&self, id: &str) -> Option<FetchRecord> {
        self.inner.lock().unwrap().fetches.get(id).cloned()
    }

    pub fn put_offer(&self, id: &str, rec: OfferRecord) -> Result<()> {
        let mut inner = self.inner.lock().unwrap();
        inner.offers.insert(id.to_string(), rec);
        self.save_offers(&inner)
    }

    /// Adds a peer to an offer's allow-list (no-op if the offer is unknown).
    pub fn allow(&self, id: &str, peer: &str) -> Result<()> {
        let mut inner = self.inner.lock().unwrap();
        if let Some(rec) = inner.offers.get_mut(id) {
            if !rec.allowed.iter().any(|p| p == peer) {
                rec.allowed.push(peer.to_string());
            }
        }
        self.save_offers(&inner)
    }

    pub fn remove_offer(&self, id: &str) -> Result<()> {
        let mut inner = self.inner.lock().unwrap();
        if inner.offers.remove(id).is_some() {
            self.save_offers(&inner)?;
        }
        Ok(())
    }

    pub fn put_fetch(&self, id: &str, rec: FetchRecord) -> Result<()> {
        let mut inner = self.inner.lock().unwrap();
        inner.fetches.insert(id.to_string(), rec);
        self.save_fetches(&inner)
    }

    pub fn remove_fetch(&self, id: &str) -> Result<()> {
        let mut inner = self.inner.lock().unwrap();
        if inner.fetches.remove(id).is_some() {
            self.save_fetches(&inner)?;
        }
        Ok(())
    }

    // Called with the lock held, so only one write happens at a time.
    fn save_offers(&self, inner: &Inner) -> Result<()> {
        let file = OffersFile { version: STATE_VERSION, offers: inner.offers.clone() };
        write_json(&self.dir.join(OFFERS_FILE), &file)
    }

    fn save_fetches(&self, inner: &Inner) -> Result<()> {
        let file = FetchesFile { version: STATE_VERSION, fetches: inner.fetches.clone() };
        write_json(&self.dir.join(FETCHES_FILE), &file)
    }
}

/// Milliseconds since 1970 (wall clock: it must survive restarts).
pub fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

/// A file's last-modified time in milliseconds (0 if unknown).
pub fn modified_ms(meta: &std::fs::Metadata) -> u64 {
    meta.modified()
        .ok()
        .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

fn load_file<T: DeserializeOwned + Default + Versioned>(path: &Path, warnings: &mut Vec<String>) -> T {
    let text = match std::fs::read_to_string(path) {
        Ok(t) => t,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return T::default(),
        Err(e) => {
            warnings.push(format!("cannot read {}: {e}; starting empty", path.display()));
            return T::default();
        }
    };
    match serde_json::from_str::<T>(&text) {
        Ok(v) if v.version() == STATE_VERSION => v,
        Ok(v) => {
            set_aside(path, &format!("has unsupported version {}", v.version()), warnings);
            T::default()
        }
        Err(e) => {
            set_aside(path, &format!("is damaged ({e})"), warnings);
            T::default()
        }
    }
}

/// Keeps a damaged file for inspection instead of deleting it.
fn set_aside(path: &Path, why: &str, warnings: &mut Vec<String>) {
    let bad = with_suffix(path, ".bad");
    match std::fs::rename(path, &bad) {
        Ok(()) => warnings.push(format!(
            "{} {why}; moved to {}, starting empty",
            path.display(),
            bad.display()
        )),
        Err(e) => warnings.push(format!("{} {why}; could not move it aside: {e}", path.display())),
    }
}

fn with_suffix(path: &Path, suffix: &str) -> PathBuf {
    let mut s = path.as_os_str().to_owned();
    s.push(suffix);
    PathBuf::from(s)
}

/// Safe write: temp file (owner-only) -> sync -> rename.
fn write_json<T: Serialize>(path: &Path, value: &T) -> Result<()> {
    use std::io::Write;
    let text = serde_json::to_string_pretty(value)?;
    let tmp = with_suffix(path, ".tmp");
    let mut options = std::fs::OpenOptions::new();
    options.write(true).create(true).truncate(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    let mut file = options.open(&tmp)?;
    file.write_all(text.as_bytes())?;
    file.sync_all()?;
    drop(file);
    std::fs::rename(&tmp, path)?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn test_dir(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("aperture-state-{}-{name}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    fn offer() -> OfferRecord {
        OfferRecord {
            hash: "ab".repeat(32),
            path: PathBuf::from("/tmp/a.bin"),
            size: 10,
            modified_ms: 1,
            allowed: vec![],
            created_ms: 2,
        }
    }

    fn fetch() -> FetchRecord {
        FetchRecord { hash: "cd".repeat(32), size: 20, sender: "x".into(), name: "a.bin".into(), created_ms: 3 }
    }

    #[test]
    fn saves_and_reloads() {
        let dir = test_dir("roundtrip");
        {
            let (s, w) = State::load(&dir);
            assert!(w.is_empty());
            s.put_offer("t1", offer()).unwrap();
            s.allow("t1", "peer-a").unwrap();
            s.allow("t1", "peer-a").unwrap(); // no duplicate
            s.put_fetch("t2", fetch()).unwrap();
        }
        let (s, w) = State::load(&dir);
        assert!(w.is_empty());
        assert_eq!(s.offers()["t1"].allowed, vec!["peer-a".to_string()]);
        assert_eq!(s.fetch("t2"), Some(fetch()));

        s.remove_offer("t1").unwrap();
        s.remove_fetch("t2").unwrap();
        let (s, _) = State::load(&dir);
        assert!(s.offers().is_empty() && s.fetches().is_empty());
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn damaged_file_is_set_aside() {
        let dir = test_dir("damaged");
        std::fs::write(dir.join(OFFERS_FILE), "{ not json").unwrap();
        let (s, w) = State::load(&dir);
        assert_eq!(w.len(), 1);
        assert!(s.offers().is_empty());
        assert!(dir.join("offers.json.bad").exists());
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn unknown_version_is_set_aside() {
        let dir = test_dir("version");
        std::fs::write(dir.join(FETCHES_FILE), r#"{"version":99,"fetches":{}}"#).unwrap();
        let (_, w) = State::load(&dir);
        assert_eq!(w.len(), 1);
        assert!(dir.join("fetches.json.bad").exists());
        let _ = std::fs::remove_dir_all(&dir);
    }
}
