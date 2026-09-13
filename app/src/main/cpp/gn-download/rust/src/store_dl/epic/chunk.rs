//! One chunk: header parse → (zlib inflate | copy) → SHA-1 → `.part` → rename into the cache.
//!
//! Port of `EpicDownloadManager.downloadChunkStreaming` minus the HTTP part (the fetch core
//! hands us the whole response body). Java's protocol, kept exactly:
//!  - 41-byte header: magic(4) headerVersion(4) headerSize(4) compressedSize(4) GUID(16) hash(8)
//!    storedAs(1); payload starts at `max(headerSize, 41)` (Java never seeks back);
//!  - payload = the next `compressedSize` bytes, or whatever the body still has (a short body
//!    is NOT an error — the SHA-1 check decides);
//!  - `storedAs & 1` → zlib; corrupt zlib = failure; a stream that ends early = partial output;
//!  - the decompressed bytes go to `<final>.part`; the SHA-1 (when the manifest has one) must
//!    match or the `.part` is deleted; then an atomic rename publishes `<final>`.
//! A cache file therefore exists under its final name ONLY if it fully downloaded and verified,
//! on either engine.

use std::fs::{self, File};
use std::io::{self, BufWriter, Write};
use std::path::{Path, PathBuf};

use sha1::{Digest, Sha1};

/// Chunk file magic (`0xB1FE3AA2`).
pub const CHUNK_MAGIC: u32 = 0xB1FE_3AA2;

/// Bytes Java reads before looking at the header (`byte[] hdrBuf = new byte[41]`).
pub const CHUNK_HEADER_PREFIX: usize = 41;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct ChunkHeader {
    pub header_version: i32,
    pub header_size: i32,
    pub compressed_size: i32,
    pub stored_as: u8,
}

impl ChunkHeader {
    pub fn is_compressed(&self) -> bool {
        (self.stored_as & 1) != 0
    }

    /// Where the payload begins in the body (`if (headerSize > 41) skipFully(headerSize - 41)`).
    pub fn payload_start(&self) -> usize {
        if self.header_size > CHUNK_HEADER_PREFIX as i32 {
            self.header_size as usize
        } else {
            CHUNK_HEADER_PREFIX
        }
    }
}

/// `readFully(hdrBuf)` + header decode. `Err` = the Java `continue`/exception paths
/// (short stream, bad magic).
pub fn parse_chunk_header(body: &[u8]) -> Result<ChunkHeader, String> {
    if body.len() < CHUNK_HEADER_PREFIX {
        return Err(format!(
            "Stream ended after {}/{} bytes",
            body.len(),
            CHUNK_HEADER_PREFIX
        ));
    }
    let le = |o: usize| i32::from_le_bytes([body[o], body[o + 1], body[o + 2], body[o + 3]]);
    let magic = le(0) as u32;
    if magic != CHUNK_MAGIC {
        return Err(format!("Bad chunk magic (streaming): 0x{magic:x}"));
    }
    Ok(ChunkHeader {
        header_version: le(4),
        header_size: le(8),
        compressed_size: le(12),
        stored_as: body[40],
    })
}

/// The payload slice Java's read loop would consume: `compressedSize` bytes after the header,
/// truncated to what the body holds (`if (n <= 0) break;`). `Err` when the header skip runs
/// past the body (`skipFully` throws).
pub fn chunk_payload<'a>(body: &'a [u8], hdr: &ChunkHeader) -> Result<&'a [u8], String> {
    let start = hdr.payload_start();
    if start > body.len() {
        return Err("Stream ended during skip".to_string());
    }
    let want = if hdr.compressed_size <= 0 {
        0
    } else {
        hdr.compressed_size as usize
    };
    let avail = body.len() - start;
    // A short body can only produce partial output: zlib streams may END cleanly
    // mid-data, and sha1-less/size-less JSON chunks have no later gate to catch it.
    if want > 0 && avail < want {
        return Err(format!(
            "truncated chunk body: have {avail} of {want} declared bytes"
        ));
    }
    Ok(&body[start..start + want.min(avail)])
}

