use crate::pb::ccontentserverdirectory::CContentServerDirectoryServerInfo;
use flate2::read::{DeflateDecoder, GzDecoder};
use std::fs;
use std::io::Read;
use std::time::Duration;

pub const USER_AGENT: &str = "Valve/Steam HTTP Client 1.0";

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct CdnManifestResult {
    pub raw_manifest: Vec<u8>,
    pub error: String,
    pub http_status: i32,
}

impl CdnManifestResult {
    pub fn ok(&self) -> bool {
        self.error.is_empty() && !self.raw_manifest.is_empty()
    }
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct CdnChunkResult {
    pub data: Vec<u8>,
    pub error: String,
    pub http_status: i32,
}

pub struct CdnConnection {
    client: Option<reqwest::blocking::Client>,
    valid: bool,
}

impl Default for CdnConnection {
    fn default() -> Self {
        Self::new()
    }
}

impl CdnConnection {
    pub fn new() -> Self {
        Self {
            client: None,
            valid: true,
        }
    }

    pub fn invalid() -> Self {
        Self {
            client: None,
            valid: false,
        }
    }

    pub fn valid(&self) -> bool {
        self.valid
    }
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct CdnClient {
    ca_bundle_path: String,
}

impl CdnClient {
    pub fn new(ca_bundle_path: impl Into<String>) -> Self {
        Self {
            ca_bundle_path: ca_bundle_path.into(),
        }
    }

    pub fn ca_bundle_path(&self) -> &str {
        &self.ca_bundle_path
    }

    pub fn open_connection(&self) -> CdnConnection {
        CdnConnection::new()
    }

    pub fn build_manifest_url(
        &self,
        server: &CContentServerDirectoryServerInfo,
        depot_id: u32,
        manifest_id: u64,
        request_code: u64,
        cdn_auth_token: &str,
    ) -> Result<String, String> {
        let host = preferred_host(server).ok_or_else(|| "cdn server has no host".to_string())?;
        let mut url = format!(
            "{}://{}:{}/depot/{}/manifest/{}/5",
            if server.use_https() { "https" } else { "http" },
            host,
            server.port(),
            depot_id,
            manifest_id
        );
        if request_code != 0 {
            url.push('/');
            url.push_str(&request_code.to_string());
        }
        append_auth_query(&mut url, cdn_auth_token);
        Ok(url)
    }

    pub fn build_chunk_url(
        &self,
        server: &CContentServerDirectoryServerInfo,
        depot_id: u32,
        chunk_sha: &[u8],
        cdn_auth_token: &str,
    ) -> Result<String, String> {
        if chunk_sha.is_empty() {
            return Err("empty chunk sha".to_string());
        }
        let host = preferred_host(server).ok_or_else(|| "cdn server has no host".to_string())?;
        let mut url = format!(
            "{}://{}:{}/depot/{}/chunk/{}",
            if server.use_https() { "https" } else { "http" },
            host,
            server.port(),
            depot_id,
            hex_encode(chunk_sha)
        );
        append_auth_query(&mut url, cdn_auth_token);
        Ok(url)
    }

    pub fn build_item_def_archive_url(app_id: u32, digest: &str) -> String {
        format!(
            "https://api.steampowered.com/IGameInventory/GetItemDefArchive/v1/?appid={app_id}&digest={}",
            percent_encode_query_value(digest)
        )
    }

    pub fn fetch_manifest(
        &self,
        server: &CContentServerDirectoryServerInfo,
        depot_id: u32,
        manifest_id: u64,
        request_code: u64,
        cdn_auth_token: &str,
        timeout: Duration,
    ) -> CdnManifestResult {
        let url = match self.build_manifest_url(
            server,
            depot_id,
            manifest_id,
            request_code,
            cdn_auth_token,
        ) {
            Ok(url) => url,
            Err(error) => {
                return CdnManifestResult {
                    error,
                    ..Default::default()
                }
            }
        };
        match self.http_get(&url, timeout) {
            Ok(response) => CdnClient::validate_manifest_response(
                response.http_status,
                response.body,
                response.content_length,
            ),
            Err(error) => CdnManifestResult {
                error,
                ..Default::default()
            },
        }
    }

