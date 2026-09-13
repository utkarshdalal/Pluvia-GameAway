//! Shared adaptive HTTP fetch core for the store download engines (Epic / GOG / Amazon).
//!
//! This is the generic half of the Steam depot writer's B2b fetch layer (`depot_writer.rs`): an
//! adaptive in-flight window (slow-start → hold at plateau → shrink on REAL errors only), a
//! speed-ranked per-host-capped scheduler, an in-flight RAW byte budget sized from the window
//! ceiling, per-item retry with exponential back-off + host rotation, one current-thread tokio
//! runtime driving every request through a [`FuturesUnordered`], and an mpsc hand-off to a sync
//! process pool that calls the adapter's [`FetchSink::process`] (inflate + hash + write).
//!
//! The Steam engine is deliberately NOT rewired onto this module: the window/scheduler types are a
//! copy of the ones in `depot_writer.rs` (the tunables are imported from there so both engines share
//! one set of numbers), which keeps Steam byte-identical while the store engines are proven.
//!
//! What the core does NOT know: manifests, resume/skip rules, output layout, hashing. The adapter
//! builds the item list (already excluding present+verified items, exactly as its Java manager
//! does), maps host index → URL, and does everything with the body inside its sink.
//!
//! Log grammar (all lines go through the `log` callback; `label` is adapter-chosen, e.g.
//! `epic app=Fortnite`):
//! - `fetch-start label=… items=… bytes_reserved=… hosts=… ceiling=… budget=…MiB tier_max=… per_host_cap=… distinct_hosts=… window=… mode=body|stream`
//! - `fetch-window label=… window=N (min=… max=…) in_flight=… last=…MB/s ewma=…MB/s best=…MB/s reason=… cooldown=…ms err_rate=…% phase=… rtt=…ms budget_stalls=… host_stalls=…`
//!   (same fields as the Steam engine's `fetch-window depot=…` line, so the two are A/B-comparable)
//! - `throughput label=… overall=…MB/s total=…MB elapsed=…s used=x/y servers: [host …MB/s …MB] …` every 5 s
//! - `fetch-end label=… items_ok=… bytes=… credited=… elapsed_ms=… avg_mbps=… peak_mbps=… result=ok|cancelled|error [error=…]`
//!
//! `MB/s` here means MiB/s (1024²), matching the Steam engine's lines.

use crate::cdn_client::{read_body_capped, AsyncFetchError, CdnClient, FetchFailKind, MAX_WHOLE_BODY_BYTES, USER_AGENT};
use crate::depot_writer::{
    budget_admits, inflight_budget_bytes, retry_backoff_millis, BOOTSTRAP_WINDOW,
    MAX_CHUNK_ATTEMPTS, NOMINAL_CHUNK_RESERVE_BYTES, RATE_LIMIT_COOLDOWN_MS, SERVER_EXPLORE_EVERY,
    WINDOW_BPS_EWMA_ALPHA, WINDOW_COOLDOWN_MS, WINDOW_DECLINE_EPS, WINDOW_ERR_BURST_IMMEDIATE,
    WINDOW_ERR_RATE_HIGH, WINDOW_ERR_RATE_LOW, WINDOW_HARD_CAP, WINDOW_IMPROVE_EPS,
    WINDOW_LOG_EVERY_PROBES, WINDOW_MIN_FLOOR, WINDOW_PLATEAU_PATIENCE, WINDOW_PLATEAU_REARM_MAX_MS,
    WINDOW_PLATEAU_REARM_MS, WINDOW_PROBE_INTERVAL_MS, WINDOW_SHRINK_FACTOR,
    WINDOW_SLOW_START_FACTOR, WINDOW_STEP_UP,
};
use futures_util::stream::FuturesUnordered;
use futures_util::StreamExt;
use std::collections::{HashMap, VecDeque};
use std::fs;
use std::sync::atomic::{AtomicBool, AtomicU32, AtomicU64, Ordering};
use std::sync::{mpsc as std_mpsc, Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};
use tokio::sync::Semaphore;

/// Maximum attempts per item (fetch failures AND `SinkError::Retry` both count). Same as Steam.
pub const MAX_ITEM_ATTEMPTS: u32 = MAX_CHUNK_ATTEMPTS;
/// How often the throughput reporter samples (and logs) — the samples feed `peak_mbps`.
pub const THROUGHPUT_SAMPLE_INTERVAL_MS: u64 = 5_000;
/// Upper bound on how long the driver sits in one `await` before re-checking cancel/error/feedback.
const DRIVER_POLL_MS: u64 = 250;

// ─────────────────────────────────────────────────────────────────────────────────────────────
// Public contract
// ─────────────────────────────────────────────────────────────────────────────────────────────

/// One unit of work: a body to fetch from one of the hosts and hand to the sink.
#[derive(Clone, Debug)]
pub struct FetchItem {
    /// Adapter-defined (index into the adapter's own table). Only echoed in errors / logs.
    pub id: u64,
    /// `len == hosts.len()` → `urls[host_idx]`; `len == 1` → the same URL on every host. Any other
    /// non-zero length falls back to `urls.get(host_idx)` then `urls[0]`. Empty = fatal.
    pub urls: Vec<String>,
    /// Expected RAW (wire) bytes, for the in-flight byte budget; `0` → nominal 1 MiB. Ignored in
    /// stream mode (streams are budgeted piece by piece as they arrive).
    pub reserve: u64,
    /// Inclusive HTTP `Range` (`bytes=a-b`); `None` = whole body.
    pub range: Option<(u64, u64)>,
}

#[derive(Clone, Debug)]
pub struct FetchOptions {
    /// Window ceiling (tier); the core clamps it to `hosts × per_host_cap` and the hard cap.
    pub max_workers: usize,
    /// Max concurrent requests per distinct host (6 unless the store needs otherwise). Honoured
    /// as given: 1 host × `per_host_cap` 8 = a ceiling of 8.
    pub per_host_cap: usize,
    /// Whole-body mode: the deadline for the entire request (connect + headers + body). Stream
    /// mode: the deadline for the response headers, then an IDLE deadline per received piece.
    pub timeout: Duration,
    /// Sent on EVERY request (applied on the request builder, so they override the client-level
    /// defaults — e.g. a store's own `User-Agent`, or `Authorization: Bearer …`). Validated once
    /// when the client is built.
    pub headers: Vec<(String, String)>,
    /// PEM bundle path; `""` = the platform roots reqwest/rustls ships with.
    pub ca_bundle_path: String,
    /// Sync process-pool threads (inflate + hash + write).
    pub process_workers: usize,
    /// Log prefix, e.g. `"epic app=Fortnite"`.
    pub label: String,
    /// `false` (default): each item's whole body is buffered and handed to
    /// [`FetchSink::process`]. `true`: the body is streamed — pieces go to
    /// [`FetchSink::on_chunk`] in order as they arrive, then [`FetchSink::on_finish`]; for
    /// multi-GB files (Amazon) that keeps memory at "pieces not yet written", not whole files.
    /// Applies to every item of the run.
    pub stream: bool,
}

impl Default for FetchOptions {
    fn default() -> Self {
        Self {
            max_workers: BOOTSTRAP_WINDOW,
            per_host_cap: crate::depot_writer::PER_HOST_CAP,
            timeout: Duration::from_secs(60),
            headers: Vec::new(),
            ca_bundle_path: String::new(),
            process_workers: 2,
            label: String::new(),
            stream: false,
        }
    }
}

/// What the sink says about a body it was handed.
#[derive(Clone, Debug)]
pub enum SinkError {
    /// Counts as a failed attempt for that item: host cooled + demoted, back-off, re-dispatch on a
    /// different host, up to [`MAX_ITEM_ATTEMPTS`]. In stream mode the whole item is re-streamed
    /// (`on_chunk` sees `offset == 0` again).
    Retry(String),
    /// Abort the whole run; this becomes the run's first error.
    Fatal(String),
}

/// Runs on a process-pool thread. Whole-body mode uses [`FetchSink::process`]; stream mode uses
/// [`FetchSink::on_chunk`] + [`FetchSink::on_finish`] (default impls refuse, so a whole-body sink
/// is unaffected by the stream option existing).
pub trait FetchSink: Send + Sync {
    /// Whole-body mode: the raw wire body. Do inflate + hash verify + write here. Returns the
    /// number of bytes to credit to progress (adapter's choice, e.g. decompressed bytes).
    fn process(&self, item: &FetchItem, body: Vec<u8>) -> Result<u64, SinkError>;

    /// Stream mode: one piece of the body, delivered in order (`offset` = bytes of this attempt
    /// delivered before this piece). `offset == 0` marks the (re)start of an attempt — truncate any
    /// partial output from a previous attempt. Each processed piece credits `data.len()` bytes.
    fn on_chunk(&self, item: &FetchItem, offset: u64, data: &[u8]) -> Result<(), SinkError> {
        let _ = (item, offset, data);
        Err(SinkError::Fatal("streaming not supported by this sink".to_string()))
    }

