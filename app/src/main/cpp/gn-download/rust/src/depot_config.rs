use serde_json::{json, Value};
use std::collections::{BTreeMap, BTreeSet};
use std::fs::{self, File};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::Mutex;

pub const INVALID_MANIFEST_ID: u64 = 0x7fff_ffff_ffff_ffff;

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct DepotConfigStore {
    config_dir: PathBuf,
    installed: BTreeMap<u32, u64>,
}

impl DepotConfigStore {
    pub fn load(config_dir: impl Into<PathBuf>) -> Self {
        let config_dir = config_dir.into();
        let mut store = Self {
            config_dir,
            installed: BTreeMap::new(),
        };
        let Ok(bytes) = fs::read_to_string(store.config_path()) else {
            return store;
        };
        let Ok(value) = serde_json::from_str::<Value>(&bytes) else {
            return store;
        };
        let Some(obj) = value
            .get("installedManifestIDs")
            .and_then(|v| v.as_object())
        else {
            return store;
        };
        for (key, value) in obj {
            if let (Ok(depot_id), Some(manifest_id)) = (key.parse::<u32>(), value.as_u64()) {
                store.installed.insert(depot_id, manifest_id);
            }
        }
        store
    }

    pub fn config_dir(&self) -> &Path {
        &self.config_dir
    }

    pub fn manifest_cache_path(&self, depot_id: u32, manifest_id: u64) -> PathBuf {
        self.config_dir
            .join(format!("{depot_id}_{manifest_id}.manifest"))
    }

    pub fn installed_manifest(&self, depot_id: u32) -> u64 {
        self.installed.get(&depot_id).copied().unwrap_or(0)
    }

    pub fn is_installed(&self, depot_id: u32, manifest_id: u64) -> bool {
        self.installed
            .get(&depot_id)
            .is_some_and(|installed| *installed == manifest_id && *installed != INVALID_MANIFEST_ID)
    }

    pub fn begin_depot(&mut self, depot_id: u32) -> bool {
        self.installed.insert(depot_id, INVALID_MANIFEST_ID);
        self.save()
    }

    pub fn finish_depot(&mut self, depot_id: u32, manifest_id: u64) -> bool {
        self.installed.insert(depot_id, manifest_id);
        self.save()
    }

    pub fn forget_depot(&mut self, depot_id: u32) -> bool {
        self.installed.remove(&depot_id);
        self.save()
    }

    pub fn discard(&mut self) {
        self.installed.clear();
        let _ = fs::remove_file(self.config_path());
    }

    fn config_path(&self) -> PathBuf {
        self.config_dir.join("depot.config")
    }

    fn save(&self) -> bool {
        if fs::create_dir_all(&self.config_dir).is_err() {
            return false;
        }
        let ids: serde_json::Map<String, Value> = self
            .installed
            .iter()
            .map(|(depot, manifest)| (depot.to_string(), json!(manifest)))
            .collect();
        let Ok(bytes) = serde_json::to_string_pretty(&json!({ "installedManifestIDs": ids }))
        else {
            return false;
        };
        atomic_write_synced(&self.config_path(), bytes.as_bytes())
    }
}

pub struct DepotProgressStore {
    path: PathBuf,
    done: Mutex<BTreeSet<u32>>,
    flushed_count: Mutex<usize>,
}

impl DepotProgressStore {
    pub fn new(config_dir: impl AsRef<Path>, depot_id: u32, manifest_id: u64) -> Self {
        let path = Self::sidecar_path(config_dir, depot_id, manifest_id);
        let mut done = BTreeSet::new();
        if let Ok(buf) = fs::read(&path) {
            if let Some(parsed) = parse_progress_sidecar(&buf) {
                done = parsed;
            }
        }
        let flushed_count = done.len();
        Self {
            path,
            done: Mutex::new(done),
            flushed_count: Mutex::new(flushed_count),
        }
    }

    pub fn is_file_done(&self, file_index: u32) -> bool {
        self.done.lock().unwrap().contains(&file_index)
    }

    pub fn mark_file_done(&self, file_index: u32) {
        self.done.lock().unwrap().insert(file_index);
    }

    pub fn done_count(&self) -> usize {
        self.done.lock().unwrap().len()
    }

    pub fn flush(&self) -> bool {
        let done = self.done.lock().unwrap();
        let mut flushed = self.flushed_count.lock().unwrap();
        if done.len() == *flushed {
            return true;
        }
        let blob = serialize_progress_sidecar(&done);
        if !atomic_write_synced(&self.path, &blob) {
            return false;
        }
        *flushed = done.len();
        true
    }

