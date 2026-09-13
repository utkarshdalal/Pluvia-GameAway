//! Store download engines (Epic / GOG / Amazon) built on [`crate::fetch_core`].
//!
//! Each store module owns its manifest → plan → `FetchItem` mapping, its `FetchSink` (inflate +
//! hash + write in the Java manager's exact output layout) and its JNI facade. Resume/skip, retry
//! semantics and error surfaces mirror the corresponding Java manager one-to-one — see
//! `docs/RUST_STORE_ENGINES.md` and the per-store `docs/RUST_<STORE>_PARITY.md`.

pub mod amazon;
pub mod epic;
pub mod gog;