    /// Stream mode: the body ended after `total_len` bytes. Finalize (rename `.tmp`, verify the
    /// hash, …). Returns EXTRA bytes to credit (pieces were already credited as they were
    /// written; return 0 for none).
    fn on_finish(&self, item: &FetchItem, total_len: u64) -> Result<u64, SinkError> {
        let _ = (item, total_len);
        Err(SinkError::Fatal("streaming not supported by this sink".to_string()))
    }
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct FetchOutcome {
    /// Sum of what the sink returned for every successfully processed item (stream mode: every
    /// piece written + every `on_finish` extra; pieces of an attempt that later failed stay
    /// counted).
    pub bytes_credited: u64,
    /// First error (fetch after max attempts, sink `Fatal`, client build failure).
    pub error: Option<String>,
    /// `cancel` was observed set.
    pub cancelled: bool,
    /// Items the sink accepted.
    pub items_ok: u64,
}

impl FetchOutcome {
    pub fn ok(&self) -> bool {
        self.error.is_none() && !self.cancelled
    }
}

/// Blocking: spawns its own current-thread tokio runtime + process pool and returns when every
/// item is processed, `cancel` is set, or the first fatal error is recorded. `progress` fires from
/// a process-pool thread after each successful `process` / each written piece + `on_finish` (the
/// caller throttles).
pub fn run_fetch(
    items: Vec<FetchItem>,
    hosts: &[String],
    opts: &FetchOptions,
    sink: &dyn FetchSink,
    cancel: &AtomicBool,
    progress: &(dyn Fn(u64, u64) + Sync),
    log: &(dyn Fn(&str) + Sync),
) -> FetchOutcome {
    let started = Instant::now();
    let label = opts.label.as_str();
    let stream = opts.stream;
    let hosts: Vec<String> = if hosts.is_empty() {
        vec![String::from("default")]
    } else {
        hosts.to_vec()
    };
    let per_host_cap = opts.per_host_cap.max(1);
    let distinct_hosts = distinct_host_count(&hosts);
    let (bootstrap, win_min, win_max) =
        window_bounds(opts.max_workers, distinct_hosts, per_host_cap);
    let budget = inflight_budget_bytes(win_max);
    let bytes_reserved: u64 = items.iter().map(reserve_bytes).sum();

    log(&format!(
        "fetch-start label={label} items={} bytes_reserved={bytes_reserved} hosts={} ceiling={win_max} \
budget={}MiB tier_max={} per_host_cap={per_host_cap} distinct_hosts={distinct_hosts} window={bootstrap} \
mode={}",
        items.len(),
        hosts.len(),
        budget / (1024 * 1024),
        opts.max_workers,
        if stream { "stream" } else { "body" },
    ));

    let bytes_credited = AtomicU64::new(0);
    let items_ok = AtomicU64::new(0);
    let in_flight = AtomicU64::new(0);
    let error_slot: Mutex<Option<String>> = Mutex::new(None);
    let reporter_done = AtomicBool::new(false);
    let meter = BandwidthMeter::new(&hosts);
    // Stream mode: per-item "attempt N was rejected by the sink mid-stream" flags, so the driver's
    // in-flight stream stops reading instead of feeding pieces nobody wants.
    let abandon: Vec<AtomicU32> = if stream {
        items.iter().map(|_| AtomicU32::new(NO_ATTEMPT)).collect()
    } else {
        Vec::new()
    };

    let proc_count = opts.process_workers.max(1).min(items.len().max(1));
    let items = &items;
    let meter = &meter;
    let abandon = &abandon;

    thread::scope(|scope| {
        // Async(fetch) → sync(process): tokio unbounded mpsc drained with `blocking_recv`. Memory is
        // bounded by the RAW byte budget enforced at DISPATCH (whole-body) / per piece (stream), not
        // by channel capacity. Whole-body mode: ONE channel shared by every worker (any worker takes
        // the next body). Stream mode: one channel PER worker and an item's pieces always go to the
        // same worker (`idx % workers`), so pieces are written in order.
        let channel_count = if stream { proc_count } else { 1 };
        let mut txs: Vec<tokio::sync::mpsc::UnboundedSender<ProcessMsg>> =
            Vec::with_capacity(channel_count);
        let mut rxs: Vec<Arc<Mutex<tokio::sync::mpsc::UnboundedReceiver<ProcessMsg>>>> =
            Vec::with_capacity(channel_count);
        for _ in 0..channel_count {
            let (tx, rx) = tokio::sync::mpsc::unbounded_channel::<ProcessMsg>();
            txs.push(tx);
            rxs.push(Arc::new(Mutex::new(rx)));
        }
        // Sync(process) → async(driver): sink verdicts, polled non-blocking by the driver.
        let (fb_tx, fb_rx) = std_mpsc::channel::<Feedback>();
        let bytes_credited = &bytes_credited;
        let items_ok = &items_ok;
        let in_flight = &in_flight;
        let error_slot = &error_slot;
        let reporter_done = &reporter_done;

        // Periodic throughput reporter — atomics only, never on the fetch hot path. Its samples are
        // also the `peak_mbps` source.
        let reporter = scope.spawn(move || loop {
            let mut waited = 0u64;
            while waited < THROUGHPUT_SAMPLE_INTERVAL_MS {
                if reporter_done.load(Ordering::Relaxed) {
                    return;
                }
                thread::sleep(Duration::from_millis(100));
                waited += 100;
            }
            if reporter_done.load(Ordering::Relaxed) {
                return;
            }
            meter.sample();
            log(&meter.summary_line(label));
        });

        // ── Process pool ──
        let mut proc_handles = Vec::with_capacity(proc_count);
        for worker in 0..proc_count {
            let rx = Arc::clone(&rxs[if stream { worker } else { 0 }]);
            let fb = fb_tx.clone();
            proc_handles.push(scope.spawn(move || {
                // Stream mode: (item → attempt, message) whose pieces the sink rejected; the rest
                // of that attempt's pieces are dropped and its Finish becomes a Retry verdict.
                let mut poisoned: HashMap<usize, (u32, String)> = HashMap::new();
                loop {
                    if cancel.load(Ordering::Relaxed) {
                        return;
                    }
                    if error_slot.lock().expect("err slot poisoned").is_some() {
                        return;
                    }
                    let msg = {
                        let mut guard = rx.lock().expect("rx poisoned");
                        guard.blocking_recv()
                    };
                    let Some(msg) = msg else {
                        return;
                    };
                    match msg {
                        ProcessMsg::Body {
                            idx,
                            attempts,
                            server_idx,
                            body,
                        } => {
                            let raw_len = body.len() as u64;
                            // A body that was already queued when cancel / the first error landed
                            // must not be written any more.
                            if cancel.load(Ordering::Relaxed)
                                || error_slot.lock().expect("err slot poisoned").is_some()
                            {
                                in_flight.fetch_sub(raw_len, Ordering::Relaxed);
                                return;
                            }
                            let item = &items[idx];
                            let res = sink.process(item, body);
                            // Free the budget on PROCESS-COMPLETE (actual raw bytes).
                            in_flight.fetch_sub(raw_len, Ordering::Relaxed);
                            let verdict = match res {
                                Ok(credited) => {
                                    let total = bytes_credited
                                        .fetch_add(credited, Ordering::Relaxed)
                                        + credited;
                                    let ok = items_ok.fetch_add(1, Ordering::Relaxed) + 1;
                                    progress(total, ok);
                                    SinkVerdict::Ok
                                }
                                Err(SinkError::Retry(message)) => SinkVerdict::Retry(message),
                                Err(SinkError::Fatal(message)) => {
                                    record_first_error(
                                        error_slot,
                                        format!("{label}: item {} failed: {message}", item.id),
                                    );
                                    SinkVerdict::Fatal
                                }
                            };
                            let _ = fb.send(Feedback {
                                idx,
                                attempts,
                                server_idx,
                                verdict,
                            });
                        }
                        ProcessMsg::Piece {
                            idx,
                            attempts,
                            offset,
                            data,
                        } => {
                            let len = data.len() as u64;
                            in_flight.fetch_sub(len, Ordering::Relaxed);
                            if poisoned.get(&idx).is_some_and(|(a, _)| *a == attempts) {
                                continue; // rest of a rejected attempt
                            }
                            if cancel.load(Ordering::Relaxed)
                                || error_slot.lock().expect("err slot poisoned").is_some()
                            {
                                return;
                            }
                            let item = &items[idx];
                            match sink.on_chunk(item, offset, &data) {
                                Ok(()) => {
                                    let total =
                                        bytes_credited.fetch_add(len, Ordering::Relaxed) + len;
                                    progress(total, items_ok.load(Ordering::Relaxed));
                                }
                                Err(SinkError::Retry(message)) => {
                                    poisoned.insert(idx, (attempts, message));
                                    if let Some(flag) = abandon.get(idx) {
                                        flag.store(attempts, Ordering::Relaxed);
                                    }
                                }
                                Err(SinkError::Fatal(message)) => {
                                    record_first_error(
                                        error_slot,
                                        format!("{label}: item {} failed: {message}", item.id),
                                    );
                                    if let Some(flag) = abandon.get(idx) {
                                        flag.store(attempts, Ordering::Relaxed);
                                    }
                                    return;
                                }
                            }
                        }
                        ProcessMsg::Finish {
                            idx,
                            attempts,
                            server_idx,
                            total_len,
                        } => {
                            let item = &items[idx];
                            let verdict = if poisoned
                                .get(&idx)
                                .is_some_and(|(a, _)| *a == attempts)
                            {
                                let (_, message) = poisoned.remove(&idx).expect("poisoned");
                                SinkVerdict::Retry(message)
                            } else {
                                match sink.on_finish(item, total_len) {
                                    Ok(extra) => {
                                        let total = bytes_credited
                                            .fetch_add(extra, Ordering::Relaxed)
                                            + extra;
                                        let ok = items_ok.fetch_add(1, Ordering::Relaxed) + 1;
                                        progress(total, ok);
                                        SinkVerdict::Ok
                                    }
                                    Err(SinkError::Retry(message)) => SinkVerdict::Retry(message),
                                    Err(SinkError::Fatal(message)) => {
                                        record_first_error(
                                            error_slot,
                                            format!(
                                                "{label}: item {} failed: {message}",
                                                item.id
                                            ),
                                        );
                                        SinkVerdict::Fatal
                                    }
                                }
                            };
                            let _ = fb.send(Feedback {
                                idx,
                                attempts,
                                server_idx,
                                verdict,
                            });
                        }
                    }
                }
            }));
        }
        drop(fb_tx);

        // ── Async fetch driver on one current-thread runtime. `txs` are moved in and dropped when
        // the driver returns, which closes the channels so the pool's `blocking_recv` yields
        // `None`. ──
        match tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
        {
            Ok(rt) => {
                rt.block_on(run_driver(DriverCtx {
                    items: items.as_slice(),
                    hosts: hosts.as_slice(),
                    opts,
                    label,
                    cancel,
                    log,
                    meter,
                    in_flight,
                    error_slot,
                    abandon: abandon.as_slice(),
                    txs,
                    fb_rx,
                    budget,
                    bootstrap,
                    win_min,
                    win_max,
                    per_host_cap,
                }));
            }
            Err(err) => {
                record_first_error(error_slot, format!("{label}: tokio runtime: {err}"));
                drop(txs);
            }
        }

        for handle in proc_handles {
            let _ = handle.join();
        }
        reporter_done.store(true, Ordering::Relaxed);
        let _ = reporter.join();
    });

    // Final partial-interval sample so a short run still has a peak.
    meter.sample();
    let elapsed = started.elapsed();
    let elapsed_secs = elapsed.as_secs_f64().max(0.001);
    let wire_total = meter.total_bytes();
    let avg_mbps = (wire_total as f64) / (1024.0 * 1024.0) / elapsed_secs;
    let peak_mbps = (meter.peak_bps() / (1024.0 * 1024.0)).max(avg_mbps);
    let cancelled = cancel.load(Ordering::Relaxed);
    let error = error_slot.lock().expect("err slot poisoned").take();
    let outcome = FetchOutcome {
        bytes_credited: bytes_credited.load(Ordering::Relaxed),
        error,
        cancelled,
        items_ok: items_ok.load(Ordering::Relaxed),
    };
    let result = if cancelled {
        "cancelled"
    } else if outcome.error.is_some() {
        "error"
    } else {
        "ok"
    };
    let mut line = format!(
        "fetch-end label={label} items_ok={} bytes={wire_total} credited={} elapsed_ms={} \
avg_mbps={avg_mbps:.2} peak_mbps={peak_mbps:.2} result={result}",
        outcome.items_ok,
        outcome.bytes_credited,
        elapsed.as_millis(),
    );
    if let Some(err) = &outcome.error {
        line.push_str(" error=");
        line.push_str(err);
    }
    log(&line);
    outcome
}

// ─────────────────────────────────────────────────────────────────────────────────────────────
// Pure helpers (unit-tested)
// ─────────────────────────────────────────────────────────────────────────────────────────────

/// Bytes reserved against the in-flight budget at DISPATCH (nominal when the adapter has none).
pub fn reserve_bytes(item: &FetchItem) -> u64 {
    if item.reserve == 0 {
        NOMINAL_CHUNK_RESERVE_BYTES
    } else {
        item.reserve
    }
}

/// Window bounds: `max` = tier ceiling clamped to distinct-hosts × per-host-cap and the hard cap;
/// `min` = the floor; `bootstrap` = the start. Same arithmetic as the Steam depot writer.
pub fn window_bounds(
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

/// Distinct host count (string equality) — the multiplier for the window's host ceiling.
pub fn distinct_host_count(hosts: &[String]) -> usize {
    let mut keys: Vec<&str> = Vec::new();
    for h in hosts {
        if !keys.contains(&h.as_str()) {
            keys.push(h.as_str());
        }
    }
    keys.len().max(1)
}

/// The URL to use for `item` on host `host_idx` (see [`FetchItem::urls`]). `None` when the item
/// carries no URL at all.
pub fn url_for_host(item: &FetchItem, host_idx: usize) -> Option<&str> {
    if item.urls.is_empty() {
        return None;
    }
    if item.urls.len() == 1 {
        return Some(item.urls[0].as_str());
    }
    Some(
        item.urls
            .get(host_idx)
            .unwrap_or(&item.urls[0])
            .as_str(),
    )
}

/// Byte count an inclusive range must deliver; `None` for an inverted range.
pub fn range_len(range: (u64, u64)) -> Option<u64> {
    let (a, b) = range;
    if b < a {
        None
    } else {
        Some(b - a + 1)
    }
}

/// Validates a received body's length: for a range request it must be exactly `b-a+1`; otherwise
/// it must match `Content-Length` when the server sent one (as the Steam client does).
pub fn validate_body_len(
    body_len: u64,
    content_length: Option<u64>,
    range: Option<(u64, u64)>,
) -> Result<(), AsyncFetchError> {
    if let Some(range) = range {
        let expected = match range_len(range) {
            Some(n) => n,
            None => {
                return Err(AsyncFetchError {
                    message: format!("invalid range {}-{}", range.0, range.1),
                    kind: FetchFailKind::Other,
                })
            }
        };
        if body_len != expected {
            return Err(AsyncFetchError {
                message: format!("range body length mismatch (got {body_len}, want {expected})"),
                kind: FetchFailKind::ServerFault,
            });
        }
        return Ok(());
    }
    if let Some(expected) = content_length {
        if body_len != expected {
            return Err(AsyncFetchError {
                message: format!("body truncated (length mismatch: got {body_len}, want {expected})"),
                kind: FetchFailKind::ServerFault,
            });
        }
    }
    Ok(())
}

/// Whether an HTTP status is acceptable for this request shape (200 always; 206 only when a range
/// was asked for).
pub fn status_accepted(status: u16, ranged: bool) -> bool {
    status == 200 || (ranged && status == 206)
}

/// Classifies a rejected HTTP status the same way the Steam async client does.
pub fn classify_status(status: u16) -> FetchFailKind {
    if status == 429 {
        FetchFailKind::RateLimited
    } else if (500..600).contains(&status) {
        FetchFailKind::ServerFault
    } else {
        FetchFailKind::Other
    }
}

/// The HTTP status carried by a `send_checked` rejection message, if that is what the error was.
/// A 4xx other than 429 is a CLIENT rejection (bad/expired signed URL, wrong host for this path,
/// 404): it says nothing about congestion, so the driver demotes the host but does not shrink the
/// shared window for it — one CDN that refuses a path must not throttle the healthy ones.
pub fn rejected_status(message: &str) -> Option<u16> {
    let rest = message.strip_prefix("unexpected HTTP status (")?;
    let digits = rest.strip_suffix(')')?;
    digits.parse().ok()
}

pub fn is_client_rejection(message: &str) -> bool {
    matches!(rejected_status(message), Some(s) if (400..500).contains(&s) && s != 429)
}

/// How many individual fetch failures a run logs verbatim before going quiet (the window and
/// throughput lines keep reporting the aggregate).
pub const FETCH_ERROR_LOG_LIMIT: u32 = 12;

fn record_first_error(slot: &Mutex<Option<String>>, err: String) {
    let mut guard = slot.lock().expect("err slot poisoned");
    if guard.is_none() {
        *guard = Some(err);
    }
}

// ─────────────────────────────────────────────────────────────────────────────────────────────
// HTTP client (mirrors `AsyncCdnClient` + per-request headers + optional Range + streaming)
// ─────────────────────────────────────────────────────────────────────────────────────────────

/// The pooled client plus the pre-validated per-request headers.
struct Http {
    client: reqwest::Client,
    headers: Vec<(reqwest::header::HeaderName, reqwest::header::HeaderValue)>,
}

fn build_http(opts: &FetchOptions, pool_max_idle_per_host: usize) -> Result<Http, String> {
    let mut headers = Vec::with_capacity(opts.headers.len());
    for (name, value) in &opts.headers {
        let hname = reqwest::header::HeaderName::from_bytes(name.as_bytes())
            .map_err(|err| format!("bad header name '{name}': {err}"))?;
        let hvalue = reqwest::header::HeaderValue::from_str(value)
            .map_err(|err| format!("bad header value for '{name}': {err}"))?;
        headers.push((hname, hvalue));
    }
    let mut builder = reqwest::Client::builder()
        .user_agent(USER_AGENT)
        .connect_timeout(CdnClient::connect_timeout())
        .pool_max_idle_per_host(pool_max_idle_per_host.max(1))
        .pool_idle_timeout(Duration::from_secs(90));
    if !opts.ca_bundle_path.is_empty() {
        let pem = fs::read(&opts.ca_bundle_path).map_err(|err| format!("read CA bundle: {err}"))?;
        let certs = reqwest::Certificate::from_pem_bundle(&pem)
            .map_err(|err| format!("parse CA bundle: {err}"))?;
        for cert in certs {
            builder = builder.add_root_certificate(cert);
        }
    }
    let client = builder.build().map_err(|err| format!("http client: {err}"))?;
    Ok(Http { client, headers })
}

impl Http {
    /// `GET url` with the run's headers (set per request, so they override the client defaults —
    /// a store's own `User-Agent` wins over the Steam one) and the optional `Range`.
    fn request(&self, url: &str, range: Option<(u64, u64)>) -> reqwest::RequestBuilder {
        let mut request = self.client.get(url);
        for (name, value) in &self.headers {
            request = request.header(name.clone(), value.clone());
        }
        if let Some((a, b)) = range {
            request = request.header(reqwest::header::RANGE, format!("bytes={a}-{b}"));
        }
        request
    }
}

fn classify_reqwest_error(err: &reqwest::Error) -> FetchFailKind {
    if err.is_timeout() {
        FetchFailKind::Timeout
    } else if err.is_connect() {
        FetchFailKind::Connect
    } else {
        FetchFailKind::Other
    }
}

/// Sends the request and checks the status; shared by both modes.
async fn send_checked(
    http: &Http,
    url: &str,
    range: Option<(u64, u64)>,
    timeout: Option<Duration>,
) -> Result<reqwest::Response, AsyncFetchError> {
    let mut request = http.request(url, range);
    if let Some(timeout) = timeout {
        request = request.timeout(timeout);
    }
    let response = match request.send().await {
        Ok(response) => response,
        Err(err) => {
            return Err(AsyncFetchError {
                // without_url(): reqwest embeds the request URL in Display; signed
                // CDN query credentials must not reach logs or error_slot.
                kind: classify_reqwest_error(&err),
                message: format!("http get: {}", err.without_url()),
            });
        }
    };
    let status = response.status().as_u16();
    if !status_accepted(status, range.is_some()) {
        return Err(AsyncFetchError {
            message: format!("unexpected HTTP status ({status})"),
            kind: classify_status(status),
        });
    }
    Ok(response)
}

/// Whole-body mode: one attempt for one item (the driver owns retry/rotation).
async fn fetch_once(
    http: &Http,
    url: &str,
    range: Option<(u64, u64)>,
    timeout: Duration,
) -> Result<Vec<u8>, AsyncFetchError> {
    // `timeout` bounds connect+headers and each body read (idle), not the whole body:
    // a slow CDN moving bytes steadily must not hit a total-transfer deadline.
    let response = match tokio::time::timeout(timeout, send_checked(http, url, range, None)).await
    {
        Ok(res) => res?,
        Err(_) => {
            return Err(AsyncFetchError {
                message: "http get: headers timeout".to_string(),
                kind: FetchFailKind::Timeout,
            });
        }
    };
    let content_length = response.content_length();
    // Bounded incremental read: a response bigger than its reservation (or with
    // no Content-Length) must not be buffered unboundedly (OOM).
    let body = read_body_capped(response, MAX_WHOLE_BODY_BYTES, Some(timeout)).await?;
    validate_body_len(body.len() as u64, content_length, range)?;
    Ok(body)
}

/// Stream mode: one attempt for one item. Pieces are pushed to the item's pool worker as they
/// arrive (each one added to the in-flight budget until the pool consumes it); the future waits
/// while the budget is full, and stops early on cancel or when the sink rejected this attempt.
/// Returns the total body length after the last piece was queued.
#[allow(clippy::too_many_arguments)]
async fn fetch_stream(
    http: &Http,
    url: &str,
    range: Option<(u64, u64)>,
    timeout: Duration,
    idx: usize,
    attempts: u32,
    tx: &tokio::sync::mpsc::UnboundedSender<ProcessMsg>,
    in_flight: &AtomicU64,
    budget: u64,
    cancel: &AtomicBool,
    abandon: Option<&AtomicU32>,
) -> Result<u64, AsyncFetchError> {
    let mut response =
        match tokio::time::timeout(timeout, send_checked(http, url, range, None)).await {
            Ok(res) => res?,
            Err(_) => {
                return Err(AsyncFetchError {
                    message: "http get: response headers timed out".to_string(),
                    kind: FetchFailKind::Timeout,
                })
            }
        };
    let content_length = response.content_length();
    let mut offset = 0u64;
    loop {
        // Budget gate: don't read ahead of the pool by more than the budget.
        while in_flight.load(Ordering::Relaxed) >= budget {
            if cancel.load(Ordering::Relaxed) {
                return Err(AsyncFetchError {
                    message: "cancelled".to_string(),
                    kind: FetchFailKind::Other,
                });
            }
            tokio::time::sleep(Duration::from_millis(5)).await;
        }
        if abandon.is_some_and(|f| f.load(Ordering::Relaxed) == attempts) {
            return Err(AsyncFetchError {
                message: "sink rejected a piece of this attempt".to_string(),
                kind: FetchFailKind::Other,
            });
        }
        let piece = match tokio::time::timeout(timeout, response.chunk()).await {
            Ok(Ok(Some(piece))) => piece,
            Ok(Ok(None)) => break,
            Ok(Err(err)) => {
                return Err(AsyncFetchError {
                    kind: classify_reqwest_error(&err),
                    message: format!("http body: {}", err.without_url()),
                });
            }
            Err(_) => {
                return Err(AsyncFetchError {
                    message: format!("http body: idle timeout at offset {offset}"),
                    kind: FetchFailKind::Timeout,
                });
            }
        };
        if piece.is_empty() {
            continue;
        }
        let data = piece.to_vec();
        let len = data.len() as u64;
        in_flight.fetch_add(len, Ordering::Relaxed);
        if tx
            .send(ProcessMsg::Piece {
                idx,
                attempts,
                offset,
                data,
            })
            .is_err()
        {
            in_flight.fetch_sub(len, Ordering::Relaxed);
            return Err(AsyncFetchError {
                message: "process pool gone".to_string(),
                kind: FetchFailKind::Other,
            });
        }
        offset += len;
    }
    validate_body_len(offset, content_length, range)?;
    Ok(offset)
}

// ─────────────────────────────────────────────────────────────────────────────────────────────
// Driver plumbing
// ─────────────────────────────────────────────────────────────────────────────────────────────

/// `abandon` sentinel: no attempt of this item has been rejected by the sink.
const NO_ATTEMPT: u32 = u32::MAX;

enum ProcessMsg {
    /// Whole-body mode: the complete raw body.
    Body {
        idx: usize,
        attempts: u32,
        server_idx: usize,
        body: Vec<u8>,
    },
    /// Stream mode: one in-order piece of attempt `attempts` of item `idx`.
    Piece {
        idx: usize,
        attempts: u32,
        offset: u64,
        data: Vec<u8>,
    },
    /// Stream mode: the body ended; the pool finalizes and reports a verdict.
    Finish {
        idx: usize,
        attempts: u32,
        server_idx: usize,
        total_len: u64,
    },
}

enum SinkVerdict {
    Ok,
    Retry(String),
    Fatal,
}

struct Feedback {
    idx: usize,
    attempts: u32,
    server_idx: usize,
    verdict: SinkVerdict,
}

/// An item awaiting (re)dispatch: `attempts` already spent; not eligible before `not_before`.
#[derive(Clone, Copy, Debug)]
struct PendingItem {
    idx: usize,
    attempts: u32,
    not_before: Instant,
}

enum Fetched {
    Body(Vec<u8>),
    Streamed(u64),
}

/// The result the driver awaits for each dispatched fetch.
struct FetchDone {
    idx: usize,
    attempts: u32,
    reserve: u64,
    server_idx: usize,
    elapsed: Duration,
    res: Result<Fetched, AsyncFetchError>,
}

struct DriverCtx<'a> {
    items: &'a [FetchItem],
    hosts: &'a [String],
    opts: &'a FetchOptions,
    label: &'a str,
    cancel: &'a AtomicBool,
    log: &'a (dyn Fn(&str) + Sync),
    meter: &'a BandwidthMeter,
    in_flight: &'a AtomicU64,
    error_slot: &'a Mutex<Option<String>>,
    abandon: &'a [AtomicU32],
    txs: Vec<tokio::sync::mpsc::UnboundedSender<ProcessMsg>>,
    fb_rx: std_mpsc::Receiver<Feedback>,
    budget: u64,
    bootstrap: usize,
    win_min: usize,
    win_max: usize,
    per_host_cap: usize,
}

async fn run_driver(ctx: DriverCtx<'_>) {
    let DriverCtx {
        items,
        hosts,
        opts,
        label,
        cancel,
        log,
        meter,
        in_flight,
        error_slot,
        abandon,
        txs,
        fb_rx,
        budget,
        bootstrap,
        win_min,
        win_max,
        per_host_cap,
    } = ctx;
    let timeout = opts.timeout;
    let stream = opts.stream;

    let http = match build_http(opts, per_host_cap) {
        Ok(http) => http,
        Err(err) => {
            record_first_error(error_slot, format!("{label}: {err}"));
            drop(txs);
            return;
        }
    };

    let mut window = AdaptiveWindow::new(bootstrap, win_min, win_max, Instant::now());
    let mut sched = FetchScheduler::new(hosts, per_host_cap);
    let mut retry: VecDeque<PendingItem> = VecDeque::new();
    let mut next_item = 0usize;
    let mut errors_logged: u32 = 0;
    // Bodies / finishes handed to the pool whose verdict has not come back yet.
    let mut outstanding = 0usize;
    // Watchdog for a silently-dead pool worker in stream mode (its own channel sender
    // stays connected via other workers, so Disconnected alone can't catch it).
    let mut stall_since: Option<Instant> = None;
    // Declared AFTER `txs`/`http` so it is dropped BEFORE them (its futures borrow both).
    let mut inflight: FuturesUnordered<_> = FuturesUnordered::new();

    'outer: loop {
        if cancel.load(Ordering::Relaxed)
            || error_slot.lock().expect("err slot poisoned").is_some()
        {
            break;
        }

        // ── Sink verdicts: Retry = counts as a failed attempt (cool + demote host, back-off,
        // rotate); Fatal was already recorded in the error slot by the pool. ──
        // A Disconnected channel with work still outstanding means a pool thread died
        // without reporting — fail the run instead of sleeping forever.
        loop {
            let fb = match fb_rx.try_recv() {
                Ok(fb) => fb,
                Err(std::sync::mpsc::TryRecvError::Empty) => break,
                Err(std::sync::mpsc::TryRecvError::Disconnected) => {
                    if outstanding > 0 {
                        record_first_error(
                            error_slot,
                            format!("{label}: process pool exited with {outstanding} item(s) outstanding"),
                        );
                        break 'outer;
                    }
                    break;
                }
            };
            outstanding = outstanding.saturating_sub(1);
            // A verdict just arrived — the pool is alive, so the stall watchdog must
            // measure from THIS verdict, not from when the last item was dispatched.
            stall_since = None;
            match fb.verdict {
                SinkVerdict::Ok => {}
                SinkVerdict::Fatal => break 'outer,
                SinkVerdict::Retry(message) => {
                    let now = Instant::now();
                    sched.on_error(fb.server_idx, now, FetchFailKind::Other);
                    if window.record_err(now, FetchFailKind::Other) {
                        log(&window.summary_line(label, inflight.len(), now));
                    }
                    if fb.attempts < MAX_ITEM_ATTEMPTS {
                        retry.push_back(PendingItem {
                            idx: fb.idx,
                            attempts: fb.attempts,
                            not_before: now + Duration::from_millis(retry_backoff_millis(fb.attempts)),
                        });
                    } else {
                        record_first_error(
                            error_slot,
                            format!(
                                "{label}: item {} failed after {MAX_ITEM_ATTEMPTS} attempts: {message}",
                                items[fb.idx].id
                            ),
                        );
                        break 'outer;
                    }
                }
            }
        }

        let probe_now = Instant::now();
        if window.maybe_probe(probe_now, meter.total_bytes()) {
            log(&window.summary_line(label, inflight.len(), probe_now));
        }

        // ── Dispatch up to the current window ──
        while inflight.len() < window.current {
            if cancel.load(Ordering::Relaxed)
                || error_slot.lock().expect("err slot poisoned").is_some()
            {
                break 'outer;
            }
            let now = Instant::now();

            // A due retry first, else the next fresh item.
            let use_retry = retry.front().is_some_and(|p| p.not_before <= now);
            let (idx, attempts) = if use_retry {
                let p = *retry.front().expect("retry nonempty");
                (p.idx, p.attempts)
            } else if next_item < items.len() {
                (next_item, 0u32)
            } else {
                break; // nothing dispatchable now (retry not due, or all dispatched)
            };
            let item = &items[idx];

            // Byte-budget gate (hard memory bound): reserve at DISPATCH. Always admits when nothing
            // is in flight, so an item bigger than the whole budget can't deadlock. Streams reserve
            // nothing here — their pieces are budgeted as they arrive.
            let reserve = if stream { 0 } else { reserve_bytes(item) };
            if !budget_admits(in_flight.load(Ordering::Relaxed), reserve, budget) {
                window.note_budget_stall();
                break; // wait for a completion to free budget
            }

            // Speed-ranked, per-host-capped host pick.
            let Some(server_idx) = sched.pick(now) else {
                window.note_host_stall();
                break; // every host at cap or cooling → wait for a completion
            };
            let permit = match sched.host_sem(server_idx).clone().try_acquire_owned() {
                Ok(permit) => permit,
                Err(_) => {
                    window.note_host_stall();
                    break;
                }
            };

            let Some(url) = url_for_host(item, server_idx) else {
                record_first_error(error_slot, format!("{label}: item {} has no url", item.id));
                break 'outer;
            };
            let url = url.to_string();
            let range = item.range;

            // Commit: pop the work item, reserve budget, launch the fetch future.
            if use_retry {
                retry.pop_front();
            } else {
                next_item += 1;
            }
            in_flight.fetch_add(reserve, Ordering::Relaxed);
            let http = &http;
            let tx = &txs[if stream { idx % txs.len() } else { 0 }];
            let abandon_flag = abandon.get(idx);
            let started = Instant::now();
            inflight.push(async move {
                let res = if stream {
                    fetch_stream(
                        http,
                        &url,
                        range,
                        timeout,
                        idx,
                        attempts + 1,
                        tx,
                        in_flight,
                        budget,
                        cancel,
                        abandon_flag,
                    )
                    .await
                    .map(Fetched::Streamed)
                } else {
                    fetch_once(http, &url, range, timeout)
                        .await
                        .map(Fetched::Body)
                };
                drop(permit); // RAII per-host permit release (also on future drop / cancel-abort)
                FetchDone {
                    idx,
                    attempts: attempts + 1,
                    reserve,
                    server_idx,
                    elapsed: started.elapsed(),
                    res,
                }
            });
        }

        // ── Nothing in flight? finished, or waiting on retry-not-due / host cooldown / the pool. ──
        if inflight.is_empty() {
            if next_item >= items.len() && retry.is_empty() && outstanding == 0 {
                break; // every item fetched and processed
            }
            if outstanding > 0 && next_item >= items.len() && retry.is_empty() {
                // Everything is dispatched; we are only waiting on pool verdicts.
                // A worker that died without reporting would hang us here forever.
                let since = stall_since.get_or_insert_with(Instant::now);
                if since.elapsed() > Duration::from_secs(60) {
                    record_first_error(
                        error_slot,
                        format!("{label}: no process-pool verdict for 60s with {outstanding} item(s) outstanding"),
                    );
                    break;
                }
            } else {
                stall_since = None;
            }
            tokio::time::sleep(Duration::from_millis(5)).await;
            continue;
        }

        // ── Await one completion (drives ALL in-flight requests cooperatively), bounded so
        // cancel / a pool Fatal / a Retry verdict are noticed promptly even mid-fetch. ──
        let done = match tokio::time::timeout(Duration::from_millis(DRIVER_POLL_MS), inflight.next())
            .await
        {
            Ok(Some(done)) => done,
            Ok(None) => continue,
            Err(_elapsed) => continue,
        };
        let now = Instant::now();
        match done.res {
            Ok(Fetched::Body(body)) => {
                let raw_len = body.len() as u64;
                meter.record(done.server_idx, raw_len);
                sched.on_success(done.server_idx, raw_len, done.elapsed);
                window.record_ok(done.elapsed.as_secs_f64() * 1000.0);
                // Reconcile the dispatch reservation to the actual raw size; the pool subtracts the
                // actual size on process-complete, netting this item's budget to zero.
                if raw_len >= done.reserve {
                    in_flight.fetch_add(raw_len - done.reserve, Ordering::Relaxed);
                } else {
                    in_flight.fetch_sub(done.reserve - raw_len, Ordering::Relaxed);
                }
                outstanding += 1;
                if txs[0]
                    .send(ProcessMsg::Body {
                        idx: done.idx,
                        attempts: done.attempts,
                        server_idx: done.server_idx,
                        body,
                    })
                    .is_err()
                {
                    break; // process side gone
                }
            }
            Ok(Fetched::Streamed(total_len)) => {
                meter.record(done.server_idx, total_len);
                sched.on_success(done.server_idx, total_len, done.elapsed);
                window.record_ok(done.elapsed.as_secs_f64() * 1000.0);
                outstanding += 1;
                if txs[done.idx % txs.len()]
                    .send(ProcessMsg::Finish {
                        idx: done.idx,
                        attempts: done.attempts,
                        server_idx: done.server_idx,
                        total_len,
                    })
                    .is_err()
                {
                    break; // process side gone
                }
            }
            Err(err) => {
                in_flight.fetch_sub(done.reserve, Ordering::Relaxed);
                let client_rejection = is_client_rejection(&err.message);
                if errors_logged < FETCH_ERROR_LOG_LIMIT {
                    errors_logged += 1;
                    let host = hosts.get(done.server_idx).map(String::as_str).unwrap_or("?");
                    log(&format!(
                        "fetch-error label={label} item={} host={host} attempt={} kind={:?} \
window_shrink={} msg={}",
                        items[done.idx].id,
                        done.attempts,
                        err.kind,
                        !client_rejection,
                        err.message
                    ));
                    if errors_logged == FETCH_ERROR_LOG_LIMIT {
                        log(&format!(
                            "fetch-error label={label} further errors not logged individually"
                        ));
                    }
                }
                sched.on_error(done.server_idx, now, err.kind);
                if !client_rejection && window.record_err(now, err.kind) {
                    log(&window.summary_line(label, inflight.len(), now));
                }
                if done.attempts < MAX_ITEM_ATTEMPTS {
                    let backoff = retry_backoff_millis(done.attempts);
                    retry.push_back(PendingItem {
                        idx: done.idx,
                        attempts: done.attempts,
                        not_before: now + Duration::from_millis(backoff),
                    });
                } else {
                    record_first_error(
                        error_slot,
                        format!(
                            "{label}: item {} failed after {MAX_ITEM_ATTEMPTS} attempts: {}",
                            items[done.idx].id, err.message
                        ),
                    );
                    break;
                }
            }
        }
    }

