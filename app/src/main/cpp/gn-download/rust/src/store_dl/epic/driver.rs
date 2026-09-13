//! Plan → `FetchItem`s → `crate::fetch_core::run_fetch` with a sink that fills the chunk cache.
//!
//! This is the replacement for the body of the Java pool block in `EpicDownloadManager.install`
//! ("Download unique chunks — 8 parallel threads"). Inputs are exactly what that block sees:
//! the parsed manifest (re-parsed here from the same bytes), the pending file set (indices Java
//! computed after its delta/verify pass), the CDN prefixes (`baseUrl + cloudDir`, cloudflare
//! already skipped) and the chunk cache dir. Output = `<installDir>/.chunks/<GUID>` for every
//! needed chunk; Java assembles the files afterwards exactly as before.

use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Mutex;
use std::time::{Duration, Instant};

use sha1::{Digest, Sha1};

use crate::fetch_core::{run_fetch, FetchItem, FetchOptions, FetchSink, SinkError};

use super::manifest::{parse_manifest, Manifest};
use super::plan::{
    cached_chunk_path, chunk_cache_dir, chunk_url, distinct_prefixes, per_host_cap,
    resolve_cached_chunk_path, resolve_cached_name, total_credit_bytes, unique_chunks_for_files,
};

/// Java `conn.setReadTimeout(60000)` — the longer of the two Java timeouts (connect was 30 s).
pub const REQUEST_TIMEOUT: Duration = Duration::from_secs(60);

/// Everything the JNI layer hands over for one run.
pub struct EpicRequest {
    pub manifest_bytes: Vec<u8>,
    pub install_dir: String,
    /// `cdn.baseUrl + cdn.cloudDir` per manifest-API CDN entry, in Java's order.
    pub cdn_prefixes: Vec<String>,
    /// Indices into `manifest.files` of Java's `pendingFiles` (post delta/verify), in order.
    pub pending_file_indices: Vec<usize>,
    /// Java's `neededChunks.size()` / `totalBytes` for the plan cross-check (`None` = skip).
    pub expected_chunks: Option<u64>,
    pub expected_bytes: Option<u64>,
    pub ca_bundle_path: String,
    /// Window ceiling: the Steam speed tier's network window (Fast = 32) since improvements
    /// round 1; the Java fallback pool keeps its fixed 8 (`super::JAVA_POOL_THREADS`).
    pub max_workers: usize,
    pub process_workers: usize,
    pub label: String,
}

/// The chunk plan for one run.
#[derive(Debug)]
pub struct EpicPlan {
    pub manifest: Manifest,
    pub cache_dir: PathBuf,
    /// Chunk indices (into `manifest.unique_chunks`) in Java's submission order.
    pub needed: Vec<usize>,
    /// `Σ max(fileSize, 1)` over `needed` (compressed) — kept for the plan log and the Java
    /// `expected_bytes` cross-check.
    pub total_compressed: u64,
    /// `Σ FileInfo.file_size()` over `pending_file_indices` — the progress TOTAL (uncompressed
    /// installed bytes; assembly afterwards tops up shared-chunk copies to reach it).
    pub total_bytes: u64,
    /// Distinct CDN prefixes = fetch-core host keys.
    pub hosts: Vec<String>,
}

/// Terminal result of a run, in the shape Java's pool block needs to reproduce its own exit
/// paths (`CANCELLED during chunk download (n/total chunks)`, `N chunks failed`, `chunksOK=`).
#[derive(Clone, Debug, Default)]
pub struct EpicOutcome {
    pub success: bool,
    pub cancelled: bool,
    pub error: String,
    /// Credited DECOMPRESSED bytes: skipped-cached (cache-file length) + fetched (inflated size).
    pub bytes_credited: u64,
    /// Chunks accounted for (skipped-cached + fetched), Java's `completedCount`.
    pub chunks_done: u64,
    pub chunks_total: u64,
    pub bytes_total: u64,
    /// Decompressed bytes actually written to the cache this run.
    pub decompressed_written: u64,
}

