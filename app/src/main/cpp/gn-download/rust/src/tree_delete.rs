//! Parallel directory-tree deletion for game uninstall / cancel cleanup.
//!
//! `File.deleteRecursively()` (Kotlin) walks single-threaded, allocates a `File` object and a
//! full path string per entry, and re-resolves the whole path through the VFS per syscall. On
//! external storage (`/storage/XXXX-XXXX`, sdcardfs/FUSE) every lookup and unlink round-trips
//! the FUSE daemon, so deleting a 50 GB game with tens of thousands of files takes many
//! minutes. This module deletes the same tree with:
//!
//!   * a shared LIFO work stack drained by `workers` threads (FUSE round-trip latency is
//!     overlapped instead of serialized), and
//!   * `DirEntry::file_type()` (d_type from getdents64) instead of a stat per entry.
//!
//! Correctness notes:
//!   * Symlinks are unlinked, never followed (`symlink_metadata` / `file_type`, not `metadata`).
//!   * A directory is removed only after ALL its children: `pending_children[dir]` counts
//!     outstanding children and `Rmdir(dir)` is pushed only when the count reaches 0 — pushing
//!     Rmdir next to Scan and relying on LIFO order is NOT sufficient with multiple workers
//!     (another worker can pop the Rmdir before the Scan's children are deleted → ENOTEMPTY).
//!   * Termination: `in_flight` counts work items popped but not yet finished (a finished item
//!     may push more work). A worker exits only when the stack is empty AND `in_flight == 0`;
//!     both are checked/updated under one lock so no push can be missed.
//!   * Failures are counted and logged (first 32), never silently ignored; a failed entry still
//!     completes its parent accounting so the run cannot deadlock. The caller verifies the root
//!     is gone afterwards and falls back to `File.deleteRecursively()` otherwise.

use std::collections::HashMap;
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::Mutex;

const MAX_ERROR_LOGS: usize = 32;

#[derive(Debug, Default, Clone, Copy, PartialEq, Eq)]
pub struct DeleteStats {
    pub files: u64,
    pub dirs: u64,
    pub failed: u64,
}

enum Work {
    /// read_dir + register children.
    Scan(PathBuf),
    Unlink(PathBuf),
    /// Only ever pushed once every child of this dir has completed.
    Rmdir(PathBuf),
}

#[derive(Default)]
struct State {
    stack: Vec<Work>,
    in_flight: usize,
    /// Outstanding children per directory; Rmdir is pushed at 0.
    pending_children: HashMap<PathBuf, usize>,
    /// Child dir/file → containing directory.
    parent: HashMap<PathBuf, PathBuf>,
}

/// Records one completed child of `child`'s parent; pushes Rmdir(parent) at zero.
fn child_done(st: &mut State, child: &Path) {
    let Some(parent) = st.parent.get(child).cloned() else {
        return;
    };
    if let Some(remaining) = st.pending_children.get_mut(&parent) {
        *remaining -= 1;
        if *remaining == 0 {
            st.stack.push(Work::Rmdir(parent));
        }
    }
}

