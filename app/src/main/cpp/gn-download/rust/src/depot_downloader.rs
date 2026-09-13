use crate::cdn_client::{CdnClient, CdnManifestResult};
use crate::content_manifest::ContentManifest;
use crate::depot_config::{DepotConfigStore, DepotProgressStore, INVALID_MANIFEST_ID};
use crate::depot_writer::{write_depot_sequential, DepotWriteOptions};
use crate::pb::ccontentserverdirectory::CContentServerDirectoryServerInfo;
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread;
use std::time::Duration;

pub const MAX_MANIFEST_FETCH_ATTEMPTS: usize = 5;

/// Name of the per-install download journal directory, created next to the game files.
///
/// GameNative keeps this engine's journal in the same `.DepotDownloader/` directory the
/// JavaSteam DepotDownloader used: both record "installed manifest per depot" in the same
/// `installedManifestIDs` JSON format, and the app reads cached `<depot>_<manifest>.manifest`
/// files from this directory, so downloads resume seamlessly across the engine swap.
pub const CONFIG_DIR_NAME: &str = ".DepotDownloader";

/// `<install_dir>/.DepotDownloader`.
pub fn config_dir_path(install_dir: impl AsRef<Path>) -> PathBuf {
    install_dir.as_ref().join(CONFIG_DIR_NAME)
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct DepotSpec {
    pub depot_id: u32,
    pub manifest_id: u64,
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct ResolvedDepotSpec {
    pub depot_id: u32,
    pub manifest_id: u64,
    pub depot_key: Vec<u8>,
    pub manifest_request_code: u64,
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct DepotDownloadProgress {
    pub depot_id: u32,
    pub depot_done: u64,
    pub depot_total: u64,
    pub depots_done: u32,
    pub depots_total: u32,
    pub verifying: bool,
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct DepotDownloadResult {
    pub success: bool,
    pub error: String,
    pub bytes_written: u64,
    pub depots_completed: u32,
    pub depots_skipped: u32,
}

impl DepotDownloadResult {
    pub fn fail(error: impl Into<String>) -> Self {
        Self {
            success: false,
            error: error.into(),
            ..Default::default()
        }
    }

    pub fn ok(bytes_written: u64, depots_completed: u32, depots_skipped: u32) -> Self {
        Self {
            success: true,
            bytes_written,
            depots_completed,
            depots_skipped,
            error: String::new(),
        }
    }
}

pub fn clean_pause_marker_name(depot_id: u32, manifest_id: u64) -> String {
    format!("{depot_id}_{manifest_id}.cleanpause")
}

pub fn clean_pause_marker_path(
    config_dir: impl AsRef<Path>,
    depot_id: u32,
    manifest_id: u64,
) -> PathBuf {
    config_dir
        .as_ref()
        .join(clean_pause_marker_name(depot_id, manifest_id))
}

pub fn has_clean_pause_marker(
    config_dir: impl AsRef<Path>,
    depot_id: u32,
    manifest_id: u64,
) -> bool {
    clean_pause_marker_path(config_dir, depot_id, manifest_id).is_file()
}

pub fn write_clean_pause_marker(
    config_dir: impl AsRef<Path>,
    depot_id: u32,
    manifest_id: u64,
) -> bool {
    let path = clean_pause_marker_path(config_dir, depot_id, manifest_id);
    let Some(parent) = path.parent() else {
        return false;
    };
    if fs::create_dir_all(parent).is_err() {
        return false;
    }
    fs::write(path, manifest_id.to_string()).is_ok()
}

pub fn remove_clean_pause_marker(config_dir: impl AsRef<Path>, depot_id: u32, manifest_id: u64) {
    let _ = fs::remove_file(clean_pause_marker_path(config_dir, depot_id, manifest_id));
}

pub fn validate_download_inputs(
    install_dir: &str,
    depots: &[DepotSpec],
) -> Result<(), DepotDownloadResult> {
    if install_dir.is_empty() {
        return Err(DepotDownloadResult::fail("download: empty install dir"));
    }
    if depots.is_empty() {
        return Err(DepotDownloadResult::fail("download: no depots"));
    }
    Ok(())
}

pub fn validate_resolved_download_inputs(
    install_dir: &str,
    depots: &[ResolvedDepotSpec],
    servers: &[CContentServerDirectoryServerInfo],
) -> Result<(), DepotDownloadResult> {
    if install_dir.is_empty() {
        return Err(DepotDownloadResult::fail("download: empty install dir"));
    }
    if depots.is_empty() {
        return Err(DepotDownloadResult::fail("download: no depots"));
    }
    if servers.is_empty() {
        return Err(DepotDownloadResult::fail(
            "download: no CDN servers available",
        ));
    }
    Ok(())
}

pub fn filter_usable_cdn_servers(
    servers: impl IntoIterator<Item = CContentServerDirectoryServerInfo>,
) -> Vec<CContentServerDirectoryServerInfo> {
    servers
        .into_iter()
        .filter(|server| !server.steam_china_only && !server.host.is_empty())
        .collect()
}

/// Region preference for the CDN pool: Valve's own SteamPipe caches are named
/// `cache<N>-<dc>.steamcontent.com`, so servers whose host (or vhost) carries `-<dc>.` are moved to
/// the front, keeping the directory's order inside each group. Manifest fetches start at index 0
/// and chunk workers bias their rotation by index, so this steers most traffic to the chosen
/// datacenter while every other server stays available for rotation and retries. An empty `dc`
/// (Auto without a remembered winner) or no matching host leaves the order unchanged.
pub fn prefer_cdn_servers_for_dc(
    servers: Vec<CContentServerDirectoryServerInfo>,
    dc: &str,
) -> Vec<CContentServerDirectoryServerInfo> {
    let dc = dc.trim().to_ascii_lowercase();
    if dc.is_empty() {
        return servers;
    }
    let needle = format!("-{dc}.");
    let matches = |server: &CContentServerDirectoryServerInfo| {
        server.host.to_ascii_lowercase().contains(&needle)
            || server.vhost.to_ascii_lowercase().contains(&needle)
    };
    let (preferred, rest): (Vec<_>, Vec<_>) = servers.into_iter().partition(matches);
    if preferred.is_empty() {
        return rest;
    }
    preferred.into_iter().chain(rest).collect()
}

pub fn manifest_retry_server_indices(server_count: usize, attempts: usize) -> Vec<usize> {
    if server_count == 0 {
        return Vec::new();
    }
    (0..attempts)
        .map(|attempt| attempt % server_count)
        .collect()
}

pub fn retry_backoff_millis(attempt: u32) -> u64 {
    if attempt == 0 {
        0
    } else {
        (300u64 << (attempt - 1)).min(4000)
    }
}

pub fn fetch_manifest_with_retry(
    cdn: &CdnClient,
    servers: &[CContentServerDirectoryServerInfo],
    depot_id: u32,
    manifest_id: u64,
    request_code: u64,
    cdn_auth_token: &str,
    timeout: Duration,
) -> CdnManifestResult {
    if servers.is_empty() {
        return CdnManifestResult {
            error: "download: no CDN servers available".to_string(),
            ..Default::default()
        };
    }
    let mut last = CdnManifestResult::default();
    for (attempt, server_idx) in
        manifest_retry_server_indices(servers.len(), MAX_MANIFEST_FETCH_ATTEMPTS)
            .into_iter()
            .enumerate()
    {
        if attempt > 0 {
            thread::sleep(Duration::from_millis(retry_backoff_millis(attempt as u32)));
        }
        last = cdn.fetch_manifest(
            &servers[server_idx],
            depot_id,
            manifest_id,
            request_code,
            cdn_auth_token,
            timeout,
        );
        if last.ok() {
            return last;
        }
    }
    last
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum DepotResumeDecision {
    SkipInstalled,
    Download { trust_existing_chunks: bool },
}

pub fn decide_depot_resume(
    fresh: bool,
    cfg: &DepotConfigStore,
    spec: DepotSpec,
    clean_pause_marker_exists: bool,
) -> DepotResumeDecision {
    if !fresh && cfg.is_installed(spec.depot_id, spec.manifest_id) {
        DepotResumeDecision::SkipInstalled
    } else {
        DepotResumeDecision::Download {
            trust_existing_chunks: !fresh && clean_pause_marker_exists,
        }
    }
}

pub fn in_progress_manifest_id() -> u64 {
    INVALID_MANIFEST_ID
}

pub fn map_write_progress(
    depot_id: u32,
    depots_done: u32,
    depots_total: u32,
    done: u64,
    total: u64,
    verifying: bool,
) -> DepotDownloadProgress {
    DepotDownloadProgress {
        depot_id,
        depot_done: done,
        depot_total: total,
        depots_done,
        depots_total,
        verifying,
    }
}

pub type DepotProgressCallback<'a> = &'a (dyn Fn(&DepotDownloadProgress) + Sync);

/// Returns a fresh manifest request code for (depot_id, manifest_id); Steam rotates codes ~every 5 min.
pub type ManifestCodeRefresher<'a> = &'a (dyn Fn(u32, u64) -> Option<u64> + Sync);

pub fn download_resolved_depots(
    install_dir: &str,
    depots: &[ResolvedDepotSpec],
    servers: &[CContentServerDirectoryServerInfo],
    ca_bundle_path: &str,
    fresh: bool,
    max_workers: u32,
    max_process_workers: u32,
) -> DepotDownloadResult {
    download_resolved_depots_with_cancel_progress(
        install_dir,
        depots,
        servers,
        ca_bundle_path,
        fresh,
        max_workers,
        max_process_workers,
        None,
        None,
        None,
        None,
    )
}

#[allow(clippy::too_many_arguments)]
pub fn download_resolved_depots_with_cancel(
    install_dir: &str,
    depots: &[ResolvedDepotSpec],
    servers: &[CContentServerDirectoryServerInfo],
    ca_bundle_path: &str,
    fresh: bool,
    max_workers: u32,
    max_process_workers: u32,
    cancel: Option<&AtomicBool>,
) -> DepotDownloadResult {
    download_resolved_depots_with_cancel_progress(
        install_dir,
        depots,
        servers,
        ca_bundle_path,
        fresh,
        max_workers,
        max_process_workers,
        cancel,
        None,
        None,
        None,
    )
}

#[allow(clippy::too_many_arguments)]
pub fn download_resolved_depots_with_cancel_progress(
    install_dir: &str,
    depots: &[ResolvedDepotSpec],
    servers: &[CContentServerDirectoryServerInfo],
    ca_bundle_path: &str,
    fresh: bool,
    max_workers: u32,
    max_process_workers: u32,
    cancel: Option<&AtomicBool>,
    on_progress: Option<DepotProgressCallback<'_>>,
    code_refresher: Option<ManifestCodeRefresher<'_>>,
    log: Option<crate::depot_writer::DepotLogCallback<'_>>,
) -> DepotDownloadResult {
    if let Err(error) = validate_resolved_download_inputs(install_dir, depots, servers) {
        return error;
    }
    let usable_servers = filter_usable_cdn_servers(servers.iter().cloned());
    if usable_servers.is_empty() {
        return DepotDownloadResult::fail("download: no usable CDN server");
    }

    if let Err(error) = fs::create_dir_all(install_dir) {
        return DepotDownloadResult::fail(format!("download: mkdir install dir: {error}"));
    }
    let config_dir = config_dir_path(install_dir);
    if let Err(error) = fs::create_dir_all(&config_dir) {
        return DepotDownloadResult::fail(format!("download: mkdir config dir: {error}"));
    }

    let mut cfg = DepotConfigStore::load(&config_dir);
    if fresh {
        // Reset only this batch's depots; a global discard would wipe earlier batches' records.
        for depot in depots {
            cfg.forget_depot(depot.depot_id);
            DepotProgressStore::remove(&config_dir, depot.depot_id, depot.manifest_id);
            remove_clean_pause_marker(&config_dir, depot.depot_id, depot.manifest_id);
        }
    }

    let cdn = CdnClient::new(ca_bundle_path);
    let mut result = DepotDownloadResult {
        success: true,
        ..Default::default()
    };

    let depots_total = depots.len() as u32;
    for (depot_index, depot) in depots.iter().enumerate() {
        if cancel.is_some_and(|cancel| cancel.load(Ordering::Relaxed)) {
            return DepotDownloadResult::fail("cancelled");
        }
        let spec = DepotSpec {
            depot_id: depot.depot_id,
            manifest_id: depot.manifest_id,
        };
        let clean_pause = has_clean_pause_marker(&config_dir, depot.depot_id, depot.manifest_id);
        if decide_depot_resume(fresh, &cfg, spec, clean_pause) == DepotResumeDecision::SkipInstalled
        {
            result.depots_skipped += 1;
            continue;
        }
        if depot.depot_key.len() != 32 {
            return DepotDownloadResult::fail(format!(
                "download: depot key unavailable for depot {}",
                depot.depot_id
            ));
        }
        if !cfg.begin_depot(depot.depot_id) {
            return DepotDownloadResult::fail(format!(
                "download: depot.config begin failed for depot {}",
                depot.depot_id
            ));
        }

        let cache_path = cfg.manifest_cache_path(depot.depot_id, depot.manifest_id);
        let raw_manifest = match read_cached_manifest(&cache_path) {
            Some(raw) => raw,
            None => {
                if cancel.is_some_and(|cancel| cancel.load(Ordering::Relaxed)) {
                    return DepotDownloadResult::fail("cancelled");
                }
                // Prefer a code obtained now; the pre-resolved one may have expired.
                let refreshed_code = code_refresher
                    .and_then(|refresh| refresh(depot.depot_id, depot.manifest_id));
                let request_code = refreshed_code.unwrap_or(depot.manifest_request_code);
                let mut manifest = fetch_manifest_with_retry(
                    &cdn,
                    &usable_servers,
                    depot.depot_id,
                    depot.manifest_id,
                    request_code,
                    "",
                    CdnClient::default_timeout(),
                );
                if !manifest.ok() {
                    // One more pass with a code obtained after the failed attempts.
                    if let Some(fresh) = code_refresher
                        .and_then(|refresh| refresh(depot.depot_id, depot.manifest_id))
                        .filter(|fresh| *fresh != request_code)
                    {
                        manifest = fetch_manifest_with_retry(
                            &cdn,
                            &usable_servers,
                            depot.depot_id,
                            depot.manifest_id,
                            fresh,
                            "",
                            CdnClient::default_timeout(),
                        );
                    }
                }
                if !manifest.ok() {
                    return DepotDownloadResult::fail(format!(
                        "download: manifest fetch failed for depot {}: {}",
                        depot.depot_id, manifest.error
                    ));
                }
                let _ = write_manifest_cache(&cache_path, &manifest.raw_manifest);
                manifest.raw_manifest
            }
        };

        let Some(mut manifest) = ContentManifest::parse(&raw_manifest) else {
            return DepotDownloadResult::fail(format!(
                "download: manifest parse failed for depot {}",
                depot.depot_id
            ));
        };
        if !manifest.decrypt_filenames(&depot.depot_key) {
            return DepotDownloadResult::fail(format!(
                "download: filename decryption failed for depot {}",
                depot.depot_id
            ));
        }

        let depot_id = depot.depot_id;
        let depots_done = depot_index as u32;
        let chunk_progress = |done: u64, total: u64, verifying: bool| {
            if let Some(on_progress) = on_progress {
                let progress = map_write_progress(
                    depot_id,
                    depots_done,
                    depots_total,
                    done,
                    total,
                    verifying,
                );
                on_progress(&progress);
            }
        };
        let chunk_progress: crate::depot_writer::DepotChunkProgressCallback =
            &chunk_progress;
        let write_result = write_depot_sequential(
            &manifest,
            &depot.depot_key,
            &cdn,
            &usable_servers,
            install_dir,
            DepotWriteOptions {
                max_workers,
                max_process_workers,
                cancel,
                on_progress: Some(chunk_progress),
                log,
                ..Default::default()
            },
        );
        if !write_result.ok() {
            if write_result.resume_trust_safe {
                let _ = write_clean_pause_marker(&config_dir, depot.depot_id, depot.manifest_id);
            }
            return DepotDownloadResult::fail(format!(
                "download: depot {} write failed: {}",
                depot.depot_id, write_result.error
            ));
        }

        if !cfg.finish_depot(depot.depot_id, depot.manifest_id) {
            return DepotDownloadResult::fail(format!(
                "download: depot.config finish failed for depot {}",
                depot.depot_id
            ));
        }
        DepotProgressStore::new(&config_dir, depot.depot_id, depot.manifest_id).discard();
        remove_clean_pause_marker(&config_dir, depot.depot_id, depot.manifest_id);
        result.bytes_written += write_result.bytes_written;
        result.depots_completed += 1;
    }

    result
}

fn read_cached_manifest(path: &Path) -> Option<Vec<u8>> {
    let bytes = fs::read(path).ok()?;
    (!bytes.is_empty()).then_some(bytes)
}

fn write_manifest_cache(path: &Path, raw_manifest: &[u8]) -> bool {
    let Some(parent) = path.parent() else {
        return false;
    };
    if fs::create_dir_all(parent).is_err() {
        return false;
    }
    fs::write(path, raw_manifest).is_ok()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::content_manifest::{END_OF_MANIFEST_MAGIC, METADATA_MAGIC, PAYLOAD_MAGIC};
    use crate::proto_wire::Writer;
    use std::time::{SystemTime, UNIX_EPOCH};

    #[test]
    fn marker_names_match_cpp_format() {
        assert_eq!(clean_pause_marker_name(123, 456), "123_456.cleanpause");
    }

    #[test]
    fn clean_pause_marker_files_roundtrip() {
        let dir = temp_dir("clean_pause");
        assert!(!has_clean_pause_marker(&dir, 123, 456));
        assert!(write_clean_pause_marker(&dir, 123, 456));
        assert!(has_clean_pause_marker(&dir, 123, 456));
        assert_eq!(
            fs::read_to_string(clean_pause_marker_path(&dir, 123, 456)).unwrap(),
            "456"
        );
        remove_clean_pause_marker(&dir, 123, 456);
        assert!(!has_clean_pause_marker(&dir, 123, 456));
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn filters_non_china_servers_with_hosts() {
        let servers = filter_usable_cdn_servers([
            CContentServerDirectoryServerInfo {
                host: "ok".into(),
                ..Default::default()
            },
            CContentServerDirectoryServerInfo {
                host: "china".into(),
                steam_china_only: true,
                ..Default::default()
            },
            CContentServerDirectoryServerInfo {
                host: String::new(),
                ..Default::default()
            },
        ]);
        assert_eq!(servers.len(), 1);
        assert_eq!(servers[0].host, "ok");
    }

    #[test]
    fn resume_decision_matches_cpp_fresh_and_installed_rules() {
        let dir = temp_dir("resume_decision");
        let mut cfg = DepotConfigStore::load(&dir);
        cfg.finish_depot(100, 555);
        let spec = DepotSpec {
            depot_id: 100,
            manifest_id: 555,
        };
        assert_eq!(
            decide_depot_resume(false, &cfg, spec, false),
            DepotResumeDecision::SkipInstalled
        );
        assert_eq!(
            decide_depot_resume(true, &cfg, spec, true),
            DepotResumeDecision::Download {
                trust_existing_chunks: false
            }
        );
        assert_eq!(
            decide_depot_resume(
                false,
                &cfg,
                DepotSpec {
                    depot_id: 100,
                    manifest_id: 777
                },
                true
            ),
            DepotResumeDecision::Download {
                trust_existing_chunks: true
            }
        );
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn region_preference_moves_matching_hosts_first_and_keeps_order() {
        let server = |host: &str| CContentServerDirectoryServerInfo {
            host: host.into(),
            ..Default::default()
        };
        let input = vec![
            server("cache1-iad1.steamcontent.com"),
            server("edge.steam-dns.top.comcast.net"),
            server("cache2-fra2.steamcontent.com"),
            server("cache9-fra2.steamcontent.com"),
        ];
        let out = prefer_cdn_servers_for_dc(input.clone(), "FRA2");
        let hosts: Vec<&str> = out.iter().map(|s| s.host.as_str()).collect();
        assert_eq!(
            hosts,
            [
                "cache2-fra2.steamcontent.com",
                "cache9-fra2.steamcontent.com",
                "cache1-iad1.steamcontent.com",
                "edge.steam-dns.top.comcast.net",
            ]
        );
        let unchanged = prefer_cdn_servers_for_dc(input.clone(), "");
        assert_eq!(unchanged, input);
        let no_match = prefer_cdn_servers_for_dc(input.clone(), "syd1");
        assert_eq!(no_match, input);
    }

    #[test]
    fn retry_rotation_and_progress_mapping_match_cpp() {
        assert_eq!(manifest_retry_server_indices(3, 5), [0, 1, 2, 0, 1]);
        assert_eq!(manifest_retry_server_indices(0, 5), Vec::<usize>::new());
        assert_eq!(retry_backoff_millis(1), 300);
        assert_eq!(retry_backoff_millis(5), 4000);
        assert_eq!(
            map_write_progress(100, 2, 4, 10, 20, true),
            DepotDownloadProgress {
                depot_id: 100,
                depot_done: 10,
                depot_total: 20,
                depots_done: 2,
                depots_total: 4,
                verifying: true
            }
        );
    }

    #[test]
    fn validates_download_inputs() {
        assert_eq!(
            validate_download_inputs("", &[DepotSpec::default()])
                .unwrap_err()
                .error,
            "download: empty install dir"
        );
        assert_eq!(
            validate_download_inputs("/tmp/app", &[]).unwrap_err().error,
            "download: no depots"
        );
        assert!(validate_download_inputs("/tmp/app", &[DepotSpec::default()]).is_ok());
        assert_eq!(in_progress_manifest_id(), INVALID_MANIFEST_ID);
    }

    #[test]
    fn validates_resolved_inputs_and_filters_servers() {
        assert_eq!(
            validate_resolved_download_inputs("", &[ResolvedDepotSpec::default()], &[])
                .unwrap_err()
                .error,
            "download: empty install dir"
        );
        assert_eq!(
            validate_resolved_download_inputs("/tmp/app", &[], &[])
                .unwrap_err()
                .error,
            "download: no depots"
        );
        assert_eq!(
            validate_resolved_download_inputs("/tmp/app", &[ResolvedDepotSpec::default()], &[])
                .unwrap_err()
                .error,
            "download: no CDN servers available"
        );
    }

    #[test]
    fn resolved_download_uses_cached_manifest_and_records_install() {
        let dir = temp_dir("resolved_download_cached_manifest");
        let config_dir = config_dir_path(&dir);
        assert_eq!(config_dir, dir.join(".DepotDownloader"));
        fs::create_dir_all(&config_dir).unwrap();
        let raw_manifest = raw_layout_manifest(100, 555, "empty.bin", 5);
        fs::write(config_dir.join("100_555.manifest"), raw_manifest).unwrap();

        let result = download_resolved_depots(
            dir.to_str().unwrap(),
            &[ResolvedDepotSpec {
                depot_id: 100,
                manifest_id: 555,
                depot_key: vec![1u8; 32],
                manifest_request_code: 0,
            }],
            &[CContentServerDirectoryServerInfo {
                host: "cdn.example".into(),
                https_support: "mandatory".into(),
                ..Default::default()
            }],
            "",
            false,
            4,
            4,
        );

        assert!(result.success, "{}", result.error);
        assert_eq!(result.depots_completed, 1);
        assert_eq!(result.depots_skipped, 0);
        assert_eq!(fs::metadata(dir.join("empty.bin")).unwrap().len(), 5);
        let cfg = DepotConfigStore::load(&config_dir);
        assert!(cfg.is_installed(100, 555));

        let skipped = download_resolved_depots(
            dir.to_str().unwrap(),
            &[ResolvedDepotSpec {
                depot_id: 100,
                manifest_id: 555,
                depot_key: vec![1u8; 32],
                manifest_request_code: 0,
            }],
            &[CContentServerDirectoryServerInfo {
                host: "cdn.example".into(),
                https_support: "mandatory".into(),
                ..Default::default()
            }],
            "",
            false,
            4,
            4,
        );
        assert!(skipped.success);
        assert_eq!(skipped.depots_completed, 0);
        assert_eq!(skipped.depots_skipped, 1);
        let _ = fs::remove_dir_all(&dir);
    }

    fn temp_dir(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!(
            "blsteam_downloader_{name}_{}",
            SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        let _ = fs::remove_dir_all(&dir);
        dir
    }

    fn raw_layout_manifest(depot_id: u32, manifest_id: u64, filename: &str, size: u64) -> Vec<u8> {
        let mut file_body = Vec::new();
        {
            let mut writer = Writer::new(&mut file_body);
            writer.string_field(1, filename);
            writer.uint64_field(2, size);
        }

        let mut payload = Vec::new();
        Writer::new(&mut payload).submessage_field(1, &file_body);

        let mut metadata = Vec::new();
        {
            let mut writer = Writer::new(&mut metadata);
            writer.uint32_field(1, depot_id);
            writer.uint64_field(2, manifest_id);
            writer.bool_field_force(4, false);
        }

        let mut raw = Vec::new();
        push_section(&mut raw, PAYLOAD_MAGIC, &payload);
        push_section(&mut raw, METADATA_MAGIC, &metadata);
        raw.extend_from_slice(&END_OF_MANIFEST_MAGIC.to_le_bytes());
        raw
    }

    fn push_section(out: &mut Vec<u8>, magic: u32, body: &[u8]) {
        out.extend_from_slice(&magic.to_le_bytes());
        out.extend_from_slice(&(body.len() as u32).to_le_bytes());
        out.extend_from_slice(body);
    }
}
