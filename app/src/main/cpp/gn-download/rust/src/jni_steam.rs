//! JNI facade for the Steam depot download engine.
//!
//! Kotlin side: `app.gamenative.service.download.NativeSteamDownload`
//! (`nativeStart(planJson, listener) -> handle`, `nativeCancel(handle)`,
//! `nativeRelease(handle)`). Unlike Bannerlator's `BlSteamSession.nativeDownloadApp`, GameNative
//! keeps JavaSteam (javasteam) as the CM client: depot keys, manifest request codes and the CDN
//! server list are resolved on the Kotlin side through `SteamApps` / `SteamContent` and handed
//! over in `planJson`. Because Steam rotates manifest request codes (~5 min), the listener also
//! implements `refreshManifestRequestCode(IJ)J`, called from native worker threads whenever a
//! manifest fetch needs a fresh code.
//!
//! `planJson` shape:
//! ```json
//! {
//!   "install_dir": "/…/GameNative/SteamLibrary/steamapps/common/<App>",
//!   "ca_bundle_path": "",
//!   "fresh": false,
//!   "max_workers": 8,
//!   "process_workers": 4,
//!   "servers": [{"host": "cache1-xxx.steamcontent.com", "vhost": "", "https_support": "mandatory", "steam_china_only": false}],
//!   "depots": [{"depot_id": 123, "manifest_id": "456", "depot_key_hex": "ab…", "manifest_request_code": "789"}]
//! }
//! ```
//! `manifest_id` and `manifest_request_code` are uint64 values that Kotlin holds in signed
//! `Long`s; they are sent as unsigned decimal strings (`Long.toUnsignedString`) so high-bit
//! values survive the trip (a raw JSON number would also be accepted and bit-cast).

use crate::depot_downloader::{
    self, DepotDownloadResult, ResolvedDepotSpec,
};
use crate::pb::ccontentserverdirectory::CContentServerDirectoryServerInfo;
use jni::objects::{GlobalRef, JClass, JObject, JString, JValue};
use jni::sys::{jint, jlong, JNI_FALSE, JNI_TRUE, JNI_VERSION_1_6};
use jni::{JNIEnv, JavaVM};
use serde_json::Value;
use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::thread;

static JVM: OnceLock<JavaVM> = OnceLock::new();

#[no_mangle]
pub extern "system" fn JNI_OnLoad(vm: JavaVM, _reserved: *mut std::ffi::c_void) -> jint {
    let _ = JVM.set(vm);
    JNI_VERSION_1_6
}

fn clear_pending_exception(env: &mut JNIEnv) {
    if env.exception_check().unwrap_or(false) {
        let _ = env.exception_clear();
    }
}

fn jstring_to_string(env: &mut JNIEnv, value: &JString) -> Option<String> {
    env.get_string(value)
        .ok()
        .map(|s| s.to_string_lossy().into_owned())
}

/// Live-run registry: opaque `jlong` ids → cancel flags. A registry (instead of raw
/// boxed pointers) makes a LATE `nativeCancel(handle)` after `nativeRelease(handle)`
/// resolve to a harmless None instead of dereferencing freed memory.
static RUNS: OnceLock<Mutex<HashMap<jlong, Arc<AtomicBool>>>> = OnceLock::new();
static NEXT_HANDLE: AtomicI64 = AtomicI64::new(1);

fn runs() -> &'static Mutex<HashMap<jlong, Arc<AtomicBool>>> {
    RUNS.get_or_init(|| Mutex::new(HashMap::new()))
}

fn register_run(cancel: Arc<AtomicBool>) -> jlong {
    let handle = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
    if let Ok(mut map) = runs().lock() {
        map.insert(handle, cancel);
    }
    handle
}

fn cancel_run(handle: jlong) {
    let flag = runs().lock().ok().and_then(|map| map.get(&handle).cloned());
    if let Some(flag) = flag {
        flag.store(true, Ordering::Relaxed);
    }
}

fn unregister_run(handle: jlong) {
    if let Ok(mut map) = runs().lock() {
        map.remove(&handle);
    }
}

