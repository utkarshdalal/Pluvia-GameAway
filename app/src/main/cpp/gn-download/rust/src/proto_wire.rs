#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u8)]
pub enum WireType {
    Varint = 0,
    Fixed64 = 1,
    LengthDelimited = 2,
    StartGroup = 3,
    EndGroup = 4,
    Fixed32 = 5,
}

impl TryFrom<u8> for WireType {
    type Error = ();

    fn try_from(value: u8) -> Result<Self, Self::Error> {
        match value {
            0 => Ok(Self::Varint),
            1 => Ok(Self::Fixed64),
            2 => Ok(Self::LengthDelimited),
            3 => Ok(Self::StartGroup),
            4 => Ok(Self::EndGroup),
            5 => Ok(Self::Fixed32),
            _ => Err(()),
        }
    }
}

pub const MAX_VARINT_BYTES: usize = 10;

pub const fn zigzag_encode_i64(v: i64) -> u64 {
    ((v as u64) << 1) ^ ((v >> 63) as u64)
}

pub const fn zigzag_decode_i64(v: u64) -> i64 {
    ((v >> 1) as i64) ^ -((v & 1) as i64)
}

pub const fn zigzag_encode_i32(v: i32) -> u32 {
    ((v as u32) << 1) ^ ((v >> 31) as u32)
}

pub const fn zigzag_decode_i32(v: u32) -> i32 {
    ((v >> 1) as i32) ^ -((v & 1) as i32)
}

pub const fn make_tag(field_number: i32, wire_type: WireType) -> u32 {
    ((field_number as u32) << 3) | wire_type as u32
}

pub struct Writer<'a> {
    out: &'a mut Vec<u8>,
}

impl<'a> Writer<'a> {
    pub fn new(out: &'a mut Vec<u8>) -> Self {
        Self { out }
    }

    pub fn varint(&mut self, mut v: u64) {
        while v >= 0x80 {
            self.out.push((v as u8) | 0x80);
            v >>= 7;
        }
        self.out.push(v as u8);
    }

    pub fn tag(&mut self, field_number: i32, wire_type: WireType) {
        self.varint(make_tag(field_number, wire_type) as u64);
    }

    pub fn uint32_field(&mut self, field_number: i32, v: u32) {
        if v == 0 {
            return;
        }
        self.tag(field_number, WireType::Varint);
        self.varint(v as u64);
    }

    pub fn uint64_field(&mut self, field_number: i32, v: u64) {
        if v == 0 {
            return;
        }
        self.tag(field_number, WireType::Varint);
        self.varint(v);
    }

    pub fn int32_field(&mut self, field_number: i32, v: i32) {
        if v == 0 {
            return;
        }
        self.tag(field_number, WireType::Varint);
        self.varint(v as i64 as u64);
    }

    pub fn int64_field(&mut self, field_number: i32, v: i64) {
        if v == 0 {
            return;
        }
        self.tag(field_number, WireType::Varint);
        self.varint(v as u64);
    }

    pub fn bool_field(&mut self, field_number: i32, v: bool) {
        if !v {
            return;
        }
        self.tag(field_number, WireType::Varint);
        self.varint(1);
    }

    pub fn fixed32_field(&mut self, field_number: i32, v: u32) {
        if v == 0 {
            return;
        }
        self.tag(field_number, WireType::Fixed32);
        self.out.extend_from_slice(&v.to_le_bytes());
    }

    pub fn fixed64_field(&mut self, field_number: i32, v: u64) {
        if v == 0 {
            return;
        }
        self.tag(field_number, WireType::Fixed64);
        self.out.extend_from_slice(&v.to_le_bytes());
    }

    pub fn string_field(&mut self, field_number: i32, s: &str) {
        if s.is_empty() {
            return;
        }
        self.tag(field_number, WireType::LengthDelimited);
        self.varint(s.len() as u64);
        self.out.extend_from_slice(s.as_bytes());
    }

    pub fn bytes_field(&mut self, field_number: i32, bytes: &[u8]) {
        if bytes.is_empty() {
            return;
        }
        self.tag(field_number, WireType::LengthDelimited);
        self.varint(bytes.len() as u64);
        self.out.extend_from_slice(bytes);
    }

    pub fn submessage_field(&mut self, field_number: i32, body: &[u8]) {
        self.tag(field_number, WireType::LengthDelimited);
        self.varint(body.len() as u64);
        self.out.extend_from_slice(body);
    }

