//! Epic Games Store download adapter for the shared fetch core (`crate::fetch_core`).
//!
//! Scope = the chunk-fetch inner loop of `EpicDownloadManager.java` (the fixed 8-thread pool that
//! fills `<installDir>/.chunks/<GUID>`) PLUS the assembly stage (`assembleFileSequential`):
//! after a successful fetch the engine writes every pending file out of the cache, deleting
//! each chunk after its last consumer. The Java manager still parses the manifest API JSON,
//! downloads the manifest, selects files (install tags), runs the delta/verify pass and does
//! every post-install step. The adapter re-parses
//! the same manifest bytes, rebuilds the same chunk plan for the pending files Java hands it,
//! skips chunks already in the cache and writes verified chunks with the same `.part` + rename
//! protocol, so the on-disk state is identical whichever engine ran (see
//! `docs/RUST_EPIC_PARITY.md` for the rule-by-rule table).
//!
//! Submodules:
//! - [`manifest`] — ChunksV4 binary manifest + legacy JSON manifest parsers (1:1 with the Java
//!   `parseManifest` / `parseJsonManifest`).
//! - [`plan`] — install-tag selection, unique-chunk plan, chunk paths / CDN URLs.
//! - [`chunk`] — chunk header parse, zlib inflate, SHA-1 verify, cache write.
//! - [`driver`] — plan → `FetchItem`s → `fetch_core::run_fetch` with the chunk-cache sink.
//! - [`jni`] — `Java_com_winlator_star_store_blsteam_BlEpicDownload_native*` exports.
//!
//! The selective-install-tag, delta/resume and chunk verification rules mirrored here were ported
//! into the Java manager from GameNative (`service/epic/EpicDownloadManager.kt`,
//! `manifest/ManifestUtils.kt`), itself derived from Legendary; both are GPL-3.0 and this crate
//! carries the same licence (see `Cargo.toml`).

pub mod chunk;
pub mod driver;
pub mod jni;
pub mod manifest;
pub mod plan;

/// User-Agent the Java manager sends on every chunk request (`EpicDownloadManager.UA`).
pub const USER_AGENT: &str =
    "UELauncher/11.0.1-14907503+++Portal+Release-Live Windows/10.0.19041.1.256.64bit";

/// Name of the chunk cache directory under the install dir (`new File(installDir, ".chunks")`).
pub const CHUNK_CACHE_DIR: &str = ".chunks";

/// Fixed pool width of the Java chunk downloader (`Executors.newFixedThreadPool(8)`).
pub const JAVA_POOL_THREADS: usize = 8;

/// logcat tag for every engine line (Java side mirrors it into `bh_epic_debug.txt`).
pub const LOG_TAG: &str = "GN_EPIC_DL";
