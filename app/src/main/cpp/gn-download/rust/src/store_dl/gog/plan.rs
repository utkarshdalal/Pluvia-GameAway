//! Depot-manifest JSON → download plan. Mirrors `GogDownloadManager.parseDepotManifest` rule for
//! rule (see `docs/RUST_GOG_PARITY.md` §3):
//! - root `depot.items[]`; `path` with `\` → `/` and one leading `/` stripped;
//! - a file is kept only when `path` is non-empty AND `chunks[]` is non-empty;
//! - per chunk: CDN hash = `compressedMd5` when non-empty, else `md5`; a chunk with neither is
//!   dropped; `compressedSize` / `size` default 0;
//! - file `md5` kept when non-empty; `total_size` = Σ chunk `size` (decompressed);
//! - a file whose chunks all dropped is not added.
//! `org.json` coercion (`optString` turns numbers/bools into their text, `optLong` parses numeric
//! strings) is reproduced by [`opt_string`] / [`opt_u64`].
//!
//! Additionally each chunk carries `offset` = prefix-sum of the preceding chunks' decompressed
//! sizes — Java appends sequentially so it never needs one; the Rust sink writes chunks at that
//! offset as they arrive so the final bytes are identical.

use serde_json::Value;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ChunkRef {
    /// CDN-path hash (`compressedMd5` preferred, else `md5`).
    pub hash: String,
    /// Verify compressed bytes before inflate (may be empty).
    pub compressed_md5: String,
    /// Verify decompressed bytes after inflate (may be empty).
    pub md5: String,
    /// Expected compressed size, 0 if absent.
    pub compressed_size: u64,
    /// Expected decompressed size, 0 if absent.
    pub size: u64,
    /// Decompressed offset inside the file (prefix-sum of preceding `size`s).
    pub offset: u64,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PlannedFile {
    /// Install-dir-relative path, forward slashes, no leading slash.
    pub relative_path: String,
    /// Whole-file MD5 from the manifest ("" when absent).
    pub md5: String,
    /// Σ chunk decompressed sizes (0 if unknown).
    pub total_size: u64,
    pub chunks: Vec<ChunkRef>,
}

/// `JSONObject.optString(key, "")` semantics: strings as-is, numbers/bools rendered, null/absent
/// → "". (`org.json` renders `JSONObject.NULL` as "null"; GOG manifests never carry JSON nulls in
/// these fields, and treating one as absent is the only safe reading.)
pub fn opt_string(obj: &Value, key: &str) -> String {
    match obj.get(key) {
        Some(Value::String(s)) => s.clone(),
        Some(Value::Number(n)) => n.to_string(),
        Some(Value::Bool(b)) => b.to_string(),
        _ => String::new(),
    }
}

/// `JSONObject.optLong(key, 0)` semantics for a size field (numbers, or numeric strings; a
/// negative/float value that Java would truncate is clamped to 0 — sizes are never negative).
pub fn opt_u64(obj: &Value, key: &str) -> u64 {
    match obj.get(key) {
        Some(Value::Number(n)) => n
            .as_u64()
            .or_else(|| n.as_i64().map(|v| v.max(0) as u64))
            .or_else(|| n.as_f64().map(|v| if v > 0.0 { v as u64 } else { 0 }))
            .unwrap_or(0),
        Some(Value::String(s)) => s.trim().parse::<i64>().map(|v| v.max(0) as u64).unwrap_or(0),
        _ => 0,
    }
}

/// Parses one inflated gen2 depot manifest and appends its files to `out`
/// (`GogDownloadManager.parseDepotManifest`). Malformed JSON adds nothing, like Java's catch-all.
pub fn parse_depot_manifest(json: &str, out: &mut Vec<PlannedFile>) {
    let Ok(root) = serde_json::from_str::<Value>(json) else {
        return;
    };
    // Java: `val depotObj = json.optJSONObject("depot") ?: json` — the container is
    // selected ONCE; when a `depot` object exists but has no `items`, Java reads
    // nothing (it does NOT fall back to root-level items).
    let container = root.get("depot").unwrap_or(&root);
    let Some(items) = container.get("items").and_then(Value::as_array) else {
        return;
    };
    for entry in items {
        if !entry.is_object() {
            continue;
        }
        let mut path = opt_string(entry, "path").replace('\\', "/");
        if let Some(stripped) = path.strip_prefix('/') {
            path = stripped.to_string();
        }
        let chunks = entry.get("chunks").and_then(Value::as_array);
        let Some(chunks) = chunks else {
            continue;
        };
        if path.is_empty() || chunks.is_empty() {
            continue;
        }
        let file_md5 = opt_string(entry, "md5");
        let mut planned = PlannedFile {
            relative_path: path,
            md5: file_md5,
            total_size: 0,
            chunks: Vec::with_capacity(chunks.len()),
        };
        let mut offset = 0u64;
        for chunk in chunks {
            if !chunk.is_object() {
                continue;
            }
            let compressed_md5 = opt_string(chunk, "compressedMd5");
            let dec_md5 = opt_string(chunk, "md5");
            let compressed_size = opt_u64(chunk, "compressedSize");
            let dec_size = opt_u64(chunk, "size");
            let hash = if !compressed_md5.is_empty() {
                compressed_md5.clone()
            } else {
                dec_md5.clone()
            };
            if hash.is_empty() {
                continue;
            }
            planned.chunks.push(ChunkRef {
                hash,
                compressed_md5,
                md5: dec_md5,
                compressed_size,
                size: dec_size,
                offset,
            });
            offset = offset.saturating_add(dec_size);
        }
        planned.total_size = offset;
        if !planned.chunks.is_empty() {
            out.push(planned);
        }
    }
}

/// One gen1 file: a byte range of a (pre-signed) depot blob (`GogDownloadManager.Gen1File`).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Gen1File {
    /// Install-dir-relative path, used verbatim (Java's gen1 path does not normalise slashes).
    pub path: String,
    pub url: String,
    pub offset: u64,
    pub size: u64,
}

