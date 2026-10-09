//! B0: persistent identity. The endpoint ID is the public half of a secret
//! key; keeping the key keeps the ID. Also owns the data folder, where the
//! blob store lives since B2.

use anyhow::{anyhow, Result};
use iroh::SecretKey;
use std::path::{Path, PathBuf};

use crate::util::{clean, MAX_ID_CHARS};

const KEY_FILE_NAME: &str = "secret.key";
pub const MIN_SEED_CHARS: usize = 32;
// Changing this string changes every derived bot ID. Keep it fixed.
const KEY_DERIVE_CONTEXT: &str = "aperture peer key v1";

/// Where the key comes from, in this order:
///   1. PEER_KEY_SEED set -> derived from seed + username (bots: same name
///      and seed give the same ID on any machine, in any region)
///   2. key file exists   -> loaded
///   3. otherwise         -> new random key, saved
/// Returns the key and the text for EVENT:IDENTITY (never the secret).
pub fn load_identity(username: &str) -> Result<(SecretKey, String)> {
    if let Ok(seed) = std::env::var("PEER_KEY_SEED") {
        if seed.chars().count() < MIN_SEED_CHARS {
            return Err(anyhow!("PEER_KEY_SEED is shorter than {MIN_SEED_CHARS} characters"));
        }
        let bytes = derive_bot_key(&seed, username);
        return Ok((SecretKey::from_bytes(&bytes), format!("derived:{}", clean(username))));
    }

    let dir = data_dir(username)?;
    let path = dir.join(KEY_FILE_NAME);
    if path.exists() {
        let bytes = std::fs::read(&path)
            .map_err(|e| anyhow!("cannot read {}: {e}", path.display()))?;
        let bytes: [u8; 32] = bytes.as_slice().try_into().map_err(|_| {
            anyhow!(
                "{} has {} bytes, expected 32 (fix or delete it; a new key means a new endpoint ID)",
                path.display(),
                bytes.len()
            )
        })?;
        Ok((SecretKey::from_bytes(&bytes), format!("loaded:{}", path.display())))
    } else {
        let key = SecretKey::generate();
        write_key_file(&dir, &path, &key.to_bytes())?;
        Ok((key, format!("created:{}", path.display())))
    }
}

/// Same seed + same username -> same 32 bytes, on any machine.
/// A 0 byte separates them, so ("ab", "c") and ("a", "bc") differ.
pub fn derive_bot_key(seed: &str, username: &str) -> [u8; 32] {
    let mut material = seed.as_bytes().to_vec();
    material.push(0);
    material.extend_from_slice(username.as_bytes());
    blake3::derive_key(KEY_DERIVE_CONTEXT, &material)
}

/// PEER_DATA_DIR, or ~/.aperture/<username>/ (one folder per username, so
/// admin and admin2 on one laptop get different IDs and stores).
/// Created if missing, owner-only.
pub fn data_dir(username: &str) -> Result<PathBuf> {
    let dir = match std::env::var("PEER_DATA_DIR") {
        Ok(d) if !d.trim().is_empty() => PathBuf::from(d),
        _ => {
            let home = std::env::var("HOME")
                .map_err(|_| anyhow!("HOME is not set; set PEER_DATA_DIR instead"))?;
            PathBuf::from(home).join(".aperture").join(safe_dir_name(username))
        }
    };
    std::fs::create_dir_all(&dir).map_err(|e| anyhow!("cannot create {}: {e}", dir.display()))?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let _ = std::fs::set_permissions(&dir, std::fs::Permissions::from_mode(0o700));
    }
    Ok(dir)
}

/// Usernames become folder names: keep A-Z a-z 0-9 - _ only.
pub fn safe_dir_name(username: &str) -> String {
    let name: String = username
        .chars()
        .map(|c| if c.is_ascii_alphanumeric() || c == '-' || c == '_' { c } else { '_' })
        .take(MAX_ID_CHARS)
        .collect();
    if name.is_empty() { "peer".to_string() } else { name }
}

/// Write the key safely: temp file (owner-only) -> sync -> rename.
/// A crash never leaves a half-written key behind.
fn write_key_file(dir: &Path, path: &Path, bytes: &[u8; 32]) -> Result<()> {
    use std::io::Write;
    let tmp = dir.join(format!("{KEY_FILE_NAME}.tmp"));
    let mut options = std::fs::OpenOptions::new();
    options.write(true).create(true).truncate(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    let mut file = options.open(&tmp).map_err(|e| anyhow!("cannot write {}: {e}", tmp.display()))?;
    file.write_all(bytes)?;
    file.sync_all()?;
    drop(file);
    std::fs::rename(&tmp, path).map_err(|e| anyhow!("cannot save {}: {e}", path.display()))?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn derived_keys() {
        // Same seed + same name -> same key (on any machine).
        let seed = "s".repeat(MIN_SEED_CHARS);
        assert_eq!(derive_bot_key(&seed, "bot-7"), derive_bot_key(&seed, "bot-7"));
        // Different name or different seed -> different key.
        assert_ne!(derive_bot_key(&seed, "bot-7"), derive_bot_key(&seed, "bot-8"));
        let other = "t".repeat(MIN_SEED_CHARS);
        assert_ne!(derive_bot_key(&seed, "bot-7"), derive_bot_key(&other, "bot-7"));
        // The 0 byte keeps seed and name apart.
        assert_ne!(derive_bot_key("ab", "c"), derive_bot_key("a", "bc"));
    }

    #[test]
    fn dir_names() {
        assert_eq!(safe_dir_name("admin"), "admin");
        assert_eq!(safe_dir_name("bot-7"), "bot-7");
        assert_eq!(safe_dir_name("../evil"), "___evil");
        assert_eq!(safe_dir_name("a b/c"), "a_b_c");
        assert_eq!(safe_dir_name(""), "peer");
    }
}