    pub fn fetch_chunk(
        &self,
        server: &CContentServerDirectoryServerInfo,
        depot_id: u32,
        chunk_sha: &[u8],
        cdn_auth_token: &str,
        timeout: Duration,
    ) -> CdnChunkResult {
        let url = match self.build_chunk_url(server, depot_id, chunk_sha, cdn_auth_token) {
            Ok(url) => url,
            Err(error) => {
                return CdnChunkResult {
                    error,
                    ..Default::default()
                }
            }
        };
        match self.http_get(&url, timeout) {
            Ok(response) => CdnClient::validate_chunk_response(
                response.http_status,
                response.body,
                response.content_length,
            ),
            Err(error) => CdnChunkResult {
                error,
                ..Default::default()
            },
        }
    }

    pub fn fetch_chunk_with_connection(
        &self,
        conn: &mut CdnConnection,
        server: &CContentServerDirectoryServerInfo,
        depot_id: u32,
        chunk_sha: &[u8],
        cdn_auth_token: &str,
        timeout: Duration,
    ) -> CdnChunkResult {
        let url = match self.build_chunk_url(server, depot_id, chunk_sha, cdn_auth_token) {
            Ok(url) => url,
            Err(error) => {
                return CdnChunkResult {
                    error,
                    ..Default::default()
                }
            }
        };
        let client = match self.ensure_connection(conn) {
            Ok(client) => client,
            Err(error) => {
                return CdnChunkResult {
                    error,
                    ..Default::default()
                }
            }
        };
        match self.http_get_with_client(client, &url, timeout) {
            Ok(response) => CdnClient::validate_chunk_response(
                response.http_status,
                response.body,
                response.content_length,
            ),
            Err(error) => CdnChunkResult {
                error,
                ..Default::default()
            },
        }
    }

    pub fn fetch_item_def_archive(
        &self,
        app_id: u32,
        digest: &str,
        timeout: Duration,
    ) -> Option<Vec<u8>> {
        let url = CdnClient::build_item_def_archive_url(app_id, digest);
        let response = self.http_get(&url, timeout).ok()?;
        if response.http_status != 200 {
            return None;
        }
        Some(CdnClient::strip_item_def_trailing_nul(response.body))
    }

    /// HTTP-delivered PICS appinfo URL (large appinfo the CM didn't inline).
    pub fn build_appinfo_url(http_host: &str, app_id: u32, sha: &[u8]) -> String {
        format!(
            "http://{}/appinfo/{}/sha/{}.txt.gz",
            http_host,
            app_id,
            hex_encode(sha)
        )
    }

    /// Fetch + gunzip an HTTP-delivered appinfo blob (same VDF as inline `buffer`).
    pub fn fetch_appinfo_with_connection(
        &self,
        conn: &mut CdnConnection,
        http_host: &str,
        app_id: u32,
        sha: &[u8],
        timeout: Duration,
    ) -> Option<Vec<u8>> {
        if http_host.is_empty() || sha.is_empty() {
            return None;
        }
        let url = CdnClient::build_appinfo_url(http_host, app_id, sha);
        let client = self.ensure_connection(conn).ok()?;
        let response = self.http_get_with_client(client, &url, timeout).ok()?;
        if response.http_status != 200 || response.body.is_empty() {
            return None;
        }
        Some(maybe_gunzip(response.body))
    }

    pub fn validate_manifest_response(
        http_status: i32,
        body: Vec<u8>,
        content_length: Option<u64>,
    ) -> CdnManifestResult {
        if http_status != 200 {
            return CdnManifestResult {
                http_status,
                error: format!("non-200 HTTP status ({http_status})"),
                ..Default::default()
            };
        }
        if let Some(expected) = content_length {
            if body.len() as u64 != expected {
                return CdnManifestResult {
                    http_status,
                    error: "manifest body truncated (length mismatch)".to_string(),
                    ..Default::default()
                };
            }
        }
        let Some(raw_manifest) = unzip_first_entry(&body) else {
            return CdnManifestResult {
                http_status,
                error: "manifest unzip failed".to_string(),
                ..Default::default()
            };
        };
        CdnManifestResult {
            raw_manifest,
            error: String::new(),
            http_status,
        }
    }