    pub fn discard(&self) {
        self.done.lock().unwrap().clear();
        *self.flushed_count.lock().unwrap() = 0;
        let _ = fs::remove_file(&self.path);
    }

    pub fn remove(config_dir: impl AsRef<Path>, depot_id: u32, manifest_id: u64) {
        let _ = fs::remove_file(Self::sidecar_path(config_dir, depot_id, manifest_id));
    }

    pub fn sidecar_path(config_dir: impl AsRef<Path>, depot_id: u32, manifest_id: u64) -> PathBuf {
        config_dir
            .as_ref()
            .join(format!("{depot_id}_{manifest_id}.progress"))
    }
}

const PROGRESS_MAGIC: &[u8; 4] = b"BLDP";
const PROGRESS_VERSION: u32 = 1;

fn parse_progress_sidecar(buf: &[u8]) -> Option<BTreeSet<u32>> {
    if buf.len() < 12 || &buf[0..4] != PROGRESS_MAGIC {
        return None;
    }
    if get_u32(&buf[4..8])? != PROGRESS_VERSION {
        return None;
    }
    let count = get_u32(&buf[8..12])? as usize;
    if 12usize.checked_add(count.checked_mul(4)?)? != buf.len() {
        return None;
    }
    let mut out = BTreeSet::new();
    for i in 0..count {
        out.insert(get_u32(&buf[12 + i * 4..16 + i * 4])?);
    }
    Some(out)
}

fn serialize_progress_sidecar(done: &BTreeSet<u32>) -> Vec<u8> {
    let mut out = Vec::with_capacity(12 + done.len() * 4);
    out.extend_from_slice(PROGRESS_MAGIC);
    put_u32(&mut out, PROGRESS_VERSION);
    put_u32(&mut out, done.len() as u32);
    for idx in done {
        put_u32(&mut out, *idx);
    }
    out
}

fn put_u32(out: &mut Vec<u8>, v: u32) {
    out.extend_from_slice(&v.to_le_bytes());
}

fn get_u32(buf: &[u8]) -> Option<u32> {
    Some(u32::from_le_bytes(buf.get(..4)?.try_into().ok()?))
}

fn atomic_write_synced(final_path: &Path, bytes: &[u8]) -> bool {
    let Some(parent) = final_path.parent() else {
        return false;
    };
    if fs::create_dir_all(parent).is_err() {
        return false;
    }
    let tmp_path =
        final_path.with_extension(match final_path.extension().and_then(|s| s.to_str()) {
            Some(ext) => format!("{ext}.tmp"),
            None => "tmp".to_string(),
        });
    let mut file = match File::create(&tmp_path) {
        Ok(file) => file,
        Err(_) => return false,
    };
    if file.write_all(bytes).is_err() || file.sync_all().is_err() {
        let _ = fs::remove_file(&tmp_path);
        return false;
    }
    drop(file);
    if fs::rename(&tmp_path, final_path).is_err() {
        let _ = fs::remove_file(&tmp_path);
        return false;
    }
    if let Ok(dir) = File::open(parent) {
        let _ = dir.sync_all();
    }
    true
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn depot_config_roundtrips_and_marks_in_progress() {
        let dir = temp_dir("depot_config_roundtrips");
        let mut store = DepotConfigStore::load(&dir);
        assert_eq!(store.installed_manifest(100), 0);
        assert!(!store.is_installed(100, 0));
        assert!(store.begin_depot(100));
        assert_eq!(
            DepotConfigStore::load(&dir).installed_manifest(100),
            INVALID_MANIFEST_ID
        );
        assert!(store.finish_depot(100, 555));
        let loaded = DepotConfigStore::load(&dir);
        assert!(loaded.is_installed(100, 555));
        assert_eq!(
            loaded.manifest_cache_path(100, 555),
            dir.join("100_555.manifest")
        );
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn progress_sidecar_roundtrips_sorted_indices() {
        let dir = temp_dir("progress_sidecar_roundtrips");
        let store = DepotProgressStore::new(&dir, 1, 2);
        store.mark_file_done(9);
        store.mark_file_done(3);
        assert_eq!(store.done_count(), 2);
        assert!(store.flush());

        let loaded = DepotProgressStore::new(&dir, 1, 2);
        assert!(loaded.is_file_done(3));
        assert!(loaded.is_file_done(9));
        assert!(!loaded.is_file_done(4));
        loaded.discard();
        assert!(!DepotProgressStore::sidecar_path(&dir, 1, 2).exists());
        let _ = fs::remove_dir_all(&dir);
    }

    fn temp_dir(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!(
            "blsteam_{name}_{}",
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        let _ = fs::remove_dir_all(&dir);
        dir
    }
}
