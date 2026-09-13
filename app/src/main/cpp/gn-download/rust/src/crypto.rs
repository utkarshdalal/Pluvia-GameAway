use aes::cipher::{
    block_padding::Pkcs7, BlockDecrypt, BlockDecryptMut, BlockEncrypt, BlockEncryptMut, KeyInit,
    KeyIvInit,
};
use aes::Aes256;
use hmac::{Hmac, Mac};
use rand::{rngs::OsRng, RngCore};
use rsa::pkcs8::DecodePublicKey;
use rsa::traits::PublicKeyParts;
use rsa::{Oaep, RsaPublicKey};
use sha1::{Digest as Sha1DigestTrait, Sha1};
use sha2::Sha256;
use zeroize::Zeroize;

pub const SESSION_KEY_LENGTH: usize = 32;
pub const AES_BLOCK_BYTES: usize = 16;
pub const SHA1_BYTES: usize = 20;
pub const SHA256_BYTES: usize = 32;
pub const HMAC_KEY_LENGTH: usize = 16;

pub type SessionKey = [u8; SESSION_KEY_LENGTH];
pub type Sha1Digest = [u8; SHA1_BYTES];
pub type Sha256Digest = [u8; SHA256_BYTES];
pub type AesBlock = [u8; AES_BLOCK_BYTES];

#[derive(Clone)]
pub struct SecureSessionKey {
    pub bytes: SessionKey,
}

impl SecureSessionKey {
    pub fn new(bytes: SessionKey) -> Self {
        Self { bytes }
    }
}

impl Drop for SecureSessionKey {
    fn drop(&mut self) {
        self.bytes.zeroize();
    }
}

pub fn secure_random_bytes(out: &mut [u8]) -> bool {
    OsRng.try_fill_bytes(out).is_ok()
}

pub fn generate_session_key() -> Option<SecureSessionKey> {
    let mut bytes = [0u8; SESSION_KEY_LENGTH];
    secure_random_bytes(&mut bytes).then_some(SecureSessionKey::new(bytes))
}

pub fn sha1(data: &[u8]) -> Sha1Digest {
    Sha1::digest(data).into()
}

pub fn sha256(data: &[u8]) -> Sha256Digest {
    Sha256::digest(data).into()
}

pub fn crc32(data: &[u8]) -> u32 {
    crc32fast::hash(data)
}

pub fn hmac_sha1(key: &[u8], data: &[u8]) -> Option<Sha1Digest> {
    let mut mac = <Hmac<Sha1> as Mac>::new_from_slice(key).ok()?;
    mac.update(data);
    Some(mac.finalize().into_bytes().into())
}

pub fn aes256_ecb_encrypt_block(key: &SessionKey, input: &AesBlock) -> Option<AesBlock> {
    let cipher = Aes256::new_from_slice(key).ok()?;
    let mut block = (*input).into();
    cipher.encrypt_block(&mut block);
    Some(block.into())
}

pub fn aes256_ecb_decrypt_block(key: &SessionKey, input: &AesBlock) -> Option<AesBlock> {
    let cipher = Aes256::new_from_slice(key).ok()?;
    let mut block = (*input).into();
    cipher.decrypt_block(&mut block);
    Some(block.into())
}

pub fn aes256_cbc_encrypt(key: &SessionKey, iv: &AesBlock, plaintext: &[u8]) -> Option<Vec<u8>> {
    Some(
        cbc::Encryptor::<Aes256>::new(key.into(), iv.into())
            .encrypt_padded_vec_mut::<Pkcs7>(plaintext),
    )
}

pub fn aes256_cbc_decrypt(key: &SessionKey, iv: &AesBlock, ciphertext: &[u8]) -> Option<Vec<u8>> {
    if ciphertext.is_empty() || !ciphertext.len().is_multiple_of(AES_BLOCK_BYTES) {
        return None;
    }
    cbc::Decryptor::<Aes256>::new(key.into(), iv.into())
        .decrypt_padded_vec_mut::<Pkcs7>(ciphertext)
        .ok()
}

pub fn rsa_oaep_sha1_encrypt(spki_der: &[u8], plaintext: &[u8]) -> Option<Vec<u8>> {
    let public_key = RsaPublicKey::from_public_key_der(spki_der).ok()?;
    let modulus = public_key.size();
    if modulus < 2 + 2 * SHA1_BYTES || plaintext.len() > modulus - 2 - 2 * SHA1_BYTES {
        return None;
    }
    public_key
        .encrypt(&mut OsRng, Oaep::new::<Sha1>(), plaintext)
        .ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hashes_match_known_values() {
        assert_eq!(
            hex(&sha1(b"abc")),
            "a9993e364706816aba3e25717850c26c9cd0d89d"
        );
        assert_eq!(
            hex(&sha256(b"abc")),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        );
        assert_eq!(crc32(b"123456789"), 0xcbf4_3926);
    }

    #[test]
    fn aes_cbc_roundtrips_with_pkcs7() {
        let key = [7u8; SESSION_KEY_LENGTH];
        let iv = [3u8; AES_BLOCK_BYTES];
        let plaintext = b"steam manifest filename";
        let encrypted = aes256_cbc_encrypt(&key, &iv, plaintext).unwrap();
        assert_ne!(encrypted, plaintext);
        let decrypted = aes256_cbc_decrypt(&key, &iv, &encrypted).unwrap();
        assert_eq!(decrypted, plaintext);
    }

    #[test]
    fn hmac_sha1_known_value() {
        assert_eq!(
            hex(&hmac_sha1(b"key", b"The quick brown fox jumps over the lazy dog").unwrap()),
            "de7c9b85b8b78aa6bc8a7a36f70a90701c9db4d9"
        );
    }

    fn hex(bytes: &[u8]) -> String {
        bytes.iter().map(|b| format!("{b:02x}")).collect()
    }
}
