use crate::proto_wire::{Reader, WireType, Writer};

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct CContentServerDirectoryGetManifestRequestCodeRequest {
    pub app_id: u32,
    pub depot_id: u32,
    pub manifest_id: u64,
    pub app_branch: String,
    pub branch_password_hash: String,
}

impl CContentServerDirectoryGetManifestRequestCodeRequest {
    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::new();
        let mut w = Writer::new(&mut out);
        w.uint32_field(1, self.app_id);
        w.uint32_field(2, self.depot_id);
        w.uint64_field(3, self.manifest_id);
        w.string_field(4, &self.app_branch);
        w.string_field(5, &self.branch_password_hash);
        out
    }
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct CContentServerDirectoryGetManifestRequestCodeResponse {
    pub manifest_request_code: u64,
}

impl CContentServerDirectoryGetManifestRequestCodeResponse {
    pub fn deserialize(body: &[u8]) -> Option<Self> {
        let mut reader = Reader::new(body);
        let mut msg = Self::default();
        while !reader.eof() {
            let Some(tag) = reader.next_tag() else {
                return reader.ok().then_some(msg);
            };
            match tag.field_number {
                1 => msg.manifest_request_code = reader.u64()?,
                _ => {
                    if !reader.skip(tag.wire_type) {
                        return None;
                    }
                }
            }
        }
        Some(msg)
    }
}

#[derive(Clone, Debug, Default, PartialEq)]
pub struct CContentServerDirectoryServerInfo {
    pub server_type: String,
    pub source_id: i32,
    pub cell_id: i32,
    pub load: i32,
    pub weighted_load: f32,
    pub num_entries_in_client_list: i32,
    pub steam_china_only: bool,
    pub host: String,
    pub vhost: String,
    pub use_as_proxy: bool,
    pub proxy_request_path_template: String,
    pub https_support: String,
    pub allowed_app_ids: Vec<u32>,
    pub priority_class: u32,
}

impl CContentServerDirectoryServerInfo {
    pub fn use_https(&self) -> bool {
        self.https_support.eq_ignore_ascii_case("mandatory")
    }

    pub fn port(&self) -> u16 {
        if self.use_https() {
            443
        } else {
            80
        }
    }

    pub fn deserialize(body: &[u8]) -> Option<Self> {
        let mut reader = Reader::new(body);
        let mut msg = Self::default();
        while !reader.eof() {
            let Some(tag) = reader.next_tag() else {
                return reader.ok().then_some(msg);
            };
            match tag.field_number {
                1 => msg.server_type = reader.string()?,
                2 => msg.source_id = reader.i32()?,
                3 => msg.cell_id = reader.i32()?,
                4 => msg.load = reader.i32()?,
                5 => msg.weighted_load = f32::from_bits(reader.fixed32()?),
                6 => msg.num_entries_in_client_list = reader.i32()?,
                7 => msg.steam_china_only = reader.boolean()?,
                8 => msg.host = reader.string()?,
                9 => msg.vhost = reader.string()?,
                10 => msg.use_as_proxy = reader.boolean()?,
                11 => msg.proxy_request_path_template = reader.string()?,
                12 => msg.https_support = reader.string()?,
                13 => match tag.wire_type {
                    WireType::LengthDelimited => {
                        let mut packed = Reader::new(reader.bytes()?);
                        while !packed.eof() {
                            msg.allowed_app_ids.push(packed.varint()? as u32);
                        }
                    }
                    _ => msg.allowed_app_ids.push(reader.u32()?),
                },
                15 => msg.priority_class = reader.u32()?,
                _ => {
                    if !reader.skip(tag.wire_type) {
                        return None;
                    }
                }
            }
        }
        Some(msg)
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct CContentServerDirectoryGetServersForSteamPipeRequest {
    pub cell_id: u32,
    pub max_servers: u32,
}

impl Default for CContentServerDirectoryGetServersForSteamPipeRequest {
    fn default() -> Self {
        Self {
            cell_id: 0,
            max_servers: 20,
        }
    }
}

impl CContentServerDirectoryGetServersForSteamPipeRequest {
    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::new();
        let mut w = Writer::new(&mut out);
        w.uint32_field(1, self.cell_id);
        w.uint32_field(2, self.max_servers);
        out
    }
}

#[derive(Clone, Debug, Default, PartialEq)]
pub struct CContentServerDirectoryGetServersForSteamPipeResponse {
    pub servers: Vec<CContentServerDirectoryServerInfo>,
    pub no_change: bool,
}

impl CContentServerDirectoryGetServersForSteamPipeResponse {
    pub fn deserialize(body: &[u8]) -> Option<Self> {
        let mut reader = Reader::new(body);
        let mut msg = Self::default();
        while !reader.eof() {
            let Some(tag) = reader.next_tag() else {
                return reader.ok().then_some(msg);
            };
            match tag.field_number {
                1 => msg
                    .servers
                    .push(CContentServerDirectoryServerInfo::deserialize(
                        reader.bytes()?,
                    )?),
                2 => msg.no_change = reader.boolean()?,
                _ => {
                    if !reader.skip(tag.wire_type) {
                        return None;
                    }
                }
            }
        }
        Some(msg)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_server_info_with_packed_and_unpacked_app_ids() {
        let mut server = Vec::new();
        {
            let mut w = Writer::new(&mut server);
            w.string_field(1, "SteamCache");
            w.int32_field(2, 5);
            w.int32_field(3, 6);
            w.int32_field(4, 7);
            w.tag(5, WireType::Fixed32);
            w.raw_bytes(&1.25f32.to_bits().to_le_bytes());
            w.bool_field(7, true);
            w.string_field(8, "cache.example");
            w.string_field(12, "MANDATORY");
            w.uint32_field(13, 480);
            let mut packed = Vec::new();
            Writer::new(&mut packed).varint(730);
            w.bytes_field(13, &packed);
            w.uint32_field(15, 2);
        }

        let parsed = CContentServerDirectoryServerInfo::deserialize(&server).unwrap();
        assert_eq!(parsed.server_type, "SteamCache");
        assert_eq!(parsed.weighted_load, 1.25);
        assert_eq!(parsed.allowed_app_ids, [480, 730]);
        assert!(parsed.use_https());
        assert_eq!(parsed.port(), 443);
    }
}
