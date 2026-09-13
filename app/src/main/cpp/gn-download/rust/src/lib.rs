//! GameNative unified game-download engine (`libgndownload.so`).
//!
//! All real store download pipelines run in Rust:
//! - Steam CDN depot downloads (`depot_downloader` / `depot_writer` / `cdn_client` /
//!   `fetch_core`), fed by JavaSteam-resolved depot keys, manifest request codes and CDN server
//!   lists from Kotlin (`jni_steam`).
//! - Epic / GOG / Amazon chunk engines (`store_dl`), fed manifest/plan JSON by the Kotlin store
//!   services through `GameDownloadService`.
//!
//! Login / CM traffic stays in JavaSteam on the Kotlin side; this crate only moves bytes.

pub mod base64;
pub mod cdn_client;
pub mod content_manifest;
pub mod crypto;
pub mod depot_chunk;
pub mod depot_config;
pub mod depot_downloader;
pub mod depot_writer;
pub mod fetch_core;
pub mod jni_steam;
pub mod jni_tree_delete;
pub mod md5_small;
pub mod pb;
pub mod proto_wire;
pub mod store_dl;
pub mod tree_delete;
