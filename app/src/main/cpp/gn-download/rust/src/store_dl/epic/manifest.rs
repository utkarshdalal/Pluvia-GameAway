//! Epic build manifest parsers — a 1:1 port of `EpicDownloadManager.parseManifest` (binary,
//! magic `0x44BEC00C`) and `parseJsonManifest` (legacy JSON manifests).
//!
//! Every quirk of the Java reader is kept on purpose (the Java manager stays the reference and
//! the two engines must derive the same plan from the same bytes): section skips by declared
//! size, `readFString` UTF-16LE/ASCII decoding, duplicate-GUID handling (last value, first
//! position), the JSON `Long.parseLong(.., 16)` / `parseInt` fallbacks, and "any exception →
//! parse failed".

use std::collections::HashMap;
use std::io::Read;

/// Binary manifest magic (`EpicDownloadManager.parseManifest`).
pub const MANIFEST_MAGIC: u32 = 0x44BE_C00C;

/// One entry of the ChunkDataList section (`EpicDownloadManager.ChunkInfo`).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ChunkInfo {
    /// Four uint32 in binary read order.
    pub guid: [u32; 4],
    /// uint64 "hash" that forms the first half of the chunk file name.
    pub hash: u64,
    /// SHA-1 of the DECOMPRESSED chunk; `None` for JSON manifests (Java: `sha1 == null`).
    pub sha1: Option<[u8; 20]>,
    /// Subfolder number (DECIMAL `%02d`). `i32` so the JSON parser's `Integer.parseInt` range is
    /// representable exactly (binary manifests always yield 0..=255).
    pub group_num: i32,
    /// Uncompressed size (0 for JSON manifests).
    pub window_size: i32,
    /// Compressed (wire) size. Signed like the Java `long`; JSON manifests may yield anything.
    pub file_size: i64,
}

impl ChunkInfo {
    /// `String.format("%08X%08X%08X%08X", guid[0..4])` — used in CDN URLs and dedup keys.
    pub fn guid_str(&self) -> String {
        guid_to_string(&self.guid)
    }

    /// Java/Kotlin `guidStr`: `guid.joinToString("-") { "%08x".format(it) }` — DASHED and
    /// LOWERCASE. This is the on-disk cache filename the Kotlin assembly stage looks for
    /// (`File(chunkCacheDir, chunkPart.guidStr)`); using the URL form (uppercase, no dashes)
    /// makes every chunk "missing" at assembly time.
    pub fn cache_file_name(&self) -> String {
        format!(
            "{:08x}-{:08x}-{:08x}-{:08x}",
            self.guid[0], self.guid[1], self.guid[2], self.guid[3]
        )
    }

    /// `ChunkInfo.getPath`: `"<chunkDir>/<%02d groupNum>/<%016X hash>_<GUID>.chunk"`.
    pub fn path(&self, chunk_dir: &str) -> String {
        format!(
            "{}/{:02}/{:016X}_{}.chunk",
            chunk_dir,
            self.group_num,
            self.hash,
            self.guid_str()
        )
    }

    /// Java `chunk.sha1 != null && length == 20 && !allZero` — the condition under which the
    /// streaming downloader verifies the decompressed chunk.
    pub fn verifiable_sha1(&self) -> Option<&[u8; 20]> {
        match &self.sha1 {
            Some(h) if h.iter().any(|b| *b != 0) => Some(h),
            _ => None,
        }
    }

    /// `Math.max(chunk.fileSize, 1)` — the byte credit the Java pool uses for totals/progress.
    pub fn credit_bytes(&self) -> u64 {
        if self.file_size < 1 {
            1
        } else {
            self.file_size as u64
        }
    }
}

/// One file part referencing a chunk (`EpicDownloadManager.ChunkPart`).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ChunkPart {
    pub guid: [u32; 4],
    /// Offset into the DECOMPRESSED chunk (Java `int`).
    pub offset: i32,
    /// Part length (Java `int`; summed as `size & 0xFFFFFFFFL`).
    pub size: i32,
}

impl ChunkPart {
    pub fn guid_str(&self) -> String {
        guid_to_string(&self.guid)
    }
}

/// One entry of the FileManifestList section (`EpicDownloadManager.FileInfo`).
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct FileInfo {
    pub filename: String,
    pub parts: Vec<ChunkPart>,
    /// Install-tag names; empty = required/base file.
    pub install_tags: Vec<String>,
    /// Full-file SHA-1; `None` for JSON manifests.
    pub sha1: Option<[u8; 20]>,
}

impl FileInfo {
    /// `FileInfo.fileSize()` = Σ `(part.size & 0xFFFFFFFFL)`.
    pub fn file_size(&self) -> u64 {
        self.parts.iter().map(|p| p.size as u32 as u64).sum()
    }
}

/// Parsed manifest (`EpicDownloadManager.EpicManifest.ParsedManifest`, minus the CDN list which
/// the Java side still owns).
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Manifest {
    /// Binary header version / JSON blobToNum(ManifestFileVersion); logged for diagnostics.
    pub version: i32,
    pub chunk_dir: String,
    pub unique_chunks: Vec<ChunkInfo>,
    pub files: Vec<FileInfo>,
}

impl Manifest {
    /// `byGuid` map as `uniqueChunksForFiles` builds it: `LinkedHashMap.put` per chunk, so a
    /// duplicate GUID resolves to the LAST `ChunkInfo`.
    pub fn chunk_index_by_guid(&self) -> HashMap<String, usize> {
        let mut map = HashMap::with_capacity(self.unique_chunks.len());
        for (i, c) in self.unique_chunks.iter().enumerate() {
            map.insert(c.guid_str(), i);
        }
        map
    }
}