    // Dropping `inflight` aborts any still-running requests (the cancel / error / final-failure
    // teardown path); dropping the senders then closes the channels so the pool drains and exits.
    drop(inflight);
    drop(txs);
}

// ─────────────────────────────────────────────────────────────────────────────────────────────
// Bandwidth meter (per-host bytes + 5-s samples for peak)
// ─────────────────────────────────────────────────────────────────────────────────────────────

pub struct BandwidthMeter {
    start: Instant,
    total_bytes: AtomicU64,
    per_server_bytes: Vec<AtomicU64>,
    hosts: Vec<String>,
    /// (bytes, instant) at the last sample; peak = max sample rate in bytes/s.
    last_sample: Mutex<(u64, Instant)>,
    peak_bps: Mutex<f64>,
}

impl BandwidthMeter {
    pub fn new(hosts: &[String]) -> Self {
        let now = Instant::now();
        Self {
            start: now,
            total_bytes: AtomicU64::new(0),
            per_server_bytes: hosts.iter().map(|_| AtomicU64::new(0)).collect(),
            hosts: hosts.to_vec(),
            last_sample: Mutex::new((0, now)),
            peak_bps: Mutex::new(0.0),
        }
    }

    #[inline]
    pub fn record(&self, server_idx: usize, bytes: u64) {
        self.total_bytes.fetch_add(bytes, Ordering::Relaxed);
        if let Some(counter) = self.per_server_bytes.get(server_idx) {
            counter.fetch_add(bytes, Ordering::Relaxed);
        }
    }