fn call_complete(
    env: &mut JNIEnv,
    listener: &JObject,
    success: bool,
    error: &str,
    bytes_written: u64,
    depots_completed: u32,
    depots_skipped: u32,
) {
    if listener.is_null() {
        return;
    }
    let Ok(error) = env.new_string(error) else {
        clear_pending_exception(env);
        return;
    };
    let error_obj = JObject::from(error);
    let _ = env.call_method(
        listener,
        "onComplete",
        "(ZLjava/lang/String;JII)V",
        &[
            JValue::Bool(if success { JNI_TRUE } else { JNI_FALSE }),
            JValue::Object(&error_obj),
            JValue::Long(bytes_written as jlong),
            JValue::Int(depots_completed as jint),
            JValue::Int(depots_skipped as jint),
        ],
    );
    clear_pending_exception(env);
}

fn call_progress(
    env: &mut JNIEnv,
    listener: &JObject,
    progress: &depot_downloader::DepotDownloadProgress,
) {
    let _ = env.call_method(
        listener,
        "onProgress",
        "(IJJIIZ)V",
        &[
            JValue::Int(progress.depot_id as jint),
            JValue::Long(progress.depot_done as jlong),
            JValue::Long(progress.depot_total as jlong),
            JValue::Int(progress.depots_done as jint),
            JValue::Int(progress.depots_total as jint),
            JValue::Bool(if progress.verifying { JNI_TRUE } else { JNI_FALSE }),
        ],
    );
    clear_pending_exception(env);
}

/// Attach the current (native) thread and run `f` with the listener object.
fn with_attached_env(listener: &GlobalRef, f: impl FnOnce(&mut JNIEnv, &JObject)) {
    let Some(vm) = JVM.get() else {
        return;
    };
    let Ok(mut env) = vm.attach_current_thread_as_daemon() else {
        return;
    };
    f(&mut env, listener.as_obj());
}

fn dispatch_complete(listener: GlobalRef, result: DepotDownloadResult) {
    with_attached_env(&listener, |env, obj| {
        call_complete(
            env,
            obj,
            result.success,
            &result.error,
            result.bytes_written,
            result.depots_completed,
            result.depots_skipped,
        );
    });
}

fn dispatch_progress(listener: &GlobalRef, progress: &depot_downloader::DepotDownloadProgress) {
    with_attached_env(listener, |env, obj| {
        call_progress(env, obj, progress);
    });
}

/// `listener.refreshManifestRequestCode(depotId, manifestId)` from a native thread.
/// Returns 0 when unavailable (engine then falls back to the pre-resolved code).
fn refresh_manifest_request_code(listener: &GlobalRef, depot_id: u32, manifest_id: u64) -> u64 {
    let mut code = 0u64;
    with_attached_env(listener, |env, obj| {
        match env.call_method(
            obj,
            "refreshManifestRequestCode",
            "(IJ)J",
            &[JValue::Int(depot_id as jint), JValue::Long(manifest_id as jlong)],
        ) {
            Ok(value) => {
                if let Ok(v) = value.j() {
                    // Bit-cast: Steam's code is uint64; Kotlin returns the same bits in a
                    // signed jlong, so ~50% of valid codes are negative as i64.
                    code = v as u64;
                }
            }
            Err(_) => clear_pending_exception(env),
        }
    });
    code
}

fn hex_decode(value: &str) -> Vec<u8> {
    let value = value.trim();
    if value.len() % 2 != 0 {
        return Vec::new();
    }
    (0..value.len() / 2)
        .map(|i| u8::from_str_radix(&value[i * 2..i * 2 + 2], 16))
        .collect::<Result<Vec<u8>, _>>()
        .unwrap_or_default()
}

fn parse_servers(value: Option<&Value>) -> Vec<CContentServerDirectoryServerInfo> {
    let mut servers = Vec::new();
    let Some(list) = value.and_then(|v| v.as_array()) else {
        return servers;
    };
    for entry in list {
        let get_str = |key: &str| {
            entry
                .get(key)
                .and_then(|v| v.as_str())
                .unwrap_or_default()
                .to_string()
        };
        let host = get_str("host");
        if host.is_empty() {
            continue;
        }
        servers.push(CContentServerDirectoryServerInfo {
            server_type: get_str("type"),
            cell_id: entry.get("cell_id").and_then(|v| v.as_i64()).unwrap_or(0) as i32,
            steam_china_only: entry
                .get("steam_china_only")
                .and_then(|v| v.as_bool())
                .unwrap_or(false),
            host,
            vhost: get_str("vhost"),
            https_support: get_str("https_support"),
            ..Default::default()
        });
    }
    servers
}

