const ALPHABET: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

pub fn encode(bytes: &[u8]) -> String {
    if bytes.is_empty() {
        return String::new();
    }

    let mut out = String::with_capacity(bytes.len().div_ceil(3) * 4);
    let mut chunks = bytes.chunks_exact(3);
    for chunk in &mut chunks {
        let v = ((chunk[0] as u32) << 16) | ((chunk[1] as u32) << 8) | chunk[2] as u32;
        out.push(ALPHABET[((v >> 18) & 0x3f) as usize] as char);
        out.push(ALPHABET[((v >> 12) & 0x3f) as usize] as char);
        out.push(ALPHABET[((v >> 6) & 0x3f) as usize] as char);
        out.push(ALPHABET[(v & 0x3f) as usize] as char);
    }

    match chunks.remainder() {
        [a] => {
            let v = (*a as u32) << 16;
            out.push(ALPHABET[((v >> 18) & 0x3f) as usize] as char);
            out.push(ALPHABET[((v >> 12) & 0x3f) as usize] as char);
            out.push('=');
            out.push('=');
        }
        [a, b] => {
            let v = ((*a as u32) << 16) | ((*b as u32) << 8);
            out.push(ALPHABET[((v >> 18) & 0x3f) as usize] as char);
            out.push(ALPHABET[((v >> 12) & 0x3f) as usize] as char);
            out.push(ALPHABET[((v >> 6) & 0x3f) as usize] as char);
            out.push('=');
        }
        [] => {}
        _ => unreachable!(),
    }

    out
}

pub fn decode(s: &str) -> Option<Vec<u8>> {
    let mut out = Vec::with_capacity((s.len() / 4) * 3 + 3);
    let mut acc = 0u32;
    let mut bits = 0u32;

    for b in s.bytes() {
        let v = value_of(b)?;
        let Some(v) = v else {
            continue;
        };
        acc = (acc << 6) | v as u32;
        bits += 6;
        if bits >= 8 {
            bits -= 8;
            out.push(((acc >> bits) & 0xff) as u8);
        }
    }

    Some(out)
}

fn value_of(b: u8) -> Option<Option<u8>> {
    match b {
        b'A'..=b'Z' => Some(Some(b - b'A')),
        b'a'..=b'z' => Some(Some(b - b'a' + 26)),
        b'0'..=b'9' => Some(Some(b - b'0' + 52)),
        b'+' | b'-' => Some(Some(62)),
        b'/' | b'_' => Some(Some(63)),
        b'=' | b' ' | b'\t' | b'\n' | b'\r' => Some(None),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn encodes_rfc4648_padding() {
        assert_eq!(encode(b""), "");
        assert_eq!(encode(b"f"), "Zg==");
        assert_eq!(encode(b"fo"), "Zm8=");
        assert_eq!(encode(b"foo"), "Zm9v");
        assert_eq!(encode(b"foobar"), "Zm9vYmFy");
    }

    #[test]
    fn decodes_standard_urlsafe_and_whitespace() {
        assert_eq!(decode("Zm9v YmFy"), Some(b"foobar".to_vec()));
        assert_eq!(decode("SGVsbG8td29ybGQ_"), Some(b"Hello-world?".to_vec()));
        assert_eq!(decode("Zg=="), Some(b"f".to_vec()));
    }

    #[test]
    fn invalid_character_fails() {
        assert_eq!(decode("Zm9v*"), None);
    }
}