pub fn guid_to_string(guid: &[u32; 4]) -> String {
    format!("{:08X}{:08X}{:08X}{:08X}", guid[0], guid[1], guid[2], guid[3])
}

/// `chunkDir` from the binary header version (`parseManifest`).
pub fn chunk_dir_for_binary_version(version: i32) -> &'static str {
    if version >= 15 {
        "ChunksV4"
    } else if version >= 6 {
        "ChunksV3"
    } else if version >= 3 {
        "ChunksV2"
    } else {
        "Chunks"
    }
}

/// `chunkDir` from `ManifestFileVersion` (`parseJsonManifest`; note the different `< 3` default).
pub fn chunk_dir_for_json_version(version: i32) -> &'static str {
    if version >= 15 {
        "ChunksV4"
    } else if version >= 6 {
        "ChunksV3"
    } else if version >= 3 {
        "ChunksV2"
    } else {
        "ChunksV4"
    }
}

// ── little-endian cursor with ByteBuffer-like failure semantics ─────────────────────────────

/// Mirrors the `ByteBuffer` calls the Java parser makes: every under-run or out-of-range
/// `position()` is an exception there, which the Java code turns into "parse failed" (null).
struct Cursor<'a> {
    buf: &'a [u8],
    pos: usize,
}

impl<'a> Cursor<'a> {
    fn new(buf: &'a [u8]) -> Self {
        Self { buf, pos: 0 }
    }

    fn position(&self) -> usize {
        self.pos
    }

    /// `ByteBuffer.position(p)` — `p` must be within `0..=limit`.
    fn set_position(&mut self, p: i64) -> Result<(), String> {
        if p < 0 || p as u64 > self.buf.len() as u64 {
            return Err(format!("position {p} out of range (limit {})", self.buf.len()));
        }
        self.pos = p as usize;
        Ok(())
    }

    fn take(&mut self, n: usize) -> Result<&'a [u8], String> {
        let end = self
            .pos
            .checked_add(n)
            .ok_or_else(|| "buffer overflow".to_string())?;
        if end > self.buf.len() {
            return Err(format!(
                "buffer underflow: need {n} at {} (limit {})",
                self.pos,
                self.buf.len()
            ));
        }
        let out = &self.buf[self.pos..end];
        self.pos = end;
        Ok(out)
    }

    fn u8(&mut self) -> Result<u8, String> {
        Ok(self.take(1)?[0])
    }

    fn i32(&mut self) -> Result<i32, String> {
        let b = self.take(4)?;
        Ok(i32::from_le_bytes([b[0], b[1], b[2], b[3]]))
    }

    fn u32(&mut self) -> Result<u32, String> {
        Ok(self.i32()? as u32)
    }

    fn u64(&mut self) -> Result<u64, String> {
        let b = self.take(8)?;
        Ok(u64::from_le_bytes([
            b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7],
        ]))
    }

    fn sha1(&mut self) -> Result<[u8; 20], String> {
        let b = self.take(20)?;
        let mut out = [0u8; 20];
        out.copy_from_slice(b);
        Ok(out)
    }

    /// `ByteBuffer.position(position + n)` with the same range check.
    fn skip(&mut self, n: i64) -> Result<(), String> {
        let target = self.pos as i64 + n;
        self.set_position(target)
    }

    fn remaining(&self) -> &'a [u8] {
        &self.buf[self.pos..]
    }

    /// `EpicDownloadManager.readFString`: int32 length; `0` → ""; negative → `(-len)-1` UTF-16LE
    /// code units + 2-byte terminator; positive → `len-1` US-ASCII bytes + 1-byte terminator.
    fn fstring(&mut self) -> Result<String, String> {
        let length = self.i32()?;
        if length == 0 {
            return Ok(String::new());
        }
        if length < 0 {
            // Java: `int chars = (-length) - 1;` — `-Integer.MIN_VALUE` wraps to itself, then -1
            // gives Integer.MAX_VALUE; `new byte[chars * 2]` overflows negative → exception.
            let chars = length.wrapping_neg().wrapping_sub(1);
            let byte_len = chars.checked_mul(2).ok_or("fstring length overflow")?;
            if byte_len < 0 {
                return Err("negative fstring length".to_string());
            }
            let bytes = self.take(byte_len as usize)?;
            self.take(2)?; // null terminator
            let units: Vec<u16> = bytes
                .chunks_exact(2)
                .map(|p| u16::from_le_bytes([p[0], p[1]]))
                .collect();
            Ok(String::from_utf16_lossy(&units))
        } else {
            let bytes = self.take((length - 1) as usize)?;
            self.take(1)?; // null terminator
            // `new String(bytes, US_ASCII)`: every byte > 0x7F decodes to U+FFFD.
            Ok(bytes
                .iter()
                .map(|&b| if b < 0x80 { b as char } else { '\u{FFFD}' })
                .collect())
        }
    }
}

// ── binary manifest ──────────────────────────────────────────────────────────────────────

/// `EpicDownloadManager.parseManifest(bytes)`: binary when the magic matches, otherwise the JSON
/// parser. `Err` wherever Java returns `null`.
pub fn parse_manifest(bytes: &[u8]) -> Result<Manifest, String> {
    let mut cur = Cursor::new(bytes);
    let magic = cur.u32()?;
    if magic != MANIFEST_MAGIC {
        return parse_json_manifest(bytes);
    }
    parse_binary_after_magic(&mut cur)
}

