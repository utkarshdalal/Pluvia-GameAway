use crate::base64;
use crate::crypto::{
    aes256_cbc_decrypt, aes256_ecb_decrypt_block, AesBlock, SessionKey, AES_BLOCK_BYTES,
    SESSION_KEY_LENGTH,
};
use crate::proto_wire::{Reader, WireType};

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct ChunkData {
    pub sha: Vec<u8>,
    pub crc: u32,
    pub offset: u64,
    pub cb_original: u32,
    pub cb_compressed: u32,
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct FileMapping {
    pub filename: String,
    pub size: u64,
    pub flags: u32,
    pub sha_filename: Vec<u8>,
    pub sha_content: Vec<u8>,
    pub chunks: Vec<ChunkData>,
    pub linktarget: String,
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct Metadata {
    pub depot_id: u32,
    pub gid_manifest: u64,
    pub creation_time: u32,
    pub filenames_encrypted: bool,
    pub cb_disk_original: u64,
    pub cb_disk_compressed: u64,
    pub unique_chunks: u32,
    pub crc_encrypted: u32,
    pub crc_clear: u32,
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct ContentManifest {
    pub metadata: Metadata,
    pub files: Vec<FileMapping>,
    pub signature: Vec<u8>,
}

pub const PAYLOAD_MAGIC: u32 = 0x71f6_17d0;
pub const METADATA_MAGIC: u32 = 0x1f48_12be;
pub const SIGNATURE_MAGIC: u32 = 0x1b81_b817;
pub const END_OF_MANIFEST_MAGIC: u32 = 0x32c4_15ab;

impl ContentManifest {
    pub fn parse(raw: &[u8]) -> Option<Self> {
        let mut manifest = Self::default();
        let mut have_payload = false;
        let mut have_metadata = false;
        let mut pos = 0usize;

        loop {
            let magic = read_u32_le(raw, &mut pos)?;
            if magic == END_OF_MANIFEST_MAGIC {
                break;
            }
            let len = read_u32_le(raw, &mut pos)? as usize;
            // checked_add: on 32-bit (armv7) a near-u32::MAX section length would
            // otherwise wrap pos + len and pass the bound check, then panic on slicing.
            let end = pos.checked_add(len)?;
            if end > raw.len() {
                return None;
            }
            let section = &raw[pos..end];
            pos = end;

            match magic {
                PAYLOAD_MAGIC => {
                    parse_payload(section, &mut manifest.files)?;
                    have_payload = true;
                }
                METADATA_MAGIC => {
                    manifest.metadata = parse_metadata(section)?;
                    have_metadata = true;
                }
                SIGNATURE_MAGIC => {
                    manifest.signature = parse_signature(section)?;
                }
                _ => return None,
            }
        }

        (have_payload && have_metadata).then_some(manifest)
    }

    pub fn decrypt_filenames(&mut self, depot_key: &[u8]) -> bool {
        if self.metadata.filenames_encrypted {
            if depot_key.len() != SESSION_KEY_LENGTH {
                return false;
            }
            let mut key = [0u8; SESSION_KEY_LENGTH];
            key.copy_from_slice(depot_key);

            for file in &mut self.files {
                let Some(clear) = decrypt_name(&key, &file.filename) else {
                    return false;
                };
                file.filename = clear;
                if !file.linktarget.is_empty() {
                    let Some(clear) = decrypt_name(&key, &file.linktarget) else {
                        return false;
                    };
                    file.linktarget = clear;
                }
            }
            self.metadata.filenames_encrypted = false;
        }

        for file in &mut self.files {
            file.filename = file.filename.replace('\\', "/");
            file.linktarget = file.linktarget.replace('\\', "/");
        }

        self.files.sort_by(|a, b| {
            a.filename
                .bytes()
                .map(|c| c.to_ascii_lowercase())
                .cmp(b.filename.bytes().map(|c| c.to_ascii_lowercase()))
        });
        true
    }
}

fn decrypt_name(key: &SessionKey, enc: &str) -> Option<String> {
    let blob = base64::decode(enc)?;
    if blob.len() < AES_BLOCK_BYTES * 2 {
        return None;
    }
    let mut wrapped = [0u8; AES_BLOCK_BYTES];
    wrapped.copy_from_slice(&blob[..AES_BLOCK_BYTES]);
    let iv: AesBlock = aes256_ecb_decrypt_block(key, &wrapped)?;
    let plain = aes256_cbc_decrypt(key, &iv, &blob[AES_BLOCK_BYTES..])?;
    let mut out = String::from_utf8_lossy(&plain).into_owned();
    if out.ends_with('\0') {
        out.pop();
    }
    Some(out)
}

fn read_u32_le(buf: &[u8], pos: &mut usize) -> Option<u32> {
    if *pos + 4 > buf.len() {
        return None;
    }
    let v = u32::from_le_bytes(buf[*pos..*pos + 4].try_into().ok()?);
    *pos += 4;
    Some(v)
}

fn parse_chunk(body: &[u8]) -> Option<ChunkData> {
    let mut reader = Reader::new(body);
    let mut chunk = ChunkData::default();
    while !reader.eof() {
        let Some(tag) = reader.next_tag() else {
            return reader.ok().then_some(chunk);
        };
        match tag.field_number {
            1 => chunk.sha = reader.bytes()?.to_vec(),
            2 => {
                if tag.wire_type != WireType::Fixed32 {
                    return None;
                }
                chunk.crc = reader.fixed32()?;
            }
            3 => chunk.offset = reader.u64()?,
            4 => chunk.cb_original = reader.u32()?,
            5 => chunk.cb_compressed = reader.u32()?,
            _ => {
                if !reader.skip(tag.wire_type) {
                    return None;
                }
            }
        }
    }
    Some(chunk)
}

fn parse_file_mapping(body: &[u8]) -> Option<FileMapping> {
    let mut reader = Reader::new(body);
    let mut file = FileMapping::default();
    while !reader.eof() {
        let Some(tag) = reader.next_tag() else {
            return reader.ok().then_some(file);
        };
        match tag.field_number {
            1 => file.filename = reader.string()?,
            2 => file.size = reader.u64()?,
            3 => file.flags = reader.u32()?,
            4 => file.sha_filename = reader.bytes()?.to_vec(),
            5 => file.sha_content = reader.bytes()?.to_vec(),
            6 => file.chunks.push(parse_chunk(reader.bytes()?)?),
            7 => file.linktarget = reader.string()?,
            _ => {
                if !reader.skip(tag.wire_type) {
                    return None;
                }
            }
        }
    }
    Some(file)
}

fn parse_payload(body: &[u8], out: &mut Vec<FileMapping>) -> Option<()> {
    let mut reader = Reader::new(body);
    while !reader.eof() {
        let Some(tag) = reader.next_tag() else {
            return reader.ok().then_some(());
        };
        if tag.field_number == 1 {
            out.push(parse_file_mapping(reader.bytes()?)?);
        } else if !reader.skip(tag.wire_type) {
            return None;
        }
    }
    Some(())
}

fn parse_metadata(body: &[u8]) -> Option<Metadata> {
    let mut reader = Reader::new(body);
    let mut metadata = Metadata::default();
    while !reader.eof() {
        let Some(tag) = reader.next_tag() else {
            return reader.ok().then_some(metadata);
        };
        match tag.field_number {
            1 => metadata.depot_id = reader.u32()?,
            2 => metadata.gid_manifest = reader.u64()?,
            3 => metadata.creation_time = reader.u32()?,
            4 => metadata.filenames_encrypted = reader.boolean()?,
            5 => metadata.cb_disk_original = reader.u64()?,
            6 => metadata.cb_disk_compressed = reader.u64()?,
            7 => metadata.unique_chunks = reader.u32()?,
            8 => metadata.crc_encrypted = reader.u32()?,
            9 => metadata.crc_clear = reader.u32()?,
            _ => {
                if !reader.skip(tag.wire_type) {
                    return None;
                }
            }
        }
    }
    Some(metadata)
}

fn parse_signature(body: &[u8]) -> Option<Vec<u8>> {
    let mut reader = Reader::new(body);
    let mut signature = Vec::new();
    while !reader.eof() {
        let Some(tag) = reader.next_tag() else {
            return reader.ok().then_some(signature);
        };
        if tag.field_number == 1 {
            signature = reader.bytes()?.to_vec();
        } else if !reader.skip(tag.wire_type) {
            return None;
        }
    }
    Some(signature)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::crypto::{aes256_cbc_encrypt, aes256_ecb_encrypt_block};
    use crate::proto_wire::Writer;

    #[test]
    fn parses_manifest_sections() {
        let mut chunk_body = Vec::new();
        {
            let mut w = Writer::new(&mut chunk_body);
            w.bytes_field(1, &[1; 20]);
            w.tag(2, WireType::Fixed32);
            w.raw_bytes(&0x1234_5678u32.to_le_bytes());
            w.uint64_field(3, 99);
            w.uint32_field(4, 11);
            w.uint32_field(5, 22);
        }

        let mut file_body = Vec::new();
        {
            let mut w = Writer::new(&mut file_body);
            w.string_field(1, "bin\\game.exe");
            w.uint64_field(2, 11);
            w.uint32_field(3, 1);
            w.submessage_field(6, &chunk_body);
        }

        let mut payload = Vec::new();
        Writer::new(&mut payload).submessage_field(1, &file_body);

        let mut metadata = Vec::new();
        {
            let mut w = Writer::new(&mut metadata);
            w.uint32_field(1, 123);
            w.uint64_field(2, 456);
            w.uint32_field(3, 789);
            w.bool_field_force(4, false);
        }

        let mut raw = Vec::new();
        push_section(&mut raw, PAYLOAD_MAGIC, &payload);
        push_section(&mut raw, METADATA_MAGIC, &metadata);
        raw.extend_from_slice(&END_OF_MANIFEST_MAGIC.to_le_bytes());

        let mut manifest = ContentManifest::parse(&raw).unwrap();
        assert_eq!(manifest.metadata.depot_id, 123);
        assert_eq!(manifest.files[0].filename, "bin\\game.exe");
        assert!(manifest.decrypt_filenames(&[]));
        assert_eq!(manifest.files[0].filename, "bin/game.exe");
        assert_eq!(manifest.files[0].chunks[0].crc, 0x1234_5678);
    }

    #[test]
    fn decrypts_encrypted_filenames() {
        let key = [9u8; SESSION_KEY_LENGTH];
        let name = encrypt_name(&key, "dir\\file.txt\0");
        let mut manifest = ContentManifest {
            metadata: Metadata {
                filenames_encrypted: true,
                ..Default::default()
            },
            files: vec![FileMapping {
                filename: name,
                ..Default::default()
            }],
            signature: Vec::new(),
        };

        assert!(manifest.decrypt_filenames(&key));
        assert_eq!(manifest.files[0].filename, "dir/file.txt");
        assert!(!manifest.metadata.filenames_encrypted);
    }

    fn push_section(out: &mut Vec<u8>, magic: u32, body: &[u8]) {
        out.extend_from_slice(&magic.to_le_bytes());
        out.extend_from_slice(&(body.len() as u32).to_le_bytes());
        out.extend_from_slice(body);
    }

    fn encrypt_name(key: &SessionKey, name: &str) -> String {
        let iv = [5u8; AES_BLOCK_BYTES];
        let wrapped = aes256_ecb_encrypt_block(key, &iv).unwrap();
        let body = aes256_cbc_encrypt(key, &iv, name.as_bytes()).unwrap();
        let mut blob = wrapped.to_vec();
        blob.extend_from_slice(&body);
        base64::encode(&blob)
    }
}
