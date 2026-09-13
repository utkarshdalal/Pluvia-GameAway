//! JNI facade for the Amazon download adapter.
//!
//! Kotlin side: `com.winlator.star.store.blsteam.BlAmazonDownload` (`nativeStart` /
//! `nativeCancel` / `nativeRelease`) with a `BlAmazonDownloadListener`
//! (`onProgress(JJJJ)V`, `onLog(String)V`, `onComplete(ZString;J)V`). Mirrors the shape of
//! `BlSteamSession.nativeDownloadApp` in `crate::jni`: the run happens on a spawned native
//! thread, callbacks attach the JavaVM as daemon threads and hold a `GlobalRef` to the
//! listener; cancel flips an `AtomicBool`. This module keeps its own `JavaVM` handle (taken
//! from the calling `JNIEnv` on first use) so it does not depend on `crate::jni` internals.

use crate::store_dl::amazon::{run_download, MAX_PARALLEL};
use jni::objects::{GlobalRef, JClass, JObject, JString, JValue};
use jni::sys::{jint, jlong, JNI_FALSE, JNI_TRUE};
use jni::{JNIEnv, JavaVM};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, OnceLock};
use std::thread;

/// Logcat tag (`adb logcat -s GN_AMAZON_DL`). Every engine line reaches logcat exactly ONCE:
/// this module only forwards lines to the listener (`onLog`), and the Java manager is the one
/// place that writes them to `android.util.Log` + its debug file. (Round 1 fix: the native side
/// used to also call `__android_log_write`, so each line appeared twice.)
pub const LOG_TAG: &str = "GN_AMAZON_DL";

static JVM: OnceLock<JavaVM> = OnceLock::new();

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

/// One live run; boxed and handed to Kotlin as an opaque `jlong`.
struct AmazonRun {
    cancel: Arc<AtomicBool>,
}

fn to_handle(run: Box<AmazonRun>) -> jlong {
    Box::into_raw(run) as jlong
}

unsafe fn from_handle<'a>(handle: jlong) -> Option<&'a AmazonRun> {
    if handle == 0 {
        None
    } else {
        Some(&*(handle as *const AmazonRun))
    }
}

fn call_log(env: &mut JNIEnv, listener: &JObject, line: &str) {
    let Ok(text) = env.new_string(line) else {
        clear_pending_exception(env);
        return;
    };
    let text_obj = JObject::from(text);
    let _ = env.call_method(
        listener,
        "onLog",
        "(Ljava/lang/String;)V",
        &[JValue::Object(&text_obj)],
    );
    clear_pending_exception(env);
}

fn call_progress(
    env: &mut JNIEnv,
    listener: &JObject,
    bytes_done: u64,
    bytes_total: u64,
    files_done: u64,
    files_total: u64,
) {
    let _ = env.call_method(
        listener,
        "onProgress",
        "(JJJJ)V",
        &[
            JValue::Long(bytes_done as jlong),
            JValue::Long(bytes_total as jlong),
            JValue::Long(files_done as jlong),
            JValue::Long(files_total as jlong),
        ],
    );
    clear_pending_exception(env);
}

fn call_complete(
    env: &mut JNIEnv,
    listener: &JObject,
    success: bool,
    error: &str,
    bytes_written: u64,
) {
    let Ok(error) = env.new_string(error) else {
        clear_pending_exception(env);
        return;
    };
    let error_obj = JObject::from(error);
    let _ = env.call_method(
        listener,
        "onComplete",
        "(ZLjava/lang/String;J)V",
        &[
            JValue::Bool(if success { JNI_TRUE } else { JNI_FALSE }),
            JValue::Object(&error_obj),
            JValue::Long(bytes_written as jlong),
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

/// `BlAmazonDownload.nativeStart(planJson, installDir, caBundlePath, maxWorkers,
/// processWorkers, listener) -> handle`. Returns 0 (and fires `onComplete(false, …)`) when the
/// inputs are unusable. The run itself happens on a spawned thread.
#[no_mangle]
pub extern "system" fn Java_app_gamenative_service_download_NativeAmazonDownload_nativeStart(
    mut env: JNIEnv,
    _class: JClass,
    plan_json: JString,
    install_dir: JString,
    ca_bundle_path: JString,
    max_workers: jint,
    process_workers: jint,
    listener: JObject,
) -> jlong {
    if listener.is_null() {
        return 0;
    }
    if JVM.get().is_none() {
        if let Ok(vm) = env.get_java_vm() {
            let _ = JVM.set(vm);
        }
    }
    let plan_json = jstring_to_string(&mut env, &plan_json).unwrap_or_default();
    let install_dir = jstring_to_string(&mut env, &install_dir).unwrap_or_default();
    let ca_bundle_path = jstring_to_string(&mut env, &ca_bundle_path).unwrap_or_default();
    if plan_json.is_empty() || install_dir.is_empty() {
        call_complete(&mut env, &listener, false, "empty plan or install dir", 0);
        return 0;
    }
    let Ok(listener) = env.new_global_ref(&listener) else {
        clear_pending_exception(&mut env);
        return 0;
    };
    let max_workers = if max_workers <= 0 {
        MAX_PARALLEL
    } else {
        max_workers as usize
    };
    let process_workers = process_workers.max(1) as usize;
    let cancel = Arc::new(AtomicBool::new(false));
    let run = Box::new(AmazonRun {
        cancel: Arc::clone(&cancel),
    });

    thread::spawn(move || {
        let log_listener = listener.clone();
        let log = move |line: &str| {
            with_attached_env(&log_listener, |env, obj| call_log(env, obj, line));
        };
        let progress_listener = listener.clone();
        let progress = move |bytes_done: u64, bytes_total: u64, files_done: u64, files_total: u64| {
            with_attached_env(&progress_listener, |env, obj| {
                call_progress(env, obj, bytes_done, bytes_total, files_done, files_total)
            });
        };
        let result = run_download(
            &plan_json,
            &install_dir,
            &ca_bundle_path,
            max_workers,
            process_workers,
            cancel,
            &progress,
            &log,
        );
        with_attached_env(&listener, |env, obj| {
            call_complete(env, obj, result.success, &result.error, result.bytes_written)
        });
    });

    to_handle(run)
}

/// `BlAmazonDownload.nativeCancel(handle)` — flips the run's cancel flag; the core stops
/// issuing requests, in-flight bodies are dropped, `onComplete(false, "cancelled", …)` follows.
#[no_mangle]
pub extern "system" fn Java_app_gamenative_service_download_NativeAmazonDownload_nativeCancel(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if let Some(run) = unsafe { from_handle(handle) } {
        run.cancel.store(true, Ordering::Relaxed);
    }
}

/// `BlAmazonDownload.nativeRelease(handle)` — frees the handle. Safe at any time: the worker
/// thread owns its own `Arc` of the cancel flag and the listener `GlobalRef`.
#[no_mangle]
pub extern "system" fn Java_app_gamenative_service_download_NativeAmazonDownload_nativeRelease(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle != 0 {
        unsafe {
            drop(Box::from_raw(handle as *mut AmazonRun));
        }
    }
}
