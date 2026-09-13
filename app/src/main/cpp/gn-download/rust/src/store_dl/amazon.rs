//! Amazon Games download adapter over the shared fetch core.
//!
//! Replaces ONLY the byte-fetching pool of `AmazonDownloadManager.install()` (Step 4).
//! Java still resolves the download spec, fetches and parses `manifest.proto`, and hands
//! this module the resolved file list (one JSON entry per manifest file with the exact
//! URL the Java loop would have opened). The adapter mirrors the Java rules 1:1 where the
//! core allows it — see `docs/RUST_AMAZON_PARITY.md` §4 / §9 for the table and the
//! documented deviations:
//!
//! * one `FetchItem` per WHOLE file, streamed (`FetchOptions.stream = true`): pieces are
//!   appended to `<dest>.tmp` as they arrive, 8 files wide like Java's pool, memory bounded
//!   to the pieces not yet written;
//! * resume-skip = `st_size == manifest size`, no hash check on skip (Java `:250`);
//! * SHA-256 is accumulated while streaming and checked in `on_finish` when the manifest
//!   carries a hash; a mismatch deletes the tmp and is a retryable failure (Java `:269`);
//! * `<dest>.tmp` → delete existing dest → rename (Java `:280`); rename failure is fatal
//!   without retry (Java `:281`);
//! * a piece credits its wire length to progress as it is written (Java `:347`), and pieces
//!   of an attempt that later fails stay counted (Java never rolls `totalDownloaded` back).

pub mod jni;

use crate::fetch_core::{run_fetch, FetchItem, FetchOptions, FetchSink, SinkError};
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::fs::{self, File};
use std::io::Write;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

/// Java `AmazonDownloadManager.MAX_PARALLEL`.
pub const MAX_PARALLEL: usize = 8;
/// Java `AmazonDownloadManager.DOWNLOAD_USER_AGENT`.
pub const DOWNLOAD_USER_AGENT: &str = "nile/0.1 Amazon";
/// Java `conn.setReadTimeout(120000)`; in stream mode the core applies this to the response
/// headers and then as an idle deadline per received piece — the same shape as a read timeout.
pub const REQUEST_TIMEOUT: Duration = Duration::from_secs(120);
/// Java `new File(installDir, file.unixPath() + ".tmp")`.
pub const TMP_SUFFIX: &str = ".tmp";
/// Log label used in the core's `fetch-window` lines.
pub const LABEL: &str = "amazon";

/// One resolved manifest file as serialised by Java (`AmazonDownloadManager.buildRustPlan`).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PlanEntry {
    /// `ManifestFile.unixPath()` — relative to the install dir, forward slashes.
    pub rel_path: String,
    /// `AmazonApiClient.appendPath(downloadUrl, "files/" + hashHex)` — signed, whole file.
    pub url: String,
    /// `ManifestFile.size`.
    pub size: u64,
    /// SHA-256 to verify against; EMPTY = Java would not verify (`hashAlgorithm != 0` or
    /// no hash bytes).
    pub sha256: Vec<u8>,
}

/// Parse the JSON array `[{relPath, url, size, sha256hex}]`. `relPath` is normalised the same
/// way as `ManifestFile.unixPath()` so a caller that forgot is still byte-identical.
pub fn parse_plan(json: &str) -> Result<Vec<PlanEntry>, String> {
    let value: serde_json::Value =
        serde_json::from_str(json).map_err(|err| format!("plan json: {err}"))?;
    let Some(array) = value.as_array() else {
        return Err("plan json: expected an array".to_string());
    };
    let mut entries = Vec::with_capacity(array.len());
    for (index, item) in array.iter().enumerate() {
        let rel_path = item
            .get("relPath")
            .and_then(|v| v.as_str())
            .map(unix_path)
            .unwrap_or_default();
        if rel_path.is_empty() {
            return Err(format!("plan json: entry {index} has no relPath"));
        }
        // Path-traversal guard: the plan is server/manifest-influenced, and dest_path
        // concatenates textually. Reject anything that could escape install_dir.
        // (Backslash checks are unnecessary: unix_path() already mapped '\\' to '/'.)
        if rel_path.starts_with('/') || rel_path.split('/').any(|seg| seg == "..") {
            return Err(format!(
                "plan json: entry {index} unsafe relPath: {rel_path}"
            ));
        }
        let url = item
            .get("url")
            .and_then(|v| v.as_str())
            .unwrap_or_default()
            .to_string();
        if url.is_empty() {
            return Err(format!("plan json: entry {index} ({rel_path}) has no url"));
        }
        let size = item
            .get("size")
            .and_then(|v| v.as_u64().or_else(|| v.as_i64().map(|s| s.max(0) as u64)))
            .unwrap_or(0);
        let sha_hex = item
            .get("sha256hex")
            .and_then(|v| v.as_str())
            .unwrap_or_default();
        let sha256 = if sha_hex.is_empty() {
            Vec::new()
        } else {
            hex_decode(sha_hex)
                .ok_or_else(|| format!("plan json: entry {index} ({rel_path}) bad sha256hex"))?
        };
        entries.push(PlanEntry {
            rel_path,
            url,
            size,
            sha256,
        });
    }
    Ok(entries)
}

