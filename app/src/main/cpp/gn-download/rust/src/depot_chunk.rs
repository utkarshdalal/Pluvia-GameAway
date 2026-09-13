use crate::cdn_client;
use crate::crypto::{
    aes256_cbc_decrypt, aes256_ecb_decrypt_block, AesBlock, SessionKey, AES_BLOCK_BYTES,
    SESSION_KEY_LENGTH,
};
use lzma_rs::decompress::raw::{LzmaDecoder, LzmaParams, LzmaProperties};
use std::io::{Cursor, Read};

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct DepotChunkResult {
    pub data: Vec<u8>,
    pub error: String,
}

impl DepotChunkResult {
    pub fn ok(&self) -> bool {
        self.error.is_empty()
    }
}

pub fn process_depot_chunk(
    raw: &[u8],
    depot_key: &[u8],
    expected_crc: u32,
    expected_size: u32,
) -> DepotChunkResult {
    if depot_key.len() != SESSION_KEY_LENGTH {
        return fail("chunk: bad depot key length");
    }
    if raw.is_empty() {
        return fail("chunk: empty");
    }
    let mut key = [0u8; SESSION_KEY_LENGTH];
    key.copy_from_slice(depot_key);

    let Some(dec) = steam_symmetric_decrypt(&key, raw) else {
        return fail("chunk: AES decrypt failed");
    };
    if dec.is_empty() {
        return fail("chunk: AES decrypt failed");
    }

    let result = if dec.starts_with(b"VSZa") {
        decompress_vzstd(&dec, expected_size)
    } else if dec.starts_with(b"VZa") {
        decompress_vzip(&dec, expected_size)
    } else if dec.starts_with(b"PK\x03\x04") {
        match cdn_client::unzip_first_entry(&dec) {
            Some(data) => DepotChunkResult {
                data,
                error: String::new(),
            },
            None => fail("chunk: PKZip decompress failed"),
        }
    } else {
        fail("chunk: unrecognised compression header")
    };
    if !result.ok() {
        return result;
    }
    if result.data.len() != expected_size as usize {
        return fail(format!(
            "chunk: size mismatch ({} != {expected_size})",
            result.data.len()
        ));
    }
    if steam_adler_hash(&result.data) != expected_crc {
        return fail("chunk: Adler32 mismatch");
    }
    result
}

pub fn steam_symmetric_decrypt(key: &SessionKey, enc: &[u8]) -> Option<Vec<u8>> {
    if enc.len() < AES_BLOCK_BYTES * 2 {
        return None;
    }
    let mut wrapped = [0u8; AES_BLOCK_BYTES];
    wrapped.copy_from_slice(&enc[..AES_BLOCK_BYTES]);
    let iv: AesBlock = aes256_ecb_decrypt_block(key, &wrapped)?;
    aes256_cbc_decrypt(key, &iv, &enc[AES_BLOCK_BYTES..])
}

fn decompress_vzstd(dec: &[u8], expected_size: u32) -> DepotChunkResult {
    if dec.len() <= 8 {
        return fail("vzstd: chunk too small");
    }
    let payload = &dec[8..];
    let reader = match ruzstd::StreamingDecoder::new(Cursor::new(payload)) {
        Ok(reader) => reader,
        Err(_) => return fail("vzstd: bad zstd frame"),
    };
    let mut data = Vec::with_capacity(expected_size as usize);
    // Decode at most expected_size + 1 bytes: a hostile/malformed frame must be
    // rejected by size, not allowed to expand unboundedly before the check.
    if reader.take(expected_size as u64 + 1).read_to_end(&mut data).is_err() {
        return fail("vzstd: decode failed");
    }
    if data.len() > expected_size as usize {
        return fail("vzstd: decoded size exceeds expected chunk size");
    }
    DepotChunkResult {
        data,
        error: String::new(),
    }
}

fn decompress_vzip(dec: &[u8], expected_size: u32) -> DepotChunkResult {
    const HEADER: usize = 3 + 4;
    const PROPS: usize = 5;
    const FOOTER: usize = 10;
    if dec.len() < HEADER + PROPS + FOOTER {
        return fail("vzip: chunk too small");
    }
    if dec[dec.len() - 2] != b'z' || dec[dec.len() - 1] != b'v' {
        return fail("vzip: bad footer");
    }
    let props = &dec[HEADER..HEADER + PROPS];
    let comp = &dec[HEADER + PROPS..dec.len() - FOOTER];
    let Some(params) = lzma_params_from_steam_props(props, expected_size as u64) else {
        return fail("vzip: bad LZMA properties");
    };
    let mut decoder = match LzmaDecoder::new(params, None) {
        Ok(decoder) => decoder,
        Err(_) => return fail("vzip: raw decoder init failed"),
    };
    let mut output = Vec::with_capacity(expected_size as usize);
    match decoder.decompress(&mut Cursor::new(comp), &mut output) {
        Ok(()) => DepotChunkResult {
            data: output,
            error: String::new(),
        },
        Err(_) => fail("vzip: LZMA decode failed"),
    }
}