    pub fn validate_chunk_response(
        http_status: i32,
        body: Vec<u8>,
        content_length: Option<u64>,
    ) -> CdnChunkResult {
        if http_status != 200 {
            return CdnChunkResult {
                http_status,
                error: format!("non-200 HTTP status ({http_status})"),
                ..Default::default()
            };
        }
        if let Some(expected) = content_length {
            if body.len() as u64 != expected {
                return CdnChunkResult {
                    http_status,
                    error: "chunk body truncated (length mismatch)".to_string(),
                    ..Default::default()
                };
            }
        }
        CdnChunkResult {
            data: body,
            error: String::new(),
            http_status,
        }
    }

    pub fn validate_connection(conn: &CdnConnection) -> Result<(), CdnChunkResult> {
        if conn.valid() {
            Ok(())
        } else {
            Err(CdnChunkResult {
                error: "cdn connection invalid".to_string(),
                ..Default::default()
            })
        }
    }

    pub fn strip_item_def_trailing_nul(mut body: Vec<u8>) -> Vec<u8> {
        if body.last() == Some(&0) {
            body.pop();
        }
        body
    }

    pub fn default_timeout() -> Duration {
        Duration::from_secs(30)
    }

    pub fn connect_timeout() -> Duration {
        Duration::from_secs(15)
    }

    fn http_get(&self, url: &str, timeout: Duration) -> Result<HttpResponse, String> {
        let client = self.http_client()?;
        self.http_get_with_client(&client, url, timeout)
    }

    fn http_get_with_client(
        &self,
        client: &reqwest::blocking::Client,
        url: &str,
        timeout: Duration,
    ) -> Result<HttpResponse, String> {
        let response = client
            .get(url)
            .timeout(timeout)
            .send()
            .map_err(|err| format!("http get: {}", err.without_url()))?;
        let http_status = response.status().as_u16() as i32;
        let content_length = response.content_length();
        let body = response
            .bytes()
            .map_err(|err| format!("http body: {}", err.without_url()))?
            .to_vec();
        Ok(HttpResponse {
            http_status,
            content_length,
            body,
        })
    }

    fn http_client(&self) -> Result<reqwest::blocking::Client, String> {
        let mut builder = reqwest::blocking::Client::builder()
            .user_agent(USER_AGENT)
            .connect_timeout(CdnClient::connect_timeout());
        if !self.ca_bundle_path.is_empty() {
            let pem =
                fs::read(&self.ca_bundle_path).map_err(|err| format!("read CA bundle: {err}"))?;
            let certs = reqwest::Certificate::from_pem_bundle(&pem)
                .map_err(|err| format!("parse CA bundle: {err}"))?;
            for cert in certs {
                builder = builder.add_root_certificate(cert);
            }
        }
        builder.build().map_err(|err| format!("http client: {err}"))
    }

    fn ensure_connection<'a>(
        &self,
        conn: &'a mut CdnConnection,
    ) -> Result<&'a reqwest::blocking::Client, String> {
        if let Err(result) = CdnClient::validate_connection(conn) {
            return Err(result.error);
        }
        if conn.client.is_none() {
            conn.client = Some(self.http_client()?);
        }
        conn.client
            .as_ref()
            .ok_or_else(|| "cdn connection not initialized".to_string())
    }
}

struct HttpResponse {
    http_status: i32,
    content_length: Option<u64>,
    body: Vec<u8>,
}

// ─────────────────────────────────────────────────────────────────────────────────────────────
// Async fetch client (B2b) — the depot chunk-download fetch layer ONLY.
//
// The blocking `CdnClient` above is UNCHANGED and still owns manifest fetches and the
// single-connection depot path. This async client is used only by the parallel depot writer's fetch
// driver, which keeps many lightweight requests in flight on one tokio current-thread runtime. A
// SINGLE pooled `reqwest::Client` is shared by every in-flight request so "many concurrent" reuses
// keep-alive connections instead of opening thousands (`pool_max_idle_per_host` matches the per-host
// concurrency cap). URL formation and the 200/content-length validation are shared with the blocking
// client (`build_chunk_url`) so the two paths fetch byte-identically.
// ─────────────────────────────────────────────────────────────────────────────────────────────

