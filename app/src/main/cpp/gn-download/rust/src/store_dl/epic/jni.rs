//! JNI exports for `com.winlator.star.store.blsteam.BlEpicDownload`.
//!
//! ```text
//! nativeStart(manifest: byte[], installDir: String, cdnPrefixes: String[], pendingFileIdx: int[],
//!             expectedChunks: int, expectedBytes: long, caBundlePath: String, maxWorkers: int,
//!             processWorkers: int, listener: BlEpicDownload.Listener) -> long handle (0 = not started)
//! nativeCancel(handle)      — flips the run's AtomicBool; the fetch core stops promptly
//! nativeRelease(handle)     — frees the handle (after onComplete)
//! ```
//! Listener (all methods run on native threads):
//! `onPlan(int chunksTotal, long bytesTotal, String chunkDir)` once before any fetch,
//! `onProgress(long bytesDone, long bytesTotal, int chunksDone, int chunksTotal)` per chunk,
//! `onAssemblyProgress(long bytesWritten)` per assembled file part (after a successful fetch),
//! `onLog(String line)`, `onComplete(boolean success, String error, long bytesCredited)`.
//!
//! Planning (manifest parse + cross-check) runs synchronously on the calling thread so a plan
//! failure returns 0 BEFORE any fetch and Java can fall back to its own pool; the fetch runs on
//! a new thread. JavaVM attach / GlobalRef handling mirrors `crate::jni`.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::thread;

use jni::objects::{GlobalRef, JByteArray, JClass, JIntArray, JObject, JObjectArray, JString, JValue};
use jni::sys::{jint, jlong, JNI_FALSE, JNI_TRUE};
use jni::{JNIEnv, JavaVM};

use super::driver::{build_plan, run_plan, EpicRequest};

#[cfg(target_os = "android")]
#[link(name = "log")]
unsafe extern "C" {
    fn __android_log_write(prio: i32, tag: *const i8, text: *const i8) -> i32;
}

fn android_log(message: &str) {
    #[cfg(target_os = "android")]
    {
        use std::ffi::CString;
        let Ok(tag) = CString::new(super::LOG_TAG) else {
            return;
        };
        let sanitized = message.replace('\0', " ");
        let Ok(text) = CString::new(sanitized) else {
            return;
        };
        unsafe {
            let _ = __android_log_write(4, tag.as_ptr().cast(), text.as_ptr().cast());
        }
    }
    #[cfg(not(target_os = "android"))]
    {
        let _ = message;
    }
}