/// Parse + plan (no I/O beyond reading the cache dir). `Err` = "engine could not start"; Java
/// then runs its own pool, since nothing has been fetched yet.
pub fn build_plan(req: &EpicRequest) -> Result<EpicPlan, String> {
    let manifest = parse_manifest(&req.manifest_bytes).map_err(|e| format!("plan: {e}"))?;
    for &i in &req.pending_file_indices {
        if i >= manifest.files.len() {
            return Err(format!(
                "plan: pending file index {i} out of range ({} files)",
                manifest.files.len()
            ));
        }
    }
    let needed = unique_chunks_for_files(&manifest, &req.pending_file_indices);
    let total_compressed = total_credit_bytes(&manifest, &needed);
    let total_bytes: u64 = req
        .pending_file_indices
        .iter()
        .map(|&i| manifest.files[i].file_size())
        .sum();
    if let Some(expected) = req.expected_chunks {
        if expected != needed.len() as u64 {
            return Err(format!(
                "plan: chunk count mismatch java={expected} rust={}",
                needed.len()
            ));
        }
    }
    if let Some(expected) = req.expected_bytes {
        if expected != total_compressed {
            return Err(format!(
                "plan: byte total mismatch java={expected} rust={total_compressed}"
            ));
        }
    }
    let hosts = distinct_prefixes(&req.cdn_prefixes);
    if hosts.is_empty() {
        return Err("plan: no CDN prefixes".to_string());
    }
    let cache_dir = chunk_cache_dir(&req.install_dir);
    std::fs::create_dir_all(&cache_dir)
        .map_err(|e| format!("plan: mkdirs {}: {e}", cache_dir.display()))?;
    Ok(EpicPlan {
        manifest,
        cache_dir,
        needed,
        total_compressed,
        total_bytes,
        hosts,
    })
}

/// Sink: one downloaded body → verified cache file. `id` indexes `fetch_chunks`.
struct ChunkCacheSink<'a> {
    plan: &'a EpicPlan,
    /// Chunk indices (into `manifest.unique_chunks`) of the items actually being fetched.
    fetch_chunks: &'a [usize],
    decompressed: AtomicU64,
}

/// Resume-skip gate: a cached chunk is only trusted when it passes every check the
/// manifest offers — decompressed size (`window_size`, when known) and SHA-1 (when
/// verifiable). Anything else is re-downloaded from the CDN instead of poisoning
/// assembly with truncated/corrupt bytes or failing with "Chunk file missing".
fn cached_chunk_valid(path: &PathBuf, chunk: &super::manifest::ChunkInfo) -> bool {
    let Ok(meta) = std::fs::metadata(path) else {
        return false;
    };
    if chunk.window_size > 0 && meta.len() != chunk.window_size as u64 {
        return false;
    }
    if let Some(expected) = chunk.verifiable_sha1() {
        let Ok(mut file) = std::fs::File::open(path) else {
            return false;
        };
        let mut sha = Sha1::new();
        if std::io::copy(&mut file, &mut sha).is_err() {
            return false;
        }
        return sha.finalize().as_slice() == &expected[..];
    }
    // JSON manifests: no size reference and no hash — accept any non-empty file,
    // matching the Java pool's `cachedFile.exists()` behaviour.
    meta.len() > 0
}

impl<'a> FetchSink for ChunkCacheSink<'a> {
    fn process(&self, item: &FetchItem, body: Vec<u8>) -> Result<u64, SinkError> {
        let Some(&ci) = self.fetch_chunks.get(item.id as usize) else {
            return Err(SinkError::Fatal(format!("item id {} out of range", item.id)));
        };
        let chunk = &self.plan.manifest.unique_chunks[ci];
        let final_path = cached_chunk_path(&self.plan.cache_dir, chunk);
        // Sharded layout: the <first2>/ subdir must exist before the write.
        if let Some(parent) = final_path.parent() {
            if let Err(e) = std::fs::create_dir_all(parent) {
                return Err(SinkError::Retry(format!(
                    "{} mkdirs {}: {e}",
                    chunk.guid_str(),
                    parent.display()
                )));
            }
        }
        match super::chunk::write_verified_chunk(
            &body,
            chunk.verifiable_sha1(),
            if chunk.window_size > 0 {
                Some(chunk.window_size as u64)
            } else {
                None
            },
            &final_path,
        ) {
            // Credit the DECOMPRESSED bytes: the progress contract is "uncompressed installed
            // bytes" (Steam-style), so the app screen's total = installed size and assembly
            // afterwards only tops up shared-chunk copies.
            Ok(written) => {
                self.decompressed.fetch_add(written, Ordering::Relaxed);
                Ok(written)
            }
            // Every per-attempt failure in Java is "try the next CDN"; the core's Retry rotates
            // hosts and backs off the same way (bounded at its attempt cap).
            Err(reason) => Err(SinkError::Retry(format!("{} {reason}", chunk.guid_str()))),
        }
    }
}

/// Rolling speed sampler for the end-of-run summary: samples at ≥500 ms like the Java pool's
/// `lastSpeedMs` logic, keeps the peak.
struct SpeedMeter {
    start: Instant,
    last_at: Instant,
    last_bytes: u64,
    peak_bps: f64,
}

impl SpeedMeter {
    fn new() -> Self {
        let now = Instant::now();
        Self {
            start: now,
            last_at: now,
            last_bytes: 0,
            peak_bps: 0.0,
        }
    }