fn parse_binary_after_magic(cur: &mut Cursor<'_>) -> Result<Manifest, String> {
    let header_size = cur.i32()?;
    let size_uncompressed = cur.i32()?;
    let _size_compressed = cur.i32()?;
    cur.skip(20)?; // SHA-1 of the body, not checked by the Java parser
    let stored_as = cur.u8()?;
    let version = cur.i32()?;
    let chunk_dir = chunk_dir_for_binary_version(version);

    cur.set_position(header_size as i64)?;
    let body_slice = cur.remaining();

    let inflated;
    let body: &[u8] = if (stored_as & 1) != 0 {
        if size_uncompressed < 0 {
            return Err("negative sizeUncompressed".to_string());
        }
        // Bound the manifest-declared allocation: the field is attacker/CDN-controlled and
        // `with_capacity` trusts it fully. Real Epic manifests are tens of MiB at most.
        const MAX_MANIFEST_INFLATE: i32 = 256 * 1024 * 1024;
        if size_uncompressed > MAX_MANIFEST_INFLATE {
            return Err(format!(
                "sizeUncompressed {size_uncompressed} exceeds sane bound"
            ));
        }
        // Java: `inflater.inflate(new byte[sizeUncompressed])` — a single call that fills at most
        // `sizeUncompressed` bytes; anything else (short, corrupt) is a size mismatch → null.
        let mut out = Vec::with_capacity(size_uncompressed as usize);
        let mut dec = flate2::read::ZlibDecoder::new(body_slice).take(size_uncompressed as u64);
        dec.read_to_end(&mut out)
            .map_err(|e| format!("manifest inflate failed: {e}"))?;
        if out.len() != size_uncompressed as usize {
            return Err(format!(
                "decomp size mismatch: expected {size_uncompressed} got {}",
                out.len()
            ));
        }
        inflated = out;
        &inflated
    } else {
        body_slice
    };

    let mut b = Cursor::new(body);

    // ManifestMeta: skipped by its declared size (the size field counts itself).
    let meta_size = b.i32()?;
    let meta_end = b.position() as i64 - 4 + meta_size as i64;
    b.set_position(meta_end)?;

    // ChunkDataList
    let cdl_start = b.position();
    let cdl_size = b.i32()?;
    let _cdl_version = b.u8()?;
    let chunk_count = b.i32()?;
    if chunk_count < 0 {
        return Err("negative chunkCount".to_string()); // `new ArrayList<>(negative)` throws
    }
    let chunk_count = chunk_count as usize;
    let mut chunks: Vec<ChunkInfo> = (0..chunk_count)
        .map(|_| ChunkInfo {
            guid: [0; 4],
            hash: 0,
            sha1: None,
            group_num: 0,
            window_size: 0,
            file_size: 0,
        })
        .collect();
    for c in chunks.iter_mut() {
        c.guid = [b.u32()?, b.u32()?, b.u32()?, b.u32()?];
    }
    for c in chunks.iter_mut() {
        c.hash = b.u64()?;
    }
    for c in chunks.iter_mut() {
        c.sha1 = Some(b.sha1()?);
    }
    for c in chunks.iter_mut() {
        c.group_num = b.u8()? as i32;
    }
    for c in chunks.iter_mut() {
        c.window_size = b.i32()?;
    }
    for c in chunks.iter_mut() {
        c.file_size = b.u64()? as i64;
    }
    b.set_position(cdl_start as i64 + cdl_size as i64)?;

    // FileManifestList
    let fml_start = b.position();
    let fml_size = b.i32()?;
    let _fml_version = b.u8()?;
    let file_count = b.i32()?;
    if file_count < 0 {
        return Err("negative fileCount".to_string());
    }
    let file_count = file_count as usize;
    let mut files: Vec<FileInfo> = (0..file_count).map(|_| FileInfo::default()).collect();
    for f in files.iter_mut() {
        f.filename = b.fstring()?;
    }
    for _ in 0..file_count {
        b.fstring()?; // symlink targets
    }
    for f in files.iter_mut() {
        f.sha1 = Some(b.sha1()?);
    }
    b.skip(file_count as i64)?; // per-file flags byte
    for f in files.iter_mut() {
        let tag_count = b.i32()?;
        let mut j = 0;
        while j < tag_count {
            let tag = b.fstring()?;
            if !tag.is_empty() {
                f.install_tags.push(tag);
            }
            j += 1;
        }
    }
    for f in files.iter_mut() {
        let part_count = b.i32()?;
        let mut j = 0;
        while j < part_count {
            let part_start = b.position();
            let part_struct_size = b.i32()?;
            let guid = [b.u32()?, b.u32()?, b.u32()?, b.u32()?];
            let offset = b.i32()?;
            let size = b.i32()?;
            f.parts.push(ChunkPart { guid, offset, size });
            b.set_position(part_start as i64 + part_struct_size as i64)?;
            j += 1;
        }
    }
    b.set_position(fml_start as i64 + fml_size as i64)?;

    Ok(Manifest {
        version,
        chunk_dir: chunk_dir.to_string(),
        unique_chunks: dedup_last_wins_first_position(chunks),
        files,
    })
}

/// `LinkedHashMap<String, ChunkInfo>` keyed by GUID string: a repeated key keeps its FIRST
/// position but takes the LAST value.
fn dedup_last_wins_first_position(chunks: Vec<ChunkInfo>) -> Vec<ChunkInfo> {
    let mut order: Vec<ChunkInfo> = Vec::with_capacity(chunks.len());
    let mut index: HashMap<String, usize> = HashMap::with_capacity(chunks.len());
    for c in chunks {
        let key = c.guid_str();
        match index.get(&key).copied() {
            Some(i) => order[i] = c,
            None => {
                index.insert(key, order.len());
                order.push(c);
            }
        }
    }
    order
}

