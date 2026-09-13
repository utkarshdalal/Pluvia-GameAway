use crate::cdn_client::{AsyncCdnClient, AsyncFetchError, CdnClient, CdnConnection, FetchFailKind};
use crate::content_manifest::{ChunkData, ContentManifest};
use crate::depot_chunk::process_depot_chunk;
use crate::pb::ccontentserverdirectory::CContentServerDirectoryServerInfo;
use futures_util::stream::FuturesUnordered;
use futures_util::StreamExt;
use std::collections::VecDeque;
use std::fs::{self, File, OpenOptions};
use std::path::{Component, Path};
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};
use tokio::sync::Semaphore;

pub const DEPOT_FILE_FLAG_EXECUTABLE: u32 = 32;
pub const DEPOT_FILE_FLAG_DIRECTORY: u32 = 64;
pub const DEPOT_FILE_FLAG_SYMLINK: u32 = 512;
pub const MAX_CHUNK_ATTEMPTS: u32 = 5;
pub const SLOW_CHUNK_ROTATE_THRESHOLD_SECS: u64 = 8;
pub const SLOW_CHUNK_ROTATE_CONSECUTIVE_LIMIT: u32 = 3;

/// Floor for the in-flight byte budget — the pre-r4 fixed value, so no depot ever gets *less*
/// buffered memory than it had before the budget started scaling. See [`inflight_budget_bytes`].
pub const FETCH_INFLIGHT_BUDGET_FLOOR_BYTES: u64 = 24 * 1024 * 1024;

/// Hard ceiling for the in-flight byte budget. This is THE OOM guard (the #408 class): whatever the
/// tier, the host count or the chunk size, the RAW bytes in memory for one depot can never exceed
/// this. 96 MiB = 4× the old fixed budget and still a small fraction of the heap an Android game
/// installer runs in — and it is only ever reached by a Blazing-tier depot whose CDN pool is large
/// enough to support a 64+ window (the observed 7-host pool tops out at a 63 MiB budget). Raising
/// this is the one change in the fetch layer that can reintroduce OOM, so it stays fixed and small.
pub const FETCH_INFLIGHT_BUDGET_HARD_CAP_BYTES: u64 = 96 * 1024 * 1024;

/// Assumed RAW (on-the-wire, still-compressed) size of one chunk when sizing the budget. Steam
/// chunks are ~1 MiB; this is the same nominal [`NOMINAL_CHUNK_RESERVE_BYTES`] uses at dispatch.
pub const TYPICAL_CHUNK_BYTES: u64 = NOMINAL_CHUNK_RESERVE_BYTES;

/// Headroom the budget carries over "one chunk per window slot" (3/2 = 1.5×), as a rational so the
/// arithmetic stays integer. The window is a count of concurrent *requests*; a slot's bytes are held
/// from DISPATCH until its WRITE completes, so at any instant some slots hold a fetched-but-not-yet-
/// written chunk while others hold a reservation for a fetch still on the wire. 1.5× covers that
/// overlap (and the occasional above-nominal chunk) without doubling the memory bound.
pub const BUDGET_HEADROOM_NUM: u64 = 3;
pub const BUDGET_HEADROOM_DEN: u64 = 2;

/// Byte budget for the *compressed* chunk bytes buffered in the fetch→process channel, scaled to the
/// window ceiling this depot can actually reach.
///
/// The decouple channel is bounded by BYTES, not chunk count: a count cap makes in-flight memory
/// swing wildly with chunk size (a 4-chunk cap is ~4 MB for 1 MB chunks but ~120 MB for 30 MB
/// chunks). Budgeting bytes keeps the heap bounded regardless of game size (the #408 OOM class) while
/// giving the fetch pool bandwidth-delay-product headroom so the socket stays busy while process
/// workers decompress+write.
///
/// r4: the r3 device run proved a fixed 24 MiB budget becomes the binding constraint the moment the
/// adaptive window works — a Blazing depot on a 1 Gbps line grew the window to 42 but the budget
/// admitted only ~24 chunks, so `in_flight` stalled at 23–28 with `budget_stalls` in the hundreds
/// and `host_stalls=0`. A budget that refuses slots the window has already decided are safe is not a
/// memory guard, it is a throttle. So the budget now scales WITH the same effective ceiling
/// [`window_bounds`] computes (tier ∧ hosts×per-host-cap ∧ hard cap) and is clamped between the old
/// fixed value and a hard cap. Low tiers are unaffected (Slow/Medium land on the floor); a fast link
/// gets a budget that stops binding before the window does.
///
/// Budget only the RAW bytes actually queued (reserved at DISPATCH, released on WRITE-COMPLETE); the
/// decoded expansion is transient inside a process worker and never accumulates. See
/// [`budget_admits`] for the single-chunk deadlock guard.
pub fn inflight_budget_bytes(effective_ceiling: usize) -> u64 {
    (effective_ceiling as u64)
        .saturating_mul(TYPICAL_CHUNK_BYTES)
        .saturating_mul(BUDGET_HEADROOM_NUM)
        .saturating_div(BUDGET_HEADROOM_DEN)
        .clamp(
            FETCH_INFLIGHT_BUDGET_FLOOR_BYTES,
            FETCH_INFLIGHT_BUDGET_HARD_CAP_BYTES,
        )
}

/// How often the parallel writer emits a per-server throughput line to the debug log.
const BANDWIDTH_LOG_INTERVAL_MS: u64 = 5_000;

/// Slack below which the free-space guard will not fail (guards against small statvfs imprecision).
const FREE_SPACE_MARGIN_BYTES: u64 = 64 * 1024 * 1024;

// ─── B2b async-fetch tuning ────────────────────────────────────────────────────────────────────
// The window/host/back-off knobs for the adaptive concurrency fetch driver. The important behaviours
// are structural (slow-start ramp → hold at plateau → back off on REAL errors, per-host cap, hard
// byte budget), not the exact numbers.
//
// r3 retune: the first device run pinned the window at the floor on a 1 Gbps link. The B2b controller
// shrank on throughput dips and latency jitter (normal CDN noise) as well as on errors, shrank a flat
// −6 on every single error, and only grew when throughput rose >10% in a 700 ms sample. The shrinks
// dominated, so the window ratcheted to the floor and stayed there. r3 makes growth eager and makes
// ERRORS the only shrink signal.

/// Max concurrent requests to any single CDN host. Steam throttles/resets per host, so a big window
/// is reached by spreading across MORE hosts, never by piling onto one. (Refinement #3.)
pub const PER_HOST_CAP: usize = 6;
/// Initial in-flight window. Slow-start doubles from here, so this only sets how fast the first
/// couple of probes get useful — the ceiling still comes from the tier × hosts clamp.
pub const BOOTSTRAP_WINDOW: usize = 8;
/// Hard safety ceiling on the window regardless of tier (also clamped to distinct-hosts × per-host).
pub const WINDOW_HARD_CAP: usize = 256;
/// The window never shrinks below this (keeps a little pipelining even on a rough link).
pub const WINDOW_MIN_FLOOR: usize = 2;
/// Additive growth step once slow-start has ended (after the first real back-off).
pub const WINDOW_STEP_UP: usize = 4;
/// Slow-start multiplier: until the first back-off the window DOUBLES each healthy probe, so a fast
/// link reaches a useful window in seconds instead of never.
pub const WINDOW_SLOW_START_FACTOR: usize = 2;
/// Proportional shrink on a real error signal (never a flat step: a flat −6 slammed a small window
/// straight to the floor). Always at least −1 while above the floor.
pub const WINDOW_SHRINK_FACTOR: f64 = 0.75;
/// How often the adaptive window re-evaluates. Long enough (vs B2b's 700 ms) that one jittery sample
/// cannot drive a decision; decisions use the EWMA, not the raw sample.
pub const WINDOW_PROBE_INTERVAL_MS: u64 = 2_000;
/// After a back-off, hold the window (no growth) for this long — hysteresis against oscillation.
pub const WINDOW_COOLDOWN_MS: u64 = 3_000;
/// EWMA smoothing for the measured throughput samples (higher = more responsive).
pub const WINDOW_BPS_EWMA_ALPHA: f64 = 0.4;
/// Throughput above `best × (1 + this)` counts as a genuine improvement (keeps slow-start climbing).
pub const WINDOW_IMPROVE_EPS: f64 = 0.05;
/// Throughput below `best × (1 − this)` counts as falling — growth STOPS (hold). It never shrinks:
/// only real errors shrink.
pub const WINDOW_DECLINE_EPS: f64 = 0.15;
/// Error-rate thresholds over one probe window: above HIGH shrinks; growth needs at/below LOW
/// (in between = hold).
pub const WINDOW_ERR_RATE_HIGH: f64 = 0.10;
pub const WINDOW_ERR_RATE_LOW: f64 = 0.05;
/// Errors inside ONE probe interval that trigger an immediate (don't-wait-for-the-probe) shrink. A
/// single stray timeout no longer moves the window; a burst does. A 429 always shrinks immediately.
pub const WINDOW_ERR_BURST_IMMEDIATE: u32 = 3;
/// Consecutive non-improving probes still allowed to grow before declaring a plateau — throughput
/// lags a window change by a probe or two, so one flat sample must not stop the ramp.
pub const WINDOW_PLATEAU_PATIENCE: u32 = 2;
/// Once plateaued, re-arm the ramp this long later (doubling, capped) to re-probe for headroom if the
/// link improved. Bounded re-probing, not a creep: a weak link answers with errors and shrinks back.
pub const WINDOW_PLATEAU_REARM_MS: u64 = 30_000;
pub const WINDOW_PLATEAU_REARM_MAX_MS: u64 = 300_000;
/// Even when the window does not change, emit one `fetch-window` line every N probes so a stuck
/// window explains itself in the log (`reason=` says why it is not moving).
pub const WINDOW_LOG_EVERY_PROBES: u32 = 5;
/// Every Nth exploit dispatch is instead an exploration pick (epsilon-greedy ≈ 1/N) so a
/// demoted-but-recovered host is retried rather than starved forever. (Refinement #5.)
pub const SERVER_EXPLORE_EVERY: u64 = 8;
/// Reservation for a chunk whose compressed size the manifest didn't carry (keeps the byte budget
/// honest at dispatch time). Steam chunks are ~1 MiB.
pub const NOMINAL_CHUNK_RESERVE_BYTES: u64 = 1024 * 1024;
/// While the fetch pipe is busy, process at most this many consecutive verify-skips before yielding
/// the driver back to poll in-flight fetches — keeps a resume's local re-hash from starving the
/// socket. (During a pure verify pass with nothing in flight there is nothing to starve, so the
/// driver blasts straight through.)
pub const VERIFY_YIELD_BATCH: u32 = 256;
/// Extra host cooldown floor when a host answers 429 (rate-limited) — back off harder than a
/// one-off timeout.
pub const RATE_LIMIT_COOLDOWN_MS: u64 = 5_000;