    fn sample(&mut self, bytes_now: u64) {
        let now = Instant::now();
        let dt = now.duration_since(self.last_at);
        if dt >= Duration::from_millis(500) {
            let delta = bytes_now.saturating_sub(self.last_bytes) as f64;
            let bps = delta / dt.as_secs_f64();
            if bps > self.peak_bps {
                self.peak_bps = bps;
            }
            self.last_at = now;
            self.last_bytes = bytes_now;
        }
    }

    fn summary(&self, bytes: u64, decompressed: u64, skipped: u64) -> String {
        let secs = self.start.elapsed().as_secs_f64().max(0.001);
        let avg_bps = bytes as f64 / secs;
        format!(
            "summary bytes={bytes} decompressed={decompressed} skipped_chunks={skipped} elapsed={secs:.1}s avg_mbps={:.2} peak_mbps={:.2} avg_MBps={:.2}",
            avg_bps * 8.0 / 1_000_000.0,
            self.peak_bps * 8.0 / 1_000_000.0,
            avg_bps / (1024.0 * 1024.0)
        )
    }
}

/// Run the plan. `progress(bytes_done, chunks_done)` fires once per accounted chunk (cached-skip
/// or fetched), exactly the cadence of the Java pool's per-task `progress(...)` call.
/// `assembly_progress(assembled_bytes)` fires per written file part once fetching succeeded.
/// `log` receives engine lines (`fetch-window`, `summary`, failures).
pub fn run_plan(
    plan: &EpicPlan,
    req: &EpicRequest,
    cancel: &AtomicBool,
    progress: &(dyn Fn(u64, u64) + Sync),
    assembly_progress: &(dyn Fn(u64) + Sync),
    log: &(dyn Fn(&str) + Sync),
) -> EpicOutcome {
    let chunks_total = plan.needed.len() as u64;
    let mut outcome = EpicOutcome {
        chunks_total,
        bytes_total: plan.total_bytes,
        ..EpicOutcome::default()
    };
    let host_cap = per_host_cap(req.max_workers, plan.hosts.len());
    log(&format!(
        "plan chunk_dir={} version={} files_pending={} chunks={} bytes_uncompressed={} bytes_compressed={} hosts={} workers={} per_host_cap={host_cap} process_workers={}",
        plan.manifest.chunk_dir,
        plan.manifest.version,
        req.pending_file_indices.len(),
        chunks_total,
        plan.total_bytes,
        plan.total_compressed,
        plan.hosts.len(),
        req.max_workers,
        req.process_workers
    ));

    // Java: each pool task first checks the cancel flag, then `cachedFile.exists()` → credit
    // without fetching. Account for the cached chunks up front (same credit, same callback).
    let mut fetch_chunks: Vec<usize> = Vec::with_capacity(plan.needed.len());
    let mut pre_bytes: u64 = 0;
    let mut pre_chunks: u64 = 0;
    for &ci in &plan.needed {
        if cancel.load(Ordering::Relaxed) {
            outcome.cancelled = true;
            outcome.error = "cancelled".to_string();
            outcome.bytes_credited = pre_bytes;
            outcome.chunks_done = pre_chunks;
            log(&format!(
                "cancelled before fetch ({pre_chunks}/{chunks_total} chunks)"
            ));
            return outcome;
        }
        let chunk = &plan.manifest.unique_chunks[ci];
        // Dual-read: sharded first, legacy flat (pre-sharding builds) second, so resuming
        // an old partial cache does not refetch everything.
        let cached = resolve_cached_chunk_path(&plan.cache_dir, chunk);
        if cached.exists() && cached_chunk_valid(&cached, chunk) {
            // Credit the DECOMPRESSED size: the cache file holds the inflated chunk, so its
            // length is exactly that (Java credited the compressed `max(fileSize,1)`).
            pre_bytes += std::fs::metadata(&cached).map(|m| m.len()).unwrap_or(0);
            pre_chunks += 1;
            progress(pre_bytes, pre_chunks);
        } else {
            // Missing OR stale/corrupt cache: drop it and re-fetch from the CDN
            // instead of failing assembly later with "Chunk file missing" / bad bytes.
            if cached.exists() {
                log(&format!(
                    "cached chunk {} failed validation — re-downloading",
                    chunk.guid_str()
                ));
                let _ = std::fs::remove_file(&cached);
            }
            fetch_chunks.push(ci);
        }
    }
    for (i, h) in plan.hosts.iter().enumerate() {
        log(&format!("host[{i}]={h}"));
    }
    log(&format!(
        "skip cached={pre_chunks} bytes={pre_bytes} to_fetch={}",
        fetch_chunks.len()
    ));

    if fetch_chunks.is_empty() {
        outcome.success = true;
        outcome.bytes_credited = pre_bytes;
        outcome.chunks_done = pre_chunks;
        log("summary bytes=0 decompressed=0 elapsed=0.0s avg_mbps=0.00 peak_mbps=0.00 (nothing to fetch)");
        // Fall through to assembly: fully cached still needs files written.
        return finish_with_assembly(plan, req, cancel, assembly_progress, log, outcome);
    }

    let items: Vec<FetchItem> = fetch_chunks
        .iter()
        .enumerate()
        .map(|(id, &ci)| {
            let chunk = &plan.manifest.unique_chunks[ci];
            FetchItem {
                id: id as u64,
                urls: plan
                    .hosts
                    .iter()
                    .map(|h| chunk_url(h, &plan.manifest.chunk_dir, chunk))
                    .collect(),
                reserve: if chunk.file_size > 0 {
                    chunk.file_size as u64
                } else {
                    0
                },
                range: None,
            }
        })
        .collect();

    let opts = FetchOptions {
        max_workers: req.max_workers.max(1),
        // Improvements round 1: the tier ceiling split across the distinct CDNs (floor 6), so
        // the whole window is reachable on 1 host or on all 3.
        per_host_cap: host_cap,
        timeout: REQUEST_TIMEOUT,
        headers: vec![("User-Agent".to_string(), super::USER_AGENT.to_string())],
        ca_bundle_path: req.ca_bundle_path.clone(),
        process_workers: req.process_workers.max(1),
        label: req.label.clone(),
        // Whole-body mode (Epic chunks are ≤ ~1 MiB compressed; the core's byte budget scales
        // with the window); `stream` and any future field keep the core's defaults.
        ..FetchOptions::default()
    };

    let sink = ChunkCacheSink {
        plan,
        fetch_chunks: &fetch_chunks,
        decompressed: AtomicU64::new(0),
    };

    let meter = Mutex::new(SpeedMeter::new());
    let progress_adapter = |bytes: u64, items_ok: u64| {
        if let Ok(mut m) = meter.lock() {
            m.sample(bytes);
        }
        progress(pre_bytes + bytes, pre_chunks + items_ok);
    };

    let fetched = run_fetch(
        items,
        &plan.hosts,
        &opts,
        &sink,
        cancel,
        &progress_adapter,
        log,
    );

    let decompressed = sink.decompressed.load(Ordering::Relaxed);
    outcome.bytes_credited = pre_bytes + fetched.bytes_credited;
    outcome.chunks_done = pre_chunks + fetched.items_ok;
    outcome.decompressed_written = decompressed;
    if let Ok(m) = meter.lock() {
        log(&m.summary(fetched.bytes_credited, decompressed, pre_chunks));
    }

    if fetched.cancelled {
        outcome.cancelled = true;
        outcome.error = "cancelled".to_string();
        log(&format!(
            "cancelled during chunk download ({}/{chunks_total} chunks)",
            outcome.chunks_done
        ));
        return outcome;
    }
    if let Some(err) = fetched.error {
        outcome.error = err;
        log(&format!(
            "FAIL {} ({}/{chunks_total} chunks ok)",
            outcome.error, outcome.chunks_done
        ));
        return outcome;
    }
    if fetched.items_ok != fetch_chunks.len() as u64 {
        outcome.error = format!(
            "fetch ended with {}/{} chunks",
            fetched.items_ok,
            fetch_chunks.len()
        );
        log(&format!("FAIL {}", outcome.error));
        return outcome;
    }
    outcome.success = true;
    log(&format!("chunksOK={}", outcome.chunks_done));
    finish_with_assembly(plan, req, cancel, assembly_progress, log, outcome)
}