/// Parses a gen1 build manifest (`runGen1`): Java does `obj.optJSONObject("depot") ?: obj`
/// then reads `files[]` from it — so `depot` is an OBJECT (or the files live at the root),
/// NOT a `depot[]` array. Every file entry that is not `support: true` → (`path`, `url`,
/// `offset` default 0, `size` default 0); an entry with `size == 0` is dropped (Java's
/// `path == null || url == null` checks never fire — `optString` returns "" — so
/// `size == 0` is the only effective filter, mirrored here).
pub fn parse_gen1_manifest(json: &str, out: &mut Vec<Gen1File>) {
    let Ok(root) = serde_json::from_str::<Value>(json) else {
        return;
    };
    let depot = root.get("depot").filter(|d| d.is_object()).unwrap_or(&root);
    // `support: true` on the depot object excludes the whole payload.
    if depot.get("support").and_then(Value::as_bool).unwrap_or(false) {
        return;
    }
    let Some(files) = depot.get("files").and_then(Value::as_array) else {
        return;
    };
    for f in files {
        if !f.is_object() {
            continue;
        }
        if f.get("support").and_then(Value::as_bool).unwrap_or(false) {
            continue;
        }
        let path = opt_string(f, "path");
        let offset = opt_u64(f, "offset");
        let size = opt_u64(f, "size");
        let url = opt_string(f, "url");
        if size == 0 {
            continue;
        }
        out.push(Gen1File { path, url, offset, size });
    }
}

/// Parses every manifest in order (the order Java fetched the depots) and optionally applies the
/// base-install largest-first (LPT) ordering — a STABLE sort by descending `total_size`, exactly
/// `Collections.sort(files, (a, b) -> Long.compare(b.totalSize, a.totalSize))`. The DLC and
/// dependency paths in Java do not sort, so they pass `sort_largest_first = false`.
pub fn build_plan(manifests: &[String], sort_largest_first: bool) -> Vec<PlannedFile> {
    let mut files = Vec::new();
    for manifest in manifests {
        parse_depot_manifest(manifest, &mut files);
    }
    if sort_largest_first {
        files.sort_by(|a, b| b.total_size.cmp(&a.total_size));
    }
    files
}

/// `GogDownloadManager.buildCdnPath`: `"ab/cd/abcdef..."`. A hash shorter than 4 chars would
/// throw in Java (and fail that file); here it is returned unchanged and fails at fetch time.
pub fn build_cdn_path(hash: &str) -> String {
    if !hash.is_char_boundary(2) || !hash.is_char_boundary(4) || hash.len() < 4 {
        return hash.to_string();
    }
    format!("{}/{}/{}", &hash[0..2], &hash[2..4], hash)
}

