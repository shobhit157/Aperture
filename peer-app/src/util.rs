//! Small helpers shared by META3 and blobs: cleaning names and reasons,
//! progress throttling, and saving a finished file under a free name.

use std::sync::OnceLock;
use std::time::Instant;

pub const MAX_NAME_CHARS: usize = 100;
pub const MAX_ID_CHARS: usize = 64;
// Phase A4: default read/write chunk. Can be changed for speed tests with
// PEER_CHUNK_KB (see chunk_size()).
pub const DEFAULT_CHUNK_KB: usize = 64;
// Phase A4: progress lines — at most one per 1% step or per second.
const PROGRESS_MIN_INTERVAL_MS: u128 = 1000;

/// Phase A1: keep reasons on one line and free of '|' so they can't break
/// the EVENT line or the TRANSFER_METRIC|failed|peer|reason message in Java.
pub fn clean(reason: &str) -> String {
    reason.replace(['\n', '\r', '|'], " ")
}

/// Phase A2: transfer IDs end up in filenames and EVENT lines.
pub fn valid_transfer_id(id: &str) -> bool {
    !id.is_empty()
        && id.len() <= MAX_ID_CHARS
        && id.chars().all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_')
}

/// B2: a blob hash is exactly 64 hex characters. Checked before parsing
/// because iroh-blobs' parser panics (crashes peer-app) on a wrong length.
pub fn valid_hash_text(hash: &str) -> bool {
    hash.len() == 64 && hash.chars().all(|c| c.is_ascii_hexdigit())
}

/// Phase A2 (bug 8 + path safety): turn any name a peer sends into a plain,
/// safe filename. Never trust the other side.
pub fn safe_filename(raw: &str) -> String {
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
pub fn chunk_size() -> usize {
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
pub struct ProgressGate {
    last_pct: Option<u64>,
    last_print: Instant,
}

impl ProgressGate {
    pub fn new() -> Self {
        ProgressGate { last_pct: None, last_print: Instant::now() }
    }

    pub fn should_print(&mut self, pct: u64) -> bool {
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

/// Percentage 0..=100; an empty file counts as 100%.
pub fn percent(done: u64, total: u64) -> u64 {
    if total == 0 { 100 } else { (done.saturating_mul(100) / total).min(100) }
}

/// Phase A2 (bug 5): give a finished file its final name without ever
/// overwriting an existing file: received_<id8>_<name>, then _2, _3, ...
/// Used by META3 and blobs, so both save files the same way.
pub async fn save_partial(partial_path: &str, transfer_id: &str, name: &str) -> Result<String, String> {
    let short_id: String = transfer_id.chars().take(8).collect();
    let base = format!("received_{short_id}_{name}");

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
pub fn short(hash: &str) -> &str {
    hash.get(..12).unwrap_or(hash)
}

/// "received_ab_photo.jpg", 2 -> "received_ab_photo_2.jpg"
pub fn with_counter(name: &str, n: u32) -> String {
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
    fn hash_text_is_checked() {
        let good = "a38cf1b054a8ac627eaed9136dd4b6e3ec183cbdcf156eabb5c49a61dff2593d";
        assert!(valid_hash_text(good));
        assert!(!valid_hash_text(&format!(":{good}"))); // the copy-paste crash
        assert!(!valid_hash_text(&good[..63]));
        assert!(!valid_hash_text(&good.replace('a', "z")));
        assert!(!valid_hash_text(""));
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
        assert_eq!(short("0123456789abcdef"), "0123456789ab");
    }

    #[test]
    fn percentages() {
        assert_eq!(percent(0, 0), 100);
        assert_eq!(percent(50, 200), 25);
        assert_eq!(percent(300, 200), 100);
    }
}