fn parse_depots(value: Option<&Value>) -> Vec<ResolvedDepotSpec> {
    let mut depots = Vec::new();
    let Some(list) = value.and_then(|v| v.as_array()) else {
        return depots;
    };
    for entry in list {
        let depot_id = entry.get("depot_id").and_then(|v| v.as_u64()).unwrap_or(0) as u32;
        let manifest_id = json_u64(entry.get("manifest_id"));
        if depot_id == 0 || manifest_id == 0 {
            continue;
        }
        depots.push(ResolvedDepotSpec {
            depot_id,
            manifest_id,
            depot_key: hex_decode(
                entry
                    .get("depot_key_hex")
                    .and_then(|v| v.as_str())
                    .unwrap_or_default(),
            ),
            manifest_request_code: json_u64(entry.get("manifest_request_code")),
        });
    }
    depots
}

/// Parses a uint64 that crossed JVM/Kotlin as a signed 64-bit long. Accepts a JSON number
/// (bit-casting negative i64 back to its uint64 bits — Steam gids/codes use the full range)
/// or a decimal string (`Long.toUnsignedString`, the form Kotlin now sends).
fn json_u64(value: Option<&Value>) -> u64 {
    match value {
        Some(Value::Number(n)) => n
            .as_u64()
            .or_else(|| n.as_i64().map(|i| i as u64))
            .unwrap_or(0),
        Some(Value::String(s)) => s
            .parse::<u64>()
            .or_else(|_| s.parse::<i64>().map(|i| i as u64))
            .unwrap_or(0),
        _ => 0,
    }
}

/// `NativeSteamDownload.nativeProbe() -> int` — symbol guard so a packaging regression degrades
/// to a clear error instead of an `UnsatisfiedLinkError` mid-download.
#[no_mangle]
pub extern "system" fn Java_app_gamenative_service_download_NativeSteamDownload_nativeProbe(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    1
}

/// `NativeSteamDownload.nativeStart(planJson, listener) -> handle`.
/// Returns 0 (and fires `onComplete(false, …)`) when the inputs are unusable.
#[no_mangle]
pub extern "system" fn Java_app_gamenative_service_download_NativeSteamDownload_nativeStart(
    mut env: JNIEnv,
    _class: JClass,
    plan_json: JString,
    listener: JObject,
) -> jlong {
    if JVM.get().is_none() {
        if let Ok(vm) = env.get_java_vm() {
            let _ = JVM.set(vm);
        }
    }
    if listener.is_null() {
        return 0;
    }
    let fail = |env: &mut JNIEnv, listener: &JObject, error: &str| -> jlong {
        call_complete(env, listener, false, error, 0, 0, 0);
        0
    };
    let Some(plan_json) = jstring_to_string(&mut env, &plan_json) else {
        return fail(&mut env, &listener, "download: empty plan");
    };
    let Ok(plan) = serde_json::from_str::<Value>(&plan_json) else {
        return fail(&mut env, &listener, "download: plan JSON parse failed");
    };
    let get_str = |key: &str| {
        plan.get(key)
            .and_then(|v| v.as_str())
            .unwrap_or_default()
            .to_string()
    };
    let install_dir = get_str("install_dir");
    let ca_bundle_path = get_str("ca_bundle_path");
    let fresh = plan
        .get("fresh")
        .and_then(|v| v.as_bool())
        .unwrap_or(false);
    let max_workers = plan
        .get("max_workers")
        .and_then(|v| v.as_u64())
        .unwrap_or(8)
        .max(1) as u32;
    let process_workers = plan
        .get("process_workers")
        .and_then(|v| v.as_u64())
        .unwrap_or(4)
        .max(1) as u32;
    let servers = parse_servers(plan.get("servers"));
    let depots = parse_depots(plan.get("depots"));
    if let Err(error) =
        depot_downloader::validate_resolved_download_inputs(&install_dir, &depots, &servers)
    {
        return fail(&mut env, &listener, &error.error);
    }
    let Ok(listener) = env.new_global_ref(&listener) else {
        return 0;
    };
    let cancel = Arc::new(AtomicBool::new(false));
    let handle = register_run(Arc::clone(&cancel));
    thread::spawn(move || {
        if cancel.load(Ordering::Relaxed) {
            dispatch_complete(listener, DepotDownloadResult::fail("cancelled"));
            return;
        }
        let progress_listener = listener.clone();
        let on_progress = move |progress: &depot_downloader::DepotDownloadProgress| {
            dispatch_progress(&progress_listener, progress);
        };
        let on_progress: depot_downloader::DepotProgressCallback = &on_progress;
        let code_listener = listener.clone();
        let code_refresher = move |depot_id: u32, manifest_id: u64| -> Option<u64> {
            let code = refresh_manifest_request_code(&code_listener, depot_id, manifest_id);
            (code != 0).then_some(code)
        };
        let code_refresher: depot_downloader::ManifestCodeRefresher = &code_refresher;
        let result = depot_downloader::download_resolved_depots_with_cancel_progress(
            &install_dir,
            &depots,
            &servers,
            &ca_bundle_path,
            fresh,
            max_workers,
            process_workers,
            Some(cancel.as_ref()),
            Some(on_progress),
            Some(code_refresher),
            None,
        );
        dispatch_complete(listener, result);
    });
    handle
}