/// `GogDownloadManager.buildChunkUrl`: appends the chunk path BEFORE any query string so the
/// secure-link token survives.
pub fn build_chunk_url(base: &str, chunk_path: &str) -> String {
    match base.find('?') {
        Some(q) => format!("{}/{}{}", &base[..q], chunk_path, &base[q..]),
        None => format!("{base}/{chunk_path}"),
    }
}

/// Per-host key for the fetch core's host semaphores: `scheme://host[:port]` of `url` (lowercased
/// host). Java has one resolved CDN base per run, so the host list has exactly one entry.
pub fn host_key(url: &str) -> String {
    let (scheme, rest) = match url.find("://") {
        Some(i) => (&url[..i], &url[i + 3..]),
        None => ("https", url),
    };
    let end = rest.find(['/', '?', '#']).unwrap_or(rest.len());
    format!("{}://{}", scheme.to_ascii_lowercase(), rest[..end].to_ascii_lowercase())
}

#[cfg(test)]
mod tests {
    use super::*;

    const MANIFEST: &str = r#"{
      "depot": { "items": [
        { "path": "\\bin\\game.exe", "md5": "AABBCCDDEEFF00112233445566778899",
          "chunks": [
            { "compressedMd5": "c1c1c1c1", "md5": "d1d1d1d1", "compressedSize": 100, "size": 250 },
            { "compressedMd5": "c2c2c2c2", "md5": "d2d2d2d2", "compressedSize": "50", "size": 75 }
          ] },
        { "path": "/data/pak0.pak",
          "chunks": [
            { "md5": "onlydec1", "size": 1000 },
            { "compressedSize": 5, "size": 5 }
          ] },
        { "path": "empty.txt", "chunks": [] },
        { "path": "", "chunks": [ { "compressedMd5": "zz", "size": 1 } ] },
        { "path": "nohash.bin", "chunks": [ { "size": 9 } ] },
        { "path": "dir", "type": "DepotDirectory" },
        "not-an-object"
      ] } }"#;

    #[test]
    fn parse_mirrors_java_rules() {
        let mut files = Vec::new();
        parse_depot_manifest(MANIFEST, &mut files);
        assert_eq!(files.len(), 2, "empty-chunk, empty-path, no-hash and directory entries drop");

        let exe = &files[0];
        assert_eq!(exe.relative_path, "bin/game.exe");
        assert_eq!(exe.md5, "AABBCCDDEEFF00112233445566778899");
        assert_eq!(exe.total_size, 325);
        assert_eq!(exe.chunks.len(), 2);
        assert_eq!(exe.chunks[0].hash, "c1c1c1c1");
        assert_eq!(exe.chunks[0].offset, 0);
        assert_eq!(exe.chunks[1].hash, "c2c2c2c2");
        assert_eq!(exe.chunks[1].compressed_size, 50, "numeric string coerces like optLong");
        assert_eq!(exe.chunks[1].offset, 250);

        let pak = &files[1];
        assert_eq!(pak.relative_path, "data/pak0.pak");
        assert_eq!(pak.md5, "", "absent file md5 → empty (no whole-file verify)");
        assert_eq!(pak.chunks.len(), 1, "chunk without any hash is dropped");
        assert_eq!(pak.chunks[0].hash, "onlydec1", "hash falls back to md5");
        assert_eq!(pak.chunks[0].compressed_md5, "");
        assert_eq!(pak.total_size, 1000);
    }

    #[test]
    fn malformed_manifest_adds_nothing() {
        let mut files = Vec::new();
        parse_depot_manifest("{not json", &mut files);
        parse_depot_manifest(r#"{"depot": {}}"#, &mut files);
        parse_depot_manifest(r#"{"items": []}"#, &mut files);
        assert!(files.is_empty());
    }

    #[test]
    fn plan_orders_largest_first_stably_only_when_asked() {
        let m1 = r#"{"depot":{"items":[
            {"path":"a","chunks":[{"md5":"a1","size":10}]},
            {"path":"b","chunks":[{"md5":"b1","size":30}]}]}}"#
            .to_string();
        let m2 = r#"{"depot":{"items":[
            {"path":"c","chunks":[{"md5":"c1","size":30}]},
            {"path":"d","chunks":[{"md5":"d1","size":20}]}]}}"#
            .to_string();
        let unsorted = build_plan(&[m1.clone(), m2.clone()], false);
        let order: Vec<&str> = unsorted.iter().map(|f| f.relative_path.as_str()).collect();
        assert_eq!(order, ["a", "b", "c", "d"], "depot order, file order within depot");

        let sorted = build_plan(&[m1, m2], true);
        let order: Vec<&str> = sorted.iter().map(|f| f.relative_path.as_str()).collect();
        assert_eq!(order, ["b", "c", "d", "a"], "stable: b (30) before c (30) keeps source order");
    }

    #[test]
    fn gen1_parse_mirrors_run_gen1() {
        // Real gen1 shape: depot is an OBJECT with files[] (Java: optJSONObject("depot") ?: obj).
        let json = r#"{"installDirectory":"Game","depot":{"files":[
                {"path":"a.bin","url":"https://cdn.gog.com/blob?t=1","offset":10,"size":20},
                {"path":"zero.bin","url":"u","offset":0,"size":0},
                {"support":true,"path":"support.bin","url":"u","offset":0,"size":5},
                {"path":"b.bin","url":"https://cdn.gog.com/blob?t=1","size":"7"},
                "junk"
            ]}}"#;
        let mut files = Vec::new();
        parse_gen1_manifest(json, &mut files);
        assert_eq!(
            files,
            vec![
                Gen1File { path: "a.bin".into(), url: "https://cdn.gog.com/blob?t=1".into(), offset: 10, size: 20 },
                Gen1File { path: "b.bin".into(), url: "https://cdn.gog.com/blob?t=1".into(), offset: 0, size: 7 },
            ]
        );
        // Root-level files[] fallback (no depot object).
        let mut rooted = Vec::new();
        parse_gen1_manifest(r#"{"files":[{"path":"r.bin","url":"u","size":3}]}"#, &mut rooted);
        assert_eq!(rooted.len(), 1);
        // support:true depot object yields nothing.
        let mut support = Vec::new();
        parse_gen1_manifest(r#"{"depot":{"support":true,"files":[{"path":"s.bin","url":"u","size":3}]}}"#, &mut support);
        assert!(support.is_empty());
        let mut none = Vec::new();
        parse_gen1_manifest(r#"{"depots":[]}"#, &mut none);
        assert!(none.is_empty());
    }

    #[test]
    fn cdn_path_and_chunk_url() {
        assert_eq!(build_cdn_path("abcdef0123"), "ab/cd/abcdef0123");
        assert_eq!(build_cdn_path("abc"), "abc");
        assert_eq!(
            build_chunk_url("https://cdn.gog.com/content-system/v2/store/1?token=x&y=1", "ab/cd/abcdef"),
            "https://cdn.gog.com/content-system/v2/store/1/ab/cd/abcdef?token=x&y=1"
        );
        assert_eq!(
            build_chunk_url("https://gog-cdn-fastly.gog.com/content-system/v2/dependencies/store", "ab/cd/abcdef"),
            "https://gog-cdn-fastly.gog.com/content-system/v2/dependencies/store/ab/cd/abcdef"
        );
    }

    #[test]
    fn host_key_strips_path_and_query() {
        assert_eq!(host_key("https://CDN.gog.com/a/b?c=1"), "https://cdn.gog.com");
        assert_eq!(host_key("http://host:8080/x"), "http://host:8080");
        assert_eq!(host_key("nohost"), "https://nohost");
    }

    #[test]
    fn opt_helpers_coerce_like_org_json() {
        let v: Value = serde_json::from_str(r#"{"n": 12, "s": "34", "b": true, "z": null, "f": 2.5, "neg": -4}"#).unwrap();
        assert_eq!(opt_string(&v, "n"), "12");
        assert_eq!(opt_string(&v, "s"), "34");
        assert_eq!(opt_string(&v, "b"), "true");
        assert_eq!(opt_string(&v, "z"), "");
        assert_eq!(opt_string(&v, "missing"), "");
        assert_eq!(opt_u64(&v, "n"), 12);
        assert_eq!(opt_u64(&v, "s"), 34);
        assert_eq!(opt_u64(&v, "f"), 2);
        assert_eq!(opt_u64(&v, "neg"), 0);
        assert_eq!(opt_u64(&v, "b"), 0);
        assert_eq!(opt_u64(&v, "missing"), 0);
    }
}