/// `Writer` that mirrors `fos.write(...)` + `sha.update(...)` on every block.
struct HashingWriter<W: Write> {
    inner: W,
    sha: Option<Sha1>,
    written: u64,
}

impl<W: Write> Write for HashingWriter<W> {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        let n = self.inner.write(buf)?;
        if let Some(sha) = self.sha.as_mut() {
            sha.update(&buf[..n]);
        }
        self.written += n as u64;
        Ok(n)
    }

    fn flush(&mut self) -> io::Result<()> {
        self.inner.flush()
    }
}

/// `new File(outFile.getPath() + ".part")`.
pub fn part_path(final_path: &Path) -> PathBuf {
    let mut s = final_path.as_os_str().to_os_string();
    s.push(".part");
    PathBuf::from(s)
}

/// Process one downloaded body into `final_path`. Returns the number of DECOMPRESSED bytes
/// written. `Err(reason)` = Java's "try the next CDN" outcome; the `.part` never survives an
/// error and `final_path` is only ever created by the rename after verification.
/// `expected_size` (manifest `window_size` when known) guards sha1-less chunks against a
/// truncated zlib stream ending cleanly with partial output.
pub fn write_verified_chunk(
    body: &[u8],
    expected_sha1: Option<&[u8; 20]>,
    expected_size: Option<u64>,
    final_path: &Path,
) -> Result<u64, String> {
    let tmp = part_path(final_path);
    // `tmp.delete()` — clear any stale partial from a prior interrupted attempt.
    let _ = fs::remove_file(&tmp);

    let result = write_part(body, expected_sha1, expected_size, &tmp, final_path);
    if result.is_err() {
        let _ = fs::remove_file(&tmp);
    }
    result
}

fn write_part(
    body: &[u8],
    expected_sha1: Option<&[u8; 20]>,
    expected_size: Option<u64>,
    tmp: &Path,
    final_path: &Path,
) -> Result<u64, String> {
    let hdr = parse_chunk_header(body)?;
    let payload = chunk_payload(body, &hdr)?;

    let file = File::create(tmp).map_err(|e| format!("open {}: {e}", tmp.display()))?;
    let mut out = HashingWriter {
        inner: BufWriter::with_capacity(128 * 1024, file),
        sha: expected_sha1.map(|_| Sha1::new()),
        written: 0,
    };

    if hdr.is_compressed() {
        // Java: inflate until the stream finishes or the input runs out; a corrupt stream throws
        // (DataFormatException) → this CDN attempt fails. flate2 behaves the same way: EOF on a
        // truncated stream is a clean end (partial output), corruption is an error.
        let mut dec = flate2::read::ZlibDecoder::new(payload);
        io::copy(&mut dec, &mut out).map_err(|e| format!("inflate: {e}"))?;
    } else {
        out.write_all(payload).map_err(|e| format!("write: {e}"))?;
    }
    out.flush().map_err(|e| format!("flush: {e}"))?;
    let written = out.written;
    let digest = out.sha.take().map(|s| s.finalize());
    drop(out); // close the file before the rename

    if let (Some(expected), Some(actual)) = (expected_sha1, digest) {
        if actual.as_slice() != &expected[..] {
            return Err("Chunk SHA-1 mismatch (streaming)".to_string());
        }
    }
    // Size gate BEFORE the rename: a mismatch must leave neither the .part nor a
    // published final file behind.
    if let Some(expected) = expected_size {
        if written != expected {
            return Err(format!(
                "Chunk size mismatch: wrote {written} bytes, expected {expected}"
            ));
        }
    }

    // Verified → publish atomically (same directory → same filesystem).
    fs::rename(tmp, final_path).map_err(|e| format!("Chunk rename failed: {e}"))?;
    Ok(written)
}