/// `NativeSteamDownload.nativeCancel(handle)` — cooperative cancel; the run's `onComplete`
/// still fires exactly once with `success=false, error="cancelled"`.
#[no_mangle]
pub extern "system" fn Java_app_gamenative_service_download_NativeSteamDownload_nativeCancel(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    cancel_run(handle);
}

/// `NativeSteamDownload.nativeRelease(handle)` — frees the handle. Call only after
/// `onComplete` (or a `nativeStart` that returned 0 handles need no release).
#[no_mangle]
pub extern "system" fn Java_app_gamenative_service_download_NativeSteamDownload_nativeRelease(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle != 0 {
        unregister_run(handle);
    }
}


#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    /// Steam's gids and manifest request codes are uint64; Kotlin carries them in signed
    /// `Long`s, so high-bit values arrive negative (JSON number) or as unsigned decimal
    /// strings. Both forms must decode back to the original bits.
    #[test]
    fn json_u64_handles_signed_and_unsigned_forms() {
        // Unsigned string form (what Kotlin now sends).
        assert_eq!(json_u64(Some(&json!("15135711086488298552"))), 15135711086488298552u64);
        // Negative JSON number (legacy plan form) bit-casts back.
        assert_eq!(json_u64(Some(&json!(-3311032987221253064i64))), 15135711086488298552u64);
        // Negative string, for completeness.
        assert_eq!(json_u64(Some(&json!("-3311032987221253064"))), 15135711086488298552u64);
        // Ordinary positive values.
        assert_eq!(json_u64(Some(&json!(5671901464708035024u64))), 5671901464708035024u64);
        assert_eq!(json_u64(Some(&json!("5671901464708035024"))), 5671901464708035024u64);
        // Missing / malformed -> 0 (unavailable).
        assert_eq!(json_u64(None), 0);
        assert_eq!(json_u64(Some(&json!("not-a-number"))), 0);
    }

    #[test]
    fn parse_depots_keeps_high_bit_manifest_request_code() {
        let depots = parse_depots(Some(&json!([{
            "depot_id": 219741,
            "manifest_id": "5671901464708035024",
            "depot_key_hex": "00".repeat(32),
            "manifest_request_code": "15135711086488298552",
        }])));
        assert_eq!(depots.len(), 1);
        assert_eq!(depots[0].depot_id, 219741);
        assert_eq!(depots[0].manifest_id, 5671901464708035024u64);
        assert_eq!(depots[0].manifest_request_code, 15135711086488298552u64);

        // Same plan with the legacy negative-number form decodes identically.
        let legacy = parse_depots(Some(&json!([{
            "depot_id": 219741,
            "manifest_id": 5671901464708035024u64,
            "depot_key_hex": "00".repeat(32),
            "manifest_request_code": -3311032987221253064i64,
        }])));
        assert_eq!(legacy[0].manifest_request_code, 15135711086488298552u64);
    }
}