// ── JSON manifest (older games) ──────────────────────────────────────────────────────────

/// `org.json` value → string coercion as `optString` / `getString` do on Android (numbers and
/// booleans print as-is, JSON null prints "null", containers print their JSON).
fn json_to_string(v: &serde_json::Value) -> String {
    match v {
        serde_json::Value::String(s) => s.clone(),
        serde_json::Value::Null => "null".to_string(),
        other => other.to_string(),
    }
}

/// `obj.optString(key, fallback)`.
fn opt_string(obj: &serde_json::Map<String, serde_json::Value>, key: &str, fallback: &str) -> String {
    match obj.get(key) {
        Some(v) => json_to_string(v),
        None => fallback.to_string(),
    }
}

/// Java `blobToNum`: Epic's BLOB number strings encode each byte as a 3-char decimal group,
/// little-endian ("013000000000" = 13 | 0<<8 | ... = 13).
fn blob_to_num(s: &str) -> i32 {
    blob_to_u64(s) as i32
}

/// Java `blobToULong` / `blobToLong` — same 3-char-group little-endian encoding, 64-bit.
/// Groups parse as SIGNED (Java toLongOrNull accepts "-5"), then wrap into the accumulator.
fn blob_to_u64(s: &str) -> u64 {
    let bytes = s.as_bytes();
    let mut num: u64 = 0;
    let mut shift = 0u32;
    let mut i = 0;
    while i < bytes.len() {
        let end = (i + 3).min(bytes.len());
        let group = std::str::from_utf8(&bytes[i..end]).unwrap_or("");
        let val = group.parse::<i64>().unwrap_or(0) as u64;
        num |= val.wrapping_shl(shift);
        shift += 8;
        i += 3;
    }
    num
}

/// Java `hexStringToByteArray` for a 20-byte SHA-1: exact 40 hex chars, else None (the Java
/// version throws on bad hex, which kills the parse — a missing/short SHA simply yields an
/// empty array, i.e. no verification).
fn parse_hex_20(s: &str) -> Option<[u8; 20]> {
    let clean: String = s.chars().filter(|c| !c.is_whitespace() && *c != '-').collect();
    if clean.len() != 40 {
        return None;
    }
    let mut out = [0u8; 20];
    for i in 0..20 {
        out[i] = u8::from_str_radix(&clean[i * 2..i * 2 + 2], 16).ok()?;
    }
    Some(out)
}

/// `(int) Long.parseLong(hex8, 16)` — the Java GUID quarter parse; invalid hex throws out of the
/// whole parse.
fn parse_guid_quarter(s: &str) -> Result<u32, String> {
    let v = i64::from_str_radix(s, 16).map_err(|e| format!("bad guid hex '{s}': {e}"))?;
    Ok(v as i32 as u32)
}

fn parse_guid_from_hex(s: &str) -> Result<[u32; 4], String> {
    // Java substrings by UTF-16 unit and `parseLong` throws on anything non-hex; a non-ASCII
    // prefix can never parse, so refuse it before slicing (keeps the slice on char boundaries).
    if s.len() < 32 || !s.as_bytes()[..32].is_ascii() {
        return Err(format!("bad guid hex '{s}'"));
    }
    Ok([
        parse_guid_quarter(&s[0..8])?,
        parse_guid_quarter(&s[8..16])?,
        parse_guid_quarter(&s[16..24])?,
        parse_guid_quarter(&s[24..32])?,
    ])
}