#[cfg(test)]
pub(crate) mod test_support {
    use super::*;

    /// Build a chunk body the way the CDN serves it.
    pub fn build_chunk_body(
        decompressed: &[u8],
        compress: bool,
        extra_header: usize,
        declared_compressed_size: Option<i32>,
    ) -> Vec<u8> {
        let payload = if compress {
            let mut enc =
                flate2::write::ZlibEncoder::new(Vec::new(), flate2::Compression::default());
            enc.write_all(decompressed).unwrap();
            enc.finish().unwrap()
        } else {
            decompressed.to_vec()
        };
        let header_size = (CHUNK_HEADER_PREFIX + extra_header) as i32;
        let mut out = Vec::new();
        out.extend_from_slice(&CHUNK_MAGIC.to_le_bytes());
        out.extend_from_slice(&3i32.to_le_bytes()); // headerVersion
        out.extend_from_slice(&header_size.to_le_bytes());
        out.extend_from_slice(
            &declared_compressed_size
                .unwrap_or(payload.len() as i32)
                .to_le_bytes(),
        );
        out.extend_from_slice(&[0xAB; 16]); // GUID (unused by the reader)
        out.extend_from_slice(&[0xCD; 8]); // hash (unused by the reader)
        out.push(if compress { 1 } else { 0 });
        out.extend(std::iter::repeat(0x5A).take(extra_header));
        assert_eq!(out.len(), header_size as usize);
        out.extend_from_slice(&payload);
        out
    }

    pub fn sha1_of(data: &[u8]) -> [u8; 20] {
        let mut h = Sha1::new();
        h.update(data);
        let d = h.finalize();
        let mut out = [0u8; 20];
        out.copy_from_slice(&d);
        out
    }

    pub fn temp_dir(tag: &str) -> PathBuf {
        let nanos = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_nanos())
            .unwrap_or(0);
        let dir = std::env::temp_dir().join(format!(
            "bl-epic-{tag}-{}-{nanos}",
            std::process::id()
        ));
        fs::create_dir_all(&dir).unwrap();
        dir
    }
}

#[cfg(test)]
mod tests {
    use super::test_support::*;
    use super::*;

    fn sample_data() -> Vec<u8> {
        (0..200_000u32).map(|i| (i % 251) as u8).collect()
    }

    #[test]
    fn header_parse_matches_java_fields() {
        let body = build_chunk_body(b"hello", true, 5, None);
        let h = parse_chunk_header(&body).unwrap();
        assert_eq!(h.header_version, 3);
        assert_eq!(h.header_size, 46);
        assert!(h.is_compressed());
        assert_eq!(h.payload_start(), 46);
        let short = ChunkHeader {
            header_version: 0,
            header_size: 20,
            compressed_size: 1,
            stored_as: 0,
        };
        assert_eq!(short.payload_start(), 41, "headerSize < 41 → payload still starts at 41");
    }

    #[test]
    fn short_or_wrong_magic_bodies_are_rejected() {
        assert!(parse_chunk_header(&[0u8; 40]).is_err());
        let mut body = build_chunk_body(b"x", false, 0, None);
        body[0] ^= 0xFF;
        assert!(parse_chunk_header(&body).unwrap_err().contains("Bad chunk magic"));
    }

    #[test]
    fn short_body_is_rejected_against_the_declared_size() {
        // Declared 100 bytes but only 6 present → hard error (a short body can only
        // produce partial output, and sha1-less/size-less chunks have no later gate).
        let body = build_chunk_body(b"abcdef", false, 0, Some(100));
        let h = parse_chunk_header(&body).unwrap();
        assert!(chunk_payload(&body, &h).unwrap_err().contains("truncated chunk body"));
        // Non-positive declared size → no declared length to enforce.
        let neg = build_chunk_body(b"abcdef", false, 0, Some(-1));
        let h = parse_chunk_header(&neg).unwrap();
        assert_eq!(chunk_payload(&neg, &h).unwrap(), b"");
        // headerSize past the body → skipFully throws.
        let mut far = build_chunk_body(b"abcdef", false, 0, None);
        far[8..12].copy_from_slice(&5000i32.to_le_bytes());
        let h = parse_chunk_header(&far).unwrap();
        assert!(chunk_payload(&far, &h).is_err());
    }