fn lzma_params_from_steam_props(props: &[u8], expected_size: u64) -> Option<LzmaParams> {
    if props.len() != 5 {
        return None;
    }
    let mut packed = props[0] as u32;
    if packed >= 225 {
        return None;
    }
    let lc = packed % 9;
    packed /= 9;
    let lp = packed % 5;
    let pb = packed / 5;
    if lc > 8 || lp > 4 || pb > 4 {
        return None;
    }
    let dict_size = u32::from_le_bytes(props[1..5].try_into().ok()?);
    Some(LzmaParams::new(
        LzmaProperties { lc, lp, pb },
        dict_size,
        Some(expected_size),
    ))
}

pub fn steam_adler_hash(data: &[u8]) -> u32 {
    let mut a = 0u32;
    let mut b = 0u32;
    for byte in data {
        a = (a + *byte as u32) % 65_521;
        b = (b + a) % 65_521;
    }
    a | (b << 16)
}

fn fail(msg: impl Into<String>) -> DepotChunkResult {
    DepotChunkResult {
        data: Vec::new(),
        error: msg.into(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::crypto::{aes256_cbc_encrypt, aes256_ecb_encrypt_block};

    #[test]
    fn steam_adler_hash_uses_zero_seed() {
        assert_eq!(steam_adler_hash(b""), 0);
        assert_eq!(steam_adler_hash(b"abc"), 0x024a_0126);
    }

    #[test]
    fn decrypts_and_processes_pkzip_chunk() {
        let key = [4u8; SESSION_KEY_LENGTH];
        let payload = b"chunk bytes";
        let mut zip = Vec::new();
        zip.extend_from_slice(&0x0403_4b50u32.to_le_bytes());
        zip.extend_from_slice(&20u16.to_le_bytes());
        zip.extend_from_slice(&0u16.to_le_bytes());
        zip.extend_from_slice(&0u16.to_le_bytes());
        zip.extend_from_slice(&0u16.to_le_bytes());
        zip.extend_from_slice(&0u16.to_le_bytes());
        zip.extend_from_slice(&0u32.to_le_bytes());
        zip.extend_from_slice(&(payload.len() as u32).to_le_bytes());
        zip.extend_from_slice(&(payload.len() as u32).to_le_bytes());
        zip.extend_from_slice(&1u16.to_le_bytes());
        zip.extend_from_slice(&0u16.to_le_bytes());
        zip.extend_from_slice(b"x");
        zip.extend_from_slice(payload);

        let raw = encrypt_steam_symmetric(&key, &zip);
        let result =
            process_depot_chunk(&raw, &key, steam_adler_hash(payload), payload.len() as u32);
        assert_eq!(result.data, payload);
        assert!(result.ok());
    }

    #[test]
    fn decrypts_and_processes_vzip_raw_lzma_chunk() {
        let key = [8u8; SESSION_KEY_LENGTH];
        let payload = b"legacy lzma chunk bytes";
        let mut lzma_alone = Vec::new();
        lzma_rs::lzma_compress(&mut Cursor::new(payload), &mut lzma_alone).unwrap();
        assert!(lzma_alone.len() > 13);

        let mut vzip = Vec::new();
        vzip.extend_from_slice(b"VZa");
        vzip.extend_from_slice(&0u32.to_le_bytes());
        vzip.extend_from_slice(&lzma_alone[..5]);
        vzip.extend_from_slice(&lzma_alone[13..]);
        vzip.extend_from_slice(&0u32.to_le_bytes());
        vzip.extend_from_slice(&(payload.len() as u32).to_le_bytes());
        vzip.extend_from_slice(b"zv");

        let raw = encrypt_steam_symmetric(&key, &vzip);
        let result =
            process_depot_chunk(&raw, &key, steam_adler_hash(payload), payload.len() as u32);
        assert_eq!(result.data, payload);
        assert!(result.ok());
    }

    #[test]
    fn vzip_rejects_bad_properties_before_raw_decode() {
        let mut dec = Vec::new();
        dec.extend_from_slice(b"VZa");
        dec.extend_from_slice(&0u32.to_le_bytes());
        dec.extend_from_slice(&[225, 0, 0, 0, 0]);
        dec.extend_from_slice(b"payload");
        dec.extend_from_slice(&0u32.to_le_bytes());
        dec.extend_from_slice(&0u32.to_le_bytes());
        dec.extend_from_slice(b"zv");
        assert_eq!(decompress_vzip(&dec, 1).error, "vzip: bad LZMA properties");
    }

    fn encrypt_steam_symmetric(key: &SessionKey, plaintext: &[u8]) -> Vec<u8> {
        let iv = [6u8; AES_BLOCK_BYTES];
        let wrapped = aes256_ecb_encrypt_block(key, &iv).unwrap();
        let body = aes256_cbc_encrypt(key, &iv, plaintext).unwrap();
        let mut out = wrapped.to_vec();
        out.extend_from_slice(&body);
        out
    }
}