/// Assembly epilogue shared by the fully-cached and freshly-fetched success paths.
fn finish_with_assembly(
    plan: &EpicPlan,
    req: &EpicRequest,
    cancel: &AtomicBool,
    assembly_progress: &(dyn Fn(u64) + Sync),
    log: &(dyn Fn(&str) + Sync),
    mut outcome: EpicOutcome,
) -> EpicOutcome {
    // ── Assembly (ported from the Kotlin `assembleFileSequential` loop) ──────
    // Writes each pending file out of the chunk cache, deleting every chunk
    // after its last consumer so peak disk stays at ~install size + remaining
    // cache instead of 2× install. Progress = assembled bytes (cumulative);
    // Kotlin credits them against the same budget as the fetch stage.
    match assemble_files(plan, req, cancel, assembly_progress, log) {
        Ok(bytes) => {
            log(&format!("assembleOK files={} bytes={bytes}", req.pending_file_indices.len()));
            // Cache files are gone (last-consumer deletion); sweep any leftover empty
            // shard dirs, then remove the cache dir when nothing else (stray .part from
            // an older run) keeps it.
            if let Ok(rd) = std::fs::read_dir(&plan.cache_dir) {
                for entry in rd.flatten() {
                    if entry.file_type().map(|t| t.is_dir()).unwrap_or(false) {
                        let _ = std::fs::remove_dir(entry.path());
                    }
                }
            }
            let _ = std::fs::remove_dir(&plan.cache_dir);
        }
        Err(err) => {
            outcome.success = false;
            if cancel.load(Ordering::Relaxed) {
                outcome.cancelled = true;
                outcome.error = "cancelled".to_string();
                log("cancelled during assembly");
            } else {
                outcome.error = format!("assembly failed: {err}");
                log(&format!("FAIL {}", outcome.error));
            }
        }
    }
    outcome
}