/// Classified fetch failure so the fetch driver can back off proportionally (429/5xx/timeout/reset
/// shrink the window + cool the host harder than a one-off protocol error).
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum FetchFailKind {
    Timeout,
    RateLimited,
    ServerFault,
    Connect,
    Other,
}

#[derive(Clone, Debug)]
pub struct AsyncFetchError {
    pub message: String,
    pub kind: FetchFailKind,
}

/// Hard cap for one whole-body response: a CDN that omits or lies about
/// Content-Length must not make us buffer an unbounded body (OOM). Real chunks
/// and whole-body items are far below this.
pub(crate) const MAX_WHOLE_BODY_BYTES: u64 = 128 * 1024 * 1024;

/// Read a response body incrementally, rejecting it as soon as it exceeds `cap`.
/// Prefer this over `response.bytes()` for any server-supplied body.
/// `idle` bounds the wait between pieces, NOT the whole body — a slow link moving
/// bytes steadily must never hit a total-transfer deadline.
pub(crate) async fn read_body_capped(
    mut response: reqwest::Response,
    cap: u64,
    idle: Option<Duration>,
) -> Result<Vec<u8>, AsyncFetchError> {
    if let Some(len) = response.content_length() {
        if len > cap {
            return Err(AsyncFetchError {
                message: format!("response too large ({len} bytes exceeds cap)"),
                kind: FetchFailKind::ServerFault,
            });
        }
    }
    let mut body = Vec::new();
    let mut offset: u64 = 0;
    loop {
        let piece = match idle {
            Some(limit) => match tokio::time::timeout(limit, response.chunk()).await {
                Ok(res) => res,
                Err(_) => {
                    return Err(AsyncFetchError {
                        message: format!("http body: idle timeout at offset {offset}"),
                        kind: FetchFailKind::Timeout,
                    });
                }
            },
            None => response.chunk().await,
        };
        match piece {
            Ok(Some(piece)) => {
                offset += piece.len() as u64;
                body.extend_from_slice(&piece);
                if body.len() as u64 > cap {
                    return Err(AsyncFetchError {
                        message: "response too large (body exceeds cap)".to_string(),
                        kind: FetchFailKind::ServerFault,
                    });
                }
            }
            Ok(None) => break,
            Err(err) => {
                let kind = if err.is_timeout() {
                    FetchFailKind::Timeout
                } else {
                    FetchFailKind::Other
                };
                return Err(AsyncFetchError {
                    message: format!("http body: {}", err.without_url()),
                    kind,
                });
            }
        }
    }
    Ok(body)
}

/// One pooled async client shared by all in-flight chunk fetches for a depot.
pub struct AsyncCdnClient {
    client: reqwest::Client,
}

impl AsyncCdnClient {
    /// Build the shared pooled client. `pool_max_idle_per_host` is set to the per-host concurrency
    /// cap so concurrent requests to one CDN host reuse keep-alive connections. CA-bundle handling
    /// mirrors the blocking client exactly.
    pub fn new(ca_bundle_path: &str, pool_max_idle_per_host: usize) -> Result<Self, String> {
        let mut builder = reqwest::Client::builder()
            .user_agent(USER_AGENT)
            .connect_timeout(CdnClient::connect_timeout())
            .pool_max_idle_per_host(pool_max_idle_per_host.max(1))
            .pool_idle_timeout(Duration::from_secs(90));
        if !ca_bundle_path.is_empty() {
            let pem =
                fs::read(ca_bundle_path).map_err(|err| format!("read CA bundle: {err}"))?;
            let certs = reqwest::Certificate::from_pem_bundle(&pem)
                .map_err(|err| format!("parse CA bundle: {err}"))?;
            for cert in certs {
                builder = builder.add_root_certificate(cert);
            }
        }
        let client = builder
            .build()
            .map_err(|err| format!("http client: {err}"))?;
        Ok(Self { client })
    }