/// `EpicDownloadManager.parseJsonManifest`.
pub fn parse_json_manifest(bytes: &[u8]) -> Result<Manifest, String> {
    let text = String::from_utf8_lossy(bytes);
    let root: serde_json::Value =
        serde_json::from_str(&text).map_err(|e| format!("JSON manifest: {e}"))?;
    let root = root
        .as_object()
        .ok_or_else(|| "JSON manifest: root is not an object".to_string())?;

    // ManifestFileVersion is a BLOB string ("013000000000" = 13), NOT a decimal integer —
    // parse it with the same 3-char-group little-endian scheme as Java's blobToNum. A plain
    // integer parse overflows i32 (13_000_000_000) and silently yields 0, which then picks
    // the wrong chunk dir (ChunksV4) and 404s every chunk.
    // The fallback must match Java's optString default "013000000000" (= 13 → ChunksV3):
    // manifests that lack the field entirely would otherwise parse as 0 → ChunksV4.
    let manifest_version: i32 =
        blob_to_num(&opt_string(root, "ManifestFileVersion", "013000000000"));
    let chunk_dir = chunk_dir_for_json_version(manifest_version);

    let chunk_hash_list = root
        .get("ChunkHashList")
        .and_then(|v| v.as_object())
        .ok_or_else(|| "JSON manifest: no ChunkHashList".to_string())?;
    let data_group_list = root.get("DataGroupList").and_then(|v| v.as_object());
    let chunk_filesize_list = root.get("ChunkFilesizeList").and_then(|v| v.as_object());
    let chunk_sha_list = root.get("ChunkShaList").and_then(|v| v.as_object());

    // `LinkedHashMap<String, ChunkInfo>` keyed by the RAW key string (not the normalized GUID).
    let mut chunk_order: Vec<ChunkInfo> = Vec::new();
    let mut chunk_index: HashMap<String, usize> = HashMap::new();
    for (guid_hex, hash_value) in chunk_hash_list.iter() {
        if guid_hex.len() < 32 || !guid_hex.is_char_boundary(32) {
            continue;
        }
        let guid = parse_guid_from_hex(guid_hex)?;
        // Like ManifestFileVersion, ALL of these are BLOB number strings in the Java parser
        // (blobToULong / blobToNum / blobToLong), not hex and not plain decimals. Reading
        // the hash as hex produces a garbage filename and every chunk URL 404s.
        let hash = blob_to_u64(&json_to_string(hash_value));
        let group_num = match data_group_list {
            Some(dgl) => blob_to_num(&opt_string(dgl, guid_hex, "0")),
            None => 0,
        };
        let file_size = match chunk_filesize_list {
            Some(cfl) => blob_to_u64(&opt_string(cfl, guid_hex, "0")) as i64,
            None => 0,
        };
        // ChunkShaList is plain hex (Java hexStringToByteArray), 20 bytes when present.
        let sha1 = chunk_sha_list
            .and_then(|csl| csl.get(guid_hex))
            .map(json_to_string)
            .and_then(|s| parse_hex_20(&s));
        let c = ChunkInfo {
            guid,
            hash,
            sha1,
            group_num,
            window_size: 0,
            file_size,
        };
        match chunk_index.get(guid_hex).copied() {
            Some(i) => chunk_order[i] = c,
            None => {
                chunk_index.insert(guid_hex.clone(), chunk_order.len());
                chunk_order.push(c);
            }
        }
    }

    let file_list = root
        .get("FileManifestList")
        .and_then(|v| v.as_array())
        .ok_or_else(|| "JSON manifest: no FileManifestList".to_string())?;

    let mut files = Vec::with_capacity(file_list.len());
    for entry in file_list {
        let file_obj = entry
            .as_object()
            .ok_or_else(|| "JSON manifest: FileManifestList entry is not an object".to_string())?;
        let mut fi = FileInfo {
            filename: opt_string(file_obj, "Filename", ""),
            ..FileInfo::default()
        };
        if let Some(tags) = file_obj.get("InstallTags").and_then(|v| v.as_array()) {
            for t in tags {
                let tag = json_to_string(t);
                if !tag.is_empty() {
                    fi.install_tags.push(tag);
                }
            }
        }
        if let Some(parts) = file_obj.get("FileChunkParts").and_then(|v| v.as_array()) {
            for p in parts {
                let part_obj = p.as_object().ok_or_else(|| {
                    "JSON manifest: FileChunkParts entry is not an object".to_string()
                })?;
                let mut part = ChunkPart {
                    guid: [0; 4],
                    offset: 0,
                    size: 0,
                };
                let part_guid = opt_string(part_obj, "Guid", "");
                if part_guid.len() >= 32 && part_guid.is_char_boundary(32) {
                    part.guid = parse_guid_from_hex(&part_guid)?;
                }
                // BLOB number strings, like the chunk lists (Java blobToNum).
                part.offset = blob_to_num(&opt_string(part_obj, "Offset", "0"));
                part.size = blob_to_num(&opt_string(part_obj, "Size", "0"));
                fi.parts.push(part);
            }
        }
        files.push(fi);
    }

    Ok(Manifest {
        version: manifest_version,
        chunk_dir: chunk_dir.to_string(),
        unique_chunks: chunk_order,
        files,
    })
}

// ── tests ────────────────────────────────────────────────────────────────────────────────

#[cfg(test)]
pub(crate) mod test_support {
    //! Synthetic binary-manifest builder used by this module's tests and by `plan`/`driver`
    //! tests. Emits exactly the layout `parseManifest` walks (meta / CDL / FML with sizes).

    use super::*;
    use std::io::Write;

    pub struct TestChunk {
        pub guid: [u32; 4],
        pub hash: u64,
        pub sha1: [u8; 20],
        pub group: u8,
        pub window: i32,
        pub file_size: u64,
    }

    pub struct TestFile {
        pub name: String,
        pub sha1: [u8; 20],
        pub tags: Vec<String>,
        /// (guid, offset, size)
        pub parts: Vec<([u32; 4], i32, i32)>,
    }

    pub fn fstring_ascii(s: &str) -> Vec<u8> {
        let mut v = Vec::new();
        if s.is_empty() {
            v.extend_from_slice(&0i32.to_le_bytes());
            return v;
        }
        v.extend_from_slice(&((s.len() as i32) + 1).to_le_bytes());
        v.extend_from_slice(s.as_bytes());
        v.push(0);
        v
    }

    pub fn fstring_utf16(s: &str) -> Vec<u8> {
        let units: Vec<u16> = s.encode_utf16().collect();
        let mut v = Vec::new();
        v.extend_from_slice(&(-((units.len() as i32) + 1)).to_le_bytes());
        for u in units {
            v.extend_from_slice(&u.to_le_bytes());
        }
        v.extend_from_slice(&[0, 0]);
        v
    }