/// Cache file name for a part's GUID: `guidStr` = dashed-lowercase, exactly what
/// the Kotlin assembly resolved (`File(chunkCacheDir, chunkPart.guidStr)`).
fn part_cache_name(guid: &[u32; 4]) -> String {
    format!("{:08x}-{:08x}-{:08x}-{:08x}", guid[0], guid[1], guid[2], guid[3])
}

/// Assemble the pending files from the chunk cache. Mirrors the Kotlin native-path
/// assembly (`assembleFileSequential` batches of 4): sequential part writes per
/// file, `Chunk file missing: <guid>` on a cache miss, cache files deleted after
/// their last consumer. Files are assembled in Java's `pendingFiles` order across
/// `process_workers` threads. `assembly_progress(cumulative_bytes)` fires per part.
fn assemble_files(
    plan: &EpicPlan,
    req: &EpicRequest,
    cancel: &AtomicBool,
    assembly_progress: &(dyn Fn(u64) + Sync),
    log: &(dyn Fn(&str) + Sync),
) -> Result<u64, String> {
    use std::collections::HashMap;
    use std::io::Write;
    use std::os::unix::fs::FileExt;

    let install_dir = std::path::Path::new(&req.install_dir);

    // Chunk → remaining consumer count over the pending set (for last-consumer deletion).
    let mut refcounts: HashMap<String, usize> = HashMap::new();
    for &fi in &req.pending_file_indices {
        for p in &plan.manifest.files[fi].parts {
            *refcounts.entry(part_cache_name(&p.guid)).or_insert(0) += 1;
        }
    }
    let refcounts = Mutex::new(refcounts);
    let assembled = AtomicU64::new(0);
    let next = AtomicU64::new(0);
    let failure: Mutex<Option<String>> = Mutex::new(None);

    let workers = req.process_workers.max(1).min(req.pending_file_indices.len().max(1));
    std::thread::scope(|scope| {
        for _ in 0..workers {
            scope.spawn(|| {
                let mut buf = vec![0u8; 1024 * 1024];
                loop {
                    if cancel.load(Ordering::Relaxed) {
                        return;
                    }
                    if failure.lock().unwrap().is_some() {
                        return;
                    }
                    let i = next.fetch_add(1, Ordering::Relaxed) as usize;
                    if i >= req.pending_file_indices.len() {
                        return;
                    }
                    let file = &plan.manifest.files[req.pending_file_indices[i]];
                    let result = (|| -> Result<(), String> {
                        let out_path = install_dir.join(&file.filename);
                        if let Some(parent) = out_path.parent() {
                            std::fs::create_dir_all(parent)
                                .map_err(|e| format!("mkdirs {}: {e}", parent.display()))?;
                        }
                        let out = std::fs::File::create(&out_path)
                            .map_err(|e| format!("create {}: {e}", out_path.display()))?;
                        let mut writer = std::io::BufWriter::with_capacity(1024 * 1024, out);
                        for part in &file.parts {
                            if cancel.load(Ordering::Relaxed) {
                                return Ok(());
                            }
                            let cache_name = part_cache_name(&part.guid);
                            // Dual-read: sharded first, legacy flat second (see plan.rs).
                            let cache_path = resolve_cached_name(&plan.cache_dir, &cache_name);
                            let cache = std::fs::File::open(&cache_path).map_err(|_| {
                                format!("Chunk file missing: {cache_name}")
                            })?;
                            // Read the part slice (offset/length into the DECOMPRESSED
                            // chunk); a short read means a corrupt cache — fail loudly
                            // instead of writing a truncated file like the Kotlin
                            // `break-on-EOF` loop did.
                            let mut remaining = part.size.max(0) as u64;
                            let mut at = part.offset.max(0) as u64;
                            while remaining > 0 {
                                let want = remaining.min(buf.len() as u64) as usize;
                                cache
                                    .read_exact_at(&mut buf[..want], at)
                                    .map_err(|e| format!("read {cache_name}: {e}"))?;
                                writer
                                    .write_all(&buf[..want])
                                    .map_err(|e| format!("write {}: {e}", out_path.display()))?;
                                at += want as u64;
                                remaining -= want as u64;
                            }
                            writer
                                .flush()
                                .map_err(|e| format!("write {}: {e}", out_path.display()))?;
                            let done = assembled.fetch_add(part.size.max(0) as u64, Ordering::Relaxed)
                                + part.size.max(0) as u64;
                            assembly_progress(done);
                            // Last consumer drops the cache file (Kotlin streaming parity).
                            let mut refs = refcounts.lock().unwrap();
                            if let Some(count) = refs.get_mut(&cache_name) {
                                *count -= 1;
                                if *count == 0 {
                                    refs.remove(&cache_name);
                                    drop(refs);
                                    let _ = std::fs::remove_file(&cache_path);
                                    // Best-effort: drop the shard dir once it is empty
                                    // (succeeds only when this was its last chunk).
                                    if let Some(shard) = cache_path.parent() {
                                        if shard != plan.cache_dir {
                                            let _ = std::fs::remove_dir(shard);
                                        }
                                    }
                                }
                            }
                        }
                        Ok(())
                    })();
                    if let Err(err) = result {
                        log(&format!("assemble {} failed: {err}", file.filename));
                        *failure.lock().unwrap() = Some(err);
                        return;
                    }
                }
            });
        }
    });

    if cancel.load(Ordering::Relaxed) {
        return Err("cancelled".to_string());
    }
    if let Some(err) = failure.lock().unwrap().take() {
        return Err(err);
    }
    Ok(assembled.load(Ordering::Relaxed))
}