    /// Fetch ONE chunk from a pre-built URL (a single attempt; the caller owns retry/rotation).
    /// Returns the validated raw (still-encrypted, still-compressed) bytes, or a classified error.
    pub async fn fetch_url_async(
        &self,
        url: &str,
        timeout: Duration,
    ) -> Result<Vec<u8>, AsyncFetchError> {
        let response = match self.client.get(url).timeout(timeout).send().await {
            Ok(response) => response,
            Err(err) => {
                let kind = if err.is_timeout() {
                    FetchFailKind::Timeout
                } else if err.is_connect() {
                    FetchFailKind::Connect
                } else {
                    FetchFailKind::Other
                };
                return Err(AsyncFetchError {
                    // without_url(): reqwest embeds the request URL in Display; signed
                    // CDN query credentials must not reach logs or error_slot.
                    message: format!("http get: {}", err.without_url()),
                    kind,
                });
            }
        };
        let status = response.status().as_u16() as i32;
        if status != 200 {
            let kind = if status == 429 {
                FetchFailKind::RateLimited
            } else if (500..600).contains(&status) {
                FetchFailKind::ServerFault
            } else {
                FetchFailKind::Other
            };
            return Err(AsyncFetchError {
                message: format!("non-200 HTTP status ({status})"),
                kind,
            });
        }
        let content_length = response.content_length();
        let body = match read_body_capped(response, MAX_WHOLE_BODY_BYTES, None).await {
            Ok(body) => body,
            Err(err) => return Err(err),
        };
        if let Some(expected) = content_length {
            if body.len() as u64 != expected {
                return Err(AsyncFetchError {
                    message: "chunk body truncated (length mismatch)".to_string(),
                    kind: FetchFailKind::ServerFault,
                });
            }
        }
        Ok(body)
    }
}

impl CdnChunkResult {
    pub fn ok(&self) -> bool {
        self.error.is_empty() && !self.data.is_empty()
    }
}

pub fn unzip_first_entry(zip: &[u8]) -> Option<Vec<u8>> {
    if zip.len() < 30 || read_u32(zip, 0)? != 0x0403_4b50 {
        return None;
    }
    let flags = read_u16(zip, 6)?;
    let method = read_u16(zip, 8)?;
    let comp_size = read_u32(zip, 18)? as usize;
    let uncomp_size = read_u32(zip, 22)? as usize;
    let name_len = read_u16(zip, 26)? as usize;
    let extra_len = read_u16(zip, 28)? as usize;

    if (flags & 0x08) != 0 {
        return None;
    }

    let data_off = 30usize.checked_add(name_len)?.checked_add(extra_len)?;
    if data_off.checked_add(comp_size)? > zip.len() {
        return None;
    }
    let data = &zip[data_off..data_off + comp_size];

    match method {
        0 => Some(data.to_vec()),
        8 => {
            let mut out = Vec::with_capacity(uncomp_size);
            DeflateDecoder::new(data).read_to_end(&mut out).ok()?;
            Some(out)
        }
        _ => None,
    }
}

/// Gunzip; pass through already-inflated bytes (edge caches may decompress).
pub fn maybe_gunzip(body: Vec<u8>) -> Vec<u8> {
    if body.len() >= 2 && body[0] == 0x1f && body[1] == 0x8b {
        let mut out = Vec::new();
        if GzDecoder::new(&body[..]).read_to_end(&mut out).is_ok() && !out.is_empty() {
            return out;
        }
    }
    body
}

pub fn hex_encode(bytes: &[u8]) -> String {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let mut out = String::with_capacity(bytes.len() * 2);
    for b in bytes {
        out.push(HEX[(b >> 4) as usize] as char);
        out.push(HEX[(b & 0x0f) as usize] as char);
    }
    out
}

fn preferred_host(server: &CContentServerDirectoryServerInfo) -> Option<&str> {
    if !server.vhost.is_empty() {
        Some(&server.vhost)
    } else if !server.host.is_empty() {
        Some(&server.host)
    } else {
        None
    }
}

