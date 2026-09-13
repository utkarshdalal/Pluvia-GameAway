//! JNI export for `app.gamenative.service.download.NativeTreeDelete`.
//!
//! ```text
//! nativeDeleteTree(path: String, workers: int) -> long
//! ```
//!
//! Returns the number of deleted entries (files + dirs), or -1 when the root could not be
//! inspected/removed at all (Kotlin then falls back to `File.deleteRecursively()`). Per-entry
//! failures are logged, not fatal — Kotlin verifies the root is gone afterwards either way.
//! Runs synchronously on the calling thread (Kotlin calls from Dispatchers.IO).

use jni::objects::{JClass, JString};
use jni::sys::{jint, jlong};
use jni::JNIEnv;

use crate::tree_delete::delete_tree;

#[cfg(target_os = "android")]
#[link(name = "log")]
unsafe extern "C" {
    fn __android_log_write(prio: i32, tag: *const i8, text: *const i8) -> i32;
}

fn android_log(message: &str) {
    #[cfg(target_os = "android")]
    {
        use std::ffi::CString;
        let Ok(tag) = CString::new("TreeDelete") else {
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

#[no_mangle]
pub extern "system" fn Java_app_gamenative_service_download_NativeTreeDelete_nativeDeleteTree(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
    workers: jint,
) -> jlong {
    let path: String = match env.get_string(&path) {
        Ok(s) => s.into(),
        Err(e) => {
            android_log(&format!("nativeDeleteTree: bad path arg: {e}"));
            return -1;
        }
    };
    let root = std::path::PathBuf::from(&path);
    let start = std::time::Instant::now();
    match delete_tree(&root, workers.max(1) as usize, &|m| android_log(&m)) {
        Ok(stats) => {
            android_log(&format!(
                "deleteTreeOK path={} files={} dirs={} failed={} ms={}",
                path,
                stats.files,
                stats.dirs,
                stats.failed,
                start.elapsed().as_millis()
            ));
            (stats.files + stats.dirs) as jlong
        }
        Err(e) => {
            android_log(&format!("deleteTreeFAIL path={path}: {e}"));
            -1
        }
    }
}