/// `ManifestFile.unixPath()`.
pub fn unix_path(path: &str) -> String {
    path.replace('\\', "/")
}

/// Lowercase/uppercase hex → bytes; `None` on odd length or a non-hex digit.
pub fn hex_decode(hex: &str) -> Option<Vec<u8>> {
    let bytes = hex.as_bytes();
    if bytes.len() % 2 != 0 {
        return None;
    }
    let nibble = |b: u8| -> Option<u8> {
        match b {
            b'0'..=b'9' => Some(b - b'0'),
            b'a'..=b'f' => Some(b - b'a' + 10),
            b'A'..=b'F' => Some(b - b'A' + 10),
            _ => None,
        }
    };
    let mut out = Vec::with_capacity(bytes.len() / 2);
    for pair in bytes.chunks(2) {
        out.push((nibble(pair[0])? << 4) | nibble(pair[1])?);
    }
    Some(out)
}

/// `new File(installDir, rel)` — Java resolves the child textually under the parent, so
/// build the path by concatenation (never `Path::join`, which would let a leading `/` escape).
pub fn dest_path(install_dir: &str, rel_path: &str) -> PathBuf {
    let base = install_dir.trim_end_matches('/');
    PathBuf::from(format!("{base}/{rel_path}"))
}

/// `new File(installDir, rel + ".tmp")`.
pub fn tmp_path(install_dir: &str, rel_path: &str) -> PathBuf {
    dest_path(install_dir, &format!("{rel_path}{TMP_SUFFIX}"))
}

/// Java `:250`: `destFile.exists() && destFile.length() == file.size`. `File.length()` is
/// `st_size`, which is what `Metadata::len()` returns — including for a directory, so the
/// (degenerate) behaviours match too. No hash is checked on skip, on purpose.
pub fn is_present_and_complete(install_dir: &str, entry: &PlanEntry) -> bool {
    fs::metadata(dest_path(install_dir, &entry.rel_path))
        .map(|meta| meta.len() == entry.size)
        .unwrap_or(false)
}

/// `scheme://host[:port]` of a URL — the per-host cap key. Amazon serves every file from the
/// one signed CDN base, so this yields a single host.
pub fn host_key(url: &str) -> String {
    let (scheme, rest) = match url.find("://") {
        Some(idx) => (&url[..idx], &url[idx + 3..]),
        None => ("https", url),
    };
    let end = rest.find(['/', '?', '#']).unwrap_or(rest.len());
    format!("{scheme}://{}", &rest[..end])
}

/// Improvements round 1: `max(6, ceil(max_workers / distinct_hosts))` — a single-host store
/// (Amazon's one signed a2z base) gets the whole ceiling on its one host; the core clamps the
/// window to `hosts × per_host_cap`, so anything smaller would silently cut the ceiling.
pub fn per_host_cap_for(max_workers: usize, distinct_hosts: usize) -> usize {
    let hosts = distinct_hosts.max(1);
    let per_host = max_workers.max(1).div_ceil(hosts);
    per_host.max(crate::depot_writer::PER_HOST_CAP)
}

/// The resolved work for one run.
#[derive(Debug, Default)]
pub struct Plan {
    /// Items to fetch (index into `entries` via `FetchItem.id`).
    pub items: Vec<FetchItem>,
    /// Every manifest file, in manifest order (fetched and skipped alike).
    pub entries: Vec<PlanEntry>,
    /// Σ size of all entries = `ParsedManifest.totalInstallSize`.
    pub total_bytes: u64,
    /// Bytes credited up front for files that passed the resume check.
    pub skipped_bytes: u64,
    /// Number of files that passed the resume check.
    pub skipped_files: u64,
    /// Distinct host keys in fetch order.
    pub hosts: Vec<String>,
}

/// Apply the Java resume-skip rule to every entry and turn the rest into whole-file items.
pub fn build_plan(entries: Vec<PlanEntry>, install_dir: &str) -> Plan {
    let mut plan = Plan {
        total_bytes: entries.iter().map(|e| e.size).sum(),
        ..Plan::default()
    };
    for (index, entry) in entries.iter().enumerate() {
        if is_present_and_complete(install_dir, entry) {
            plan.skipped_bytes = plan.skipped_bytes.saturating_add(entry.size);
            plan.skipped_files += 1;
            continue;
        }
        let host = host_key(&entry.url);
        if !plan.hosts.contains(&host) {
            plan.hosts.push(host);
        }
        plan.items.push(FetchItem {
            id: index as u64,
            urls: vec![entry.url.clone()],
            reserve: entry.size,
            range: None,
        });
    }
    if plan.hosts.is_empty() {
        plan.hosts.push("https://amazon".to_string());
    }
    plan.entries = entries;
    plan
}

/// One `<dest>.tmp` being streamed: the open handle, the running hash and the bytes written
/// by the current attempt.
struct OpenTmp {
    file: File,
    hasher: Sha256,
    written: u64,
}