    pub fn raw_bytes(&mut self, bytes: &[u8]) {
        self.out.extend_from_slice(bytes);
    }

    pub fn uint32_field_force(&mut self, field_number: i32, v: u32) {
        self.tag(field_number, WireType::Varint);
        self.varint(v as u64);
    }

    pub fn bool_field_force(&mut self, field_number: i32, v: bool) {
        self.tag(field_number, WireType::Varint);
        self.varint(u64::from(v));
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct Tag {
    pub field_number: i32,
    pub wire_type: WireType,
}

pub struct Reader<'a> {
    buf: &'a [u8],
    pos: usize,
    ok: bool,
}

impl<'a> Reader<'a> {
    pub fn new(buf: &'a [u8]) -> Self {
        Self {
            buf,
            pos: 0,
            ok: true,
        }
    }

    pub fn ok(&self) -> bool {
        self.ok
    }

    pub fn eof(&self) -> bool {
        self.pos >= self.buf.len()
    }

    pub fn position(&self) -> usize {
        self.pos
    }

    pub fn next_tag(&mut self) -> Option<Tag> {
        if self.eof() {
            return None;
        }
        let raw = self.varint()?;
        // Reject tags that don't fit u32 — the truncate-cast below could otherwise
        // turn an invalid/unknown tag into a known field.
        if raw > u32::MAX as u64 {
            self.ok = false;
            return None;
        }
        let raw = raw as u32;
        let wire_type = match WireType::try_from((raw & 0x07) as u8) {
            Ok(wire_type) => wire_type,
            Err(_) => {
                self.ok = false;
                return None;
            }
        };
        let field_number = (raw >> 3) as i32;
        if matches!(wire_type, WireType::StartGroup | WireType::EndGroup) || field_number <= 0 {
            self.ok = false;
            return None;
        }
        Some(Tag {
            field_number,
            wire_type,
        })
    }

    pub fn varint(&mut self) -> Option<u64> {
        let mut result = 0u64;
        for i in 0..MAX_VARINT_BYTES {
            if self.pos >= self.buf.len() {
                self.ok = false;
                return None;
            }
            let b = self.buf[self.pos];
            self.pos += 1;
            // The 10th byte may only carry bit 0 (value <= 1); anything larger
            // would silently truncate into a plausible-looking length/id.
            if i == MAX_VARINT_BYTES - 1 && b > 1 {
                self.ok = false;
                return None;
            }
            result |= ((b & 0x7f) as u64) << (i * 7);
            if (b & 0x80) == 0 {
                return Some(result);
            }
        }
        self.ok = false;
        None
    }

    pub fn skip(&mut self, wire_type: WireType) -> bool {
        match wire_type {
            WireType::Varint => self.varint().is_some(),
            WireType::Fixed64 => self.advance(8),
            WireType::LengthDelimited => {
                let Some(len) = self.varint() else {
                    return false;
                };
                if len > usize::MAX as u64 {
                    self.ok = false;
                    return false;
                }
                self.advance(len as usize)
            }
            WireType::Fixed32 => self.advance(4),
            WireType::StartGroup | WireType::EndGroup => {
                self.ok = false;
                false
            }
        }
    }

    pub fn u32(&mut self) -> Option<u32> {
        self.varint().map(|v| v as u32)
    }

    pub fn u64(&mut self) -> Option<u64> {
        self.varint()
    }

    pub fn i32(&mut self) -> Option<i32> {
        self.varint().map(|v| v as i32)
    }

    pub fn i64(&mut self) -> Option<i64> {
        self.varint().map(|v| v as i64)
    }

    pub fn boolean(&mut self) -> Option<bool> {
        self.varint().map(|v| v != 0)
    }

    pub fn fixed32(&mut self) -> Option<u32> {
        let Some(end) = self.pos.checked_add(4) else {
            self.ok = false;
            return None;
        };
        if end > self.buf.len() {
            self.ok = false;
            return None;
        }
        let bytes: [u8; 4] = self.buf[self.pos..end].try_into().ok()?;
        self.pos = end;
        Some(u32::from_le_bytes(bytes))
    }

    pub fn fixed64(&mut self) -> Option<u64> {
        let Some(end) = self.pos.checked_add(8) else {
            self.ok = false;
            return None;
        };
        if end > self.buf.len() {
            self.ok = false;
            return None;
        }
        let bytes: [u8; 8] = self.buf[self.pos..end].try_into().ok()?;
        self.pos = end;
        Some(u64::from_le_bytes(bytes))
    }