    /// Body (meta + CDL + FML) without the outer header.
    pub fn build_body(chunks: &[TestChunk], files: &[TestFile], meta_padding: usize) -> Vec<u8> {
        let mut body = Vec::new();
        // ManifestMeta: size field counts itself.
        let meta_size = 4 + meta_padding;
        body.extend_from_slice(&(meta_size as i32).to_le_bytes());
        body.extend(std::iter::repeat(0xEEu8).take(meta_padding));

        // ChunkDataList
        let mut cdl = Vec::new();
        cdl.push(0u8); // version
        cdl.extend_from_slice(&(chunks.len() as i32).to_le_bytes());
        for c in chunks {
            for g in c.guid {
                cdl.extend_from_slice(&g.to_le_bytes());
            }
        }
        for c in chunks {
            cdl.extend_from_slice(&c.hash.to_le_bytes());
        }
        for c in chunks {
            cdl.extend_from_slice(&c.sha1);
        }
        for c in chunks {
            cdl.push(c.group);
        }
        for c in chunks {
            cdl.extend_from_slice(&c.window.to_le_bytes());
        }
        for c in chunks {
            cdl.extend_from_slice(&c.file_size.to_le_bytes());
        }
        // Trailing padding the parser must skip via cdlSize.
        cdl.extend_from_slice(&[0xAA, 0xBB, 0xCC]);
        body.extend_from_slice(&((cdl.len() + 4) as i32).to_le_bytes());
        body.extend_from_slice(&cdl);

        // FileManifestList
        let mut fml = Vec::new();
        fml.push(0u8); // version
        fml.extend_from_slice(&(files.len() as i32).to_le_bytes());
        for f in files {
            if f.name.is_ascii() {
                fml.extend_from_slice(&fstring_ascii(&f.name));
            } else {
                fml.extend_from_slice(&fstring_utf16(&f.name));
            }
        }
        for _ in files {
            fml.extend_from_slice(&fstring_ascii("")); // symlink target
        }
        for f in files {
            fml.extend_from_slice(&f.sha1);
        }
        for _ in files {
            fml.push(0); // flags
        }
        for f in files {
            fml.extend_from_slice(&(f.tags.len() as i32).to_le_bytes());
            for t in &f.tags {
                fml.extend_from_slice(&fstring_ascii(t));
            }
        }
        for f in files {
            fml.extend_from_slice(&(f.parts.len() as i32).to_le_bytes());
            for (guid, offset, size) in &f.parts {
                // struct size 4 + 16 + 4 + 4 = 28, plus 2 bytes of padding the parser skips.
                fml.extend_from_slice(&30i32.to_le_bytes());
                for g in guid {
                    fml.extend_from_slice(&g.to_le_bytes());
                }
                fml.extend_from_slice(&offset.to_le_bytes());
                fml.extend_from_slice(&size.to_le_bytes());
                fml.extend_from_slice(&[0x11, 0x22]);
            }
        }
        body.extend_from_slice(&((fml.len() + 4) as i32).to_le_bytes());
        body.extend_from_slice(&fml);
        body
    }

    /// Full manifest bytes: header + (optionally zlib-compressed) body.
    pub fn build_manifest(
        chunks: &[TestChunk],
        files: &[TestFile],
        version: i32,
        compress: bool,
    ) -> Vec<u8> {
        let body = build_body(chunks, files, 7);
        let payload = if compress {
            let mut enc =
                flate2::write::ZlibEncoder::new(Vec::new(), flate2::Compression::default());
            enc.write_all(&body).unwrap();
            enc.finish().unwrap()
        } else {
            body.clone()
        };
        let header_size: i32 = 41 + 3; // 3 bytes of extra header the parser must jump over
        let mut out = Vec::new();
        out.extend_from_slice(&MANIFEST_MAGIC.to_le_bytes());
        out.extend_from_slice(&header_size.to_le_bytes());
        out.extend_from_slice(&(body.len() as i32).to_le_bytes());
        out.extend_from_slice(&(payload.len() as i32).to_le_bytes());
        out.extend_from_slice(&[0u8; 20]);
        out.push(if compress { 1 } else { 0 });
        out.extend_from_slice(&version.to_le_bytes());
        out.extend_from_slice(&[0xF0, 0xF1, 0xF2]);
        assert_eq!(out.len(), header_size as usize);
        out.extend_from_slice(&payload);
        out
    }

    pub fn sample_chunks() -> Vec<TestChunk> {
        vec![
            TestChunk {
                guid: [0x0000_0001, 0x0000_0002, 0x0000_0003, 0x0000_0004],
                hash: 0x0123_4567_89AB_CDEF,
                sha1: [0x11; 20],
                group: 5,
                window: 1_048_576,
                file_size: 700_000,
            },
            TestChunk {
                guid: [0xDEAD_BEEF, 0xFFFF_FFFF, 0x0000_0000, 0x8000_0000],
                hash: 0xFFFF_FFFF_FFFF_FFFE,
                sha1: [0x22; 20],
                group: 123,
                window: 4096,
                file_size: 0,
            },
            TestChunk {
                guid: [0x0000_00AA, 0x0000_00BB, 0x0000_00CC, 0x0000_00DD],
                hash: 0x10,
                sha1: [0u8; 20],
                group: 0,
                window: 10,
                file_size: 9,
            },
        ]
    }

    pub fn sample_files() -> Vec<TestFile> {
        let c = sample_chunks();
        vec![
            TestFile {
                name: "Game/Binaries/Win64/Game.exe".to_string(),
                sha1: [0x51; 20],
                tags: vec![],
                parts: vec![(c[0].guid, 0, 1_048_576), (c[1].guid, 100, 4000)],
            },
            TestFile {
                name: "Game\\Content\\Localization\\de.pak".to_string(),
                sha1: [0x52; 20],
                tags: vec!["German".to_string(), "de-DE".to_string()],
                parts: vec![(c[1].guid, 0, 4096)],
            },
            TestFile {
                name: "Game/Content/Ünïcode.pak".to_string(),
                sha1: [0x53; 20],
                tags: vec!["French".to_string()],
                parts: vec![(c[2].guid, 0, 10), (c[0].guid, 5, 7)],
            },
        ]
    }
}

#[cfg(test)]
mod tests {
    use super::test_support::*;
    use super::*;