/// Sink: stream pieces into `<dest>.tmp` (SHA-256 accumulated on the fly), verify and rename
/// in `on_finish`. `process` (whole-body mode) does the same in one step.
pub struct AmazonSink {
    install_dir: String,
    entries: Vec<PlanEntry>,
    cancel: Arc<AtomicBool>,
    files_done: AtomicU64,
    /// Per-item open tmp files. The map lock is held only to look the entry up; the write
    /// itself happens under the per-item lock so 8 streams never serialise on one mutex.
    open: Mutex<HashMap<u64, Arc<Mutex<OpenTmp>>>>,
}

impl AmazonSink {
    pub fn new(install_dir: &str, entries: Vec<PlanEntry>, cancel: Arc<AtomicBool>) -> Self {
        Self {
            install_dir: install_dir.to_string(),
            entries,
            cancel,
            files_done: AtomicU64::new(0),
            open: Mutex::new(HashMap::new()),
        }
    }

    pub fn files_done(&self) -> u64 {
        self.files_done.load(Ordering::Relaxed)
    }

    fn entry(&self, item: &FetchItem) -> Result<&PlanEntry, SinkError> {
        self.entries
            .get(item.id as usize)
            .ok_or_else(|| SinkError::Fatal(format!("plan index {} out of range", item.id)))
    }

    /// Java `:260` — `destFile.getParentFile().mkdirs()` before each attempt; a failure
    /// surfaces on the create/write below, like Java's.
    fn ensure_parent(&self, entry: &PlanEntry) {
        if let Some(parent) = dest_path(&self.install_dir, &entry.rel_path).parent() {
            let _ = fs::create_dir_all(parent);
        }
    }

    /// Java `:280-284`: delete an existing dest, rename tmp → dest; rename failure fails the
    /// file with NO retry (→ whole install fails).
    fn rename_into_place(&self, entry: &PlanEntry) -> Result<(), SinkError> {
        let dest = dest_path(&self.install_dir, &entry.rel_path);
        let tmp = tmp_path(&self.install_dir, &entry.rel_path);
        if dest.exists() {
            let _ = fs::remove_file(&dest);
        }
        if let Err(err) = fs::rename(&tmp, &dest) {
            let _ = fs::remove_file(&tmp);
            return Err(SinkError::Fatal(format!(
                "Failed to rename tmp → {} ({err})",
                dest.display()
            )));
        }
        self.files_done.fetch_add(1, Ordering::Relaxed);
        Ok(())
    }

    /// Java `:267-277`: SHA-256 check when the manifest carries a hash; a mismatch deletes the
    /// tmp and is retried.
    fn verify(&self, entry: &PlanEntry, digest: &[u8]) -> Result<(), SinkError> {
        if !entry.sha256.is_empty() && digest != entry.sha256.as_slice() {
            let _ = fs::remove_file(tmp_path(&self.install_dir, &entry.rel_path));
            return Err(SinkError::Retry(format!(
                "SHA-256 mismatch for: {}",
                entry.rel_path
            )));
        }
        Ok(())
    }

    /// Whole-body path (`FetchOptions.stream = false`): verify → write tmp → rename.
    pub fn commit(&self, entry: &PlanEntry, body: &[u8]) -> Result<u64, SinkError> {
        if !entry.sha256.is_empty() {
            let digest = Sha256::digest(body);
            if digest.as_slice() != entry.sha256.as_slice() {
                return Err(SinkError::Retry(format!(
                    "SHA-256 mismatch for: {}",
                    entry.rel_path
                )));
            }
        }
        self.ensure_parent(entry);
        let tmp = tmp_path(&self.install_dir, &entry.rel_path);
        if let Err(err) = fs::write(&tmp, body) {
            let _ = fs::remove_file(&tmp);
            return Err(SinkError::Retry(format!("write {}: {err}", tmp.display())));
        }
        self.rename_into_place(entry)?;
        Ok(body.len() as u64)
    }

    /// After the run: close and delete every tmp that never reached `on_finish` (a stream that
    /// failed after max attempts, or was cut by cancel / a fatal error). Java deletes the tmp
    /// of every failed or cancelled attempt (`:271`, `:288`, `:293`, `:297`), so no `.tmp`
    /// may survive a run on either engine. Returns how many were removed.
    pub fn cleanup_partials(&self) -> usize {
        let leftovers: Vec<(u64, Arc<Mutex<OpenTmp>>)> = match self.open.lock() {
            Ok(mut open) => open.drain().collect(),
            Err(_) => return 0,
        };
        let mut removed = 0;
        for (id, open) in leftovers {
            drop(open);
            if let Some(entry) = self.entries.get(id as usize) {
                if fs::remove_file(tmp_path(&self.install_dir, &entry.rel_path)).is_ok() {
                    removed += 1;
                }
            }
        }
        removed
    }
}

impl FetchSink for AmazonSink {
    fn process(&self, item: &FetchItem, body: Vec<u8>) -> Result<u64, SinkError> {
        if self.cancel.load(Ordering::Relaxed) {
            return Err(SinkError::Fatal("cancelled".to_string()));
        }
        let entry = self.entry(item)?;
        self.commit(entry, &body)
    }