/// Per-run native state behind the `jlong` handle.
pub struct EpicDownloadHandle {
    cancel: Arc<AtomicBool>,
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

fn string_array_to_vec(env: &mut JNIEnv, array: &JObjectArray) -> Vec<String> {
    let len = env.get_array_length(array).unwrap_or(0);
    let mut out = Vec::with_capacity(len.max(0) as usize);
    let mut i = 0;
    while i < len {
        match env.get_object_array_element(array, i) {
            Ok(obj) => {
                if obj.is_null() {
                    out.push(String::new());
                } else {
                    let js = JString::from(obj);
                    out.push(jstring_to_string(env, &js).unwrap_or_default());
                }
            }
            Err(_) => {
                clear_pending_exception(env);
                out.push(String::new());
            }
        }
        i += 1;
    }
    out
}

fn int_array_to_vec(env: &JNIEnv, array: &JIntArray) -> Vec<i32> {
    let len = env.get_array_length(array).unwrap_or(0);
    if len <= 0 {
        return Vec::new();
    }
    let mut values = vec![0i32; len as usize];
    if env.get_int_array_region(array, 0, &mut values).is_err() {
        return Vec::new();
    }
    values
}

fn call_on_log(env: &mut JNIEnv, listener: &JObject, line: &str) {
    if listener.is_null() {
        return;
    }
    let Ok(s) = env.new_string(line) else {
        clear_pending_exception(env);
        return;
    };
    let obj = JObject::from(s);
    let _ = env.call_method(
        listener,
        "onLog",
        "(Ljava/lang/String;)V",
        &[JValue::Object(&obj)],
    );
    clear_pending_exception(env);
}

fn call_on_plan(env: &mut JNIEnv, listener: &JObject, chunks_total: u64, bytes_total: u64, chunk_dir: &str) {
    if listener.is_null() {
        return;
    }
    let Ok(s) = env.new_string(chunk_dir) else {
        clear_pending_exception(env);
        return;
    };
    let obj = JObject::from(s);
    let _ = env.call_method(
        listener,
        "onPlan",
        "(IJLjava/lang/String;)V",
        &[
            JValue::Int(chunks_total.min(i32::MAX as u64) as jint),
            JValue::Long(bytes_total as jlong),
            JValue::Object(&obj),
        ],
    );
    clear_pending_exception(env);
}

fn call_on_progress(
    env: &mut JNIEnv,
    listener: &JObject,
    bytes_done: u64,
    bytes_total: u64,
    chunks_done: u64,
    chunks_total: u64,
) {
    if listener.is_null() {
        return;
    }
    let _ = env.call_method(
        listener,
        "onProgress",
        "(JJII)V",
        &[
            JValue::Long(bytes_done as jlong),
            JValue::Long(bytes_total as jlong),
            JValue::Int(chunks_done.min(i32::MAX as u64) as jint),
            JValue::Int(chunks_total.min(i32::MAX as u64) as jint),
        ],
    );
    clear_pending_exception(env);
}

fn call_on_assembly_progress(env: &mut JNIEnv, listener: &JObject, bytes_written: u64) {
    if listener.is_null() {
        return;
    }
    let _ = env.call_method(
        listener,
        "onAssemblyProgress",
        "(J)V",
        &[JValue::Long(bytes_written as jlong)],
    );
    clear_pending_exception(env);
}

fn call_on_complete(env: &mut JNIEnv, listener: &JObject, success: bool, error: &str, bytes: u64) {
    if listener.is_null() {
        return;
    }
    let Ok(s) = env.new_string(error) else {
        clear_pending_exception(env);
        return;
    };
    let obj = JObject::from(s);
    let _ = env.call_method(
        listener,
        "onComplete",
        "(ZLjava/lang/String;J)V",
        &[
            JValue::Bool(if success { JNI_TRUE } else { JNI_FALSE }),
            JValue::Object(&obj),
            JValue::Long(bytes as jlong),
        ],
    );
    clear_pending_exception(env);
}

/// Log to logcat AND to the listener (Java mirrors it into `bh_epic_debug.txt`).
fn log_both(env: &mut JNIEnv, listener: &JObject, line: &str) {
    android_log(line);
    call_on_log(env, listener, line);
}

#[no_mangle]
pub extern "system" fn Java_app_gamenative_service_download_NativeEpicDownload_nativeStart(
    mut env: JNIEnv,
    _class: JClass,
    manifest: JByteArray,
    install_dir: JString,
    cdn_prefixes: JObjectArray,
    pending_file_idx: JIntArray,
    expected_chunks: jint,
    expected_bytes: jlong,
    ca_bundle_path: JString,
    max_workers: jint,
    process_workers: jint,
    listener: JObject,
) -> jlong {
    if listener.is_null() {
        android_log("nativeStart: null listener");
        return 0;
    }
    let Ok(manifest_bytes) = env.convert_byte_array(manifest) else {
        clear_pending_exception(&mut env);
        call_on_complete(&mut env, &listener, false, "manifest bytes unreadable", 0);
        return 0;
    };
    let install_dir = jstring_to_string(&mut env, &install_dir).unwrap_or_default();
    let cdn_prefixes = string_array_to_vec(&mut env, &cdn_prefixes);
    let pending_file_indices: Vec<usize> = int_array_to_vec(&env, &pending_file_idx)
        .into_iter()
        .map(|i| i.max(0) as usize)
        .collect();
    let ca_bundle_path = jstring_to_string(&mut env, &ca_bundle_path).unwrap_or_default();
    let max_workers = max_workers.max(1) as usize;
    let process_workers = process_workers.max(1) as usize;
    let label = {
        let base = std::path::Path::new(&install_dir)
            .file_name()
            .map(|s| s.to_string_lossy().into_owned())
            .unwrap_or_default();
        format!("epic app={base}")
    };

    let host_count = super::plan::distinct_prefixes(&cdn_prefixes).len();
    let host_cap = super::plan::per_host_cap(max_workers, host_count);
    log_both(
        &mut env,
        &listener,
        &format!(
            "engine=rust label=\"{label}\" install_dir={install_dir} cdns={} hosts={host_count} pending_files={} workers={max_workers} per_host_cap={host_cap} process_workers={process_workers} manifest_bytes={}",
            cdn_prefixes.len(),
            pending_file_indices.len(),
            manifest_bytes.len()
        ),
    );

    let req = EpicRequest {
        manifest_bytes,
        install_dir,
        cdn_prefixes,
        pending_file_indices,
        expected_chunks: if expected_chunks >= 0 {
            Some(expected_chunks as u64)
        } else {
            None
        },
        expected_bytes: if expected_bytes >= 0 {
            Some(expected_bytes as u64)
        } else {
            None
        },
        ca_bundle_path,
        max_workers,
        process_workers,
        label,
    };

    let plan = match build_plan(&req) {
        Ok(plan) => plan,
        Err(err) => {
            log_both(&mut env, &listener, &format!("not started: {err}"));
            call_on_complete(&mut env, &listener, false, &err, 0);
            return 0;
        }
    };
    call_on_plan(
        &mut env,
        &listener,
        plan.needed.len() as u64,
        plan.total_bytes,
        &plan.manifest.chunk_dir,
    );

    let Ok(vm) = env.get_java_vm() else {
        clear_pending_exception(&mut env);
        call_on_complete(&mut env, &listener, false, "JavaVM unavailable", 0);
        return 0;
    };
    let Ok(listener) = env.new_global_ref(&listener) else {
        clear_pending_exception(&mut env);
        call_on_complete(&mut env, &listener, false, "listener ref failed", 0);
        return 0;
    };

    let cancel = Arc::new(AtomicBool::new(false));
    let thread_cancel = Arc::clone(&cancel);
    let handle = Box::new(EpicDownloadHandle { cancel });

    thread::spawn(move || {
        run_on_thread(vm, listener, plan, req, thread_cancel);
    });

    Box::into_raw(handle) as jlong
}

fn run_on_thread(
    vm: JavaVM,
    listener: GlobalRef,
    plan: super::driver::EpicPlan,
    req: EpicRequest,
    cancel: Arc<AtomicBool>,
) {
    let bytes_total = plan.total_bytes;
    let chunks_total = plan.needed.len() as u64;

    // Progress fires from the core's process-pool threads; attach each as a daemon like the
    // Steam engine's `dispatch_download_progress` does.
    let progress = |bytes_done: u64, chunks_done: u64| {
        let Ok(mut env) = vm.attach_current_thread_as_daemon() else {
            return;
        };
        call_on_progress(
            &mut env,
            listener.as_obj(),
            bytes_done,
            bytes_total,
            chunks_done,
            chunks_total,
        );
    };
    let log = |line: &str| {
        android_log(line);
        let Ok(mut env) = vm.attach_current_thread_as_daemon() else {
            return;
        };
        call_on_log(&mut env, listener.as_obj(), line);
    };
    // Assembly runs on the driver's worker threads after a successful fetch.
    let assembly_progress = |bytes_written: u64| {
        let Ok(mut env) = vm.attach_current_thread_as_daemon() else {
            return;
        };
        call_on_assembly_progress(&mut env, listener.as_obj(), bytes_written);
    };

    let outcome = run_plan(&plan, &req, cancel.as_ref(), &progress, &assembly_progress, &log);

    let Ok(mut env) = vm.attach_current_thread_as_daemon() else {
        return;
    };
    call_on_complete(
        &mut env,
        listener.as_obj(),
        outcome.success,
        &outcome.error,
        outcome.bytes_credited,
    );
}

#[no_mangle]
pub extern "system" fn Java_app_gamenative_service_download_NativeEpicDownload_nativeCancel(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    let handle = unsafe { &*(handle as *const EpicDownloadHandle) };
    handle.cancel.store(true, Ordering::Relaxed);
    android_log("cancel requested");
}

#[no_mangle]
pub extern "system" fn Java_app_gamenative_service_download_NativeEpicDownload_nativeRelease(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    // The worker thread owns its own Arc clone of the cancel flag and its own GlobalRef, so
    // freeing the handle is safe even if a (cancelled) run is still winding down.
    drop(unsafe { Box::from_raw(handle as *mut EpicDownloadHandle) });
}