    #[test]
    fn binary_manifest_round_trips_uncompressed() {
        let bytes = build_manifest(&sample_chunks(), &sample_files(), 21, false);
        let m = parse_manifest(&bytes).expect("parse");
        assert_eq!(m.chunk_dir, "ChunksV4");
        assert_eq!(m.unique_chunks.len(), 3);
        assert_eq!(m.files.len(), 3);

        let c0 = &m.unique_chunks[0];
        assert_eq!(c0.guid_str(), "00000001000000020000000300000004");
        assert_eq!(c0.hash, 0x0123_4567_89AB_CDEF);
        assert_eq!(c0.sha1, Some([0x11; 20]));
        assert_eq!(c0.group_num, 5);
        assert_eq!(c0.window_size, 1_048_576);
        assert_eq!(c0.file_size, 700_000);
        assert_eq!(
            c0.path("ChunksV4"),
            "ChunksV4/05/0123456789ABCDEF_00000001000000020000000300000004.chunk"
        );

        let c1 = &m.unique_chunks[1];
        assert_eq!(c1.guid_str(), "DEADBEEFFFFFFFFF0000000080000000");
        assert_eq!(
            c1.path("ChunksV4"),
            "ChunksV4/123/FFFFFFFFFFFFFFFE_DEADBEEFFFFFFFFF0000000080000000.chunk"
        );
        assert_eq!(c1.credit_bytes(), 1, "Math.max(fileSize, 1)");
        assert!(c1.verifiable_sha1().is_some());
        assert!(m.unique_chunks[2].verifiable_sha1().is_none(), "all-zero SHA-1 → unverifiable");

        let f0 = &m.files[0];
        assert_eq!(f0.filename, "Game/Binaries/Win64/Game.exe");
        assert!(f0.install_tags.is_empty());
        assert_eq!(f0.sha1, Some([0x51; 20]));
        assert_eq!(f0.parts.len(), 2);
        assert_eq!(f0.parts[1].offset, 100);
        assert_eq!(f0.parts[1].size, 4000);
        assert_eq!(f0.file_size(), 1_048_576 + 4000);

        let f1 = &m.files[1];
        assert_eq!(f1.filename, "Game\\Content\\Localization\\de.pak");
        assert_eq!(f1.install_tags, vec!["German".to_string(), "de-DE".to_string()]);

        let f2 = &m.files[2];
        assert_eq!(f2.filename, "Game/Content/Ünïcode.pak", "UTF-16LE fstring");
        assert_eq!(f2.parts[1].guid_str(), c0.guid_str());
    }

    #[test]
    fn binary_manifest_round_trips_compressed() {
        let bytes = build_manifest(&sample_chunks(), &sample_files(), 17, true);
        let m = parse_manifest(&bytes).expect("parse");
        assert_eq!(m.unique_chunks.len(), 3);
        assert_eq!(m.files.len(), 3);
        assert_eq!(m.files[2].filename, "Game/Content/Ünïcode.pak");
    }