#[cfg(test)]
mod tests {
    use super::super::manifest::test_support::*;
    use super::super::plan::legacy_cached_chunk_path;
    use super::*;

    fn request(dir: &str, pending: Vec<usize>) -> EpicRequest {
        EpicRequest {
            manifest_bytes: build_manifest(&sample_chunks(), &sample_files(), 21, true),
            install_dir: dir.to_string(),
            cdn_prefixes: vec![
                "https://fastly-download.epicgames.com/Builds/o/x/default".to_string(),
                "https://download.epicgames.com/Builds/o/x/default".to_string(),
                "https://fastly-download.epicgames.com/Builds/o/x/default".to_string(),
            ],
            pending_file_indices: pending,
            expected_chunks: None,
            expected_bytes: None,
            ca_bundle_path: String::new(),
            max_workers: super::super::JAVA_POOL_THREADS,
            process_workers: 2,
            label: "epic test".to_string(),
        }
    }

    #[test]
    fn plan_matches_java_for_the_pending_set() {
        let dir = super::super::chunk::test_support::temp_dir("plan");
        let req = request(dir.to_str().unwrap(), vec![0, 1]);
        let plan = build_plan(&req).unwrap();
        assert_eq!(plan.needed, vec![0, 1]);
        // Progress total = uncompressed installed bytes of the pending files:
        // file0 = 1_048_576 + 4000, file1 = 4096.
        assert_eq!(plan.total_bytes, 1_056_672);
        assert_eq!(plan.total_compressed, 700_000 + 1, "Java credit total kept for cross-check");
        assert_eq!(plan.hosts.len(), 2, "duplicate CDN prefix collapsed");
        assert!(plan.cache_dir.ends_with(".chunks"));
        assert!(plan.cache_dir.is_dir());
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn plan_cross_check_rejects_mismatches() {
        let dir = super::super::chunk::test_support::temp_dir("xcheck");
        let mut req = request(dir.to_str().unwrap(), vec![0, 1]);
        req.expected_chunks = Some(3);
        assert!(build_plan(&req).unwrap_err().contains("chunk count mismatch"));
        req.expected_chunks = Some(2);
        req.expected_bytes = Some(5);
        assert!(build_plan(&req).unwrap_err().contains("byte total mismatch"));
        req.expected_bytes = Some(700_001);
        assert!(build_plan(&req).is_ok());
        req.pending_file_indices = vec![7];
        assert!(build_plan(&req).unwrap_err().contains("out of range"));
        req.pending_file_indices = vec![0];
        req.cdn_prefixes.clear();
        assert!(build_plan(&req).unwrap_err().contains("no CDN"));
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn fully_cached_plan_completes_without_fetching() {
        // Manifest whose chunk carries the REAL sha1/window of our test data, so the
        // cache validation gate can be exercised both ways.
        let data = vec![7u8; 6000];
        let body = super::super::chunk::test_support::build_chunk_body(&data, true, 0, None);
        let sha1 = super::super::chunk::test_support::sha1_of(&data);
        let chunks = vec![TestChunk {
            guid: [0xAAAA_0001, 0xAAAA_0002, 0xAAAA_0003, 0xAAAA_0004],
            hash: 0x0123_4567,
            sha1,
            group: 5,
            window: data.len() as i32,
            file_size: body.len() as u64,
        }];
        let files = vec![TestFile {
            name: "Game/f.bin".to_string(),
            sha1: [0x51; 20],
            tags: vec![],
            parts: vec![(chunks[0].guid, 0, data.len() as i32)],
        }];
        let manifest_bytes = build_manifest(&chunks, &files, 21, true);

        let dir = super::super::chunk::test_support::temp_dir("cached");
        let mut req = request(dir.to_str().unwrap(), vec![0]);
        req.manifest_bytes = manifest_bytes;
        let plan = build_plan(&req).unwrap();
        // Populate the cache with a genuinely valid chunk (size + SHA-1 verified),
        // in the sharded layout the fetch sink writes to.
        let cache_path = cached_chunk_path(&plan.cache_dir, &plan.manifest.unique_chunks[0]);
        std::fs::create_dir_all(cache_path.parent().unwrap()).unwrap();
        let n = super::super::chunk::write_verified_chunk(
            &body,
            Some(&sha1),
            Some(data.len() as u64),
            &cache_path,
        )
        .unwrap();
        assert_eq!(n, data.len() as u64);

        let cancel = AtomicBool::new(false);
        let calls = Mutex::new(Vec::new());
        let asm = Mutex::new(Vec::new());
        let progress = |b: u64, c: u64| calls.lock().unwrap().push((b, c));
        let assembly_progress = |b: u64| asm.lock().unwrap().push(b);
        let log = |_: &str| {};
        let out = run_plan(&plan, &req, &cancel, &progress, &assembly_progress, &log);
        assert!(out.success);
        assert_eq!(out.chunks_done, 1);
        // Cached credit = cache-file length (decompressed bytes).
        assert_eq!(out.bytes_credited, data.len() as u64);
        assert_eq!(*calls.lock().unwrap(), vec![(data.len() as u64, 1)]);
        // Assembly ran in-engine: file written, cache chunk consumed (last consumer).
        assert_eq!(*asm.lock().unwrap(), vec![data.len() as u64]);
        let assembled_file = dir.join("Game/f.bin");
        assert_eq!(std::fs::read(&assembled_file).unwrap(), data);
        assert!(!cache_path.exists(), "cache chunk deleted after its last consumer");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn corrupt_cached_chunk_is_dropped_and_refetched() {
        // Same manifest as above, but the cache file is garbage: the resume path must
        // delete it and re-download (which then fails without a reachable CDN) instead
        // of crediting corrupt bytes and poisoning assembly.
        let data = vec![7u8; 6000];
        let sha1 = super::super::chunk::test_support::sha1_of(&data);
        let chunks = vec![TestChunk {
            guid: [0xBBBB_0001, 0xBBBB_0002, 0xBBBB_0003, 0xBBBB_0004],
            hash: 0x0123_4567,
            sha1,
            group: 5,
            window: data.len() as i32,
            file_size: 1234,
        }];
        let files = vec![TestFile {
            name: "Game/f.bin".to_string(),
            sha1: [0x51; 20],
            tags: vec![],
            parts: vec![(chunks[0].guid, 0, data.len() as i32)],
        }];
        let manifest_bytes = build_manifest(&chunks, &files, 21, true);

        let dir = super::super::chunk::test_support::temp_dir("corrupt");
        let mut req = request(dir.to_str().unwrap(), vec![0]);
        req.manifest_bytes = manifest_bytes;
        req.max_workers = 1;
        let plan = build_plan(&req).unwrap();
        // Seed the corrupt chunk in the LEGACY FLAT layout (pre-sharding builds): dual-read
        // must find it, declare it invalid, remove it, and refetch.
        let cache_path = legacy_cached_chunk_path(&plan.cache_dir, &plan.manifest.unique_chunks[0]);
        std::fs::write(&cache_path, b"garbage").unwrap();

        let cancel = AtomicBool::new(false);
        let calls = Mutex::new(Vec::new());
        let progress = |b: u64, c: u64| calls.lock().unwrap().push((b, c));
        let log = |_: &str| {};
        let assembly_progress = |_: u64| {};
        let out = run_plan(&plan, &req, &cancel, &progress, &assembly_progress, &log);
        assert!(!out.success, "refetch must fail without a reachable CDN");
        assert!(calls.lock().unwrap().is_empty(), "no credit for corrupt cache");
        assert!(!cache_path.exists(), "corrupt cache was removed before refetch");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn cancel_before_fetch_reports_cancelled() {
        let dir = super::super::chunk::test_support::temp_dir("cancel");
        let req = request(dir.to_str().unwrap(), vec![0]);
        let plan = build_plan(&req).unwrap();
        let cancel = AtomicBool::new(true);
        let progress = |_: u64, _: u64| {};
        let assembly_progress = |_: u64| {};
        let log = |_: &str| {};
        let out = run_plan(&plan, &req, &cancel, &progress, &assembly_progress, &log);
        assert!(out.cancelled);
        assert!(!out.success);
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn assembly_writes_files_and_drops_shared_chunks_after_last_consumer() {
        // file a = chunk1[0..4000] + chunk2[0..2000]; file b = chunk2[100..600] (shared chunk).
        let chunks = vec![
            TestChunk {
                guid: [1, 2, 3, 4],
                hash: 0,
                sha1: [0; 20],
                group: 0,
                window: 4000,
                file_size: 10,
            },
            TestChunk {
                guid: [5, 6, 7, 8],
                hash: 0,
                sha1: [0; 20],
                group: 0,
                window: 3000,
                file_size: 10,
            },
        ];
        let files = vec![
            TestFile {
                name: "Game/a.bin".to_string(),
                sha1: [0; 20],
                tags: vec![],
                parts: vec![(chunks[0].guid, 0, 4000), (chunks[1].guid, 0, 2000)],
            },
            TestFile {
                name: "Game/b.bin".to_string(),
                sha1: [0; 20],
                tags: vec![],
                parts: vec![(chunks[1].guid, 100, 500)],
            },
        ];
        let manifest_bytes = build_manifest(&chunks, &files, 21, true);
        let dir = super::super::chunk::test_support::temp_dir("assemble");
        let mut req = request(dir.to_str().unwrap(), vec![0, 1]);
        req.manifest_bytes = manifest_bytes;
        let plan = build_plan(&req).unwrap();

        let d1 = vec![1u8; 4000];
        let d2: Vec<u8> = (0..3000).map(|i| (i % 251) as u8).collect();
        let c1 = plan.cache_dir.join(part_cache_name(&chunks[0].guid));
        let c2 = plan.cache_dir.join(part_cache_name(&chunks[1].guid));
        std::fs::write(&c1, &d1).unwrap();
        std::fs::write(&c2, &d2).unwrap();

        let cancel = AtomicBool::new(false);
        let asm = Mutex::new(Vec::new());
        let assembly_progress = |b: u64| asm.lock().unwrap().push(b);
        let log = |_: &str| {};
        let n = assemble_files(&plan, &req, &cancel, &assembly_progress, &log).unwrap();
        assert_eq!(n, 6500);

        let a = std::fs::read(dir.join("Game/a.bin")).unwrap();
        assert_eq!(&a[..4000], &d1[..]);
        assert_eq!(&a[4000..], &d2[..2000]);
        let b = std::fs::read(dir.join("Game/b.bin")).unwrap();
        assert_eq!(b, &d2[100..600]);
        assert!(!c1.exists(), "single-consumer chunk deleted");
        assert!(!c2.exists(), "shared chunk deleted after its LAST consumer");
        // Progress is cumulative over written parts (order across threads not asserted).
        assert_eq!(asm.lock().unwrap().last().copied(), Some(6500));
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn assembly_fails_loudly_on_missing_chunk() {
        let chunks = vec![TestChunk {
            guid: [9, 9, 9, 9],
            hash: 0,
            sha1: [0; 20],
            group: 0,
            window: 100,
            file_size: 10,
        }];
        let files = vec![TestFile {
            name: "Game/x.bin".to_string(),
            sha1: [0; 20],
            tags: vec![],
            parts: vec![(chunks[0].guid, 0, 100)],
        }];
        let manifest_bytes = build_manifest(&chunks, &files, 21, true);
        let dir = super::super::chunk::test_support::temp_dir("assemble-missing");
        let mut req = request(dir.to_str().unwrap(), vec![0]);
        req.manifest_bytes = manifest_bytes;
        let plan = build_plan(&req).unwrap();

        let cancel = AtomicBool::new(false);
        let assembly_progress = |_: u64| {};
        let log = |_: &str| {};
        let err = assemble_files(&plan, &req, &cancel, &assembly_progress, &log).unwrap_err();
        assert!(err.contains("Chunk file missing"), "unexpected error: {err}");
        let _ = std::fs::remove_dir_all(&dir);
    }
}