    #[test]
    fn compressed_chunk_is_inflated_verified_and_renamed() {
        let dir = temp_dir("ok");
        let data = sample_data();
        let body = build_chunk_body(&data, true, 3, None);
        let final_path = dir.join("00000001000000020000000300000004");
        let n = write_verified_chunk(&body, Some(&sha1_of(&data)), None, &final_path).unwrap();
        assert_eq!(n, data.len() as u64);
        assert_eq!(fs::read(&final_path).unwrap(), data);
        assert!(!part_path(&final_path).exists(), ".part renamed away");
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn stored_chunk_is_copied_as_is() {
        let dir = temp_dir("stored");
        let data = b"stored-as-is payload".to_vec();
        let body = build_chunk_body(&data, false, 0, None);
        let final_path = dir.join("chunk");
        let n = write_verified_chunk(&body, Some(&sha1_of(&data)), None, &final_path).unwrap();
        assert_eq!(n, data.len() as u64);
        assert_eq!(fs::read(&final_path).unwrap(), data);
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn sha1_mismatch_leaves_nothing_behind() {
        let dir = temp_dir("sha");
        let data = sample_data();
        let body = build_chunk_body(&data, true, 0, None);
        let final_path = dir.join("chunk");
        let err = write_verified_chunk(&body, Some(&[0x42; 20]), None, &final_path).unwrap_err();
        assert!(err.contains("SHA-1 mismatch"), "{err}");
        assert!(!final_path.exists());
        assert!(!part_path(&final_path).exists());
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn unverifiable_chunk_is_accepted_without_a_hash() {
        let dir = temp_dir("nosha");
        let data = sample_data();
        let body = build_chunk_body(&data, true, 0, None);
        let final_path = dir.join("chunk");
        assert_eq!(
            write_verified_chunk(&body, None, None, &final_path).unwrap(),
            data.len() as u64
        );
        assert_eq!(fs::read(&final_path).unwrap(), data);
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn corrupt_zlib_fails_and_truncated_zlib_fails_the_hash() {
        let dir = temp_dir("zlib");
        let data = sample_data();
        let mut body = build_chunk_body(&data, true, 0, None);
        let final_path = dir.join("chunk");
        // Truncate the body: rejected up front by the declared-size gate.
        let cut = body.len() - 1000;
        let err = write_verified_chunk(&body[..cut], Some(&sha1_of(&data)), None, &final_path).unwrap_err();
        assert!(
            err.contains("truncated chunk body") || err.contains("SHA-1 mismatch") || err.contains("inflate"),
            "{err}"
        );
        assert!(!final_path.exists());
        // Corrupt the deflate stream itself.
        for b in body.iter_mut().skip(60).take(64) {
            *b ^= 0x55;
        }
        let err = write_verified_chunk(&body, Some(&sha1_of(&data)), None, &final_path).unwrap_err();
        assert!(err.contains("SHA-1 mismatch") || err.contains("inflate"), "{err}");
        assert!(!final_path.exists());
        assert!(!part_path(&final_path).exists());
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn stale_part_file_is_cleared_before_the_attempt() {
        let dir = temp_dir("stale");
        let final_path = dir.join("chunk");
        fs::write(part_path(&final_path), b"stale").unwrap();
        let body = [0u8; 10]; // too short → header failure
        assert!(write_verified_chunk(&body, None, None, &final_path).is_err());
        assert!(!part_path(&final_path).exists());
        assert!(!final_path.exists());
        let _ = fs::remove_dir_all(&dir);
    }
}