    #[test]
    fn json_manifest_missing_version_defaults_to_java_fallback() {
        // Java's optString default is "013000000000" (= 13 → ChunksV3): real manifests that
        // omit ManifestFileVersion must land on ChunksV3, not 0 → ChunksV4.
        let json = r#"{
          "ChunkHashList": { "0000000100000002000000030000000ff": "0123456789ABCDEF00" },
          "FileManifestList": [ { "Filename": "a.bin" } ]
        }"#;
        let m = parse_manifest(json.as_bytes()).expect("json parse");
        assert_eq!(m.version, 13);
        assert_eq!(m.chunk_dir, "ChunksV3");
    }

    #[test]
    fn blob_to_num_matches_java() {
        assert_eq!(blob_to_num(""), 0);
        assert_eq!(blob_to_num("013"), 13);
        // Real-world 12-char form: a plain integer parse overflows i32 and yields 0 — the
        // bug that picked ChunksV4 and 404'd every chunk.
        assert_eq!(blob_to_num("013000000000"), 13);
        assert_eq!(blob_to_num("015000000000"), 15);
        assert_eq!(blob_to_num("006000000000"), 6);
        // Multi-byte groups (little-endian): 1 | 2<<8 = 513
        assert_eq!(blob_to_num("001002"), 513);
        assert_eq!(blob_to_num("xyz"), 0, "Java toIntOrNull ?: 0");
        assert_eq!(blob_to_num("-5"), -5, "Java parses negative groups");
    }

    #[test]
    fn chunk_dir_follows_version_thresholds() {
        for (v, dir) in [(2, "Chunks"), (3, "ChunksV2"), (6, "ChunksV3"), (15, "ChunksV4")] {
            let bytes = build_manifest(&sample_chunks(), &sample_files(), v, false);
            assert_eq!(parse_manifest(&bytes).unwrap().chunk_dir, dir, "version {v}");
        }
        assert_eq!(chunk_dir_for_json_version(1), "ChunksV4");
        assert_eq!(chunk_dir_for_json_version(4), "ChunksV2");
    }

    #[test]
    fn duplicate_guid_keeps_first_position_last_value() {
        let mut chunks = sample_chunks();
        let dup = TestChunk {
            guid: chunks[0].guid,
            hash: 0x99,
            sha1: [0x77; 20],
            group: 9,
            window: 1,
            file_size: 1,
        };
        chunks.push(dup);
        let bytes = build_manifest(&chunks, &sample_files(), 21, false);
        let m = parse_manifest(&bytes).unwrap();
        assert_eq!(m.unique_chunks.len(), 3);
        assert_eq!(m.unique_chunks[0].hash, 0x99);
        assert_eq!(m.unique_chunks[0].group_num, 9);
    }

    #[test]
    fn truncated_manifest_is_a_parse_failure() {
        let bytes = build_manifest(&sample_chunks(), &sample_files(), 21, false);
        assert!(parse_manifest(&bytes[..bytes.len() - 40]).is_err());
        assert!(parse_manifest(&bytes[..20]).is_err());
    }

    #[test]
    fn bad_uncompressed_size_is_a_parse_failure() {
        let good = build_manifest(&sample_chunks(), &sample_files(), 21, true);
        let real = i32::from_le_bytes([good[8], good[9], good[10], good[11]]);
        // Claim MORE than the body inflates to: Java's single `inflate()` returns fewer bytes
        // than `sizeUncompressed` → "Decomp size mismatch" → null.
        let mut bigger = good.clone();
        bigger[8..12].copy_from_slice(&(real + 1).to_le_bytes());
        assert!(parse_manifest(&bigger).is_err());
        // Claim LESS: Java fills exactly `sizeUncompressed` bytes (silently truncating), then the
        // FileManifestList end position lands past the limit → exception → null.
        let mut smaller = good;
        smaller[8..12].copy_from_slice(&(real - 1).to_le_bytes());
        assert!(parse_manifest(&smaller).is_err());
    }

    #[test]
    fn fstring_decoding_matches_java() {
        let mut v = fstring_ascii("abc");
        v.extend_from_slice(&fstring_utf16("dé"));
        v.extend_from_slice(&fstring_ascii(""));
        v.extend_from_slice(&[4, 0, 0, 0, b'a', 0xFF, b'b', 0]); // non-ASCII byte → U+FFFD
        let mut c = Cursor::new(&v);
        assert_eq!(c.fstring().unwrap(), "abc");
        assert_eq!(c.fstring().unwrap(), "dé");
        assert_eq!(c.fstring().unwrap(), "");
        assert_eq!(c.fstring().unwrap(), "a\u{FFFD}b");
    }

    #[test]
    fn json_manifest_parses_like_java() {
        // All numeric fields use Epic's BLOB encoding (%03d per byte, little-endian):
        // hash 0x0123456789ABCDEF → bytes LE [EF CD AB 89 67 45 23 01] → "239205171137103069035001"
        let json = r#"{
          "ManifestFileVersion": "013000000000",
          "ChunkHashList": {
            "0000000100000002000000030000000ff": "239205171137103069035001",
            "short": "00",
            "DEADBEEFFFFFFFFF0000000080000000": "zz"
          },
          "DataGroupList": { "0000000100000002000000030000000ff": "007", "DEADBEEFFFFFFFFF0000000080000000": "x" },
          "ChunkFilesizeList": { "0000000100000002000000030000000ff": "255" },
          "ChunkShaList": { "0000000100000002000000030000000ff": "0123456789abcdef0123456789ABCDEF01234567" },
          "FileManifestList": [
            { "Filename": "a.bin", "InstallTags": ["German", ""],
              "FileChunkParts": [ { "Guid": "0000000100000002000000030000000ff", "Offset": "012", "Size": "034" },
                                  { "Guid": "tooshort", "Offset": "x", "Size": "-5" } ] },
            { "Filename": "b.bin" }
          ]
        }"#;
        let m = parse_manifest(json.as_bytes()).expect("json parse");
        assert_eq!(m.chunk_dir, "ChunksV3");
        assert_eq!(m.unique_chunks.len(), 2, "keys shorter than 32 chars are skipped");
        let c0 = &m.unique_chunks[0];
        assert_eq!(c0.guid, [1, 2, 3, 0x0000_000F]);
        assert_eq!(c0.hash, 0x0123_4567_89AB_CDEF);
        assert_eq!(c0.group_num, 7);
        assert_eq!(c0.file_size, 255);
        assert_eq!(c0.window_size, 0);
        assert_eq!(
            c0.sha1,
            Some([
                0x01, 0x23, 0x45, 0x67, 0x89, 0xab, 0xcd, 0xef, 0x01, 0x23, 0x45, 0x67, 0x89,
                0xAB, 0xCD, 0xEF, 0x01, 0x23, 0x45, 0x67
            ])
        );
        let c1 = &m.unique_chunks[1];
        assert_eq!(c1.hash, 0, "unparseable hash → 0");
        assert_eq!(c1.group_num, 0, "unparseable group → 0");
        assert_eq!(c1.file_size, 0, "missing size → 0");
        assert!(c1.sha1.is_none(), "missing SHA → None");

        assert_eq!(m.files.len(), 2);
        assert_eq!(m.files[0].install_tags, vec!["German".to_string()]);
        assert_eq!(m.files[0].parts.len(), 2);
        assert_eq!(m.files[0].parts[0].offset, 12);
        assert_eq!(m.files[0].parts[0].size, 34);
        assert_eq!(m.files[0].parts[1].guid, [0; 4]);
        assert_eq!(m.files[0].parts[1].offset, 0);
        assert_eq!(m.files[0].parts[1].size, -5);
        assert!(m.files[0].sha1.is_none());
        assert!(m.files[1].parts.is_empty());
    }

    #[test]
    fn json_manifest_failures_match_java_nulls() {
        assert!(parse_manifest(br#"{"FileManifestList": []}"#).is_err(), "no ChunkHashList");
        assert!(parse_manifest(br#"{"ChunkHashList": {}}"#).is_err(), "no FileManifestList");
        assert!(
            parse_manifest(br#"{"ChunkHashList": {"zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz": "0"}, "FileManifestList": []}"#)
                .is_err(),
            "invalid GUID hex throws out of the parse"
        );
        assert!(parse_manifest(b"not json at all").is_err());
    }
}