/// Deletes `root` (file, symlink, or directory tree) with up to `workers` threads.
/// Returns an error string only when `root` itself cannot be inspected/removed; per-entry
/// failures are accumulated in `DeleteStats::failed`.
pub fn delete_tree(root: &Path, workers: usize, log: &(dyn Fn(String) + Sync)) -> Result<DeleteStats, String> {
    let md = fs::symlink_metadata(root).map_err(|e| format!("stat {}: {e}", root.display()))?;
    if !md.file_type().is_dir() {
        // File or symlink at the root: single unlink, no thread pool needed.
        fs::remove_file(root).map_err(|e| format!("unlink {}: {e}", root.display()))?;
        return Ok(DeleteStats { files: 1, dirs: 0, failed: 0 });
    }

    let n = workers.clamp(1, 16);
    let state = Mutex::new(State {
        stack: vec![Work::Scan(root.to_path_buf())],
        ..State::default()
    });
    let stats = Mutex::new(DeleteStats::default());
    let logged = Mutex::new(0usize);

    let fail = |stats: &Mutex<DeleteStats>, logged: &Mutex<usize>, msg: String| {
        stats.lock().unwrap().failed += 1;
        let mut n = logged.lock().unwrap();
        if *n < MAX_ERROR_LOGS {
            *n += 1;
            log(msg);
        }
    };

    std::thread::scope(|s| {
        for _ in 0..n {
            s.spawn(|| loop {
                let work = {
                    let mut st = state.lock().unwrap();
                    match st.stack.pop() {
                        Some(w) => {
                            st.in_flight += 1;
                            w
                        }
                        None => {
                            if st.in_flight == 0 {
                                break; // no work left and nothing in flight can push more
                            }
                            drop(st);
                            std::thread::yield_now();
                            continue;
                        }
                    }
                };
                match work {
                    Work::Scan(dir) => {
                        // Collect WITHOUT holding the lock — getdents round-trips must not
                        // serialize the other workers.
                        let collected: Result<Vec<(PathBuf, bool)>, std::io::Error> =
                            match fs::read_dir(&dir) {
                                Ok(rd) => Ok(rd
                                    .flatten()
                                    .map(|e| {
                                        let is_dir = matches!(e.file_type(), Ok(t) if t.is_dir() && !t.is_symlink());
                                        (e.path(), is_dir)
                                    })
                                    .collect()),
                                Err(e) => Err(e),
                            };
                        let mut st = state.lock().unwrap();
                        match collected {
                            Ok(entries) => {
                                if entries.is_empty() {
                                    st.stack.push(Work::Rmdir(dir));
                                } else {
                                    st.pending_children.insert(dir.clone(), entries.len());
                                    for (path, is_dir) in entries {
                                        st.parent.insert(path.clone(), dir.clone());
                                        if is_dir {
                                            st.stack.push(Work::Scan(path));
                                        } else {
                                            // Files AND symlinks (incl. symlink-to-dir): unlink only.
                                            st.stack.push(Work::Unlink(path));
                                        }
                                    }
                                }
                            }
                            Err(e) => {
                                drop(st);
                                fail(&stats, &logged, format!("readdir {}: {e}", dir.display()));
                                let mut st = state.lock().unwrap();
                                // Never scanned → never gets an Rmdir; settle parent accounting now.
                                child_done(&mut st, &dir);
                                st.in_flight -= 1;
                                continue;
                            }
                        }
                        st.in_flight -= 1;
                    }
                    Work::Unlink(path) => {
                        let result = fs::remove_file(&path);
                        let mut st = state.lock().unwrap();
                        match result {
                            Ok(()) => stats.lock().unwrap().files += 1,
                            Err(e) => {
                                drop(st);
                                fail(&stats, &logged, format!("unlink {}: {e}", path.display()));
                                st = state.lock().unwrap();
                            }
                        }
                        child_done(&mut st, &path);
                        st.in_flight -= 1;
                    }
                    Work::Rmdir(dir) => {
                        let result = fs::remove_dir(&dir);
                        let mut st = state.lock().unwrap();
                        match result {
                            Ok(()) => stats.lock().unwrap().dirs += 1,
                            Err(e) => {
                                drop(st);
                                fail(&stats, &logged, format!("rmdir {}: {e}", dir.display()));
                                st = state.lock().unwrap();
                            }
                        }
                        child_done(&mut st, &dir);
                        st.in_flight -= 1;
                    }
                }
            });
        }
    });

    let final_stats = *stats.lock().unwrap();
    Ok(final_stats)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs::{create_dir_all, File};
    use std::io::Write;

    fn build_tree(root: &Path, dirs: usize, files_per_dir: usize) -> u64 {
        let mut files = 0u64;
        for d in 0..dirs {
            let dir = root.join(format!("dir{d}/sub"));
            create_dir_all(&dir).unwrap();
            for f in 0..files_per_dir {
                let mut file = File::create(dir.join(format!("f{f}.bin"))).unwrap();
                file.write_all(&[0u8; 128]).unwrap();
                files += 1;
            }
        }
        files
    }

    #[test]
    fn deletes_nested_tree_and_reports_counts() {
        let tmp = std::env::temp_dir().join(format!("gn_td_{}", std::process::id()));
        let _ = fs::remove_dir_all(&tmp);
        let files = build_tree(&tmp, 6, 25);
        let stats = delete_tree(&tmp, 4, &|m| eprintln!("{m}")).unwrap();
        assert!(!tmp.exists());
        assert_eq!(stats.files, files);
        assert_eq!(stats.dirs, 6 * 2 + 1); // dirN + sub + root
        assert_eq!(stats.failed, 0);
    }

    #[test]
    fn deletes_deep_single_chain_tree() {
        // Worst case for parallelism: one long chain — must still terminate and delete all.
        let tmp = std::env::temp_dir().join(format!("gn_td_deep_{}", std::process::id()));
        let _ = fs::remove_dir_all(&tmp);
        let mut dir = tmp.clone();
        for d in 0..64 {
            dir = dir.join(format!("d{d}"));
        }
        create_dir_all(&dir).unwrap();
        File::create(dir.join("leaf.bin")).unwrap();
        let stats = delete_tree(&tmp, 4, &|m| eprintln!("{m}")).unwrap();
        assert!(!tmp.exists());
        assert_eq!(stats.files, 1);
        assert_eq!(stats.dirs, 65); // 64 levels + root
        assert_eq!(stats.failed, 0);
    }

    #[test]
    fn unlinks_symlink_to_dir_without_following_it() {
        let tmp = std::env::temp_dir().join(format!("gn_td_sym_{}", std::process::id()));
        let _ = fs::remove_dir_all(&tmp);
        let real = tmp.join("real");
        create_dir_all(&real).unwrap();
        File::create(real.join("keep.bin")).unwrap();
        let tree = tmp.join("tree");
        create_dir_all(&tree).unwrap();
        #[cfg(unix)]
        std::os::unix::fs::symlink(&real, tree.join("link")).unwrap();

        let stats = delete_tree(&tree, 2, &|m| eprintln!("{m}")).unwrap();
        assert!(!tree.exists());
        assert!(real.join("keep.bin").exists(), "symlink target must survive");
        assert_eq!(stats.failed, 0);
        let _ = fs::remove_dir_all(&tmp);
    }

    #[test]
    fn single_file_root_needs_no_pool() {
        let tmp = std::env::temp_dir().join(format!("gn_td_file_{}", std::process::id()));
        File::create(&tmp).unwrap();
        let stats = delete_tree(&tmp, 4, &|m| eprintln!("{m}")).unwrap();
        assert!(!tmp.exists());
        assert_eq!(stats.files, 1);
    }

    #[test]
    fn missing_root_is_an_error_not_a_silent_success() {
        let tmp = std::env::temp_dir().join("gn_td_missing_nope");
        assert!(delete_tree(&tmp, 4, &|m| eprintln!("{m}")).is_err());
    }
}