fn append_auth_query(url: &mut String, cdn_auth_token: &str) {
    if cdn_auth_token.is_empty() {
        return;
    }
    url.push('?');
    url.push_str(cdn_auth_token.strip_prefix('?').unwrap_or(cdn_auth_token));
}

fn percent_encode_query_value(value: &str) -> String {
    let mut out = String::with_capacity(value.len());
    for byte in value.bytes() {
        match byte {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(byte as char);
            }
            _ => {
                const HEX: &[u8; 16] = b"0123456789ABCDEF";
                out.push('%');
                out.push(HEX[(byte >> 4) as usize] as char);
                out.push(HEX[(byte & 0x0f) as usize] as char);
            }
        }
    }
    out
}

fn read_u16(buf: &[u8], off: usize) -> Option<u16> {
    Some(u16::from_le_bytes(buf.get(off..off + 2)?.try_into().ok()?))
}

fn read_u32(buf: &[u8], off: usize) -> Option<u32> {
    Some(u32::from_le_bytes(buf.get(off..off + 4)?.try_into().ok()?))
}

#[cfg(test)]
mod tests {
    use super::*;
    use flate2::{write::DeflateEncoder, Compression};
    use std::io::Write;

    #[test]
    fn extracts_stored_zip_entry() {
        let zip = zip_with_entry(0, b"manifest bytes");
        assert_eq!(unzip_first_entry(&zip), Some(b"manifest bytes".to_vec()));
    }

    #[test]
    fn extracts_deflated_zip_entry() {
        let mut encoder = DeflateEncoder::new(Vec::new(), Compression::default());
        encoder.write_all(b"manifest bytes").unwrap();
        let deflated = encoder.finish().unwrap();
        let zip = zip_with_compressed_entry(8, &deflated, b"manifest bytes".len() as u32);
        assert_eq!(unzip_first_entry(&zip), Some(b"manifest bytes".to_vec()));
    }

    #[test]
    fn rejects_data_descriptor_zip_entry() {
        let mut zip = zip_with_entry(0, b"x");
        zip[6] = 0x08;
        assert_eq!(unzip_first_entry(&zip), None);
    }

    #[test]
    fn hex_encodes_lowercase() {
        assert_eq!(hex_encode(&[0, 1, 0xab, 0xff]), "0001abff");
    }

    #[test]
    fn builds_http_appinfo_url() {
        assert_eq!(
            CdnClient::build_appinfo_url("cache1.steamcontent.com", 601150, &[0xab, 0xcd, 0x01]),
            "http://cache1.steamcontent.com/appinfo/601150/sha/abcd01.txt.gz"
        );
    }

    #[test]
    fn gunzips_gzip_and_passes_through_plain_vdf() {
        use flate2::{write::GzEncoder, Compression};
        use std::io::Write;
        let mut encoder = GzEncoder::new(Vec::new(), Compression::default());
        encoder.write_all(b"\"appinfo\"{}").unwrap();
        let gz = encoder.finish().unwrap();
        assert_eq!(maybe_gunzip(gz), b"\"appinfo\"{}");
        // Already-inflated payload (gzip magic absent) is returned verbatim.
        assert_eq!(maybe_gunzip(b"\"appinfo\"{}".to_vec()), b"\"appinfo\"{}");
    }

    #[test]
    fn builds_manifest_and_chunk_urls_like_cpp() {
        let client = CdnClient::new("/cacert.pem");
        let server = CContentServerDirectoryServerInfo {
            host: "edge.steamcontent.com".into(),
            vhost: "cdn.example".into(),
            https_support: "mandatory".into(),
            ..Default::default()
        };
        assert_eq!(
            client
                .build_manifest_url(&server, 100, 200, 300, "?token=abc")
                .unwrap(),
            "https://cdn.example:443/depot/100/manifest/200/5/300?token=abc"
        );
        assert_eq!(
            client
                .build_chunk_url(&server, 100, &[0xab, 0xcd], "token=abc")
                .unwrap(),
            "https://cdn.example:443/depot/100/chunk/abcd?token=abc"
        );
        assert_eq!(client.ca_bundle_path(), "/cacert.pem");
        assert_eq!(CdnClient::default_timeout(), Duration::from_secs(30));
        assert_eq!(CdnClient::connect_timeout(), Duration::from_secs(15));
    }