    pub fn bytes(&mut self) -> Option<&'a [u8]> {
        let len = self.varint()?;
        if len > usize::MAX as u64 {
            self.ok = false;
            return None;
        }
        let len = len as usize;
        let Some(end) = self.pos.checked_add(len) else {
            self.ok = false;
            return None;
        };
        if end > self.buf.len() {
            self.ok = false;
            return None;
        }
        let out = &self.buf[self.pos..end];
        self.pos = end;
        Some(out)
    }

    pub fn string(&mut self) -> Option<String> {
        Some(String::from_utf8_lossy(self.bytes()?).into_owned())
    }

    fn advance(&mut self, amount: usize) -> bool {
        let Some(end) = self.pos.checked_add(amount) else {
            self.ok = false;
            return false;
        };
        if end > self.buf.len() {
            self.ok = false;
            return false;
        }
        self.pos = end;
        true
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn varint_roundtrips_common_edges() {
        let cases = [0, 1, 127, 128, 300, u32::MAX as u64, u64::MAX];
        for case in cases {
            let mut buf = Vec::new();
            Writer::new(&mut buf).varint(case);
            let mut reader = Reader::new(&buf);
            assert_eq!(reader.varint(), Some(case));
            assert!(reader.ok());
            assert!(reader.eof());
        }
    }

    #[test]
    fn writes_and_reads_typed_fields() {
        let mut buf = Vec::new();
        let mut writer = Writer::new(&mut buf);
        writer.uint32_field(1, 42);
        writer.fixed32_field(2, 0x1234_5678);
        writer.string_field(3, "steam");
        writer.bool_field_force(4, false);

        let mut reader = Reader::new(&buf);
        assert_eq!(
            reader.next_tag(),
            Some(Tag {
                field_number: 1,
                wire_type: WireType::Varint
            })
        );
        assert_eq!(reader.u32(), Some(42));
        assert_eq!(
            reader.next_tag(),
            Some(Tag {
                field_number: 2,
                wire_type: WireType::Fixed32
            })
        );
        assert_eq!(reader.fixed32(), Some(0x1234_5678));
        assert_eq!(
            reader.next_tag(),
            Some(Tag {
                field_number: 3,
                wire_type: WireType::LengthDelimited
            })
        );
        assert_eq!(reader.string(), Some("steam".to_string()));
        assert_eq!(
            reader.next_tag(),
            Some(Tag {
                field_number: 4,
                wire_type: WireType::Varint
            })
        );
        assert_eq!(reader.boolean(), Some(false));
        assert!(reader.eof());
    }

    #[test]
    fn rejects_deprecated_groups_and_truncated_data() {
        let mut group = Vec::new();
        Writer::new(&mut group).tag(1, WireType::StartGroup);
        let mut reader = Reader::new(&group);
        assert_eq!(reader.next_tag(), None);
        assert!(!reader.ok());

        let mut truncated = Reader::new(&[0x0a, 0x05, b'a']);
        assert_eq!(
            truncated.next_tag(),
            Some(Tag {
                field_number: 1,
                wire_type: WireType::LengthDelimited
            })
        );
        assert_eq!(truncated.bytes(), None);
        assert!(!truncated.ok());
    }

    #[test]
    fn rejects_invalid_wire_type_and_oversized_length() {
        let mut invalid = Reader::new(&[0x0e]);
        assert_eq!(invalid.next_tag(), None);
        assert!(!invalid.ok());

        let mut oversized = Vec::new();
        Writer::new(&mut oversized).varint(make_tag(1, WireType::LengthDelimited) as u64);
        Writer::new(&mut oversized).varint(u64::MAX);
        let mut reader = Reader::new(&oversized);
        assert_eq!(
            reader.next_tag(),
            Some(Tag {
                field_number: 1,
                wire_type: WireType::LengthDelimited
            })
        );
        assert_eq!(reader.bytes(), None);
        assert!(!reader.ok());
    }

    #[test]
    fn zigzag_matches_known_values() {
        assert_eq!(zigzag_encode_i32(0), 0);
        assert_eq!(zigzag_encode_i32(-1), 1);
        assert_eq!(zigzag_encode_i32(1), 2);
        assert_eq!(zigzag_decode_i32(1), -1);
        assert_eq!(zigzag_decode_i64(3), -2);
    }
}