    fn on_chunk(&self, item: &FetchItem, offset: u64, data: &[u8]) -> Result<(), SinkError> {
        if self.cancel.load(Ordering::Relaxed) {
            // Java `:342-344`: cancel inside the read loop → tmp deleted (cleanup_partials).
            return Err(SinkError::Fatal("cancelled".to_string()));
        }
        let entry = self.entry(item)?;
        let tmp = tmp_path(&self.install_dir, &entry.rel_path);
        let open = if offset == 0 {
            // (Re)start of an attempt: Java opens a fresh FileOutputStream (truncating).
            self.ensure_parent(entry);
            let file = File::create(&tmp).map_err(|err| {
                let _ = fs::remove_file(&tmp);
                SinkError::Retry(format!("create {}: {err}", tmp.display()))
            })?;
            let open = Arc::new(Mutex::new(OpenTmp {
                file,
                hasher: Sha256::new(),
                written: 0,
            }));
            match self.open.lock() {
                Ok(mut map) => {
                    map.insert(item.id, Arc::clone(&open));
                }
                Err(_) => return Err(SinkError::Fatal("sink map poisoned".to_string())),
            }
            open
        } else {
            let found = match self.open.lock() {
                Ok(map) => map.get(&item.id).cloned(),
                Err(_) => return Err(SinkError::Fatal("sink map poisoned".to_string())),
            };
            match found {
                Some(open) => open,
                None => {
                    return Err(SinkError::Retry(format!(
                        "piece at offset {offset} without a stream start for: {}",
                        entry.rel_path
                    )))
                }
            }
        };
        let Ok(mut state) = open.lock() else {
            return Err(SinkError::Fatal("sink item poisoned".to_string()));
        };
        if state.written != offset {
            let _ = fs::remove_file(&tmp);
            return Err(SinkError::Retry(format!(
                "piece out of order ({} != {offset}) for: {}",
                state.written, entry.rel_path
            )));
        }
        if let Err(err) = state.file.write_all(data) {
            // Java: IOException inside downloadFile → tmp deleted → next attempt.
            let _ = fs::remove_file(&tmp);
            return Err(SinkError::Retry(format!("write {}: {err}", tmp.display())));
        }
        state.hasher.update(data);
        state.written = state.written.saturating_add(data.len() as u64);
        Ok(())
    }

    fn on_finish(&self, item: &FetchItem, total_len: u64) -> Result<u64, SinkError> {
        let entry = self.entry(item)?;
        let tmp = tmp_path(&self.install_dir, &entry.rel_path);
        let taken = match self.open.lock() {
            Ok(mut map) => map.remove(&item.id),
            Err(_) => return Err(SinkError::Fatal("sink map poisoned".to_string())),
        };
        let (written, digest) = match taken {
            Some(open) => {
                let Ok(mut state) = open.lock() else {
                    return Err(SinkError::Fatal("sink item poisoned".to_string()));
                };
                if let Err(err) = state.file.flush() {
                    let _ = fs::remove_file(&tmp);
                    return Err(SinkError::Retry(format!("flush {}: {err}", tmp.display())));
                }
                let written = state.written;
                let digest = std::mem::replace(&mut state.hasher, Sha256::new()).finalize();
                (written, digest)
            }
            None => {
                // Zero-byte body: no piece ever arrived. Java would have created an empty tmp.
                if total_len != 0 {
                    return Err(SinkError::Retry(format!(
                        "finish without a stream for: {}",
                        entry.rel_path
                    )));
                }
                self.ensure_parent(entry);
                if let Err(err) = File::create(&tmp) {
                    return Err(SinkError::Retry(format!("create {}: {err}", tmp.display())));
                }
                (0, Sha256::new().finalize())
            }
        };
        if written != total_len {
            let _ = fs::remove_file(&tmp);
            return Err(SinkError::Retry(format!(
                "stream length {written} != {total_len} for: {}",
                entry.rel_path
            )));
        }
        // A response without Content-Length can end early with total_len == written;
        // the manifest size is the authoritative completeness check.
        // Unconditional: a zero-sized manifest entry must not commit unexpected bytes
        // either (written 0 == size 0 is the only acceptable zero case).
        if written != entry.size {
            let _ = fs::remove_file(&tmp);
            return Err(SinkError::Retry(format!(
                "truncated body {written} != manifest size {} for: {}",
                entry.size, entry.rel_path
            )));
        }
        self.verify(entry, digest.as_slice())?;
        self.rename_into_place(entry)?;
        Ok(0)
    }
}

/// Outcome of one `run_download`.
#[derive(Clone, Debug, Default)]
pub struct RunResult {
    pub success: bool,
    pub cancelled: bool,
    pub error: String,
    /// Bytes fetched by this run (skipped files excluded; failed attempts' pieces included,
    /// like Java's `totalDownloaded`).
    pub bytes_written: u64,
    /// Files committed by this run plus files skipped by the resume check.
    pub files_done: u64,
    pub files_total: u64,
}