    #[test]
    fn validates_http_status_lengths_and_manifest_zip() {
        let zip = zip_with_entry(0, b"manifest bytes");
        let ok = CdnClient::validate_manifest_response(200, zip.clone(), Some(zip.len() as u64));
        assert!(ok.ok());
        assert_eq!(ok.raw_manifest, b"manifest bytes");

        let short = CdnClient::validate_manifest_response(200, zip, Some(999));
        assert_eq!(short.error, "manifest body truncated (length mismatch)");

        let chunk = CdnClient::validate_chunk_response(200, vec![1, 2, 3], Some(3));
        assert!(chunk.ok());
        assert_eq!(
            CdnClient::validate_chunk_response(404, vec![], None).error,
            "non-200 HTTP status (404)"
        );
        assert_eq!(
            CdnClient::validate_manifest_response(401, vec![], None).error,
            "non-200 HTTP status (401)"
        );
    }

    #[test]
    fn itemdef_url_escapes_digest_and_strips_nul() {
        assert_eq!(
            CdnClient::build_item_def_archive_url(480, "abc+/="),
            "https://api.steampowered.com/IGameInventory/GetItemDefArchive/v1/?appid=480&digest=abc%2B%2F%3D"
        );
        assert_eq!(
            CdnClient::strip_item_def_trailing_nul(b"[{\"x\":1}]\0".to_vec()),
            b"[{\"x\":1}]"
        );
    }

    #[test]
    fn persistent_connection_state_matches_cpp_validity_contract() {
        assert!(CdnClient::validate_connection(&CdnConnection::new()).is_ok());
        assert_eq!(
            CdnClient::validate_connection(&CdnConnection::invalid())
                .unwrap_err()
                .error,
            "cdn connection invalid"
        );
    }

    #[test]
    fn persistent_connection_lazily_initializes_and_reuses_client() {
        let client = CdnClient::new("");
        let server = CContentServerDirectoryServerInfo {
            host: "127.0.0.1".into(),
            ..Default::default()
        };
        let mut conn = client.open_connection();
        assert!(conn.client.is_none());

        let _ = client.fetch_chunk_with_connection(
            &mut conn,
            &server,
            100,
            &[0xab, 0xcd],
            "",
            Duration::from_millis(50),
        );
        assert!(conn.client.is_some());

        let first_client = conn
            .client
            .as_ref()
            .map(|inner| inner as *const reqwest::blocking::Client)
            .unwrap();
        let _ = client.fetch_chunk_with_connection(
            &mut conn,
            &server,
            100,
            &[0xab, 0xcd],
            "",
            Duration::from_millis(50),
        );
        let second_client = conn
            .client
            .as_ref()
            .map(|inner| inner as *const reqwest::blocking::Client)
            .unwrap();
        assert_eq!(first_client, second_client);
    }

    fn zip_with_entry(method: u16, data: &[u8]) -> Vec<u8> {
        zip_with_compressed_entry(method, data, data.len() as u32)
    }

    fn zip_with_compressed_entry(method: u16, data: &[u8], uncomp_size: u32) -> Vec<u8> {
        let mut out = Vec::new();
        out.extend_from_slice(&0x0403_4b50u32.to_le_bytes());
        out.extend_from_slice(&20u16.to_le_bytes());
        out.extend_from_slice(&0u16.to_le_bytes());
        out.extend_from_slice(&method.to_le_bytes());
        out.extend_from_slice(&0u16.to_le_bytes());
        out.extend_from_slice(&0u16.to_le_bytes());
        out.extend_from_slice(&0u32.to_le_bytes());
        out.extend_from_slice(&(data.len() as u32).to_le_bytes());
        out.extend_from_slice(&uncomp_size.to_le_bytes());
        out.extend_from_slice(&1u16.to_le_bytes());
        out.extend_from_slice(&0u16.to_le_bytes());
        out.extend_from_slice(b"x");
        out.extend_from_slice(data);
        out
    }
}