/// Sink for engine-side download diagnostics (throughput lines, fallocate fallback notice). Wired by
/// the JNI layer to `android_log("GN_STEAM_DL", …)`; `None` in tests. Must be `Sync` — it is called
/// from the fetch/process pools and the reporter thread.
pub type DepotLogCallback<'a> = &'a (dyn Fn(&str) + Sync);

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct DepotWriteResult {
    pub files_written: u64,
    pub bytes_written: u64,
    pub resume_trust_safe: bool,
    pub error: String,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum DepotFileAction {
    Directory { path: String },
    Symlink { path: String, target: String },
    Regular { path: String, size: u64, mode: u32 },
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct ChunkWriteJob {
    pub file_idx: u32,
    pub chunk_idx: u32,
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct DepotWritePlan {
    pub total_bytes: u64,
    pub files_written: u64,
    pub actions: Vec<DepotFileAction>,
    pub chunk_jobs: Vec<ChunkWriteJob>,
    pub worker_count: u32,
}

pub type DepotChunkProgressCallback<'a> = &'a (dyn Fn(u64, u64, bool) + Sync);

#[derive(Clone, Copy)]
pub struct DepotWriteOptions<'a> {
    pub cdn_auth_token: &'a str,
    pub timeout: Duration,
    /// Fetch-pool size (network parallelism) = the tier's `maxDownloads`.
    pub max_workers: u32,
    /// Process-pool size (decrypt+decompress+write parallelism) = the tier's `maxDecompress`.
    /// Defaults to [`Self::max_workers`]'s default so existing callers/tests keep their behaviour.
    pub max_process_workers: u32,
    pub cancel: Option<&'a AtomicBool>,
    pub on_progress: Option<DepotChunkProgressCallback<'a>>,
    /// Diagnostics sink (throughput + fallocate fallback). `None` = silent (tests).
    pub log: Option<DepotLogCallback<'a>>,
}

impl Default for DepotWriteOptions<'_> {
    fn default() -> Self {
        Self {
            cdn_auth_token: "",
            timeout: CdnClient::default_timeout(),
            max_workers: 8,
            max_process_workers: 8,
            cancel: None,
            on_progress: None,
            log: None,
        }
    }
}

impl DepotWriteResult {
    pub fn ok(&self) -> bool {
        self.error.is_empty()
    }

    pub fn success(files_written: u64, bytes_written: u64) -> Self {
        Self {
            files_written,
            bytes_written,
            resume_trust_safe: true,
            error: String::new(),
        }
    }

    pub fn fail(error: impl Into<String>, resume_trust_safe: bool) -> Self {
        Self {
            error: error.into(),
            resume_trust_safe,
            ..Default::default()
        }
    }
}

pub fn retry_backoff_millis(attempt: u32) -> u64 {
    if attempt == 0 {
        return 0;
    }
    (300u64 << (attempt - 1)).min(4000)
}

pub fn chunk_attempt_server_indices(
    start_server_index: usize,
    server_count: usize,
    attempts: u32,
) -> Vec<usize> {
    if server_count == 0 || attempts == 0 {
        return Vec::new();
    }
    let mut out = Vec::with_capacity(attempts as usize);
    let mut server_index = start_server_index % server_count;
    for attempt in 0..attempts {
        if attempt > 0 && server_count > 1 {
            server_index = (server_index + 1) % server_count;
        }
        out.push(server_index);
    }
    out
}

pub fn should_rotate_after_slow_chunks(consecutive_slow_chunks: u32, server_count: usize) -> bool {
    server_count > 1 && consecutive_slow_chunks >= SLOW_CHUNK_ROTATE_CONSECUTIVE_LIMIT
}

/// Byte-budget admission test for the fetch→process channel. Always admits when nothing is in
/// flight (`in_flight == 0`) so a single chunk LARGER than the whole budget can never deadlock — it
/// simply waits until the channel drains, then goes through alone.
#[inline]
pub fn budget_admits(in_flight: u64, raw_len: u64, budget: u64) -> bool {
    in_flight == 0 || in_flight.saturating_add(raw_len) <= budget
}

pub fn depot_adler_hash(data: &[u8]) -> u32 {
    const BLOCK: usize = 5552;
    let mut a = 0u32;
    let mut b = 0u32;
    for chunk in data.chunks(BLOCK) {
        for byte in chunk {
            a += *byte as u32;
            b += a;
        }
        a %= 65521;
        b %= 65521;
    }
    a | (b << 16)
}

pub fn path_is_safe(rel: &str) -> bool {
    if rel.is_empty() || rel.starts_with('/') || rel.starts_with('\\') {
        return false;
    }
    let path = Path::new(rel);
    if path.is_absolute() {
        return false;
    }
    path.components().all(|component| {
        matches!(component, Component::Normal(_)) || matches!(component, Component::CurDir)
    }) && !path
        .components()
        .any(|component| matches!(component, Component::ParentDir | Component::Prefix(_)))
}

pub fn clamp_worker_count(max_workers: u32, outstanding_chunks: usize) -> u32 {
    if outstanding_chunks == 0 {
        return 0;
    }
    let requested = if max_workers == 0 { 1 } else { max_workers };
    requested.min(64).min(outstanding_chunks as u32)
}

pub fn plan_depot_write(
    manifest: &ContentManifest,
    depot_key: &[u8],
    server_count: usize,
    target_dir: &str,
    max_workers: u32,
) -> Result<DepotWritePlan, DepotWriteResult> {
    if manifest.metadata.filenames_encrypted {
        return Err(DepotWriteResult::fail(
            "write_depot: manifest filenames are still encrypted",
            false,
        ));
    }
    if depot_key.len() != 32 {
        return Err(DepotWriteResult::fail(
            "write_depot: bad depot key length",
            false,
        ));
    }
    if server_count == 0 {
        return Err(DepotWriteResult::fail("write_depot: no CDN servers", false));
    }

    let mut plan = DepotWritePlan {
        total_bytes: manifest.files.iter().map(|file| file.size).sum(),
        ..Default::default()
    };

    for (file_idx, file) in manifest.files.iter().enumerate() {
        if !path_is_safe(&file.filename) {
            return Err(DepotWriteResult::fail(
                format!("write_depot: unsafe path '{}'", file.filename),
                false,
            ));
        }
        let path = join_target_path(target_dir, &file.filename);
        if !file.linktarget.is_empty() {
            plan.actions.push(DepotFileAction::Symlink {
                path,
                target: file.linktarget.clone(),
            });
            plan.files_written += 1;
            continue;
        }
        if (file.flags & DEPOT_FILE_FLAG_DIRECTORY) != 0 {
            plan.actions.push(DepotFileAction::Directory { path });
            continue;
        }
        let mode = if (file.flags & DEPOT_FILE_FLAG_EXECUTABLE) != 0 {
            0o755
        } else {
            0o644
        };
        plan.actions.push(DepotFileAction::Regular {
            path,
            size: file.size,
            mode,
        });
        plan.files_written += 1;
        for chunk_idx in 0..file.chunks.len() {
            plan.chunk_jobs.push(ChunkWriteJob {
                file_idx: file_idx as u32,
                chunk_idx: chunk_idx as u32,
            });
        }
    }
    plan.worker_count = clamp_worker_count(max_workers, plan.chunk_jobs.len());
    Ok(plan)
}

// ─────────────────────────────────────────────────────────────────────────────────────────────
// Per-server throughput measurement (atomic counters only — never a mutex on the fetch hot path).
// ─────────────────────────────────────────────────────────────────────────────────────────────

/// Lightweight per-CDN-server byte/duration accounting. Fetch workers `record()` the compressed
/// bytes served by the winning server (a lock-free atomic add); the reporter thread reads snapshots
/// and emits `overall MB/s + per-server MB/s` to the debug log. Feeds the tester clean before/after
/// numbers per lever and future adaptive tuning.
pub struct BandwidthMeter {
    start: Instant,
    total_bytes: AtomicU64,
    per_server_bytes: Vec<AtomicU64>,
    hosts: Vec<String>,
}

impl BandwidthMeter {
    pub fn new(servers: &[CContentServerDirectoryServerInfo]) -> Self {
        Self {
            start: Instant::now(),
            total_bytes: AtomicU64::new(0),
            per_server_bytes: servers.iter().map(|_| AtomicU64::new(0)).collect(),
            hosts: servers
                .iter()
                .map(|s| {
                    if s.vhost.is_empty() {
                        s.host.clone()
                    } else {
                        s.vhost.clone()
                    }
                })
                .collect(),
        }
    }

    #[inline]
    pub fn record(&self, server_idx: usize, bytes: u64) {
        self.total_bytes.fetch_add(bytes, Ordering::Relaxed);
        if let Some(counter) = self.per_server_bytes.get(server_idx) {
            counter.fetch_add(bytes, Ordering::Relaxed);
        }
    }

    /// Cumulative bytes served so far (feeds the adaptive window's throughput probe).
    #[inline]
    pub fn total_bytes(&self) -> u64 {
        self.total_bytes.load(Ordering::Relaxed)
    }

    /// A one-line snapshot for the debug log: overall MB/s + the busiest servers' MB/s.
    pub fn summary_line(&self, depot_id: u32) -> String {
        let secs = self.start.elapsed().as_secs_f64().max(0.001);
        let total = self.total_bytes.load(Ordering::Relaxed);
        let mb = |b: u64| (b as f64) / (1024.0 * 1024.0);
        let overall_mbps = mb(total) / secs;

        let mut per: Vec<(usize, u64)> = self
            .per_server_bytes
            .iter()
            .enumerate()
            .map(|(i, c)| (i, c.load(Ordering::Relaxed)))
            .filter(|(_, b)| *b > 0)
            .collect();
        per.sort_by(|a, b| b.1.cmp(&a.1));

        // `used` = how many of the pool's servers actually carried bytes. With a small window the
        // speed ranking piles onto one host, so this is the quick read on whether the fetch is
        // spread across the CDN pool or has degenerated to a single server.
        let used = per.len();
        let pool = self.per_server_bytes.len();
        let mut servers = String::new();
        for (i, bytes) in per.iter().take(6) {
            let host = self.hosts.get(*i).map(String::as_str).unwrap_or("?");
            servers.push_str(&format!(
                " [{host} {:.1}MB/s {:.0}MB]",
                mb(*bytes) / secs,
                mb(*bytes)
            ));
        }
        format!(
            "throughput depot={depot_id} overall={overall_mbps:.2}MB/s total={:.0}MB elapsed={secs:.0}s used={used}/{pool} servers:{servers}",
            mb(total)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────────────────────
// B2b async fetch: adaptive concurrency window + speed-ranked, per-host-capped server selection.
// This state lives on (and is mutated only by) the single fetch-driver task, so it needs no locks;
// per-host caps are enforced with tokio Semaphores whose permits ride INSIDE the in-flight futures
// (RAII release on success, error, timeout, AND cancel/abort-drop).
// ─────────────────────────────────────────────────────────────────────────────────────────────

/// Distinct CDN host count (keyed on vhost|host) — the multiplier for the window's host ceiling.
fn distinct_host_count(servers: &[CContentServerDirectoryServerInfo]) -> usize {
    let mut keys: Vec<&str> = Vec::new();
    for s in servers {
        let key = if s.vhost.is_empty() {
            s.host.as_str()
        } else {
            s.vhost.as_str()
        };
        if !keys.contains(&key) {
            keys.push(key);
        }
    }
    keys.len().max(1)
}

/// Per-CDN-server health used to rank selection. `ewma_bps` smooths measured speed (a fast CDN can
/// throttle mid-download, so one sample is never trusted); `cooldown_until` demotes a host that just
/// errored.
#[derive(Clone, Debug, Default)]
struct ServerHealth {
    ewma_bps: f64,
    samples: u32,
    consecutive_errors: u32,
    cooldown_until: Option<Instant>,
}

/// Speed-ranked, per-host-capped server picker. Cold hosts are probed first, then selection is
/// epsilon-greedy over the measured EWMA so a demoted-but-recovered host returns. Per-host caps key
/// on the host STRING (one `tokio::sync::Semaphore` each) so two directory entries that share a host
/// still share one cap.
struct FetchScheduler {
    health: Vec<ServerHealth>,
    /// server index → distinct-host index.
    server_host: Vec<usize>,
    /// one semaphore per distinct host, each with `per_host_cap` permits.
    host_sems: Vec<Arc<Semaphore>>,
    dispatch_counter: u64,
}

impl FetchScheduler {
    fn new(servers: &[CContentServerDirectoryServerInfo], per_host_cap: usize) -> Self {
        let mut host_keys: Vec<String> = Vec::new();
        let mut server_host: Vec<usize> = Vec::with_capacity(servers.len());
        for s in servers {
            let key = if s.vhost.is_empty() {
                s.host.clone()
            } else {
                s.vhost.clone()
            };
            let idx = host_keys.iter().position(|k| *k == key).unwrap_or_else(|| {
                host_keys.push(key);
                host_keys.len() - 1
            });
            server_host.push(idx);
        }
        let host_sems = host_keys
            .iter()
            .map(|_| Arc::new(Semaphore::new(per_host_cap.max(1))))
            .collect();
        Self {
            health: servers.iter().map(|_| ServerHealth::default()).collect(),
            server_host,
            host_sems,
            dispatch_counter: 0,
        }
    }

    fn host_sem(&self, server_idx: usize) -> &Arc<Semaphore> {
        &self.host_sems[self.server_host[server_idx]]
    }

    fn eligible(&self, server_idx: usize, now: Instant) -> bool {
        self.host_sem(server_idx).available_permits() > 0
            && self.health[server_idx]
                .cooldown_until
                .map_or(true, |t| t <= now)
    }

    /// Pick the server for the next request, or `None` if every host is at its cap or cooling down
    /// (the driver then waits for an in-flight request to complete).
    fn pick(&mut self, now: Instant) -> Option<usize> {
        let eligible: Vec<usize> = (0..self.health.len())
            .filter(|&i| self.eligible(i, now))
            .collect();
        if eligible.is_empty() {
            return None;
        }
        // Cold start: probe an unsampled host before exploiting anything.
        if let Some(&cold) = eligible.iter().find(|&&i| self.health[i].samples == 0) {
            return Some(cold);
        }
        self.dispatch_counter = self.dispatch_counter.wrapping_add(1);
        // Epsilon-greedy exploration: revisit the least-sampled eligible host.
        if SERVER_EXPLORE_EVERY > 0 && self.dispatch_counter % SERVER_EXPLORE_EVERY == 0 {
            return eligible
                .iter()
                .copied()
                .min_by_key(|&i| self.health[i].samples);
        }
        // Exploit: highest EWMA, tie-break to the host with more free permits, then lower index.
        eligible.iter().copied().max_by(|&a, &b| {
            self.health[a]
                .ewma_bps
                .partial_cmp(&self.health[b].ewma_bps)
                .unwrap_or(std::cmp::Ordering::Equal)
                .then(
                    self.host_sem(a)
                        .available_permits()
                        .cmp(&self.host_sem(b).available_permits()),
                )
                .then(b.cmp(&a))
        })
    }

    fn on_success(&mut self, server_idx: usize, bytes: u64, elapsed: Duration) {
        let secs = elapsed.as_secs_f64().max(0.001);
        let bps = bytes as f64 / secs;
        let h = &mut self.health[server_idx];
        h.ewma_bps = if h.samples == 0 {
            bps
        } else {
            0.7 * h.ewma_bps + 0.3 * bps
        };
        h.samples = h.samples.saturating_add(1);
        h.consecutive_errors = 0;
        h.cooldown_until = None;
    }

    fn on_error(&mut self, server_idx: usize, now: Instant, kind: FetchFailKind) {
        let h = &mut self.health[server_idx];
        h.consecutive_errors = h.consecutive_errors.saturating_add(1);
        let mut backoff = retry_backoff_millis(h.consecutive_errors);
        if kind == FetchFailKind::RateLimited {
            backoff = backoff.max(RATE_LIMIT_COOLDOWN_MS);
        }
        h.cooldown_until = Some(now + Duration::from_millis(backoff));
        // Demote so ranking avoids it even after the cooldown expires, until it re-earns speed.
        h.ewma_bps *= 0.5;
    }
}

/// Why the window last moved (or refused to). Logged on every `fetch-window` line so the next device
/// run is self-diagnosing: a window sitting still now says whether it is at the ceiling, plateaued,
/// cooling down after an error, or being held back by errors.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum WindowReason {
    Start,
    GrowSlowStart,
    GrowThroughputUp,
    GrowProbe,
    HoldPlateau,
    HoldCooldown,
    HoldCeiling,
    HoldErrors,
    HoldThroughputDown,
    ShrinkRateLimited,
    ShrinkTimeout,
    ShrinkReset,
    ShrinkServerFault,
    ShrinkErrorRate,
    ShrinkErrorBurst,
}

impl WindowReason {
    fn code(self) -> &'static str {
        match self {
            WindowReason::Start => "start",
            WindowReason::GrowSlowStart => "grow:slow-start",
            WindowReason::GrowThroughputUp => "grow:throughput-up",
            WindowReason::GrowProbe => "grow:probe",
            WindowReason::HoldPlateau => "hold:plateau",
            WindowReason::HoldCooldown => "hold:cooldown",
            WindowReason::HoldCeiling => "hold:ceiling",
            WindowReason::HoldErrors => "hold:errors",
            WindowReason::HoldThroughputDown => "hold:throughput-down",
            WindowReason::ShrinkRateLimited => "shrink:429",
            WindowReason::ShrinkTimeout => "shrink:timeout",
            WindowReason::ShrinkReset => "shrink:reset",
            WindowReason::ShrinkServerFault => "shrink:5xx",
            WindowReason::ShrinkErrorRate => "shrink:err-rate",
            WindowReason::ShrinkErrorBurst => "shrink:err-burst",
        }
    }

    fn from_fail_kind(kind: FetchFailKind) -> Self {
        match kind {
            FetchFailKind::RateLimited => WindowReason::ShrinkRateLimited,
            FetchFailKind::Timeout => WindowReason::ShrinkTimeout,
            FetchFailKind::Connect => WindowReason::ShrinkReset,
            FetchFailKind::ServerFault => WindowReason::ShrinkServerFault,
            FetchFailKind::Other => WindowReason::ShrinkErrorBurst,
        }
    }
}

/// Adaptive in-flight window (r3). Slow-start doubles the window every healthy probe until the first
/// real back-off, then grows additively; it keeps growing while the error rate is low and throughput
/// is not falling, HOLDS once throughput plateaus, and shrinks PROPORTIONALLY only on real errors
/// (429 / timeout / connection reset / server fault). Throughput dips and latency jitter never shrink
/// it — that noise is exactly what pinned B2b at the floor.
///
/// Slow-connection safety is unchanged in kind: a thin pipe saturates and answers with timeouts and
/// resets (real errors → shrink + cooldown), plateaus early (→ hold, so growth simply stops), and is
/// still bounded by the per-host cap, the floor/ceiling, and the in-flight byte budget (which on a
/// thin link stays at its 24 MiB floor, since that budget is sized from the same effective ceiling).
/// `max` is the tier ceiling (already clamped to distinct-hosts × per-host-cap).
#[derive(Clone, Debug)]
struct AdaptiveWindow {
    current: usize,
    min: usize,
    max: usize,
    last_probe: Instant,
    last_bytes: u64,
    /// Raw throughput of the last probe sample (logged as `last=`).
    last_bps: f64,
    /// Smoothed throughput — the ONLY throughput signal decisions use.
    bps_ewma: f64,
    /// High-water smoothed throughput at the current regime (reset on a shrink).
    best_bps: f64,
    /// True until the first real back-off: growth is multiplicative.
    slow_start: bool,
    /// Consecutive non-improving probes that still grew (plateau patience).
    plateau_streak: u32,
    /// When the plateau hold started, and how long to hold before re-probing for headroom.
    plateau_since: Option<Instant>,
    plateau_rearm_ms: u64,
    ok_count: u32,
    err_count: u32,
    /// Dispatch attempts since the last probe that WANTED another slot but could not take one: the
    /// byte budget was full, or every CDN host was at its per-host cap / cooling down. These say
    /// whether the window is the binding constraint at all — a big window with a high `budget_stalls`
    /// is bounded by the byte budget, and a high `host_stalls` is bounded by hosts × per-host cap.
    /// Since r4 the budget is sized from the window ceiling, so `budget_stalls` should stay near zero
    /// — a spike means the budget is binding again and the headroom/hard cap needs revisiting.
    budget_stalls: u32,
    host_stalls: u32,
    last_budget_stalls: u32,
    last_host_stalls: u32,
    latency_ewma_ms: f64,
    cooldown_until: Option<Instant>,
    last_reason: WindowReason,
    last_err_rate: f64,
    probes_since_log: u32,
}

impl AdaptiveWindow {
    fn new(bootstrap: usize, min: usize, max: usize, now: Instant) -> Self {
        let max = max.max(1);
        let min = min.clamp(1, max);
        Self {
            current: bootstrap.clamp(min, max),
            min,
            max,
            last_probe: now,
            last_bytes: 0,
            last_bps: 0.0,
            bps_ewma: 0.0,
            best_bps: 0.0,
            slow_start: true,
            plateau_streak: 0,
            plateau_since: None,
            plateau_rearm_ms: WINDOW_PLATEAU_REARM_MS,
            ok_count: 0,
            err_count: 0,
            budget_stalls: 0,
            host_stalls: 0,
            last_budget_stalls: 0,
            last_host_stalls: 0,
            latency_ewma_ms: 0.0,
            cooldown_until: None,
            last_reason: WindowReason::Start,
            last_err_rate: 0.0,
            probes_since_log: 0,
        }
    }

    /// The dispatch loop wanted another in-flight slot but the byte budget was full.
    fn note_budget_stall(&mut self) {
        self.budget_stalls = self.budget_stalls.saturating_add(1);
    }

    /// The dispatch loop wanted another in-flight slot but every host was at cap or cooling.
    fn note_host_stall(&mut self) {
        self.host_stalls = self.host_stalls.saturating_add(1);
    }

    fn record_ok(&mut self, latency_ms: f64) {
        self.ok_count = self.ok_count.saturating_add(1);
        self.latency_ewma_ms = if self.latency_ewma_ms == 0.0 {
            latency_ms
        } else {
            0.8 * self.latency_ewma_ms + 0.2 * latency_ms
        };
    }

    /// Proportional shrink (never a flat step) + hysteresis cooldown, and slow-start ends here: from
    /// now on the window advances additively.
    fn shrink(&mut self, now: Instant, reason: WindowReason) {
        let scaled = (self.current as f64 * WINDOW_SHRINK_FACTOR).floor() as usize;
        // Always give up at least one slot while above the floor, so a factor that rounds to the same
        // value still makes progress.
        let next = scaled.min(self.current.saturating_sub(1)).max(self.min);
        self.current = next.max(self.min);
        self.cooldown_until = Some(now + Duration::from_millis(WINDOW_COOLDOWN_MS));
        self.slow_start = false;
        // The old high-water mark was measured at a bigger window; keep it and we would read
        // "falling" forever and never recover. Re-baseline to what we are actually seeing.
        self.best_bps = self.bps_ewma;
        self.plateau_streak = 0;
        self.plateau_since = None;
        self.plateau_rearm_ms = WINDOW_PLATEAU_REARM_MS;
        self.last_reason = reason;
    }

    /// `multiplicative` doubles (slow-start); otherwise a single additive step. Doubling is reserved
    /// for probes where throughput actually ROSE — a link that is already saturated reports FLAT
    /// throughput, and doubling into a saturated thin pipe is exactly the flood the window exists to
    /// prevent. Flat-but-clean probes advance one step at a time.
    fn grow(&mut self, reason: WindowReason, multiplicative: bool) {
        let next = if multiplicative {
            self.current.saturating_mul(WINDOW_SLOW_START_FACTOR)
        } else {
            self.current + WINDOW_STEP_UP
        };
        self.current = next.min(self.max).max(self.min);
        self.last_reason = reason;
    }

    /// Record a fetch error. Back-off is REAL-ERROR-ONLY, and a single stray failure no longer moves
    /// the window: a rate-limit (429) shrinks immediately, otherwise it takes a burst inside one probe
    /// interval (the probe's error-rate check catches slower error storms). Returns `true` if the
    /// window changed, so the caller can log the back-off as it happens.
    fn record_err(&mut self, now: Instant, kind: FetchFailKind) -> bool {
        self.err_count = self.err_count.saturating_add(1);
        let immediate = kind == FetchFailKind::RateLimited
            || self.err_count >= WINDOW_ERR_BURST_IMMEDIATE;
        if !immediate {
            return false;
        }
        let before = self.current;
        self.shrink(now, WindowReason::from_fail_kind(kind));
        if before != self.current {
            self.probes_since_log = 0;
            true
        } else {
            false
        }
    }

    /// Re-evaluate on the probe interval. Returns `true` when the caller should log (the window moved,
    /// or the heartbeat is due so a stuck window keeps explaining itself).
    fn maybe_probe(&mut self, now: Instant, total_bytes: u64) -> bool {
        let dt = now.duration_since(self.last_probe);
        if dt < Duration::from_millis(WINDOW_PROBE_INTERVAL_MS) {
            return false;
        }
        let secs = dt.as_secs_f64().max(0.001);
        let sample_bps = total_bytes.saturating_sub(self.last_bytes) as f64 / secs;
        self.bps_ewma = if self.bps_ewma == 0.0 {
            sample_bps
        } else {
            WINDOW_BPS_EWMA_ALPHA * sample_bps + (1.0 - WINDOW_BPS_EWMA_ALPHA) * self.bps_ewma
        };
        let events = self.ok_count + self.err_count;
        let err_rate = if events == 0 {
            0.0
        } else {
            self.err_count as f64 / events as f64
        };
        let before = self.current;
        let cooling = self.cooldown_until.is_some_and(|t| now < t);

        if err_rate > WINDOW_ERR_RATE_HIGH {
            // A sustained error storm: the one and only shrink signal at probe time.
            self.shrink(now, WindowReason::ShrinkErrorRate);
        } else if cooling {
            self.last_reason = WindowReason::HoldCooldown;
        } else if self.current >= self.max {
            self.last_reason = WindowReason::HoldCeiling;
        } else if err_rate > WINDOW_ERR_RATE_LOW {
            // Errors present but not a storm: stop growing, but do NOT shrink.
            self.last_reason = WindowReason::HoldErrors;
        } else {
            let improving = self.bps_ewma > self.best_bps * (1.0 + WINDOW_IMPROVE_EPS);
            let falling = self.bps_ewma < self.best_bps * (1.0 - WINDOW_DECLINE_EPS);
            if improving {
                self.best_bps = self.bps_ewma;
                self.plateau_streak = 0;
                self.plateau_since = None;
                self.plateau_rearm_ms = WINDOW_PLATEAU_REARM_MS;
                let doubling = self.slow_start;
                let reason = if doubling {
                    WindowReason::GrowSlowStart
                } else {
                    WindowReason::GrowThroughputUp
                };
                self.grow(reason, doubling);
            } else if falling {
                // Throughput dropped without errors (a CDN slowed, or we are past the useful
                // concurrency): HOLD. Shrinking here is what collapsed B2b to the floor.
                self.last_reason = WindowReason::HoldThroughputDown;
            } else if self.plateau_streak < WINDOW_PLATEAU_PATIENCE {
                // Flat, but throughput lags a window change — spend a little patience before calling
                // it a plateau.
                self.plateau_streak += 1;
                // Additive even during slow-start: flat throughput can mean "already saturated".
                self.grow(WindowReason::GrowProbe, false);
            } else {
                // Plateau: more concurrency stopped helping. HOLD (correct behaviour on a slow link),
                // and re-arm the ramp later — with a doubling interval — in case the link improves.
                let since = *self.plateau_since.get_or_insert(now);
                if now.duration_since(since) >= Duration::from_millis(self.plateau_rearm_ms) {
                    self.plateau_streak = 0;
                    self.plateau_since = None;
                    self.plateau_rearm_ms =
                        (self.plateau_rearm_ms * 2).min(WINDOW_PLATEAU_REARM_MAX_MS);
                }
                self.last_reason = WindowReason::HoldPlateau;
            }
        }

        self.last_bytes = total_bytes;
        self.last_bps = sample_bps;
        self.last_probe = now;
        self.last_err_rate = err_rate;
        self.ok_count = 0;
        self.err_count = 0;
        self.last_budget_stalls = self.budget_stalls;
        self.last_host_stalls = self.host_stalls;
        self.budget_stalls = 0;
        self.host_stalls = 0;
        self.probes_since_log = self.probes_since_log.saturating_add(1);
        let changed = before != self.current;
        if changed || self.probes_since_log >= WINDOW_LOG_EVERY_PROBES {
            self.probes_since_log = 0;
            true
        } else {
            false
        }
    }

    fn cooldown_left_ms(&self, now: Instant) -> u64 {
        self.cooldown_until
            .map(|t| t.saturating_duration_since(now).as_millis() as u64)
            .unwrap_or(0)
    }

    fn summary_line(&self, depot_id: u32, in_flight_requests: usize, now: Instant) -> String {
        let mbps = |bps: f64| bps / (1024.0 * 1024.0);
        format!(
            "fetch-window depot={depot_id} window={} (min={} max={}) in_flight={in_flight_requests} \
last={:.2}MB/s ewma={:.2}MB/s best={:.2}MB/s reason={} cooldown={}ms err_rate={:.1}% \
phase={} rtt={:.0}ms budget_stalls={} host_stalls={}",
            self.current,
            self.min,
            self.max,
            mbps(self.last_bps),
            mbps(self.bps_ewma),
            mbps(self.best_bps),
            self.last_reason.code(),
            self.cooldown_left_ms(now),
            self.last_err_rate * 100.0,
            if self.slow_start { "slow-start" } else { "steady" },
            self.latency_ewma_ms,
            self.last_budget_stalls,
            self.last_host_stalls
        )
    }
}

/// A chunk awaiting (re)dispatch: `attempts` already spent; not eligible before `not_before`.
#[derive(Clone, Copy, Debug)]
struct PendingChunk {
    job: ChunkWriteJob,
    attempts: u32,
    not_before: Instant,
}

/// The result the driver awaits for each dispatched fetch.
struct FetchDone {
    job: ChunkWriteJob,
    attempts: u32,
    reserve: u64,
    server_idx: usize,
    elapsed: Duration,
    res: Result<Vec<u8>, AsyncFetchError>,
}

/// Bytes to reserve against the in-flight budget for a chunk BEFORE it is fetched (compressed size
/// from the manifest, or a nominal fallback). Reserving at dispatch — not at enqueue — makes the byte
/// budget bound the TOTAL raw bytes in memory (being fetched + queued), the hard memory cap.
fn reserve_bytes(manifest: &ContentManifest, job: ChunkWriteJob) -> u64 {
    manifest
        .files
        .get(job.file_idx as usize)
        .and_then(|f| f.chunks.get(job.chunk_idx as usize))
        .map(|c| {
            if c.cb_compressed == 0 {
                NOMINAL_CHUNK_RESERVE_BYTES
            } else {
                c.cb_compressed as u64
            }
        })
        .unwrap_or(NOMINAL_CHUNK_RESERVE_BYTES)
}

/// Window bounds for a depot: `max` = the tier ceiling (`options.max_workers`) clamped to
/// distinct-hosts × per-host-cap and the hard cap; `min` = the floor; `bootstrap` = the start.
fn window_bounds(
    max_workers: usize,
    distinct_hosts: usize,
    per_host_cap: usize,
) -> (usize, usize, usize) {
    let host_ceiling = distinct_hosts.saturating_mul(per_host_cap).max(1);
    let max = max_workers.max(1).min(host_ceiling).min(WINDOW_HARD_CAP);
    let min = WINDOW_MIN_FLOOR.min(max).max(1);
    let bootstrap = BOOTSTRAP_WINDOW.clamp(min, max);
    (bootstrap, min, max)
}

// ─────────────────────────────────────────────────────────────────────────────────────────────
// Open-file table: one persistent handle per file + positioned (pwrite) writes.
// ─────────────────────────────────────────────────────────────────────────────────────────────

struct SlotState {
    /// `Some` while the file is open; `None` for non-regular files and after finalize (fd closed).
    handle: Option<Arc<File>>,
    opened: bool,
}

struct FileSlot {
    state: Mutex<SlotState>,
    /// Chunk jobs for this file not yet completed (verified-skip OR written). Whoever decrements it
    /// to zero owns the exactly-once finalize (set_len + fsync + close).
    remaining: AtomicUsize,
    size: u64,
    /// On-disk length before this pass began (resume detection + free-space accounting).
    preexisting_len: u64,
    path: String,
    mode: u32,
    is_regular: bool,
}

/// Per-depot open-file table. Handles are opened lazily on first touch and closed as soon as the
/// last chunk of a file lands, so the peak open-fd count is bounded by the worker concurrency (not
/// the file count) — safe for many-thousand-file games. Writes use `pwrite` (positioned, no shared
/// seek cursor) so the work-stealing pool can write different chunks of the SAME file concurrently
/// without racing a cursor.
struct DepotFiles {
    slots: Vec<FileSlot>,
    fallocate_fallback_logged: AtomicBool,
    /// Sum of already-present bytes on disk (clamped to each file's size) — for the free-space guard.
    already_present_bytes: u64,
}

impl DepotFiles {
    fn prepare(manifest: &ContentManifest, target_dir: &str) -> Self {
        let mut slots = Vec::with_capacity(manifest.files.len());
        let mut already_present = 0u64;
        for file in &manifest.files {
            let is_regular =
                file.linktarget.is_empty() && (file.flags & DEPOT_FILE_FLAG_DIRECTORY) == 0;
            let path = join_target_path(target_dir, &file.filename);
            let preexisting = if is_regular {
                fs::metadata(&path).map(|m| m.len()).unwrap_or(0)
            } else {
                0
            };
            if is_regular {
                already_present += preexisting.min(file.size);
            }
            let mode = if (file.flags & DEPOT_FILE_FLAG_EXECUTABLE) != 0 {
                0o755
            } else {
                0o644
            };
            slots.push(FileSlot {
                state: Mutex::new(SlotState {
                    handle: None,
                    opened: false,
                }),
                remaining: AtomicUsize::new(if is_regular { file.chunks.len() } else { 0 }),
                size: file.size,
                preexisting_len: preexisting,
                path,
                mode,
                is_regular,
            });
        }
        Self {
            slots,
            fallocate_fallback_logged: AtomicBool::new(false),
            already_present_bytes: already_present,
        }
    }

    /// A file needs its on-disk chunks verified only if something is already there (resume/verify).
    /// A fresh file has nothing to verify and re-reading pre-allocated zeros would be wasted IO.
    fn needs_verify(&self, file_idx: usize) -> bool {
        self.slots
            .get(file_idx)
            .map(|s| s.preexisting_len > 0)
            .unwrap_or(false)
    }

    /// Get the shared handle for a file, opening (+ pre-allocating to the exact manifest size) on
    /// first touch. Thread-safe: the first caller opens, the rest clone the `Arc<File>`.
    fn acquire(&self, file_idx: usize, log: Option<DepotLogCallback>) -> Result<Arc<File>, String> {
        let slot = self
            .slots
            .get(file_idx)
            .ok_or_else(|| "write_depot: bad file index".to_string())?;
        let mut st = slot.state.lock().expect("slot state poisoned");
        if !st.opened {
            make_parent_dirs(Path::new(&slot.path))?;
            let file = OpenOptions::new()
                .create(true)
                .write(true)
                .read(true)
                .truncate(false)
                .open(&slot.path)
                .map_err(|err| format!("write_depot: open '{}': {err}", slot.path))?;
            set_file_mode(Path::new(&slot.path), slot.mode)?;
            // Pre-allocate real contiguous blocks to the EXACT manifest size — unless the file is
            // already at/over that size (resume), where we must not re-allocate or shrink here.
            if slot.size > 0 && slot.preexisting_len < slot.size {
                preallocate_file(&file, slot.size, &self.fallocate_fallback_logged, log)?;
            }
            st.handle = Some(Arc::new(file));
            st.opened = true;
        }
        st.handle
            .as_ref()
            .map(Arc::clone)
            .ok_or_else(|| format!("write_depot: handle already closed for '{}'", slot.path))
    }

    /// Mark one chunk of a file done. The worker that lands the LAST chunk (`remaining` reaches 0)
    /// sets the file to its exact size, fsyncs it, and closes the shared handle — exactly once,
    /// never per-worker/per-chunk.
    fn complete_chunk(&self, file_idx: usize, handle: &Arc<File>) -> Result<(), String> {
        let slot = self
            .slots
            .get(file_idx)
            .ok_or_else(|| "write_depot: bad file index".to_string())?;
        if slot.remaining.fetch_sub(1, Ordering::AcqRel) == 1 {
            handle
                .set_len(slot.size)
                .map_err(|err| format!("write_depot: final truncate '{}': {err}", slot.path))?;
            handle
                .sync_all()
                .map_err(|err| format!("write_depot: final sync '{}': {err}", slot.path))?;
            // Drop the table's strong ref; the fd closes once the caller's `handle` clone drops.
            slot.state.lock().expect("slot state poisoned").handle = None;
        }
        Ok(())
    }

    /// After the pools join: finalize any regular file the counter path missed — a straggler still
    /// holding an open handle (a safety net; on success this is a no-op) and 0-chunk regular files
    /// (which have no chunk jobs to trigger `complete_chunk`).
    fn finalize_remaining(&self) -> Result<(), String> {
        for slot in &self.slots {
            if !slot.is_regular {
                continue;
            }
            let mut st = slot.state.lock().expect("slot state poisoned");
            if let Some(handle) = st.handle.take() {
                handle.set_len(slot.size).map_err(|err| {
                    format!("write_depot: final truncate '{}': {err}", slot.path)
                })?;
                handle
                    .sync_all()
                    .map_err(|err| format!("write_depot: final sync '{}': {err}", slot.path))?;
            } else if !st.opened {
                // 0-chunk regular file (never touched by a worker): ensure it exists at exact size.
                let file = OpenOptions::new()
                    .create(true)
                    .write(true)
                    .truncate(false)
                    .open(&slot.path)
                    .map_err(|err| format!("write_depot: final open '{}': {err}", slot.path))?;
                file.set_len(slot.size).map_err(|err| {
                    format!("write_depot: final truncate '{}': {err}", slot.path)
                })?;
                file.sync_all()
                    .map_err(|err| format!("write_depot: final sync '{}': {err}", slot.path))?;
            }
        }
        Ok(())
    }
}

// ─────────────────────────────────────────────────────────────────────────────────────────────
// Depot write entry point.
// ─────────────────────────────────────────────────────────────────────────────────────────────

pub fn write_depot_sequential(
    manifest: &ContentManifest,
    depot_key: &[u8],
    cdn: &CdnClient,
    servers: &[CContentServerDirectoryServerInfo],
    target_dir: &str,
    options: DepotWriteOptions<'_>,
) -> DepotWriteResult {
    let plan = match plan_depot_write(
        manifest,
        depot_key,
        servers.len(),
        target_dir,
        options.max_workers,
    ) {
        Ok(plan) => plan,
        Err(error) => return error,
    };

    let files = DepotFiles::prepare(manifest, target_dir);

    // Free-space guard using the EXACT manifest sizes (ground truth), minus what is already on disk.
    // Conservative: only fail when statvfs succeeds with a sane non-zero figure and the deficit
    // clearly exceeds the margin — a wrong statvfs must never false-fail a valid download.
    let needed = plan.total_bytes.saturating_sub(files.already_present_bytes);
    if let Some(available) = available_space_bytes(target_dir) {
        if available > 0 && available.saturating_add(FREE_SPACE_MARGIN_BYTES) < needed {
            return DepotWriteResult::fail(
                format!(
                    "write_depot: not enough free space (need {} MiB, have {} MiB)",
                    needed / (1024 * 1024),
                    available / (1024 * 1024)
                ),
                false,
            );
        }
    }

    let layout = create_depot_layout(&plan);
    if !layout.ok() {
        return layout;
    }

    let meter = BandwidthMeter::new(servers);
    let depot_id = manifest.metadata.depot_id;

    let result = if plan.worker_count > 1 && plan.chunk_jobs.len() > 1 && !servers.is_empty() {
        write_depot_parallel(
            manifest, depot_key, cdn, servers, &plan, &options, &files, &meter,
        )
    } else {
        write_depot_single(
            manifest, depot_key, cdn, servers, &plan, &options, &files, &meter,
        )
    };

    if let Some(log) = options.log {
        log(&meter.summary_line(depot_id));
    }
    result
}

/// Single-connection path (1 worker or ≤1 chunk). No concurrency, so a plain positioned write is
/// race-free; still uses the held-handle table so it never re-opens per chunk.
#[allow(clippy::too_many_arguments)]
fn write_depot_single(
    manifest: &ContentManifest,
    depot_key: &[u8],
    cdn: &CdnClient,
    servers: &[CContentServerDirectoryServerInfo],
    plan: &DepotWritePlan,
    options: &DepotWriteOptions<'_>,
    files: &DepotFiles,
    meter: &BandwidthMeter,
) -> DepotWriteResult {
    let total_bytes = plan.total_bytes;
    let mut bytes_written = 0u64;
    let mut conn = cdn.open_connection();
    for (job_index, job) in plan.chunk_jobs.iter().enumerate() {
        if options
            .cancel
            .is_some_and(|cancel| cancel.load(Ordering::Relaxed))
        {
            return DepotWriteResult::fail("cancelled", true);
        }
        let file_idx = job.file_idx as usize;
        let file = match manifest.files.get(file_idx) {
            Some(file) => file,
            None => return DepotWriteResult::fail("bad file index", true),
        };
        let chunk = match file.chunks.get(job.chunk_idx as usize) {
            Some(chunk) => chunk,
            None => return DepotWriteResult::fail("bad chunk index", true),
        };
        let handle = match files.acquire(file_idx, options.log) {
            Ok(handle) => handle,
            Err(error) => return DepotWriteResult::fail(error, true),
        };
        if files.needs_verify(file_idx) && existing_chunk_matches(&handle, chunk) {
            bytes_written += chunk.cb_original as u64;
            if let Some(on_progress) = options.on_progress {
                on_progress(bytes_written, total_bytes, true);
            }
            if let Err(error) = files.complete_chunk(file_idx, &handle) {
                return DepotWriteResult::fail(error, true);
            }
            continue;
        }
        match fetch_process_write_chunk(
            cdn,
            Some(&mut conn),
            servers,
            manifest,
            file_idx,
            job.chunk_idx as usize,
            depot_key,
            &handle,
            options.cdn_auth_token,
            job_index % servers.len(),
            options.timeout,
            Some(meter),
        ) {
            Ok(bytes) => {
                bytes_written += bytes;
                if let Some(on_progress) = options.on_progress {
                    on_progress(bytes_written, total_bytes, false);
                }
                if let Err(error) = files.complete_chunk(file_idx, &handle) {
                    return DepotWriteResult::fail(error, true);
                }
            }
            Err(error) => return DepotWriteResult::fail(error, true),
        }
    }

    if let Err(error) = files.finalize_remaining() {
        return DepotWriteResult::fail(error, true);
    }

    DepotWriteResult {
        files_written: plan.files_written,
        bytes_written,
        resume_trust_safe: true,
        error: String::new(),
    }
}

/// Decoupled fetch→process depot writer (B2b async fetch).
///
/// The **fetch side** is an async driver on ONE tokio current-thread runtime (built here so the rest
/// of the `.so` stays sync): it keeps up to an ADAPTIVE window of lightweight chunk requests in flight
/// (async tasks, not OS threads), fanned across CDN hosts with a per-host cap and speed-ranked
/// selection, and hands each fetched RAW (still-encrypted) chunk to a byte-budget-bounded channel. A
/// chunk already correct on disk is counted as verifying and never fetched. The **process side** is
/// unchanged from B2a — a pool of OS threads draining the channel and doing
/// decrypt+decompress+positioned-write (`pwrite`), counting bytes on the WRITE and finalizing each
/// file exactly once as its last chunk lands. Decode/write stay sync; only the fetch mechanism is
/// async.
///
/// Slow-connection safety is structural: growth stops the moment throughput plateaus (a thin link
/// plateaus almost immediately), the window shrinks proportionally on REAL errors (429 / timeout /
/// connection reset / 5xx) with a cooldown, it never exceeds the per-host cap or tier ceiling, and the
/// byte budget hard-bounds raw memory regardless of window.
#[allow(clippy::too_many_arguments)]
fn write_depot_parallel(
    manifest: &ContentManifest,
    depot_key: &[u8],
    cdn: &CdnClient,
    servers: &[CContentServerDirectoryServerInfo],
    plan: &DepotWritePlan,
    options: &DepotWriteOptions<'_>,
    files: &DepotFiles,
    meter: &BandwidthMeter,
) -> DepotWriteResult {
    let total_bytes = plan.total_bytes;
    let bytes_written = AtomicU64::new(0);
    let in_flight = AtomicU64::new(0);
    let error_slot: Mutex<Option<String>> = Mutex::new(None);
    let reporter_done = AtomicBool::new(false);
    let jobs = &plan.chunk_jobs;

    let proc_count = (options.max_process_workers as usize).max(1).min(jobs.len());
    let cdn_auth_token = options.cdn_auth_token;
    let timeout = options.timeout;
    let cancel = options.cancel;
    let progress = options.on_progress;
    let log = options.log;
    let depot_id = manifest.metadata.depot_id;

    // Tier ceiling → adaptive window bounds (clamped to distinct-hosts × per-host-cap).
    let distinct_hosts = distinct_host_count(servers);
    let (bootstrap, win_min, win_max) =
        window_bounds(options.max_workers as usize, distinct_hosts, PER_HOST_CAP);
    // The in-flight byte budget is sized from the SAME effective ceiling the window is bounded by, so
    // memory stops being the thing that refuses a slot the window already decided is safe.
    let budget = inflight_budget_bytes(win_max);
    if let Some(log) = log {
        // One line per depot showing exactly how the ceiling was derived, so a low ceiling is
        // attributable to the tier or to the host count without guessing — and what byte budget that
        // ceiling bought, so a future `budget_stalls` spike is attributable too.
        log(&format!(
            "fetch-window depot={depot_id} init window={bootstrap} (min={win_min} max={win_max}) \
tier_max={} servers={} distinct_hosts={distinct_hosts} per_host_cap={PER_HOST_CAP} \
host_ceiling={} budget={}MiB reason=start",
            options.max_workers,
            servers.len(),
            distinct_hosts.saturating_mul(PER_HOST_CAP),
            budget / (1024 * 1024),
        ));
    }

    let scope_result = thread::scope(|scope| -> DepotWriteResult {
        // Async(fetch) → sync(process) hand-off: a tokio unbounded mpsc. The fetch side uses the
        // non-blocking `send` (never parks a tokio worker); the process side drains with
        // `blocking_recv`. Memory is bounded not by channel capacity but by the RAW byte budget
        // enforced at DISPATCH. `rx` is created inside the scope, so it is shared as an owned `Arc`
        // clone per process worker.
        let (tx, rx) = tokio::sync::mpsc::unbounded_channel::<(ChunkWriteJob, Vec<u8>)>();
        let rx = Arc::new(Mutex::new(rx));
        // Reference bindings so the `move` workers capture Copy references, not the owned locals.
        let bytes_written = &bytes_written;
        let in_flight = &in_flight;
        let error_slot = &error_slot;
        let reporter_done = &reporter_done;

        // Periodic throughput reporter — atomics only, never on the fetch hot path.
        let reporter = if log.is_some() {
            Some(scope.spawn(move || loop {
                let mut waited = 0u64;
                while waited < BANDWIDTH_LOG_INTERVAL_MS {
                    if reporter_done.load(Ordering::Relaxed) {
                        return;
                    }
                    thread::sleep(Duration::from_millis(100));
                    waited += 100;
                }
                if reporter_done.load(Ordering::Relaxed) {
                    return;
                }
                if let Some(log) = log {
                    log(&meter.summary_line(depot_id));
                }
            }))
        } else {
            None
        };

        // ── Process pool: decrypt + decompress + positioned-write each fetched chunk. ──
        // Unchanged from B2a except the receiver is a tokio mpsc drained with `blocking_recv`.
        let mut proc_handles = Vec::with_capacity(proc_count);
        for _ in 0..proc_count {
            let rx = Arc::clone(&rx);
            proc_handles.push(scope.spawn(move || {
                loop {
                    if cancel.is_some_and(|c| c.load(Ordering::Relaxed)) {
                        return;
                    }
                    if error_slot.lock().expect("err slot poisoned").is_some() {
                        return;
                    }
                    // Lock held only across the (fast) dequeue; the decode+write below runs unlocked.
                    let msg = {
                        let mut guard = rx.lock().expect("rx poisoned");
                        guard.blocking_recv()
                    };
                    let (job, raw) = match msg {
                        Some(item) => item,
                        None => return,
                    };
                    let raw_len = raw.len() as u64;
                    let file_idx = job.file_idx as usize;
                    let file = match manifest.files.get(file_idx) {
                        Some(file) => file,
                        None => {
                            in_flight.fetch_sub(raw_len, Ordering::Relaxed);
                            record_first_error(error_slot, "bad file index".to_string());
                            return;
                        }
                    };
                    let chunk = match file.chunks.get(job.chunk_idx as usize) {
                        Some(chunk) => chunk,
                        None => {
                            in_flight.fetch_sub(raw_len, Ordering::Relaxed);
                            record_first_error(error_slot, "bad chunk index".to_string());
                            return;
                        }
                    };
                    let handle = match files.acquire(file_idx, log) {
                        Ok(handle) => handle,
                        Err(error) => {
                            in_flight.fetch_sub(raw_len, Ordering::Relaxed);
                            record_first_error(error_slot, error);
                            return;
                        }
                    };
                    match process_and_write_chunk(&handle, chunk, &raw, depot_key) {
                        Ok(bytes) => {
                            // Free the budget on WRITE-COMPLETE (actual raw bytes).
                            in_flight.fetch_sub(raw_len, Ordering::Relaxed);
                            if let Err(error) = files.complete_chunk(file_idx, &handle) {
                                record_first_error(error_slot, error);
                                return;
                            }
                            let total = bytes_written.fetch_add(bytes, Ordering::Relaxed) + bytes;
                            if let Some(cb) = progress {
                                cb(total, total_bytes, false);
                            }
                        }
                        Err(error) => {
                            in_flight.fetch_sub(raw_len, Ordering::Relaxed);
                            record_first_error(error_slot, error);
                            return;
                        }
                    }
                }
            }));
        }

        // ── Async fetch driver: keep an adaptive window of requests in flight on one runtime. ──
        // `tx` is MOVED in and dropped when the driver returns, which closes the channel so the
        // process pool's `blocking_recv` returns `None` and its workers exit.
        match tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
        {
            Ok(rt) => {
                rt.block_on(run_async_fetch_driver(
                    manifest,
                    servers,
                    cdn,
                    files,
                    jobs,
                    bytes_written,
                    in_flight,
                    error_slot,
                    cancel,
                    progress,
                    meter,
                    log,
                    tx,
                    cdn_auth_token,
                    timeout,
                    total_bytes,
                    budget,
                    bootstrap,
                    win_min,
                    win_max,
                    depot_id,
                ));
            }
            Err(err) => {
                record_first_error(error_slot, format!("write_depot: tokio runtime: {err}"));
                drop(tx);
            }
        }

        for handle in proc_handles {
            let _ = handle.join();
        }
        reporter_done.store(true, Ordering::Relaxed);
        if let Some(reporter) = reporter {
            let _ = reporter.join();
        }

        if cancel.is_some_and(|c| c.load(Ordering::Relaxed)) {
            return DepotWriteResult::fail("cancelled", true);
        }
        if let Some(error) = error_slot.lock().expect("err slot poisoned").take() {
            return DepotWriteResult::fail(error, true);
        }
        DepotWriteResult {
            files_written: plan.files_written,
            bytes_written: bytes_written.load(Ordering::Relaxed),
            resume_trust_safe: true,
            error: String::new(),
        }
    });

    if !scope_result.ok() {
        return scope_result;
    }
    if let Err(error) = files.finalize_remaining() {
        return DepotWriteResult::fail(error, true);
    }
    scope_result
}

/// The async fetch driver — runs on one tokio current-thread runtime via `block_on`. It keeps an
/// adaptive window of chunk requests in flight in a [`FuturesUnordered`] (NOT spawned: the futures
/// borrow the depot state directly, and dropping the set ABORTS every in-flight request — that is the
/// cancel/error teardown). It skips already-correct chunks inline (resume/verify), reserves the byte
/// budget at DISPATCH, and hands raw chunks to the process pool. `tx` is dropped on return.
#[allow(clippy::too_many_arguments)]
async fn run_async_fetch_driver(
    manifest: &ContentManifest,
    servers: &[CContentServerDirectoryServerInfo],
    cdn: &CdnClient,
    files: &DepotFiles,
    jobs: &[ChunkWriteJob],
    bytes_written: &AtomicU64,
    in_flight: &AtomicU64,
    error_slot: &Mutex<Option<String>>,
    cancel: Option<&AtomicBool>,
    progress: Option<DepotChunkProgressCallback<'_>>,
    meter: &BandwidthMeter,
    log: Option<DepotLogCallback<'_>>,
    tx: tokio::sync::mpsc::UnboundedSender<(ChunkWriteJob, Vec<u8>)>,
    cdn_auth_token: &str,
    timeout: Duration,
    total_bytes: u64,
    budget: u64,
    bootstrap: usize,
    win_min: usize,
    win_max: usize,
    depot_id: u32,
) {
    let async_client = match AsyncCdnClient::new(cdn.ca_bundle_path(), PER_HOST_CAP) {
        Ok(client) => client,
        Err(err) => {
            record_first_error(error_slot, format!("write_depot: {err}"));
            drop(tx);
            return;
        }
    };

    let mut window = AdaptiveWindow::new(bootstrap, win_min, win_max, Instant::now());
    let mut sched = FetchScheduler::new(servers, PER_HOST_CAP);
    let mut inflight: FuturesUnordered<_> = FuturesUnordered::new();
    let mut ready: VecDeque<ChunkWriteJob> = VecDeque::new();
    let mut retry: VecDeque<PendingChunk> = VecDeque::new();
    let mut next_job = 0usize;
    let mut verify_since_yield: u32 = 0;

    loop {
        if cancel.is_some_and(|c| c.load(Ordering::Relaxed))
            || error_slot.lock().expect("err slot poisoned").is_some()
        {
            break;
        }
        let probe_now = Instant::now();
        if window.maybe_probe(probe_now, meter.total_bytes()) {
            if let Some(log) = log {
                log(&window.summary_line(depot_id, inflight.len(), probe_now));
            }
        }

        // ── Dispatch up to the current window ──
        let mut aborted = false;
        while inflight.len() < window.current {
            if cancel.is_some_and(|c| c.load(Ordering::Relaxed))
                || error_slot.lock().expect("err slot poisoned").is_some()
            {
                aborted = true;
                break;
            }
            let now = Instant::now();

            // Refill `ready` from fresh jobs, skipping already-correct chunks inline (resume/verify).
            while ready.is_empty()
                && next_job < jobs.len()
                && !retry.front().is_some_and(|p| p.not_before <= now)
            {
                let job = jobs[next_job];
                next_job += 1;
                let file_idx = job.file_idx as usize;
                if files.needs_verify(file_idx) {
                    let handle = match files.acquire(file_idx, log) {
                        Ok(handle) => handle,
                        Err(error) => {
                            record_first_error(error_slot, error);
                            aborted = true;
                            break;
                        }
                    };
                    let chunk = match manifest
                        .files
                        .get(file_idx)
                        .and_then(|f| f.chunks.get(job.chunk_idx as usize))
                    {
                        Some(chunk) => chunk,
                        None => {
                            record_first_error(error_slot, "bad chunk index".to_string());
                            aborted = true;
                            break;
                        }
                    };
                    if existing_chunk_matches(&handle, chunk) {
                        let total = bytes_written
                            .fetch_add(chunk.cb_original as u64, Ordering::Relaxed)
                            + chunk.cb_original as u64;
                        if let Some(cb) = progress {
                            cb(total, total_bytes, true);
                        }
                        if let Err(error) = files.complete_chunk(file_idx, &handle) {
                            record_first_error(error_slot, error);
                            aborted = true;
                            break;
                        }
                        verify_since_yield += 1;
                        // While the pipe is busy, don't let a long verify run starve in-flight
                        // fetches; with nothing in flight there is nothing to starve, so blast on.
                        if !inflight.is_empty() && verify_since_yield >= VERIFY_YIELD_BATCH {
                            break;
                        }
                        continue;
                    }
                }
                ready.push_back(job);
            }
            if aborted {
                break;
            }

            // Choose the next unit of work: a due retry first, else a fresh ready job.
            let use_retry = retry.front().is_some_and(|p| p.not_before <= now);
            let (job, attempts) = if use_retry {
                let p = *retry.front().expect("retry nonempty");
                (p.job, p.attempts)
            } else if let Some(&job) = ready.front() {
                (job, 0u32)
            } else {
                break; // nothing dispatchable now (verify yield, retry not due, or all done)
            };

            // Byte-budget gate (hard memory bound): reserve the compressed size at DISPATCH. Always
            // admits when nothing is in flight, so a chunk bigger than the whole budget can't deadlock.
            let reserve = reserve_bytes(manifest, job);
            if !budget_admits(in_flight.load(Ordering::Relaxed), reserve, budget) {
                // Diagnostic only: the window wanted this slot, the memory budget refused it.
                window.note_budget_stall();
                break; // wait for a completion to free budget
            }

            // Speed-ranked, per-host-capped server pick.
            let Some(server_idx) = sched.pick(now) else {
                // Diagnostic only: the window wanted this slot, hosts × per-host-cap refused it.
                window.note_host_stall();
                break; // every host at cap or cooling → wait for a completion
            };
            let permit = match sched.host_sem(server_idx).clone().try_acquire_owned() {
                Ok(permit) => permit,
                Err(_) => {
                    window.note_host_stall();
                    break; // raced to the cap; wait
                }
            };

            let chunk_sha = match manifest
                .files
                .get(job.file_idx as usize)
                .and_then(|f| f.chunks.get(job.chunk_idx as usize))
            {
                Some(chunk) => chunk.sha.clone(),
                None => {
                    record_first_error(error_slot, "bad chunk index".to_string());
                    aborted = true;
                    break;
                }
            };
            let url = match cdn.build_chunk_url(
                &servers[server_idx],
                depot_id,
                &chunk_sha,
                cdn_auth_token,
            ) {
                Ok(url) => url,
                Err(error) => {
                    record_first_error(error_slot, error);
                    aborted = true;
                    break;
                }
            };

            // Commit: pop the work item, reserve budget, launch the fetch future.
            if use_retry {
                retry.pop_front();
            } else {
                ready.pop_front();
            }
            in_flight.fetch_add(reserve, Ordering::Relaxed);
            verify_since_yield = 0;
            let async_client = &async_client;
            let started = Instant::now();
            inflight.push(async move {
                let res = async_client.fetch_url_async(&url, timeout).await;
                drop(permit); // RAII per-host permit release (also on future drop / cancel-abort)
                FetchDone {
                    job,
                    attempts: attempts + 1,
                    reserve,
                    server_idx,
                    elapsed: started.elapsed(),
                    res,
                }
            });
        }
        if aborted {
            break;
        }

        // ── Nothing in flight? either finished, or waiting on budget / retry-not-due / host cooldown.
        if inflight.is_empty() {
            if next_job >= jobs.len() && ready.is_empty() && retry.is_empty() {
                break; // every chunk fetched or verified
            }
            tokio::time::sleep(Duration::from_millis(5)).await;
            continue;
        }

        // ── Await one completion (drives ALL in-flight requests cooperatively). ──
        let Some(done) = inflight.next().await else {
            continue;
        };
        let now = Instant::now();
        match done.res {
            Ok(raw) => {
                let raw_len = raw.len() as u64;
                meter.record(done.server_idx, raw_len);
                sched.on_success(done.server_idx, raw_len, done.elapsed);
                window.record_ok(done.elapsed.as_secs_f64() * 1000.0);
                // Reconcile the dispatch reservation to the actual raw size; the process pool then
                // subtracts the actual size on WRITE-COMPLETE, netting this chunk's budget to zero.
                if raw_len >= done.reserve {
                    in_flight.fetch_add(raw_len - done.reserve, Ordering::Relaxed);
                } else {
                    in_flight.fetch_sub(done.reserve - raw_len, Ordering::Relaxed);
                }
                if tx.send((done.job, raw)).is_err() {
                    break; // process side gone
                }
            }
            Err(err) => {
                in_flight.fetch_sub(done.reserve, Ordering::Relaxed);
                sched.on_error(done.server_idx, now, err.kind);
                if window.record_err(now, err.kind) {
                    if let Some(log) = log {
                        log(&window.summary_line(depot_id, inflight.len(), now));
                    }
                }
                // Preserve the existing per-chunk retry/rotation as fallback: up to
                // MAX_CHUNK_ATTEMPTS, each re-dispatch rotates to a different (non-cooling) host with
                // the same exponential backoff.
                if done.attempts < MAX_CHUNK_ATTEMPTS {
                    let backoff = retry_backoff_millis(done.attempts);
                    retry.push_back(PendingChunk {
                        job: done.job,
                        attempts: done.attempts,
                        not_before: now + Duration::from_millis(backoff),
                    });
                } else {
                    let name = manifest
                        .files
                        .get(done.job.file_idx as usize)
                        .map(|f| f.filename.clone())
                        .unwrap_or_default();
                    record_first_error(
                        error_slot,
                        format!(
                            "write_depot: chunk for '{}' failed after {} attempts: {}",
                            name, MAX_CHUNK_ATTEMPTS, err.message
                        ),
                    );
                    break;
                }
            }
        }
    }

    // Close the channel so the process pool drains and exits; dropping `inflight` aborts any
    // still-running requests (the cancel / error / final-failure teardown path).
    drop(tx);
    drop(inflight);
}

pub fn create_depot_layout(plan: &DepotWritePlan) -> DepotWriteResult {
    for action in &plan.actions {
        let result = match action {
            DepotFileAction::Directory { path } => create_directory(path),
            DepotFileAction::Symlink { path, target } => create_symlink(path, target),
            DepotFileAction::Regular { path, mode, .. } => create_regular_file(path, *mode),
        };
        if let Err(error) = result {
            return DepotWriteResult::fail(error, false);
        }
    }
    DepotWriteResult {
        files_written: plan.files_written,
        bytes_written: 0,
        resume_trust_safe: true,
        error: String::new(),
    }
}

/// Positioned write to an already-open file handle. Uses `pwrite` (no shared seek cursor) so
/// concurrent writers to different offsets of the same file never race.
pub fn write_chunk_at(file: &File, offset: u64, data: &[u8]) -> Result<u64, String> {
    pwrite_all_at(file, offset, data)
        .map_err(|err| format!("write_depot: write at offset {offset}: {err}"))?;
    Ok(data.len() as u64)
}

pub fn process_and_write_chunk(
    file: &File,
    chunk: &ChunkData,
    raw_chunk: &[u8],
    depot_key: &[u8],
) -> Result<u64, String> {
    let processed = process_depot_chunk(raw_chunk, depot_key, chunk.crc, chunk.cb_original);
    if !processed.ok() {
        return Err(format!("decode: {}", processed.error));
    }
    write_chunk_at(file, chunk.offset, &processed.data)
}

/// Record the FIRST error seen by either pool; later workers must not clobber it.
fn record_first_error(slot: &Mutex<Option<String>>, err: String) {
    let mut guard = slot.lock().expect("err slot poisoned");
    if guard.is_none() {
        *guard = Some(err);
    }
}

/// Fetch ONE raw (still-encrypted, still-compressed) chunk from the CDN — the network half of
/// [`fetch_process_write_chunk`], split out for the decoupled two-pool writer. Same retry/rotation
/// as the fused path: [`MAX_CHUNK_ATTEMPTS`] tries, server rotation via
/// [`chunk_attempt_server_indices`], [`retry_backoff_millis`] backoff, and a fresh connection on
/// each retry. On success the winning server's byte count is recorded to the bandwidth meter.
#[allow(clippy::too_many_arguments)]
pub fn fetch_raw_chunk(
    cdn: &CdnClient,
    mut conn: Option<&mut CdnConnection>,
    servers: &[CContentServerDirectoryServerInfo],
    manifest: &ContentManifest,
    file_idx: usize,
    chunk_idx: usize,
    cdn_auth_token: &str,
    start_server_index: usize,
    timeout: Duration,
    meter: Option<&BandwidthMeter>,
) -> Result<Vec<u8>, String> {
    if servers.is_empty() {
        return Err("write_depot: no CDN servers".to_string());
    }
    let file = manifest
        .files
        .get(file_idx)
        .ok_or_else(|| "write_depot: bad file index".to_string())?;
    let chunk = file
        .chunks
        .get(chunk_idx)
        .ok_or_else(|| "write_depot: bad chunk index".to_string())?;
    let mut last_error = String::new();
    for (attempt, server_idx) in
        chunk_attempt_server_indices(start_server_index, servers.len(), MAX_CHUNK_ATTEMPTS)
            .into_iter()
            .enumerate()
    {
        if attempt > 0 {
            thread::sleep(Duration::from_millis(retry_backoff_millis(attempt as u32)));
            if let Some(connection) = conn.as_deref_mut() {
                *connection = cdn.open_connection();
            }
        }
        let fetched = match conn.as_deref_mut() {
            Some(connection) => cdn.fetch_chunk_with_connection(
                connection,
                &servers[server_idx],
                manifest.metadata.depot_id,
                &chunk.sha,
                cdn_auth_token,
                timeout,
            ),
            None => cdn.fetch_chunk(
                &servers[server_idx],
                manifest.metadata.depot_id,
                &chunk.sha,
                cdn_auth_token,
                timeout,
            ),
        };
        if !fetched.ok() {
            last_error = fetched.error;
            continue;
        }
        if let Some(meter) = meter {
            meter.record(server_idx, fetched.data.len() as u64);
        }
        return Ok(fetched.data);
    }
    Err(format!(
        "write_depot: chunk for '{}' failed after {} attempts: {}",
        file.filename, MAX_CHUNK_ATTEMPTS, last_error
    ))
}

#[allow(clippy::too_many_arguments)]
pub fn fetch_process_write_chunk(
    cdn: &CdnClient,
    mut conn: Option<&mut CdnConnection>,
    servers: &[CContentServerDirectoryServerInfo],
    manifest: &ContentManifest,
    file_idx: usize,
    chunk_idx: usize,
    depot_key: &[u8],
    file_handle: &File,
    cdn_auth_token: &str,
    start_server_index: usize,
    timeout: Duration,
    meter: Option<&BandwidthMeter>,
) -> Result<u64, String> {
    if servers.is_empty() {
        return Err("write_depot: no CDN servers".to_string());
    }
    let file = manifest
        .files
        .get(file_idx)
        .ok_or_else(|| "write_depot: bad file index".to_string())?;
    let chunk = file
        .chunks
        .get(chunk_idx)
        .ok_or_else(|| "write_depot: bad chunk index".to_string())?;
    let mut last_error = String::new();
    for (attempt, server_idx) in
        chunk_attempt_server_indices(start_server_index, servers.len(), MAX_CHUNK_ATTEMPTS)
            .into_iter()
            .enumerate()
    {
        if attempt > 0 {
            thread::sleep(Duration::from_millis(retry_backoff_millis(attempt as u32)));
            if let Some(connection) = conn.as_deref_mut() {
                *connection = cdn.open_connection();
            }
        }
        let fetched = match conn.as_deref_mut() {
            Some(connection) => cdn.fetch_chunk_with_connection(
                connection,
                &servers[server_idx],
                manifest.metadata.depot_id,
                &chunk.sha,
                cdn_auth_token,
                timeout,
            ),
            None => cdn.fetch_chunk(
                &servers[server_idx],
                manifest.metadata.depot_id,
                &chunk.sha,
                cdn_auth_token,
                timeout,
            ),
        };
        if !fetched.ok() {
            last_error = fetched.error;
            continue;
        }
        if let Some(meter) = meter {
            meter.record(server_idx, fetched.data.len() as u64);
        }
        match process_and_write_chunk(file_handle, chunk, &fetched.data, depot_key) {
            Ok(bytes) => return Ok(bytes),
            Err(error) => last_error = error,
        }
    }
    Err(format!(
        "write_depot: chunk for '{}' failed after {} attempts: {}",
        file.filename, MAX_CHUNK_ATTEMPTS, last_error
    ))
}

pub fn existing_chunk_matches(file: &File, chunk: &ChunkData) -> bool {
    if chunk.cb_original == 0 {
        return false;
    }
    let Ok(metadata) = file.metadata() else {
        return false;
    };
    let end = chunk.offset.saturating_add(chunk.cb_original as u64);
    if metadata.len() < end {
        return false;
    }
    let mut buf = vec![0u8; chunk.cb_original as usize];
    if pread_exact_at(file, chunk.offset, &mut buf).is_err() {
        return false;
    }
    depot_adler_hash(&buf) == chunk.crc
}

pub fn sync_file(path: impl AsRef<Path>) -> bool {
    OpenOptions::new()
        .read(true)
        .write(true)
        .open(path)
        .and_then(|file| file.sync_all())
        .is_ok()
}

pub fn finalize_regular_file(path: impl AsRef<Path>, size: u64) -> Result<(), String> {
    let path = path.as_ref();
    let file = OpenOptions::new()
        .write(true)
        .open(path)
        .map_err(|err| format!("write_depot: final open '{}': {err}", path.display()))?;
    file.set_len(size)
        .map_err(|err| format!("write_depot: final truncate '{}': {err}", path.display()))?;
    file.sync_all()
        .map_err(|err| format!("write_depot: final sync '{}': {err}", path.display()))
}

// ─────────────────────────────────────────────────────────────────────────────────────────────
// Positioned IO + pre-allocation + free-space (platform helpers).
// ─────────────────────────────────────────────────────────────────────────────────────────────

#[cfg(unix)]
fn pwrite_all_at(file: &File, offset: u64, data: &[u8]) -> std::io::Result<()> {
    use std::os::unix::fs::FileExt;
    file.write_all_at(data, offset)
}

#[cfg(not(unix))]
fn pwrite_all_at(file: &File, offset: u64, data: &[u8]) -> std::io::Result<()> {
    use std::os::windows::fs::FileExt;
    let mut done = 0usize;
    while done < data.len() {
        let n = file.seek_write(&data[done..], offset + done as u64)?;
        if n == 0 {
            return Err(std::io::Error::new(
                std::io::ErrorKind::WriteZero,
                "seek_write wrote 0",
            ));
        }
        done += n;
    }
    Ok(())
}

#[cfg(unix)]
fn pread_exact_at(file: &File, offset: u64, buf: &mut [u8]) -> std::io::Result<()> {
    use std::os::unix::fs::FileExt;
    file.read_exact_at(buf, offset)
}

#[cfg(not(unix))]
fn pread_exact_at(file: &File, offset: u64, buf: &mut [u8]) -> std::io::Result<()> {
    use std::os::windows::fs::FileExt;
    let mut done = 0usize;
    while done < buf.len() {
        let n = file.seek_read(&mut buf[done..], offset + done as u64)?;
        if n == 0 {
            return Err(std::io::Error::new(
                std::io::ErrorKind::UnexpectedEof,
                "seek_read reached EOF",
            ));
        }
        done += n;
    }
    Ok(())
}

enum FallocOutcome {
    Ok,
    Unsupported,
    OtherErr,
}

/// Pre-allocate `file` to exactly `size` bytes. Prefers `fallocate` for real contiguous blocks;
/// on FUSE/sdcardfs (`/storage/emulated`, SD) that returns EOPNOTSUPP/ENOSYS, so we branch on the
/// errno and fall back to `set_len` (logging the fallback once per download, not per file). Never
/// hard-fails on the unsupported case.
fn preallocate_file(
    file: &File,
    size: u64,
    fallback_logged: &AtomicBool,
    log: Option<DepotLogCallback>,
) -> Result<(), String> {
    if size == 0 {
        return Ok(());
    }
    match try_fallocate(file, size) {
        FallocOutcome::Ok => Ok(()),
        FallocOutcome::Unsupported => {
            if !fallback_logged.swap(true, Ordering::Relaxed) {
                if let Some(log) = log {
                    log("fallocate unsupported on target filesystem (FUSE/sdcardfs?); using set_len fallback for this download");
                }
            }
            file.set_len(size)
                .map_err(|err| format!("write_depot: set_len fallback failed: {err}"))
        }
        FallocOutcome::OtherErr => {
            // Not the unsupported case (e.g. ENOSPC): still fall back to a sparse set_len so we do
            // not hard-fail here — a genuine out-of-space surfaces on the actual write, and the
            // free-space guard is the real gate.
            file.set_len(size)
                .map_err(|err| format!("write_depot: set_len fallback failed: {err}"))
        }
    }
}

#[cfg(any(target_os = "linux", target_os = "android"))]
fn try_fallocate(file: &File, size: u64) -> FallocOutcome {
    use std::os::unix::io::AsRawFd;
    // mode 0 = allocate blocks and extend the file to offset+len WITHOUT zeroing existing bytes,
    // so resumed content in [0, preexisting_len) is preserved and the tail reads as zeros.
    let rc = unsafe { libc::fallocate(file.as_raw_fd(), 0, 0, size as libc::off_t) };
    if rc == 0 {
        return FallocOutcome::Ok;
    }
    match std::io::Error::last_os_error().raw_os_error() {
        Some(errno) if errno == libc::EOPNOTSUPP || errno == libc::ENOSYS => {
            FallocOutcome::Unsupported
        }
        _ => FallocOutcome::OtherErr,
    }
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
fn try_fallocate(_file: &File, _size: u64) -> FallocOutcome {
    // No fallocate on this platform (host dev on macOS/Windows): take the set_len path.
    FallocOutcome::Unsupported
}

#[cfg(any(target_os = "linux", target_os = "android"))]
fn available_space_bytes(path: &str) -> Option<u64> {
    use std::ffi::CString;
    let c_path = CString::new(path).ok()?;
    let mut st: libc::statvfs = unsafe { std::mem::zeroed() };
    let rc = unsafe { libc::statvfs(c_path.as_ptr(), &mut st) };
    if rc != 0 {
        return None;
    }
    // Space available to an unprivileged process = f_bavail * f_frsize.
    Some((st.f_bavail as u64).saturating_mul(st.f_frsize as u64))
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
fn available_space_bytes(_path: &str) -> Option<u64> {
    None
}

fn join_target_path(target_dir: &str, rel: &str) -> String {
    if target_dir.ends_with('/') || target_dir.ends_with('\\') {
        format!("{target_dir}{rel}")
    } else {
        format!("{target_dir}/{rel}")
    }
}

fn create_directory(path: &str) -> Result<(), String> {
    fs::create_dir_all(path).map_err(|err| format!("write_depot: mkdir '{path}': {err}"))
}

fn create_regular_file(path: &str, mode: u32) -> Result<(), String> {
    let path_ref = Path::new(path);
    make_parent_dirs(path_ref)?;
    OpenOptions::new()
        .create(true)
        .write(true)
        .read(true)
        .truncate(false)
        .open(path_ref)
        .map_err(|err| format!("write_depot: open '{path}': {err}"))?;
    set_file_mode(path_ref, mode)
}

fn create_symlink(path: &str, target: &str) -> Result<(), String> {
    let path_ref = Path::new(path);
    make_parent_dirs(path_ref)?;
    if path_ref.exists() {
        fs::remove_file(path_ref).map_err(|err| format!("write_depot: unlink '{path}': {err}"))?;
    }
    create_platform_symlink(target, path_ref)
        .map_err(|err| format!("write_depot: symlink '{path}': {err}"))
}

fn make_parent_dirs(path: &Path) -> Result<(), String> {
    if let Some(parent) = path.parent() {
        if !parent.as_os_str().is_empty() {
            fs::create_dir_all(parent)
                .map_err(|err| format!("write_depot: mkdir '{}': {err}", parent.display()))?;
        }
    }
    Ok(())
}

#[cfg(unix)]
fn create_platform_symlink(target: &str, path: &Path) -> std::io::Result<()> {
    std::os::unix::fs::symlink(target, path)
}

#[cfg(windows)]
fn create_platform_symlink(target: &str, path: &Path) -> std::io::Result<()> {
    std::os::windows::fs::symlink_file(target, path)
}

#[cfg(unix)]
fn set_file_mode(path: &Path, mode: u32) -> Result<(), String> {
    use std::os::unix::fs::PermissionsExt;
    let permissions = fs::Permissions::from_mode(mode);
    fs::set_permissions(path, permissions)
        .map_err(|err| format!("write_depot: chmod '{}': {err}", path.display()))
}

#[cfg(not(unix))]
fn set_file_mode(_path: &Path, _mode: u32) -> Result<(), String> {
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::crypto::{aes256_cbc_encrypt, aes256_ecb_encrypt_block, AES_BLOCK_BYTES};
    use std::path::PathBuf;

    #[test]
    fn depot_adler_uses_steam_zero_seed() {
        assert_eq!(depot_adler_hash(b""), 0);
        assert_eq!(depot_adler_hash(b"abc"), 0x024a_0126);
    }

    #[test]
    fn rejects_paths_that_escape_target() {
        assert!(path_is_safe("a/b/file.txt"));
        assert!(path_is_safe("./a/file.txt"));
        assert!(!path_is_safe(""));
        assert!(!path_is_safe("../file.txt"));
        assert!(!path_is_safe("a/../file.txt"));
        assert!(!path_is_safe("/abs/file.txt"));
    }

    #[test]
    fn retry_backoff_matches_cpp_caps() {
        assert_eq!(retry_backoff_millis(1), 300);
        assert_eq!(retry_backoff_millis(2), 600);
        assert_eq!(retry_backoff_millis(5), 4000);
        assert_eq!(MAX_CHUNK_ATTEMPTS, 5);
        assert_eq!(SLOW_CHUNK_ROTATE_THRESHOLD_SECS, 8);
    }

    #[test]
    fn chunk_retry_rotates_across_servers_like_cpp_worker() {
        assert_eq!(chunk_attempt_server_indices(0, 3, 5), [0, 1, 2, 0, 1]);
        assert_eq!(chunk_attempt_server_indices(2, 3, 5), [2, 0, 1, 2, 0]);
        assert_eq!(chunk_attempt_server_indices(0, 1, 5), [0, 0, 0, 0, 0]);
        assert!(chunk_attempt_server_indices(0, 0, 5).is_empty());
        assert!(!should_rotate_after_slow_chunks(2, 3));
        assert!(should_rotate_after_slow_chunks(3, 3));
        assert!(!should_rotate_after_slow_chunks(3, 1));
    }

    #[test]
    fn byte_budget_always_admits_at_least_one_chunk() {
        // Deadlock guard: a chunk larger than the whole budget still admits when nothing is queued.
        assert!(budget_admits(0, 100 * 1024 * 1024, 24 * 1024 * 1024));
        // Normal admits while under budget.
        assert!(budget_admits(0, 1_000_000, 24 * 1024 * 1024));
        assert!(budget_admits(20 * 1024 * 1024, 1_000_000, 24 * 1024 * 1024));
        // Blocks when the addition would exceed budget and something is already queued.
        assert!(!budget_admits(1, 100 * 1024 * 1024, 24 * 1024 * 1024));
        assert!(!budget_admits(24 * 1024 * 1024, 1, 24 * 1024 * 1024));
    }

    #[test]
    fn depot_plan_validates_inputs_and_enumerates_actions() {
        let manifest = ContentManifest {
            metadata: crate::content_manifest::Metadata {
                filenames_encrypted: false,
                ..Default::default()
            },
            files: vec![
                crate::content_manifest::FileMapping {
                    filename: "bin".into(),
                    flags: DEPOT_FILE_FLAG_DIRECTORY,
                    ..Default::default()
                },
                crate::content_manifest::FileMapping {
                    filename: "bin/game".into(),
                    size: 10,
                    flags: DEPOT_FILE_FLAG_EXECUTABLE,
                    chunks: vec![crate::content_manifest::ChunkData::default()],
                    ..Default::default()
                },
                crate::content_manifest::FileMapping {
                    filename: "link".into(),
                    linktarget: "bin/game".into(),
                    ..Default::default()
                },
            ],
            signature: Vec::new(),
        };

        let plan = plan_depot_write(&manifest, &[7u8; 32], 2, "/target", 99).unwrap();
        assert_eq!(plan.total_bytes, 10);
        assert_eq!(plan.files_written, 2);
        assert_eq!(plan.worker_count, 1);
        assert_eq!(
            plan.actions,
            [
                DepotFileAction::Directory {
                    path: "/target/bin".into()
                },
                DepotFileAction::Regular {
                    path: "/target/bin/game".into(),
                    size: 10,
                    mode: 0o755
                },
                DepotFileAction::Symlink {
                    path: "/target/link".into(),
                    target: "bin/game".into()
                }
            ]
        );
        assert_eq!(
            plan.chunk_jobs,
            [ChunkWriteJob {
                file_idx: 1,
                chunk_idx: 0
            }]
        );
    }

    #[test]
    fn depot_plan_rejects_cpp_error_cases() {
        let mut manifest = ContentManifest {
            files: vec![crate::content_manifest::FileMapping {
                filename: "../escape".into(),
                ..Default::default()
            }],
            ..Default::default()
        };
        assert_eq!(
            plan_depot_write(&manifest, &[0u8; 32], 1, "/target", 8)
                .unwrap_err()
                .error,
            "write_depot: unsafe path '../escape'"
        );
        manifest.files[0].filename = "ok".into();
        manifest.metadata.filenames_encrypted = true;
        assert_eq!(
            plan_depot_write(&manifest, &[0u8; 32], 1, "/target", 8)
                .unwrap_err()
                .error,
            "write_depot: manifest filenames are still encrypted"
        );
        manifest.metadata.filenames_encrypted = false;
        assert_eq!(
            plan_depot_write(&manifest, &[0u8; 31], 1, "/target", 8)
                .unwrap_err()
                .error,
            "write_depot: bad depot key length"
        );
        assert_eq!(
            plan_depot_write(&manifest, &[0u8; 32], 0, "/target", 8)
                .unwrap_err()
                .error,
            "write_depot: no CDN servers"
        );
    }

    #[test]
    fn worker_clamping_matches_cpp_limits() {
        assert_eq!(clamp_worker_count(0, 10), 1);
        assert_eq!(clamp_worker_count(128, 100), 64);
        assert_eq!(clamp_worker_count(8, 3), 3);
        assert_eq!(clamp_worker_count(8, 0), 0);
    }

    #[test]
    fn creates_layout_and_writes_chunks_at_offsets() {
        let dir = temp_dir("depot_writer_layout");
        let manifest = ContentManifest {
            metadata: crate::content_manifest::Metadata {
                filenames_encrypted: false,
                ..Default::default()
            },
            files: vec![
                crate::content_manifest::FileMapping {
                    filename: "bin".into(),
                    flags: DEPOT_FILE_FLAG_DIRECTORY,
                    ..Default::default()
                },
                crate::content_manifest::FileMapping {
                    filename: "bin/game.dat".into(),
                    size: 6,
                    chunks: vec![ChunkData {
                        offset: 2,
                        cb_original: 3,
                        crc: depot_adler_hash(b"abc"),
                        ..Default::default()
                    }],
                    ..Default::default()
                },
            ],
            signature: Vec::new(),
        };
        let plan = plan_depot_write(&manifest, &[1u8; 32], 1, dir.to_str().unwrap(), 4).unwrap();
        let result = create_depot_layout(&plan);
        assert!(result.ok(), "{}", result.error);
        let path = dir.join("bin/game.dat");
        assert!(path.exists());

        let file = OpenOptions::new()
            .read(true)
            .write(true)
            .open(&path)
            .unwrap();
        assert_eq!(write_chunk_at(&file, 2, b"abc").unwrap(), 3);
        assert!(existing_chunk_matches(&file, &manifest.files[1].chunks[0]));
        assert!(!existing_chunk_matches(
            &file,
            &ChunkData {
                offset: 2,
                cb_original: 3,
                crc: 1,
                ..Default::default()
            }
        ));
        drop(file);
        assert!(sync_file(&path));
        finalize_regular_file(&path, 6).unwrap();
        assert_eq!(fs::metadata(&path).unwrap().len(), 6);
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn concurrent_pwrite_at_different_offsets_is_race_free() {
        // Multiple threads share ONE handle and write different chunks of the SAME file via
        // write_at (pwrite). No shared seek cursor ⇒ no corruption regardless of interleaving.
        let dir = temp_dir("depot_writer_pwrite_concurrency");
        fs::create_dir_all(&dir).unwrap();
        let path = dir.join("data.bin");
        let file = OpenOptions::new()
            .create(true)
            .read(true)
            .write(true)
            .truncate(false)
            .open(&path)
            .unwrap();
        file.set_len(26).unwrap();
        let file = Arc::new(file);
        thread::scope(|scope| {
            for i in 0..26u64 {
                let file = Arc::clone(&file);
                scope.spawn(move || {
                    let byte = [b'a' + i as u8];
                    write_chunk_at(&file, i, &byte).unwrap();
                });
            }
        });
        let mut buf = vec![0u8; 26];
        pread_exact_at(&file, 0, &mut buf).unwrap();
        assert_eq!(&buf, b"abcdefghijklmnopqrstuvwxyz");
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn process_and_write_chunk_decrypts_and_materializes_bytes() {
        let dir = temp_dir("depot_writer_process_chunk");
        fs::create_dir_all(&dir).unwrap();
        let path = dir.join("content.bin");
        let key = [9u8; 32];
        let payload = b"materialized chunk";
        let raw = encrypted_stored_zip_chunk(&key, payload);
        let chunk = ChunkData {
            offset: 4,
            cb_original: payload.len() as u32,
            crc: depot_adler_hash(payload),
            ..Default::default()
        };

        let file = OpenOptions::new()
            .create(true)
            .read(true)
            .write(true)
            .truncate(false)
            .open(&path)
            .unwrap();
        assert_eq!(
            process_and_write_chunk(&file, &chunk, &raw, &key).unwrap(),
            payload.len() as u64
        );
        assert!(existing_chunk_matches(&file, &chunk));
        let mut bytes = vec![0u8; 4 + payload.len()];
        pread_exact_at(&file, 0, &mut bytes).unwrap();
        assert_eq!(&bytes[4..], payload);
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn preallocate_then_write_then_finalize_produces_exact_size() {
        let dir = temp_dir("depot_writer_prealloc");
        fs::create_dir_all(&dir).unwrap();
        let manifest = ContentManifest {
            metadata: crate::content_manifest::Metadata {
                filenames_encrypted: false,
                depot_id: 7,
                ..Default::default()
            },
            files: vec![crate::content_manifest::FileMapping {
                filename: "data.bin".into(),
                size: 9,
                chunks: vec![ChunkData {
                    offset: 0,
                    cb_original: 3,
                    crc: depot_adler_hash(b"abc"),
                    ..Default::default()
                }],
                ..Default::default()
            }],
            signature: Vec::new(),
        };
        let files = DepotFiles::prepare(&manifest, dir.to_str().unwrap());
        assert_eq!(files.already_present_bytes, 0);
        let handle = files.acquire(0, None).unwrap();
        // Pre-allocated to the full manifest size even though only one 3-byte chunk exists.
        assert_eq!(handle.metadata().unwrap().len(), 9);
        write_chunk_at(&handle, 0, b"abc").unwrap();
        files.complete_chunk(0, &handle).unwrap();
        // complete_chunk trimmed the padding via set_len to the exact size.
        assert_eq!(fs::metadata(dir.join("data.bin")).unwrap().len(), 9);
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn sequential_write_handles_layout_only_manifest() {
        let dir = temp_dir("depot_writer_sequential_layout");
        let manifest = ContentManifest {
            metadata: crate::content_manifest::Metadata {
                filenames_encrypted: false,
                depot_id: 7,
                ..Default::default()
            },
            files: vec![
                crate::content_manifest::FileMapping {
                    filename: "empty.bin".into(),
                    size: 5,
                    ..Default::default()
                },
                crate::content_manifest::FileMapping {
                    filename: "folder".into(),
                    flags: DEPOT_FILE_FLAG_DIRECTORY,
                    ..Default::default()
                },
            ],
            signature: Vec::new(),
        };
        let server = CContentServerDirectoryServerInfo {
            host: "cdn.example".into(),
            https_support: "mandatory".into(),
            ..Default::default()
        };
        let result = write_depot_sequential(
            &manifest,
            &[3u8; 32],
            &CdnClient::new(""),
            &[server],
            dir.to_str().unwrap(),
            DepotWriteOptions::default(),
        );
        assert!(result.ok(), "{}", result.error);
        assert_eq!(result.files_written, 1);
        assert_eq!(result.bytes_written, 0);
        assert_eq!(fs::metadata(dir.join("empty.bin")).unwrap().len(), 5);
        assert!(dir.join("folder").is_dir());
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn default_options_carry_a_process_pool() {
        // The process-pool cap defaults to the fetch-pool cap so pre-2-pool callers/tests behave.
        assert_eq!(DepotWriteOptions::default().max_process_workers, 8);
        assert_eq!(DepotWriteOptions::default().max_workers, 8);
    }

    fn three_chunk_manifest() -> ContentManifest {
        ContentManifest {
            metadata: crate::content_manifest::Metadata {
                filenames_encrypted: false,
                depot_id: 7,
                ..Default::default()
            },
            files: vec![crate::content_manifest::FileMapping {
                filename: "data.bin".into(),
                size: 9,
                chunks: vec![
                    ChunkData {
                        offset: 0,
                        cb_original: 3,
                        crc: depot_adler_hash(b"abc"),
                        ..Default::default()
                    },
                    ChunkData {
                        offset: 3,
                        cb_original: 3,
                        crc: depot_adler_hash(b"def"),
                        ..Default::default()
                    },
                    ChunkData {
                        offset: 6,
                        cb_original: 3,
                        crc: depot_adler_hash(b"ghi"),
                        ..Default::default()
                    },
                ],
                ..Default::default()
            }],
            signature: Vec::new(),
        }
    }

    #[test]
    fn two_pool_writer_verifies_existing_chunks_and_finalizes() {
        // Multi-chunk + multi-worker forces the parallel two-pool path. Every chunk is already
        // correct on disk, so the FETCH pool counts each as verifying (never enqueues) and the
        // PROCESS pool drains an empty channel and exits cleanly — with no network at all.
        let dir = temp_dir("two_pool_existing");
        fs::create_dir_all(&dir).unwrap();
        fs::write(dir.join("data.bin"), b"abcdefghi").unwrap();
        let manifest = three_chunk_manifest();
        let server = CContentServerDirectoryServerInfo {
            host: "cdn.example".into(),
            https_support: "mandatory".into(),
            ..Default::default()
        };

        let verified = std::sync::atomic::AtomicU64::new(0);
        let progress = |done: u64, total: u64, verifying: bool| {
            assert_eq!(total, 9);
            if verifying {
                verified.fetch_add(1, Ordering::Relaxed);
            }
            assert!(done <= 9);
        };
        let progress_cb: DepotChunkProgressCallback = &progress;
        let result = write_depot_sequential(
            &manifest,
            &[3u8; 32],
            &CdnClient::new(""),
            &[server],
            dir.to_str().unwrap(),
            DepotWriteOptions {
                max_workers: 4,
                max_process_workers: 2,
                on_progress: Some(progress_cb),
                ..Default::default()
            },
        );

        assert!(result.ok(), "{}", result.error);
        assert_eq!(result.bytes_written, 9);
        assert_eq!(result.files_written, 1);
        // All three chunks were verified on disk, none fetched.
        assert_eq!(verified.load(Ordering::Relaxed), 3);
        assert_eq!(fs::metadata(dir.join("data.bin")).unwrap().len(), 9);
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn two_pool_writer_respects_cancel() {
        // Cancel set before the pools start: both drain their loops immediately and the writer
        // reports the cancel verdict rather than a chunk error.
        let dir = temp_dir("two_pool_cancel");
        fs::create_dir_all(&dir).unwrap();
        fs::write(dir.join("data.bin"), b"abcdefghi").unwrap();
        let manifest = three_chunk_manifest();
        let server = CContentServerDirectoryServerInfo {
            host: "cdn.example".into(),
            https_support: "mandatory".into(),
            ..Default::default()
        };
        let cancel = AtomicBool::new(true);
        let result = write_depot_sequential(
            &manifest,
            &[3u8; 32],
            &CdnClient::new(""),
            &[server],
            dir.to_str().unwrap(),
            DepotWriteOptions {
                max_workers: 4,
                max_process_workers: 2,
                cancel: Some(&cancel),
                ..Default::default()
            },
        );
        assert!(!result.ok());
        assert_eq!(result.error, "cancelled");
        assert!(result.resume_trust_safe);
        let _ = fs::remove_dir_all(&dir);
    }

    // ── B2b async-fetch adaptive window + scheduler ───────────────────────────────────────────

    fn srv(host: &str) -> CContentServerDirectoryServerInfo {
        CContentServerDirectoryServerInfo {
            host: host.into(),
            ..Default::default()
        }
    }

    #[test]
    fn window_bounds_clamps_tier_ceiling_to_hosts() {
        // Fast tier ceiling 32, 10 hosts × cap 6 = 60 → 32; bootstrap 8, floor 2.
        assert_eq!(window_bounds(32, 10, 6), (8, 2, 32));
        // Blazing 96 but only 4 hosts × 6 = 24 → clamped to 24.
        assert_eq!(window_bounds(96, 4, 6), (8, 2, 24));
        // Slow 6 with plenty of hosts stays 6 (deliberately gentle) and clamps the bootstrap down.
        assert_eq!(window_bounds(6, 10, 6), (6, 2, 6));
        // Tiny ceiling < bootstrap: bootstrap clamps down to max.
        assert_eq!(window_bounds(3, 1, 6), (3, 2, 3));
        // Degenerate single-host single-permit: everything collapses to 1.
        assert_eq!(window_bounds(10, 1, 1), (1, 1, 1));
    }

    const MIB: u64 = 1024 * 1024;

    #[test]
    fn inflight_budget_scales_with_the_window_ceiling() {
        // r4's whole point: the budget must not refuse a slot the window already granted. At the
        // ceilings a 7-host CDN pool (the observed real pool) produces per tier:
        //   Slow    ceiling min(6, 42)  =  6 →   9 MiB → floor 24 MiB
        //   Medium  ceiling min(16, 42) = 16 →  24 MiB → exactly the floor
        //   Fast    ceiling min(32, 42) = 32 →  48 MiB
        //   Blazing ceiling min(96, 42) = 42 →  63 MiB  (the r3 device run's ceiling)
        let hosts_7 = window_bounds(6, 7, PER_HOST_CAP).2;
        assert_eq!(hosts_7, 6);
        assert_eq!(inflight_budget_bytes(hosts_7), 24 * MIB);
        assert_eq!(inflight_budget_bytes(window_bounds(16, 7, PER_HOST_CAP).2), 24 * MIB);
        assert_eq!(inflight_budget_bytes(window_bounds(32, 7, PER_HOST_CAP).2), 48 * MIB);
        assert_eq!(inflight_budget_bytes(window_bounds(96, 7, PER_HOST_CAP).2), 63 * MIB);

        // Monotonic in the ceiling, and always ≥ one chunk per slot (never a budget that would stall
        // a window it was sized for).
        let mut prev = 0u64;
        for ceiling in 1..=WINDOW_HARD_CAP {
            let budget = inflight_budget_bytes(ceiling);
            assert!(budget >= prev, "budget shrank at ceiling {ceiling}");
            assert!(
                budget >= (ceiling as u64) * TYPICAL_CHUNK_BYTES
                    || budget == FETCH_INFLIGHT_BUDGET_HARD_CAP_BYTES,
                "ceiling {ceiling} would stall on its own budget"
            );
            prev = budget;
        }
    }

    #[test]
    fn inflight_budget_respects_floor_and_hard_cap() {
        // Floor: nothing ever gets less than the pre-r4 fixed budget, however small the ceiling.
        assert_eq!(inflight_budget_bytes(0), FETCH_INFLIGHT_BUDGET_FLOOR_BYTES);
        assert_eq!(inflight_budget_bytes(1), FETCH_INFLIGHT_BUDGET_FLOOR_BYTES);
        assert_eq!(inflight_budget_bytes(15), FETCH_INFLIGHT_BUDGET_FLOOR_BYTES);
        // 16 slots × 1 MiB × 1.5 = 24 MiB — the first ceiling that meets the floor exactly.
        assert_eq!(inflight_budget_bytes(16), FETCH_INFLIGHT_BUDGET_FLOOR_BYTES);
        assert!(inflight_budget_bytes(17) > FETCH_INFLIGHT_BUDGET_FLOOR_BYTES);

        // Hard cap: the OOM guard. 64 slots × 1.5 MiB = 96 MiB is the last uncapped ceiling; every
        // ceiling above it — up to the window hard cap and beyond — pins at 96 MiB.
        assert_eq!(inflight_budget_bytes(64), FETCH_INFLIGHT_BUDGET_HARD_CAP_BYTES);
        assert_eq!(inflight_budget_bytes(65), FETCH_INFLIGHT_BUDGET_HARD_CAP_BYTES);
        assert_eq!(
            inflight_budget_bytes(WINDOW_HARD_CAP),
            FETCH_INFLIGHT_BUDGET_HARD_CAP_BYTES
        );
        assert_eq!(
            inflight_budget_bytes(usize::MAX),
            FETCH_INFLIGHT_BUDGET_HARD_CAP_BYTES
        );
        // And the cap really is a bound, not a suggestion.
        for ceiling in [0usize, 1, 42, 96, 1_000, usize::MAX] {
            assert!(inflight_budget_bytes(ceiling) <= FETCH_INFLIGHT_BUDGET_HARD_CAP_BYTES);
            assert!(inflight_budget_bytes(ceiling) >= FETCH_INFLIGHT_BUDGET_FLOOR_BYTES);
        }
    }

    #[test]
    fn scaled_budget_keeps_the_single_chunk_admit_guard() {
        // The deadlock guard is independent of how the budget is sized: at ANY budget the scaler can
        // produce, a chunk larger than the whole budget still admits when nothing is in flight.
        for ceiling in [0usize, 6, 16, 32, 42, 96, WINDOW_HARD_CAP] {
            let budget = inflight_budget_bytes(ceiling);
            assert!(
                budget_admits(0, 512 * MIB, budget),
                "oversized chunk deadlocked at ceiling {ceiling}"
            );
            // …and still refuses that chunk once anything at all is queued.
            assert!(!budget_admits(1, 512 * MIB, budget));
        }
    }

    #[test]
    fn scaled_budget_admits_a_full_blazing_window_of_nominal_chunks() {
        // The r3 device failure in one assertion: at the 42-slot ceiling a 7-host Blazing pool
        // reached, the OLD fixed budget admitted only 24 of the 42 slots the window granted (hence
        // budget_stalls=102..176). The scaled budget admits all 42, with headroom to spare.
        let ceiling = window_bounds(96, 7, PER_HOST_CAP).2;
        assert_eq!(ceiling, 42);

        let admitted = |budget: u64| {
            let mut in_flight = 0u64;
            let mut n = 0usize;
            while n < 10_000 && budget_admits(in_flight, NOMINAL_CHUNK_RESERVE_BYTES, budget) {
                in_flight += NOMINAL_CHUNK_RESERVE_BYTES;
                n += 1;
            }
            n
        };
        assert_eq!(admitted(FETCH_INFLIGHT_BUDGET_FLOOR_BYTES), 24);
        assert!(
            admitted(inflight_budget_bytes(ceiling)) >= ceiling,
            "scaled budget still binds before the window"
        );
    }

    #[test]
    fn reserve_bytes_uses_compressed_or_nominal() {
        let manifest = ContentManifest {
            files: vec![crate::content_manifest::FileMapping {
                filename: "a".into(),
                size: 10,
                chunks: vec![
                    ChunkData {
                        cb_compressed: 4096,
                        ..Default::default()
                    },
                    ChunkData {
                        cb_compressed: 0,
                        ..Default::default()
                    },
                ],
                ..Default::default()
            }],
            ..Default::default()
        };
        assert_eq!(
            reserve_bytes(&manifest, ChunkWriteJob { file_idx: 0, chunk_idx: 0 }),
            4096
        );
        // Missing compressed size → nominal reservation keeps the budget honest.
        assert_eq!(
            reserve_bytes(&manifest, ChunkWriteJob { file_idx: 0, chunk_idx: 1 }),
            NOMINAL_CHUNK_RESERVE_BYTES
        );
        // Out-of-range → nominal (never zero).
        assert_eq!(
            reserve_bytes(&manifest, ChunkWriteJob { file_idx: 9, chunk_idx: 0 }),
            NOMINAL_CHUNK_RESERVE_BYTES
        );
    }

    #[test]
    fn dispatch_reservation_never_exceeds_budget_except_single_chunk_guard() {
        // Reserving compressed bytes at DISPATCH means total raw bytes in flight can never exceed
        // the budget — at the floor, 24 MiB / 1 MiB = 24 chunks, then the gate closes until a write
        // frees space.
        let budget = FETCH_INFLIGHT_BUDGET_FLOOR_BYTES;
        let reserve = 1024 * 1024u64;
        let mut in_flight = 0u64;
        let mut admitted = 0u64;
        while budget_admits(in_flight, reserve, budget) {
            in_flight += reserve;
            admitted += 1;
            if admitted > 10_000 {
                break;
            }
        }
        assert_eq!(admitted, 24);
        assert!(in_flight <= budget, "in_flight {in_flight} exceeded budget {budget}");
        // Deadlock guard: a chunk bigger than the whole budget still admits when nothing is queued.
        assert!(budget_admits(0, 100 * 1024 * 1024, budget));
    }

    /// Drive `probes` probe intervals starting at interval `first_probe`, each delivering `delta(i)`
    /// bytes, `oks` clean completions and `errs` timeouts. `start_total` continues a previous run's
    /// cumulative byte counter (the window reads a monotonic total); returns the new total.
    #[allow(clippy::too_many_arguments)]
    fn run_probes(
        w: &mut AdaptiveWindow,
        t: Instant,
        first_probe: u64,
        probes: u64,
        oks: u32,
        errs: u32,
        start_total: u64,
        mut delta: impl FnMut(u64) -> u64,
    ) -> u64 {
        let mut total = start_total;
        for i in first_probe..first_probe + probes {
            for _ in 0..oks {
                w.record_ok(20.0);
            }
            let now = t + Duration::from_millis(WINDOW_PROBE_INTERVAL_MS * i + 10);
            for _ in 0..errs {
                w.record_err(now, FetchFailKind::Timeout);
            }
            total += delta(i);
            w.maybe_probe(now, total);
        }
        total
    }

    #[test]
    fn adaptive_window_ramps_up_fast_under_healthy_conditions() {
        let t = Instant::now();
        let mut w = AdaptiveWindow::new(8, 2, 64, t);
        assert_eq!(w.current, 8);
        // Clean successes with rising throughput → slow-start doubles every probe: 8→16→32→64 in
        // three probes (~6 s), not "never" like the pre-r3 >10%-per-700ms gate.
        run_probes(&mut w, t, 1, 3, 20, 0, 0, |i| 10_000_000 * (1u64 << i));
        assert_eq!(w.current, 64, "slow-start should reach the ceiling fast");
        assert_eq!(w.last_reason, WindowReason::GrowSlowStart);
    }

    #[test]
    fn adaptive_window_grows_on_flat_healthy_throughput() {
        // The r3 regression guard: B2b required a >10% RISE every probe, so ordinary flat-but-fine
        // CDN throughput never grew the window (and the noise shrank it). Flat + clean must GROW.
        let t = Instant::now();
        let mut w = AdaptiveWindow::new(8, 2, 256, t);
        run_probes(&mut w, t, 1, 3, 20, 0, 0, |_| 20_000_000);
        assert!(
            w.current > 8,
            "flat healthy throughput must still ramp, got {}",
            w.current
        );
    }

    #[test]
    fn adaptive_window_does_not_double_into_a_saturated_link() {
        let t = Instant::now();
        let mut w = AdaptiveWindow::new(8, 2, 256, t);
        // Probe 1 sees throughput appear from nothing — a genuine rise — so slow-start doubles.
        let total = run_probes(&mut w, t, 1, 1, 20, 0, 0, |_| 20_000_000);
        assert_eq!(w.current, 16);
        // From here throughput is FLAT, which is what an already-saturated link reports. Growth must
        // continue (no errors) but ONE additive step at a time — doubling into a saturated thin pipe
        // is the flood the window exists to prevent.
        run_probes(&mut w, t, 2, 1, 20, 0, total, |_| 20_000_000);
        assert_eq!(
            w.current, 20,
            "flat throughput must grow additively, never double"
        );
        assert_eq!(w.last_reason, WindowReason::GrowProbe);
    }

    #[test]
    fn adaptive_window_holds_at_plateau_and_never_shrinks() {
        let t = Instant::now();
        let mut w = AdaptiveWindow::new(8, 2, 256, t);
        // Steady, identical throughput forever: grow through the patience budget, then HOLD — never
        // shrink, because there are no errors. (Correct slow-link behaviour.)
        let total = run_probes(&mut w, t, 1, 4, 20, 0, 0, |_| 20_000_000);
        let plateau_at = w.current;
        assert!(plateau_at > 8);
        run_probes(&mut w, t, 5, 6, 20, 0, total, |_| 20_000_000);
        assert_eq!(w.current, plateau_at, "plateau must hold, not grow or shrink");
        assert_eq!(w.last_reason, WindowReason::HoldPlateau);
    }

    #[test]
    fn adaptive_window_does_not_shrink_on_throughput_dips_or_jitter() {
        let t = Instant::now();
        let mut w = AdaptiveWindow::new(16, 2, 256, t);
        // One good probe to establish a baseline, then throughput halves each probe with ZERO errors
        // (exactly the CDN noise that pinned B2b at the floor). The window must hold, never shrink.
        let total = run_probes(&mut w, t, 1, 1, 20, 0, 0, |_| 40_000_000);
        let after_ramp = w.current;
        run_probes(&mut w, t, 2, 5, 20, 0, total, |i| {
            40_000_000u64 >> (i - 1).min(20)
        });
        assert_eq!(
            w.current, after_ramp,
            "throughput dips alone must not shrink the window"
        );
        assert_eq!(w.last_reason, WindowReason::HoldThroughputDown);
    }

    #[test]
    fn adaptive_window_never_exceeds_tier_ceiling() {
        let t = Instant::now();
        let mut w = AdaptiveWindow::new(4, 2, 8, t); // ceiling 8
        run_probes(&mut w, t, 1, 20, 10, 0, 0, |i| 10_000_000 * i * i);
        assert_eq!(w.current, 8);
        assert_eq!(w.last_reason, WindowReason::HoldCeiling);
    }

    #[test]
    fn adaptive_window_ignores_a_single_error_but_shrinks_on_a_burst() {
        let t = Instant::now();
        let mut w = AdaptiveWindow::new(32, 2, 64, t);
        // A lone timeout is noise, not a signal: the window must not move (B2b shrank a flat −6).
        assert!(!w.record_err(t, FetchFailKind::Timeout));
        assert_eq!(w.current, 32);
        assert!(!w.record_err(t, FetchFailKind::Timeout));
        assert_eq!(w.current, 32);
        // A burst inside one probe interval is real: shrink PROPORTIONALLY (32 → 24), not to floor.
        assert!(w.record_err(t, FetchFailKind::Timeout));
        assert_eq!(w.current, 24);
        assert_eq!(w.last_reason, WindowReason::ShrinkTimeout);
        assert!(w.cooldown_left_ms(t) > 0);
    }

    #[test]
    fn adaptive_window_shrinks_immediately_on_rate_limit() {
        let t = Instant::now();
        let mut w = AdaptiveWindow::new(32, 2, 64, t);
        // 429 is unambiguous back-pressure: shrink on the first one, no burst needed.
        assert!(w.record_err(t, FetchFailKind::RateLimited));
        assert_eq!(w.current, 24);
        assert_eq!(w.last_reason, WindowReason::ShrinkRateLimited);
        assert!(!w.slow_start, "a real back-off ends slow-start");
    }

    #[test]
    fn adaptive_window_error_storm_walks_down_to_the_floor() {
        let t = Instant::now();
        let mut w = AdaptiveWindow::new(32, 2, 64, t);
        // 1 error per 5 successes = 16% > HIGH: the probe shrinks every time (below the immediate
        // burst threshold, so this exercises the probe path) until the floor stops it.
        run_probes(&mut w, t, 1, 30, 5, 1, 0, |_| 1_000_000);
        assert_eq!(w.current, 2, "a sustained error storm must reach the floor");
        assert_eq!(w.min, 2, "and stop there");
    }

    #[test]
    fn adaptive_window_recovers_after_the_error_storm_stops() {
        let t = Instant::now();
        let mut w = AdaptiveWindow::new(32, 2, 64, t);
        let total = run_probes(&mut w, t, 1, 10, 5, 1, 0, |_| 1_000_000);
        let bottom = w.current;
        // Clean probes after the cooldown expires must climb again (additively — slow-start is over).
        run_probes(&mut w, t, 11, 6, 20, 0, total, |_| 20_000_000);
        assert!(
            w.current > bottom,
            "window must recover once errors stop, got {} from {bottom}",
            w.current
        );
    }

    #[test]
    fn window_summary_line_carries_the_decision_reason() {
        let t = Instant::now();
        let mut w = AdaptiveWindow::new(8, 2, 30, t);
        // Two dispatch attempts were refused by the byte budget before this probe: the line must say
        // so, otherwise a window that looks healthy but is memory-bound reads as a mystery on device.
        w.note_budget_stall();
        w.note_budget_stall();
        w.note_host_stall();
        run_probes(&mut w, t, 1, 1, 10, 0, 0, |_| 20_000_000);
        let line = w.summary_line(49521, 12, t + Duration::from_millis(2_010));
        assert!(line.starts_with("fetch-window depot=49521 window=16 (min=2 max=30) in_flight=12"));
        assert!(line.contains("reason=grow:slow-start"), "{line}");
        assert!(line.contains("cooldown=0ms"), "{line}");
        assert!(line.contains("err_rate=0.0%"), "{line}");
        assert!(line.contains("phase=slow-start"), "{line}");
        assert!(line.contains("budget_stalls=2"), "{line}");
        assert!(line.contains("host_stalls=1"), "{line}");
    }

    #[test]
    fn fetch_scheduler_respects_per_host_cap_and_counts_distinct_hosts() {
        // Two directory entries share hostA; hostB is separate → 2 distinct hosts, per-host cap 2.
        let servers = vec![srv("hostA"), srv("hostA"), srv("hostB")];
        let mut sched = FetchScheduler::new(&servers, 2);
        assert_eq!(sched.host_sems.len(), 2);
        let now = Instant::now();
        // Exhaust hostA's shared 2 permits (servers 0 and 1 map to the same semaphore).
        let p1 = sched.host_sem(0).clone().try_acquire_owned().unwrap();
        let p2 = sched.host_sem(1).clone().try_acquire_owned().unwrap();
        assert!(!sched.eligible(0, now));
        assert!(!sched.eligible(1, now));
        assert!(sched.eligible(2, now));
        // Only the un-capped host is pickable.
        assert_eq!(sched.pick(now), Some(2));
        drop(p1);
        drop(p2);
        assert!(sched.eligible(0, now));
    }

    #[test]
    fn fetch_scheduler_ranks_fastest_and_recovers_after_cooldown() {
        let servers = vec![srv("h0"), srv("h1")];
        let mut sched = FetchScheduler::new(&servers, 4);
        let now = Instant::now();
        // Cold start probes unsampled hosts first (round-robin over the two).
        let a = sched.pick(now).unwrap();
        sched.on_success(a, 1_000_000, Duration::from_millis(100)); // ~10 MB/s
        let b = sched.pick(now).unwrap();
        assert_ne!(a, b, "cold start should probe the other host");
        sched.on_success(b, 100_000, Duration::from_millis(100)); // ~1 MB/s (slower)
        // Both sampled now → exploit picks the faster host.
        assert_eq!(sched.pick(now), Some(a));
        // An error cools + demotes the fast host; it recovers once the cooldown expires.
        sched.on_error(a, now, FetchFailKind::Timeout);
        assert!(!sched.eligible(a, now));
        let later = now + Duration::from_secs(10);
        assert!(sched.eligible(a, later));
    }

    fn temp_dir(name: &str) -> PathBuf {
        std::env::temp_dir().join(format!(
            "blsteam_{name}_{}",
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ))
    }

    fn encrypted_stored_zip_chunk(key: &[u8; 32], payload: &[u8]) -> Vec<u8> {
        let mut zip = Vec::new();
        zip.extend_from_slice(&0x0403_4b50u32.to_le_bytes());
        zip.extend_from_slice(&20u16.to_le_bytes());
        zip.extend_from_slice(&0u16.to_le_bytes());
        zip.extend_from_slice(&0u16.to_le_bytes());
        zip.extend_from_slice(&0u16.to_le_bytes());
        zip.extend_from_slice(&0u16.to_le_bytes());
        zip.extend_from_slice(&0u32.to_le_bytes());
        zip.extend_from_slice(&(payload.len() as u32).to_le_bytes());
        zip.extend_from_slice(&(payload.len() as u32).to_le_bytes());
        zip.extend_from_slice(&1u16.to_le_bytes());
        zip.extend_from_slice(&0u16.to_le_bytes());
        zip.extend_from_slice(b"x");
        zip.extend_from_slice(payload);

        let iv = [6u8; AES_BLOCK_BYTES];
        let wrapped = aes256_ecb_encrypt_block(key, &iv).unwrap();
        let body = aes256_cbc_encrypt(key, &iv, &zip).unwrap();
        let mut out = wrapped.to_vec();
        out.extend_from_slice(&body);
        out
    }
}