    #[inline]
    pub fn total_bytes(&self) -> u64 {
        self.total_bytes.load(Ordering::Relaxed)
    }

    /// Take a throughput sample since the previous one (≥ 1 s apart to be counted) and fold it
    /// into the peak.
    pub fn sample(&self) {
        let now = Instant::now();
        let total = self.total_bytes();
        let mut last = self.last_sample.lock().expect("meter poisoned");
        let dt = now.duration_since(last.1).as_secs_f64();
        if dt >= 1.0 {
            let bps = total.saturating_sub(last.0) as f64 / dt;
            let mut peak = self.peak_bps.lock().expect("meter poisoned");
            if bps > *peak {
                *peak = bps;
            }
            *last = (total, now);
        }
    }

    pub fn peak_bps(&self) -> f64 {
        *self.peak_bps.lock().expect("meter poisoned")
    }

    pub fn summary_line(&self, label: &str) -> String {
        let secs = self.start.elapsed().as_secs_f64().max(0.001);
        let total = self.total_bytes();
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
            "throughput label={label} overall={overall_mbps:.2}MB/s total={:.0}MB elapsed={secs:.0}s used={used}/{pool} servers:{servers}",
            mb(total)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────────────────────
// Scheduler + adaptive window — a copy of the Steam depot writer's (keyed on host strings).
// ─────────────────────────────────────────────────────────────────────────────────────────────

#[derive(Clone, Debug, Default)]
struct ServerHealth {
    ewma_bps: f64,
    samples: u32,
    consecutive_errors: u32,
    cooldown_until: Option<Instant>,
}

/// Speed-ranked, per-host-capped host picker. Cold hosts are probed first, then selection is
/// epsilon-greedy over the measured EWMA so a demoted-but-recovered host returns. Per-host caps key
/// on the host STRING (one `tokio::sync::Semaphore` each) so two entries that share a host still
/// share one cap.
struct FetchScheduler {
    health: Vec<ServerHealth>,
    server_host: Vec<usize>,
    host_sems: Vec<Arc<Semaphore>>,
    dispatch_counter: u64,
}

impl FetchScheduler {
    fn new(hosts: &[String], per_host_cap: usize) -> Self {
        let mut host_keys: Vec<&str> = Vec::new();
        let mut server_host: Vec<usize> = Vec::with_capacity(hosts.len());
        for h in hosts {
            let idx = host_keys
                .iter()
                .position(|k| *k == h.as_str())
                .unwrap_or_else(|| {
                    host_keys.push(h.as_str());
                    host_keys.len() - 1
                });
            server_host.push(idx);
        }
        let host_sems = host_keys
            .iter()
            .map(|_| Arc::new(Semaphore::new(per_host_cap.max(1))))
            .collect();
        Self {
            health: hosts.iter().map(|_| ServerHealth::default()).collect(),
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

    fn pick(&mut self, now: Instant) -> Option<usize> {
        let eligible: Vec<usize> = (0..self.health.len())
            .filter(|&i| self.eligible(i, now))
            .collect();
        if eligible.is_empty() {
            return None;
        }
        if let Some(&cold) = eligible.iter().find(|&&i| self.health[i].samples == 0) {
            return Some(cold);
        }
        self.dispatch_counter = self.dispatch_counter.wrapping_add(1);
        if SERVER_EXPLORE_EVERY > 0 && self.dispatch_counter % SERVER_EXPLORE_EVERY == 0 {
            return eligible
                .iter()
                .copied()
                .min_by_key(|&i| self.health[i].samples);
        }
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
        h.ewma_bps *= 0.5;
    }
}

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

/// Adaptive in-flight window: slow-start doubles every healthy probe until the first real back-off,
/// then grows additively; holds on plateau; shrinks proportionally only on real errors.
#[derive(Clone, Debug)]
struct AdaptiveWindow {
    current: usize,
    min: usize,
    max: usize,
    last_probe: Instant,
    last_bytes: u64,
    last_bps: f64,
    bps_ewma: f64,
    best_bps: f64,
    slow_start: bool,
    plateau_streak: u32,
    plateau_since: Option<Instant>,
    plateau_rearm_ms: u64,
    ok_count: u32,
    err_count: u32,
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

    fn note_budget_stall(&mut self) {
        self.budget_stalls = self.budget_stalls.saturating_add(1);
    }

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

    fn shrink(&mut self, now: Instant, reason: WindowReason) {
        let scaled = (self.current as f64 * WINDOW_SHRINK_FACTOR).floor() as usize;
        let next = scaled.min(self.current.saturating_sub(1)).max(self.min);
        self.current = next.max(self.min);
        self.cooldown_until = Some(now + Duration::from_millis(WINDOW_COOLDOWN_MS));
        self.slow_start = false;
        self.best_bps = self.bps_ewma;
        self.plateau_streak = 0;
        self.plateau_since = None;
        self.plateau_rearm_ms = WINDOW_PLATEAU_REARM_MS;
        self.last_reason = reason;
    }

    fn grow(&mut self, reason: WindowReason, multiplicative: bool) {
        let next = if multiplicative {
            self.current.saturating_mul(WINDOW_SLOW_START_FACTOR)
        } else {
            self.current + WINDOW_STEP_UP
        };
        self.current = next.min(self.max).max(self.min);
        self.last_reason = reason;
    }

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
            self.shrink(now, WindowReason::ShrinkErrorRate);
        } else if cooling {
            self.last_reason = WindowReason::HoldCooldown;
        } else if self.current >= self.max {
            self.last_reason = WindowReason::HoldCeiling;
        } else if err_rate > WINDOW_ERR_RATE_LOW {
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
                self.last_reason = WindowReason::HoldThroughputDown;
            } else if self.plateau_streak < WINDOW_PLATEAU_PATIENCE {
                self.plateau_streak += 1;
                self.grow(WindowReason::GrowProbe, false);
            } else {
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

    fn summary_line(&self, label: &str, in_flight_requests: usize, now: Instant) -> String {
        let mbps = |bps: f64| bps / (1024.0 * 1024.0);
        format!(
            "fetch-window label={label} window={} (min={} max={}) in_flight={in_flight_requests} \
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

// ─────────────────────────────────────────────────────────────────────────────────────────────
// Tests
// ─────────────────────────────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    #[test]
    fn client_rejection_is_4xx_but_not_429() {
        assert_eq!(rejected_status("unexpected HTTP status (403)"), Some(403));
        assert!(is_client_rejection("unexpected HTTP status (403)"));
        assert!(is_client_rejection("unexpected HTTP status (404)"));
        assert!(!is_client_rejection("unexpected HTTP status (429)"));
        assert!(!is_client_rejection("unexpected HTTP status (503)"));
        assert!(!is_client_rejection("http get: connection reset"));
        assert_eq!(rejected_status("http body: timeout"), None);
    }

    use super::*;
    use crate::depot_writer::{
        FETCH_INFLIGHT_BUDGET_FLOOR_BYTES, FETCH_INFLIGHT_BUDGET_HARD_CAP_BYTES, PER_HOST_CAP,
    };
    use std::collections::HashMap;
    use std::io::{Read, Write};
    use std::net::TcpListener;
    use std::sync::atomic::AtomicUsize;

    fn item(id: u64, urls: &[&str], range: Option<(u64, u64)>) -> FetchItem {
        FetchItem {
            id,
            urls: urls.iter().map(|s| s.to_string()).collect(),
            reserve: 0,
            range,
        }
    }

    #[test]
    fn window_bounds_clamp_to_hosts_and_hard_cap() {
        assert_eq!(window_bounds(64, 7, PER_HOST_CAP), (8, 2, 42));
        assert_eq!(window_bounds(8, 1, PER_HOST_CAP), (6, 2, 6));
        assert_eq!(window_bounds(1, 1, PER_HOST_CAP), (1, 1, 1));
        assert_eq!(window_bounds(10_000, 100, 6), (8, 2, WINDOW_HARD_CAP));
        assert_eq!(window_bounds(0, 0, 0), (1, 1, 1));
    }

    #[test]
    fn budget_scales_with_ceiling_between_floor_and_cap() {
        assert_eq!(inflight_budget_bytes(1), FETCH_INFLIGHT_BUDGET_FLOOR_BYTES);
        assert_eq!(inflight_budget_bytes(16), FETCH_INFLIGHT_BUDGET_FLOOR_BYTES);
        assert_eq!(inflight_budget_bytes(42), 63 * 1024 * 1024);
        assert_eq!(inflight_budget_bytes(256), FETCH_INFLIGHT_BUDGET_HARD_CAP_BYTES);
        assert!(budget_admits(0, u64::MAX, 1));
        assert!(!budget_admits(1, 100, 100));
    }

    #[test]
    fn reserve_defaults_to_nominal() {
        let mut i = item(1, &["u"], None);
        assert_eq!(reserve_bytes(&i), NOMINAL_CHUNK_RESERVE_BYTES);
        i.reserve = 77;
        assert_eq!(reserve_bytes(&i), 77);
    }

    #[test]
    fn url_per_host_selection() {
        let none = item(1, &[], None);
        assert_eq!(url_for_host(&none, 0), None);
        let one = item(1, &["a"], None);
        assert_eq!(url_for_host(&one, 0), Some("a"));
        assert_eq!(url_for_host(&one, 5), Some("a"));
        let many = item(1, &["a", "b", "c"], None);
        assert_eq!(url_for_host(&many, 1), Some("b"));
        assert_eq!(url_for_host(&many, 2), Some("c"));
        assert_eq!(url_for_host(&many, 9), Some("a"));
    }

    #[test]
    fn distinct_hosts_dedupe_by_string() {
        let h = vec!["a".to_string(), "b".to_string(), "a".to_string()];
        assert_eq!(distinct_host_count(&h), 2);
        assert_eq!(distinct_host_count(&[]), 1);
    }

    #[test]
    fn range_validation() {
        assert_eq!(range_len((0, 0)), Some(1));
        assert_eq!(range_len((10, 19)), Some(10));
        assert_eq!(range_len((5, 4)), None);
        assert!(validate_body_len(10, None, Some((10, 19))).is_ok());
        assert!(validate_body_len(10, Some(10), Some((10, 19))).is_ok());
        let e = validate_body_len(9, None, Some((10, 19))).unwrap_err();
        assert_eq!(e.kind, FetchFailKind::ServerFault);
        assert_eq!(
            validate_body_len(1, None, Some((5, 4))).unwrap_err().kind,
            FetchFailKind::Other
        );
        assert!(validate_body_len(100, None, None).is_ok());
        assert!(validate_body_len(100, Some(100), None).is_ok());
        assert!(validate_body_len(99, Some(100), None).is_err());
        assert!(status_accepted(200, false));
        assert!(status_accepted(200, true));
        assert!(status_accepted(206, true));
        assert!(!status_accepted(206, false));
        assert!(!status_accepted(404, true));
        assert_eq!(classify_status(429), FetchFailKind::RateLimited);
        assert_eq!(classify_status(503), FetchFailKind::ServerFault);
        assert_eq!(classify_status(404), FetchFailKind::Other);
    }

    #[test]
    fn scheduler_probes_cold_hosts_then_ranks() {
        let hosts = vec!["a".to_string(), "b".to_string()];
        let mut s = FetchScheduler::new(&hosts, 2);
        let now = Instant::now();
        assert_eq!(s.pick(now), Some(0));
        s.on_success(0, 1_000_000, Duration::from_millis(100));
        assert_eq!(s.pick(now), Some(1));
        s.on_success(1, 1_000_000, Duration::from_millis(500));
        assert_eq!(s.pick(now), Some(0));
        s.on_error(0, now, FetchFailKind::Timeout);
        assert_eq!(s.pick(now), Some(1));
        s.on_error(1, now, FetchFailKind::RateLimited);
        assert_eq!(s.pick(now), None);
        assert_eq!(s.pick(now + Duration::from_secs(6)), Some(0));
    }

    #[test]
    fn window_shrinks_only_on_error_bursts() {
        let now = Instant::now();
        let mut w = AdaptiveWindow::new(8, 2, 42, now);
        assert!(!w.record_err(now, FetchFailKind::Timeout));
        assert!(!w.record_err(now, FetchFailKind::Timeout));
        assert!(w.record_err(now, FetchFailKind::Timeout));
        assert_eq!(w.current, 6);
        assert!(!w.slow_start);
        let line = w.summary_line("t", 3, now);
        assert!(line.starts_with("fetch-window label=t window=6 (min=2 max=42) in_flight=3 "));
        assert!(line.contains("reason=shrink:timeout"));
        assert!(line.contains("phase=steady"));
    }

    // ── Minimal HTTP/1.1 stub: one thread per connection, `Connection: close`, Range-aware. ──
    struct Stub {
        base: String,
        hits: Arc<Mutex<HashMap<String, usize>>>,
    }

    fn start_stub(bodies: HashMap<String, Vec<u8>>, flaky_first: &[&str]) -> Stub {
        let listener = TcpListener::bind("127.0.0.1:0").expect("bind");
        let port = listener.local_addr().expect("addr").port();
        let hits: Arc<Mutex<HashMap<String, usize>>> = Arc::new(Mutex::new(HashMap::new()));
        let flaky: Vec<String> = flaky_first.iter().map(|s| s.to_string()).collect();
        let bodies = Arc::new(bodies);
        let hits2 = Arc::clone(&hits);
        thread::spawn(move || {
            for stream in listener.incoming() {
                let Ok(mut stream) = stream else { break };
                let bodies = Arc::clone(&bodies);
                let hits = Arc::clone(&hits2);
                let flaky = flaky.clone();
                thread::spawn(move || {
                    let mut buf = Vec::new();
                    let mut tmp = [0u8; 1024];
                    loop {
                        let n = match stream.read(&mut tmp) {
                            Ok(0) | Err(_) => return,
                            Ok(n) => n,
                        };
                        buf.extend_from_slice(&tmp[..n]);
                        if buf.windows(4).any(|w| w == b"\r\n\r\n") {
                            break;
                        }
                    }
                    let text = String::from_utf8_lossy(&buf).to_string();
                    let mut lines = text.lines();
                    let request = lines.next().unwrap_or("");
                    let path = request.split(' ').nth(1).unwrap_or("/").to_string();
                    let mut range: Option<(u64, u64)> = None;
                    let mut auth = String::new();
                    let mut ua = String::new();
                    for line in lines {
                        let lower = line.to_ascii_lowercase();
                        if let Some(v) = lower.strip_prefix("range: bytes=") {
                            let mut it = v.trim().split('-');
                            let a = it.next().and_then(|s| s.parse().ok());
                            let b = it.next().and_then(|s| s.parse().ok());
                            if let (Some(a), Some(b)) = (a, b) {
                                range = Some((a, b));
                            }
                        }
                        if let Some(v) = line.strip_prefix("Authorization: ") {
                            auth = v.trim().to_string();
                        } else if let Some(v) = line.strip_prefix("authorization: ") {
                            auth = v.trim().to_string();
                        } else if let Some(v) = line.strip_prefix("User-Agent: ") {
                            ua = v.trim().to_string();
                        } else if let Some(v) = line.strip_prefix("user-agent: ") {
                            ua = v.trim().to_string();
                        }
                    }
                    let count = {
                        let mut h = hits.lock().unwrap();
                        let c = h.entry(path.clone()).or_insert(0);
                        *c += 1;
                        *c
                    };
                    let response: Vec<u8> = if path == "/auth" || path == "/ua" {
                        let body = if path == "/auth" { auth } else { ua }.into_bytes();
                        let mut r = format!(
                            "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                            body.len()
                        )
                        .into_bytes();
                        r.extend_from_slice(&body);
                        r
                    } else if flaky.contains(&path) && count == 1 {
                        b"HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".to_vec()
                    } else if let Some(full) = bodies.get(&path) {
                        match range {
                            Some((a, b)) if (b as usize) < full.len() && a <= b => {
                                let slice = &full[a as usize..=b as usize];
                                let mut r = format!(
                                    "HTTP/1.1 206 Partial Content\r\nContent-Length: {}\r\nContent-Range: bytes {a}-{b}/{}\r\nConnection: close\r\n\r\n",
                                    slice.len(),
                                    full.len()
                                )
                                .into_bytes();
                                r.extend_from_slice(slice);
                                r
                            }
                            _ => {
                                let mut r = format!(
                                    "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                                    full.len()
                                )
                                .into_bytes();
                                r.extend_from_slice(full);
                                r
                            }
                        }
                    } else {
                        b"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".to_vec()
                    };
                    let _ = stream.write_all(&response);
                    let _ = stream.flush();
                });
            }
        });
        Stub {
            base: format!("http://127.0.0.1:{port}"),
            hits,
        }
    }

    struct CollectSink {
        got: Mutex<HashMap<u64, Vec<u8>>>,
        retry_once_id: Option<u64>,
        retried: AtomicUsize,
    }

    impl FetchSink for CollectSink {
        fn process(&self, item: &FetchItem, body: Vec<u8>) -> Result<u64, SinkError> {
            if self.retry_once_id == Some(item.id) && self.retried.fetch_add(1, Ordering::SeqCst) == 0 {
                return Err(SinkError::Retry("simulated hash mismatch".into()));
            }
            let n = body.len() as u64;
            self.got.lock().unwrap().insert(item.id, body);
            Ok(n * 2)
        }
    }

    fn opts(label: &str) -> FetchOptions {
        FetchOptions {
            max_workers: 8,
            per_host_cap: 4,
            timeout: Duration::from_secs(10),
            headers: vec![("Authorization".to_string(), "Bearer test-token".to_string())],
            ca_bundle_path: String::new(),
            process_workers: 2,
            label: label.to_string(),
            stream: false,
        }
    }

    /// Stream-mode sink: appends pieces per item (offset 0 restarts), finalizes by length check.
    struct StreamSink {
        got: Mutex<HashMap<u64, Vec<u8>>>,
        finished: Mutex<HashMap<u64, u64>>,
        /// Reject the first piece of this item once (exercises the mid-stream Retry path).
        retry_once_id: Option<u64>,
        retried: AtomicUsize,
    }

    impl FetchSink for StreamSink {
        fn process(&self, _item: &FetchItem, _body: Vec<u8>) -> Result<u64, SinkError> {
            Err(SinkError::Fatal("whole-body mode not expected".into()))
        }

        fn on_chunk(&self, item: &FetchItem, offset: u64, data: &[u8]) -> Result<(), SinkError> {
            let mut got = self.got.lock().unwrap();
            let buf = got.entry(item.id).or_default();
            if offset == 0 {
                buf.clear();
                if self.retry_once_id == Some(item.id)
                    && self.retried.fetch_add(1, Ordering::SeqCst) == 0
                {
                    return Err(SinkError::Retry("simulated bad first piece".into()));
                }
            }
            assert_eq!(buf.len() as u64, offset, "pieces must arrive in order");
            buf.extend_from_slice(data);
            Ok(())
        }

        fn on_finish(&self, item: &FetchItem, total_len: u64) -> Result<u64, SinkError> {
            let got = self.got.lock().unwrap();
            let len = got.get(&item.id).map(|b| b.len() as u64).unwrap_or(0);
            if len != total_len {
                return Err(SinkError::Retry(format!("length {len} != {total_len}")));
            }
            self.finished.lock().unwrap().insert(item.id, total_len);
            Ok(7)
        }
    }

    #[test]
    fn end_to_end_stream_mode_against_local_stub() {
        let mut bodies = HashMap::new();
        bodies.insert("/big".to_string(), (0..=255u8).cycle().take(300_000).collect::<Vec<u8>>());
        bodies.insert("/small".to_string(), b"tiny".to_vec());
        bodies.insert("/flaky".to_string(), vec![9u8; 20_000]);
        let stub = start_stub(bodies, &["/flaky"]);
        let hosts = vec!["127.0.0.1".to_string()];
        let items = vec![
            item(1, &[format!("{}/big", stub.base).as_str()], None),
            item(2, &[format!("{}/small", stub.base).as_str()], None),
            item(3, &[format!("{}/flaky", stub.base).as_str()], None),
            item(4, &[format!("{}/big", stub.base).as_str()], Some((100, 1099))),
            item(5, &[format!("{}/ua", stub.base).as_str()], None),
        ];
        let sink = StreamSink {
            got: Mutex::new(HashMap::new()),
            finished: Mutex::new(HashMap::new()),
            retry_once_id: Some(1),
            retried: AtomicUsize::new(0),
        };
        let mut options = opts("stream app=stub");
        options.stream = true;
        options.per_host_cap = 8;
        options.headers.push(("User-Agent".to_string(), "nile/0.1 Amazon".to_string()));
        let cancel = AtomicBool::new(false);
        let lines: Mutex<Vec<String>> = Mutex::new(Vec::new());
        let outcome = run_fetch(
            items,
            &hosts,
            &options,
            &sink,
            &cancel,
            &|_: u64, _: u64| {},
            &|line: &str| lines.lock().unwrap().push(line.to_string()),
        );
        let lines = lines.lock().unwrap();
        assert!(outcome.ok(), "outcome={outcome:?} lines={lines:?}");
        assert_eq!(outcome.items_ok, 5);
        let got = sink.got.lock().unwrap();
        assert_eq!(got[&1].len(), 300_000);
        assert_eq!(got[&1][1000], (1000 % 256) as u8);
        assert_eq!(got[&2], b"tiny".to_vec());
        assert_eq!(got[&3], vec![9u8; 20_000]);
        assert_eq!(got[&4].len(), 1000);
        assert_eq!(got[&4][0], 100u8);
        assert_eq!(got[&5], b"nile/0.1 Amazon".to_vec());
        let finished = sink.finished.lock().unwrap();
        assert_eq!(finished.len(), 5);
        // Pieces credited as written + 7 extra per on_finish; the rejected attempt of /big
        // contributed nothing (its first piece was refused before any write).
        let written: u64 = got.values().map(|b| b.len() as u64).sum();
        assert_eq!(outcome.bytes_credited, written + 5 * 7);
        let hits = stub.hits.lock().unwrap();
        assert_eq!(hits["/flaky"], 2);
        assert!(hits["/big"] >= 3, "big={} (2 items + 1 retry)", hits["/big"]);
        assert!(lines[0].contains(" mode=stream"), "{}", lines[0]);
        assert!(lines[0].contains(" ceiling=8 "), "{}", lines[0]);
        assert!(lines.last().unwrap().ends_with("result=ok"));
    }

    #[test]
    fn end_to_end_against_local_stub() {
        let mut bodies = HashMap::new();
        bodies.insert("/a".to_string(), vec![1u8; 5000]);
        bodies.insert("/b".to_string(), (0..=255u8).cycle().take(70_000).collect::<Vec<u8>>());
        bodies.insert("/c".to_string(), b"hello world".to_vec());
        let stub = start_stub(bodies, &["/c"]);
        let hosts = vec!["127.0.0.1".to_string()];
        let items = vec![
            item(1, &[format!("{}/a", stub.base).as_str()], None),
            item(2, &[format!("{}/b", stub.base).as_str()], Some((1000, 1999))),
            item(3, &[format!("{}/c", stub.base).as_str()], None),
            item(4, &[format!("{}/auth", stub.base).as_str()], None),
        ];
        let sink = CollectSink {
            got: Mutex::new(HashMap::new()),
            retry_once_id: Some(1),
            retried: AtomicUsize::new(0),
        };
        let cancel = AtomicBool::new(false);
        let lines: Mutex<Vec<String>> = Mutex::new(Vec::new());
        let last_progress: Mutex<(u64, u64)> = Mutex::new((0, 0));
        let outcome = run_fetch(
            items,
            &hosts,
            &opts("test app=stub"),
            &sink,
            &cancel,
            &|bytes: u64, ok: u64| {
                // Pool threads race on the callback order; keep the high-water mark.
                let mut cur = last_progress.lock().unwrap();
                if bytes >= cur.0 {
                    *cur = (bytes, ok);
                }
            },
            &|line: &str| lines.lock().unwrap().push(line.to_string()),
        );
        let lines = lines.lock().unwrap();
        assert!(outcome.ok(), "outcome={outcome:?} lines={lines:?}");
        assert_eq!(outcome.items_ok, 4);
        let got = sink.got.lock().unwrap();
        assert_eq!(got[&1], vec![1u8; 5000]);
        assert_eq!(got[&2].len(), 1000);
        assert_eq!(got[&2][0], (1000 % 256) as u8);
        assert_eq!(got[&3], b"hello world".to_vec());
        assert_eq!(got[&4], b"Bearer test-token".to_vec());
        let expected_credit: u64 = got.values().map(|b| b.len() as u64 * 2).sum();
        assert_eq!(outcome.bytes_credited, expected_credit);
        assert_eq!(*last_progress.lock().unwrap(), (expected_credit, 4));
        // /c was served 503 once, then 200; /a was re-fetched once after the sink's Retry.
        let hits = stub.hits.lock().unwrap();
        assert_eq!(hits["/c"], 2);
        assert_eq!(hits["/a"], 2);
        assert!(lines[0].starts_with("fetch-start label=test app=stub items=4 "));
        assert!(lines.last().unwrap().starts_with("fetch-end label=test app=stub items_ok=4 "));
        assert!(lines.last().unwrap().ends_with("result=ok"));
    }

    #[test]
    fn end_to_end_gives_up_after_max_attempts() {
        let stub = start_stub(HashMap::new(), &[]);
        let hosts = vec!["127.0.0.1".to_string()];
        let items = vec![item(9, &[format!("{}/missing", stub.base).as_str()], None)];
        let sink = CollectSink {
            got: Mutex::new(HashMap::new()),
            retry_once_id: None,
            retried: AtomicUsize::new(0),
        };
        let cancel = AtomicBool::new(false);
        let outcome = run_fetch(
            items,
            &hosts,
            &opts("t"),
            &sink,
            &cancel,
            &|_: u64, _: u64| {},
            &|_: &str| {},
        );
        assert!(!outcome.ok());
        assert_eq!(outcome.items_ok, 0);
        let err = outcome.error.unwrap();
        assert!(err.starts_with("t: item 9 failed after 5 attempts: "), "{err}");
        assert_eq!(stub.hits.lock().unwrap()["/missing"], 5);
    }

    #[test]
    fn cancel_before_start_returns_cancelled() {
        let hosts = vec!["h".to_string()];
        let items = vec![item(1, &["http://127.0.0.1:9/x"], None)];
        let sink = CollectSink {
            got: Mutex::new(HashMap::new()),
            retry_once_id: None,
            retried: AtomicUsize::new(0),
        };
        let cancel = AtomicBool::new(true);
        let outcome = run_fetch(
            items,
            &hosts,
            &opts("t"),
            &sink,
            &cancel,
            &|_: u64, _: u64| {},
            &|_: &str| {},
        );
        assert!(outcome.cancelled);
        assert_eq!(outcome.items_ok, 0);
    }
}