/// Throughput bookkeeping for the final summary line (avg over the run, peak over ≥500 ms
/// progress intervals — the same sampling window the Java speed label uses).
struct SpeedMeter {
    started: Instant,
    last_at: Instant,
    last_bytes: u64,
    peak_bps: f64,
}

impl SpeedMeter {
    fn new() -> Self {
        let now = Instant::now();
        Self {
            started: now,
            last_at: now,
            last_bytes: 0,
            peak_bps: 0.0,
        }
    }

    fn observe(&mut self, bytes: u64) {
        let now = Instant::now();
        let dt = now.duration_since(self.last_at).as_secs_f64();
        if dt >= 0.5 {
            let bps = bytes.saturating_sub(self.last_bytes) as f64 / dt;
            if bps > self.peak_bps {
                self.peak_bps = bps;
            }
            self.last_at = now;
            self.last_bytes = bytes;
        }
    }

    fn summary(&self, bytes: u64) -> (f64, f64, f64) {
        let elapsed = self.started.elapsed().as_secs_f64().max(0.001);
        let avg = bytes as f64 / elapsed;
        (elapsed, avg * 8.0 / 1_000_000.0, self.peak_bps * 8.0 / 1_000_000.0)
    }
}

/// Blocking: plan → stream-fetch → verify/rename, calling `progress(bytes_done, bytes_total,
/// files_done, files_total)` after each written piece and each finished file (skipped files
/// are pre-credited) and `log` for every diagnostic line. `bytes_done` includes skipped bytes,
/// matching Java's `totalDownloaded` (which credits skipped files at `:251`).
pub fn run_download(
    plan_json: &str,
    install_dir: &str,
    ca_bundle_path: &str,
    max_workers: usize,
    process_workers: usize,
    cancel: Arc<AtomicBool>,
    progress: &(dyn Fn(u64, u64, u64, u64) + Sync),
    log: &(dyn Fn(&str) + Sync),
) -> RunResult {
    let max_workers = if max_workers == 0 { MAX_PARALLEL } else { max_workers };
    let process_workers = process_workers.max(1);
    let entries = match parse_plan(plan_json) {
        Ok(entries) => entries,
        Err(err) => {
            log(&format!("engine=rust plan error: {err}"));
            return RunResult {
                error: err,
                ..RunResult::default()
            };
        }
    };
    let plan = build_plan(entries, install_dir);
    let files_total = plan.entries.len() as u64;
    let fetch_bytes: u64 = plan.items.iter().map(|item| item.reserve).sum();
    let per_host_cap = per_host_cap_for(max_workers, plan.hosts.len());
    log(&format!(
        "engine=rust mode=stream plan={} files skip={} ({} bytes) fetch={} ({} bytes) hosts={} workers={} per_host_cap={} process={} dir={}",
        files_total,
        plan.skipped_files,
        plan.skipped_bytes,
        plan.items.len(),
        fetch_bytes,
        plan.hosts.len(),
        max_workers,
        per_host_cap,
        process_workers,
        install_dir
    ));
    progress(plan.skipped_bytes, plan.total_bytes, plan.skipped_files, files_total);
    if plan.items.is_empty() {
        log("summary bytes=0 elapsed=0.000 avg_mbps=0.0 peak_mbps=0.0 files=all-present");
        return RunResult {
            success: true,
            files_done: plan.skipped_files,
            files_total,
            ..RunResult::default()
        };
    }
    if cancel.load(Ordering::Relaxed) {
        return RunResult {
            cancelled: true,
            error: "cancelled".to_string(),
            files_done: plan.skipped_files,
            files_total,
            ..RunResult::default()
        };
    }

    let opts = FetchOptions {
        max_workers,
        // Every Amazon file comes from the one signed CDN base: give that host the whole
        // ceiling (`per_host_cap_for`), or the core would clamp the window to hosts × cap.
        per_host_cap,
        timeout: REQUEST_TIMEOUT,
        headers: vec![("User-Agent".to_string(), DOWNLOAD_USER_AGENT.to_string())],
        ca_bundle_path: ca_bundle_path.to_string(),
        process_workers,
        label: LABEL.to_string(),
        // Whole files, streamed into the tmp as they arrive: 8 wide like Java's pool, memory
        // bounded to the pieces not yet written.
        stream: true,
        ..FetchOptions::default()
    };
    let sink = AmazonSink::new(install_dir, plan.entries.clone(), Arc::clone(&cancel));
    let meter = Mutex::new(SpeedMeter::new());
    let skipped_bytes = plan.skipped_bytes;
    let skipped_files = plan.skipped_files;
    let total_bytes = plan.total_bytes;
    let progress_cb = |credited: u64, items_ok: u64| {
        if let Ok(mut meter) = meter.lock() {
            meter.observe(credited);
        }
        progress(
            skipped_bytes.saturating_add(credited),
            total_bytes,
            skipped_files.saturating_add(items_ok),
            files_total,
        );
    };
    let outcome = run_fetch(
        plan.items,
        &plan.hosts,
        &opts,
        &sink,
        cancel.as_ref(),
        &progress_cb,
        log,
    );
    let leftovers = sink.cleanup_partials();
    let (elapsed, avg_mbps, peak_mbps) = meter
        .lock()
        .map(|meter| meter.summary(outcome.bytes_credited))
        .unwrap_or((0.0, 0.0, 0.0));
    let files_done = skipped_files.saturating_add(sink.files_done());
    log(&format!(
        "summary bytes={} elapsed={:.3} avg_mbps={:.1} peak_mbps={:.1} files={}/{} items_ok={} tmp_removed={} cancelled={} error={}",
        outcome.bytes_credited,
        elapsed,
        avg_mbps,
        peak_mbps,
        files_done,
        files_total,
        outcome.items_ok,
        leftovers,
        outcome.cancelled,
        outcome.error.as_deref().unwrap_or("")
    ));
    let cancelled = outcome.cancelled || cancel.load(Ordering::Relaxed);
    let error = if cancelled {
        outcome.error.unwrap_or_else(|| "cancelled".to_string())
    } else {
        outcome.error.unwrap_or_default()
    };
    RunResult {
        success: !cancelled && error.is_empty(),
        cancelled,
        error,
        bytes_written: outcome.bytes_credited,
        files_done,
        files_total,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::AtomicUsize;

    static COUNTER: AtomicUsize = AtomicUsize::new(0);

    fn scratch_dir() -> String {
        let n = COUNTER.fetch_add(1, Ordering::Relaxed);
        let dir = std::env::temp_dir().join(format!(
            "bl-amazon-test-{}-{}",
            std::process::id(),
            n
        ));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        dir.to_string_lossy().into_owned()
    }

    fn sha_hex(data: &[u8]) -> String {
        crate::cdn_client::hex_encode(Sha256::digest(data).as_slice())
    }

    fn item_for(entry: &PlanEntry, id: u64) -> FetchItem {
        FetchItem {
            id,
            urls: vec![entry.url.clone()],
            reserve: entry.size,
            range: None,
        }
    }

    const PLAN: &str = r#"[
        {"relPath":"Binaries\\Win64\\Game.exe","url":"https://d1.cdn.example/base/files/aa?Sig=1","size":4,"sha256hex":"9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"},
        {"relPath":"data/level1.pak","url":"https://d1.cdn.example/base/files/bb?Sig=1","size":2,"sha256hex":""},
        {"relPath":"readme.txt","url":"https://d2.cdn.example/base/files/cc?Sig=1","size":0,"sha256hex":""}
    ]"#;

    #[test]
    fn parses_plan_and_normalises_paths() {
        let entries = parse_plan(PLAN).unwrap();
        assert_eq!(entries.len(), 3);
        assert_eq!(entries[0].rel_path, "Binaries/Win64/Game.exe");
        assert_eq!(entries[0].size, 4);
        assert_eq!(entries[0].sha256.len(), 32);
        assert!(entries[1].sha256.is_empty());
        assert_eq!(entries[2].size, 0);
    }

    #[test]
    fn rejects_bad_plan() {
        assert!(parse_plan("{}").is_err());
        assert!(parse_plan(r#"[{"url":"x","size":1}]"#).is_err());
        assert!(parse_plan(r#"[{"relPath":"a","size":1}]"#).is_err());
        assert!(parse_plan(r#"[{"relPath":"a","url":"u","size":1,"sha256hex":"zz"}]"#).is_err());
    }

    #[test]
    fn hex_roundtrip() {
        assert_eq!(hex_decode("00ff10"), Some(vec![0x00, 0xff, 0x10]));
        assert_eq!(hex_decode("00FF"), Some(vec![0x00, 0xff]));
        assert_eq!(hex_decode("0"), None);
        assert_eq!(hex_decode("0g"), None);
    }

    #[test]
    fn per_host_cap_gives_single_host_the_whole_ceiling() {
        assert_eq!(per_host_cap_for(32, 1), 32);
        assert_eq!(per_host_cap_for(32, 3), 11);
        assert_eq!(per_host_cap_for(8, 1), 8);
        assert_eq!(per_host_cap_for(4, 1), 6);
        assert_eq!(per_host_cap_for(0, 0), 6);
    }

    #[test]
    fn host_key_strips_path_and_query() {
        assert_eq!(
            host_key("https://d1.cdn.example/base/files/aa?Sig=1"),
            "https://d1.cdn.example"
        );
        assert_eq!(host_key("http://h:8080/x"), "http://h:8080");
        assert_eq!(host_key("nohost"), "https://nohost");
    }

    #[test]
    fn dest_path_never_escapes_install_dir() {
        assert_eq!(
            dest_path("/inst/", "a/b.txt"),
            PathBuf::from("/inst/a/b.txt")
        );
        assert_eq!(dest_path("/inst", "/abs.txt"), PathBuf::from("/inst//abs.txt"));
        assert_eq!(tmp_path("/inst", "a.bin"), PathBuf::from("/inst/a.bin.tmp"));
    }

    #[test]
    fn build_plan_skips_only_size_matched_files() {
        let dir = scratch_dir();
        let entries = parse_plan(PLAN).unwrap();
        // Entry 1 present with the right size → skipped (no hash check even if wrong bytes).
        fs::create_dir_all(format!("{dir}/data")).unwrap();
        fs::write(format!("{dir}/data/level1.pak"), b"zz").unwrap();
        // Entry 0 present with the wrong size → fetched.
        fs::create_dir_all(format!("{dir}/Binaries/Win64")).unwrap();
        fs::write(format!("{dir}/Binaries/Win64/Game.exe"), b"tes").unwrap();
        let plan = build_plan(entries, &dir);
        assert_eq!(plan.total_bytes, 6);
        assert_eq!(plan.skipped_files, 1);
        assert_eq!(plan.skipped_bytes, 2);
        assert_eq!(plan.items.len(), 2);
        assert_eq!(plan.items[0].id, 0);
        assert_eq!(plan.items[0].reserve, 4);
        assert!(plan.items[0].range.is_none());
        assert_eq!(plan.items[1].id, 2);
        assert_eq!(
            plan.hosts,
            vec!["https://d1.cdn.example".to_string(), "https://d2.cdn.example".to_string()]
        );
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn zero_size_missing_file_is_fetched_and_present_zero_is_skipped() {
        let dir = scratch_dir();
        let entry = PlanEntry {
            rel_path: "empty.txt".into(),
            url: "https://h/x".into(),
            size: 0,
            sha256: Vec::new(),
        };
        assert!(!is_present_and_complete(&dir, &entry));
        fs::write(format!("{dir}/empty.txt"), b"").unwrap();
        assert!(is_present_and_complete(&dir, &entry));
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn whole_body_sink_verifies_writes_and_renames() {
        let dir = scratch_dir();
        let body = b"test".to_vec();
        let entry = PlanEntry {
            rel_path: "sub/Game.exe".into(),
            url: "https://h/x".into(),
            size: 4,
            sha256: hex_decode(&sha_hex(&body)).unwrap(),
        };
        let cancel = Arc::new(AtomicBool::new(false));
        let sink = AmazonSink::new(&dir, vec![entry.clone()], Arc::clone(&cancel));
        let item = item_for(&entry, 0);
        // Mismatch → Retry, nothing on disk.
        match sink.process(&item, b"nope".to_vec()) {
            Err(SinkError::Retry(msg)) => assert!(msg.contains("SHA-256 mismatch")),
            Err(SinkError::Fatal(msg)) => panic!("expected Retry, got Fatal({msg})"),
            Ok(_) => panic!("expected Retry, got Ok"),
        }
        assert!(!dest_path(&dir, "sub/Game.exe").exists());
        assert!(!tmp_path(&dir, "sub/Game.exe").exists());
        // Match → written, renamed, tmp gone, credited body length.
        assert_eq!(sink.process(&item, body.clone()).unwrap(), 4);
        assert_eq!(fs::read(dest_path(&dir, "sub/Game.exe")).unwrap(), body);
        assert!(!tmp_path(&dir, "sub/Game.exe").exists());
        assert_eq!(sink.files_done(), 1);
        // Existing dest is replaced (Java `:280`).
        assert_eq!(sink.process(&item, body.clone()).unwrap(), 4);
        // Cancel → Fatal, no write.
        cancel.store(true, Ordering::Relaxed);
        assert!(matches!(
            sink.process(&item, body),
            Err(SinkError::Fatal(_))
        ));
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn stream_sink_appends_verifies_and_renames() {
        let dir = scratch_dir();
        let body = b"hello world".to_vec();
        let entry = PlanEntry {
            rel_path: "sub/big.bin".into(),
            url: "https://h/x".into(),
            size: body.len() as u64,
            sha256: hex_decode(&sha_hex(&body)).unwrap(),
        };
        let sink = AmazonSink::new(&dir, vec![entry.clone()], Arc::new(AtomicBool::new(false)));
        let item = item_for(&entry, 0);
        sink.on_chunk(&item, 0, b"hello").unwrap();
        assert!(tmp_path(&dir, "sub/big.bin").exists());
        sink.on_chunk(&item, 5, b" wor").unwrap();
        sink.on_chunk(&item, 9, b"ld").unwrap();
        assert_eq!(sink.on_finish(&item, 11).unwrap(), 0);
        assert_eq!(fs::read(dest_path(&dir, "sub/big.bin")).unwrap(), body);
        assert!(!tmp_path(&dir, "sub/big.bin").exists());
        assert_eq!(sink.files_done(), 1);
        assert_eq!(sink.cleanup_partials(), 0);
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn stream_sink_restart_truncates_and_mismatch_retries() {
        let dir = scratch_dir();
        let body = b"abcdef".to_vec();
        let entry = PlanEntry {
            rel_path: "f.bin".into(),
            url: "https://h/x".into(),
            size: 6,
            sha256: hex_decode(&sha_hex(&body)).unwrap(),
        };
        let sink = AmazonSink::new(&dir, vec![entry.clone()], Arc::new(AtomicBool::new(false)));
        let item = item_for(&entry, 0);
        // Attempt 1 delivers garbage, then the core restarts at offset 0.
        sink.on_chunk(&item, 0, b"zzzz").unwrap();
        sink.on_chunk(&item, 0, b"abc").unwrap();
        sink.on_chunk(&item, 3, b"xyz").unwrap();
        match sink.on_finish(&item, 6) {
            Err(SinkError::Retry(msg)) => assert!(msg.contains("SHA-256 mismatch")),
            Err(SinkError::Fatal(msg)) => panic!("expected Retry, got Fatal({msg})"),
            Ok(_) => panic!("expected Retry, got Ok"),
        }
        assert!(!tmp_path(&dir, "f.bin").exists());
        assert!(!dest_path(&dir, "f.bin").exists());
        // Out-of-order piece → Retry.
        sink.on_chunk(&item, 0, b"abc").unwrap();
        assert!(matches!(
            sink.on_chunk(&item, 5, b"f"),
            Err(SinkError::Retry(_))
        ));
        // Length mismatch at finish → Retry.
        sink.on_chunk(&item, 0, b"abc").unwrap();
        assert!(matches!(sink.on_finish(&item, 6), Err(SinkError::Retry(_))));
        // Piece without a start → Retry.
        assert!(matches!(
            sink.on_chunk(&item, 3, b"def"),
            Err(SinkError::Retry(_))
        ));
        // Good attempt.
        sink.on_chunk(&item, 0, b"abc").unwrap();
        sink.on_chunk(&item, 3, b"def").unwrap();
        assert_eq!(sink.on_finish(&item, 6).unwrap(), 0);
        assert_eq!(fs::read(dest_path(&dir, "f.bin")).unwrap(), body);
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn stream_sink_cleanup_removes_unfinished_tmp_and_zero_len_finishes() {
        let dir = scratch_dir();
        let big = PlanEntry {
            rel_path: "cut.bin".into(),
            url: "https://h/x".into(),
            size: 10,
            sha256: Vec::new(),
        };
        let empty = PlanEntry {
            rel_path: "empty.bin".into(),
            url: "https://h/y".into(),
            size: 0,
            sha256: Vec::new(),
        };
        let cancel = Arc::new(AtomicBool::new(false));
        let sink = AmazonSink::new(&dir, vec![big.clone(), empty.clone()], Arc::clone(&cancel));
        sink.on_chunk(&item_for(&big, 0), 0, b"12345").unwrap();
        assert!(tmp_path(&dir, "cut.bin").exists());
        // Zero-byte body: on_finish with no pieces creates and commits an empty file.
        assert_eq!(sink.on_finish(&item_for(&empty, 1), 0).unwrap(), 0);
        assert_eq!(fs::read(dest_path(&dir, "empty.bin")).unwrap(), b"");
        // Cancel mid-stream → Fatal; cleanup deletes the partial tmp like Java does.
        cancel.store(true, Ordering::Relaxed);
        assert!(matches!(
            sink.on_chunk(&item_for(&big, 0), 5, b"6"),
            Err(SinkError::Fatal(_))
        ));
        assert_eq!(sink.cleanup_partials(), 1);
        assert!(!tmp_path(&dir, "cut.bin").exists());
        assert!(!dest_path(&dir, "cut.bin").exists());
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn stream_sink_skips_hash_when_manifest_has_none() {
        let dir = scratch_dir();
        let entry = PlanEntry {
            rel_path: "a.bin".into(),
            url: "https://h/x".into(),
            size: 3,
            sha256: Vec::new(),
        };
        let sink = AmazonSink::new(&dir, vec![entry.clone()], Arc::new(AtomicBool::new(false)));
        let item = item_for(&entry, 0);
        sink.on_chunk(&item, 0, b"abc").unwrap();
        assert_eq!(sink.on_finish(&item, 3).unwrap(), 0);
        assert_eq!(fs::read(dest_path(&dir, "a.bin")).unwrap(), b"abc");
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn run_download_all_present_short_circuits() {
        let dir = scratch_dir();
        fs::write(format!("{dir}/a.bin"), b"abc").unwrap();
        let plan = r#"[{"relPath":"a.bin","url":"https://h/x","size":3,"sha256hex":""}]"#;
        let seen = Mutex::new(Vec::new());
        let logs = Mutex::new(Vec::new());
        let result = run_download(
            plan,
            &dir,
            "",
            0,
            1,
            Arc::new(AtomicBool::new(false)),
            &|done, total, fd, ft| seen.lock().unwrap().push((done, total, fd, ft)),
            &|line| logs.lock().unwrap().push(line.to_string()),
        );
        assert!(result.success);
        assert_eq!(result.files_done, 1);
        assert_eq!(result.files_total, 1);
        assert_eq!(seen.lock().unwrap().as_slice(), &[(3, 3, 1, 1)]);
        assert!(logs.lock().unwrap()[0].starts_with("engine=rust mode=stream"));
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn run_download_reports_plan_error() {
        let result = run_download(
            "not json",
            "/nonexistent",
            "",
            8,
            2,
            Arc::new(AtomicBool::new(false)),
            &|_, _, _, _| {},
            &|_| {},
        );
        assert!(!result.success);
        assert!(result.error.contains("plan json"));
    }
}
