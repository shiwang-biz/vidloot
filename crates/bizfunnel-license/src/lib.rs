//! # bizfunnel-license
//!
//! License client for Bizfunnel License Manager, made for Tauri apps (works in any Rust app).
//!
//! * Binds a license key to this device (a SHA-256 of the OS machine id + product slug).
//! * Verifies every server answer with your **Ed25519 public key**, so a fake server or an
//!   edited license file cannot unlock the app.
//! * Works offline: the signed local key is trusted until `next_check`, then the app keeps
//!   running until `grace_until` if the server can't be reached.
//! * A definite "suspended / expired / revoked" answer locks the app straight away.
//!
//! ```no_run
//! use bizfunnel_license::{Config, LicenseClient};
//! # async fn demo() -> Result<(), bizfunnel_license::Error> {
//! let client = LicenseClient::new(Config::new(
//!     "https://licenses.example.com/api/v1",
//!     "pos-desktop",
//!     [("k1", "BASE64_PUBLIC_KEY_FROM_SETTINGS")],
//!     "/path/to/app-data/license.json",
//! ).app_version("3.4.1"))?;
//!
//! // First run: the user pastes their key.
//! let status = client.activate("POS-80W7B-AD8XX-VY6E2-KQRJB").await?;
//! // Every start-up (cheap: no network until the re-check is due):
//! let status = client.check(false).await;
//! if !status.valid { /* show the activation screen with status.message */ }
//! if status.has_feature("offline_mode") { /* … */ }
//! # Ok(()) }
//! ```

use base64::engine::general_purpose::{STANDARD, URL_SAFE_NO_PAD};
use base64::Engine;
use ed25519_dalek::{Signature, Verifier, VerifyingKey};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::fs;
use std::path::PathBuf;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("invalid configuration: {0}")]
    Config(String),
    #[error("could not read the machine id: {0}")]
    MachineId(String),
    #[error("license server could not be reached: {0}")]
    Network(String),
    #[error("the license server's answer could not be verified")]
    Untrusted,
    #[error("storage error: {0}")]
    Storage(String),
}

/// Client settings. Build with [`Config::new`] and the chainable setters.
#[derive(Clone, Debug)]
pub struct Config {
    pub api_url: String,
    pub product: String,
    pub public_keys: HashMap<String, String>,
    pub storage_path: PathBuf,
    pub app_version: Option<String>,
    pub device_label: Option<String>,
    pub platform: String,
    pub timeout: Duration,
    /// Override the machine id (tests, or apps with their own device id). Hashed before use.
    pub machine_id_override: Option<String>,
}

impl Config {
    pub fn new<K, V>(api_url: &str, product: &str, public_keys: impl IntoIterator<Item = (K, V)>, storage_path: impl Into<PathBuf>) -> Self
    where
        K: Into<String>,
        V: Into<String>,
    {
        Self {
            api_url: api_url.trim_end_matches('/').to_string(),
            product: product.to_string(),
            public_keys: public_keys.into_iter().map(|(k, v)| (k.into(), v.into())).collect(),
            storage_path: storage_path.into(),
            app_version: None,
            device_label: None,
            platform: std::env::consts::OS.to_string(),
            timeout: Duration::from_secs(10),
            machine_id_override: None,
        }
    }
    pub fn app_version(mut self, v: &str) -> Self { self.app_version = Some(v.into()); self }
    pub fn device_label(mut self, v: &str) -> Self { self.device_label = Some(v.into()); self }
    pub fn timeout(mut self, d: Duration) -> Self { self.timeout = d; self }
    pub fn machine_id_override(mut self, v: &str) -> Self { self.machine_id_override = Some(v.into()); self }
}

/// The signed license payload ("local key") issued by the server.
#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct Claims {
    pub lid: u64,
    pub pid: String,
    pub plan: String,
    #[serde(default)]
    pub term: String,
    #[serde(default)]
    pub features: Vec<String>,
    #[serde(default)]
    pub limits: HashMap<String, i64>,
    pub status: String,
    pub exp: Option<u64>,
    pub updates_until: Option<String>,
    #[serde(default)]
    pub licensed_to: String,
    pub iat: u64,
    pub next_check: u64,
    pub grace_until: u64,
    pub bind: Bind,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct Bind {
    #[serde(rename = "type")]
    pub kind: String,
    pub value: String,
}

/// Result of a check. `status` is one of: active, trial, grace, suspended, expired, revoked,
/// invalid, limit_reached, not_activated, binding_mismatch, rate_limited, no_key, error.
#[derive(Clone, Debug, Serialize)]
pub struct LicenseStatus {
    pub valid: bool,
    pub status: String,
    pub message: String,
    pub claims: Option<Claims>,
}

impl LicenseStatus {
    fn new(valid: bool, status: &str, message: impl Into<String>, claims: Option<Claims>) -> Self {
        Self { valid, status: status.into(), message: message.into(), claims }
    }
    pub fn has_feature(&self, flag: &str) -> bool {
        self.claims.as_ref().is_some_and(|c| c.features.iter().any(|f| f == flag))
    }
    pub fn limit(&self, name: &str) -> Option<i64> {
        self.claims.as_ref().and_then(|c| c.limits.get(name).copied())
    }
    pub fn in_grace(&self) -> bool { self.status == "grace" }
}

/// A newer build this license may install.
#[derive(Clone, Debug, Serialize)]
pub struct UpdateInfo {
    pub version: String,
    pub url: String,
    pub sha256: Option<String>,
    pub notes: String,
    pub released_at: String,
    pub size: Option<u64>,
}

#[derive(Serialize, Deserialize, Default)]
struct Stored {
    license_key: String,
    local_key: Option<String>,
}

#[derive(Deserialize)]
struct ApiReply {
    #[serde(default)]
    message: String,
    local_key: Option<String>,
    response: Option<String>,
    #[serde(default)]
    notes: String,
    released_at: Option<String>,
    size: Option<u64>,
}

pub struct LicenseClient {
    cfg: Config,
    http: reqwest::Client,
    keys: HashMap<String, VerifyingKey>,
}

impl LicenseClient {
    pub fn new(cfg: Config) -> Result<Self, Error> {
        if cfg.public_keys.is_empty() {
            return Err(Error::Config("at least one public key is required".into()));
        }
        let mut keys = HashMap::new();
        for (kid, b64) in &cfg.public_keys {
            let raw = STANDARD.decode(b64.trim()).map_err(|_| Error::Config(format!("public key {kid} is not base64")))?;
            let arr: [u8; 32] = raw.try_into().map_err(|_| Error::Config(format!("public key {kid} must be 32 bytes")))?;
            keys.insert(kid.clone(), VerifyingKey::from_bytes(&arr).map_err(|_| Error::Config(format!("public key {kid} is invalid")))?);
        }
        let http = reqwest::Client::builder()
            .timeout(cfg.timeout)
            .user_agent(concat!("bizfunnel-license-rs/", env!("CARGO_PKG_VERSION")))
            .build()
            .map_err(|e| Error::Config(e.to_string()))?;
        Ok(Self { cfg, http, keys })
    }

    /// Stable id for this device and product (never the raw OS id).
    pub fn machine_id(&self) -> Result<String, Error> {
        let raw = match &self.cfg.machine_id_override {
            Some(v) => v.clone(),
            None => machine_uid::get().map_err(|e| Error::MachineId(e.to_string()))?,
        };
        Ok(hex::encode(Sha256::digest(format!("{}|{}", self.cfg.product, raw.trim()).as_bytes())))
    }

    /// What the server stores for this device: SHA-256 of "<product>|<machine_id>".
    fn bound_value(&self) -> Result<String, Error> {
        Ok(hex::encode(Sha256::digest(format!("{}|{}", self.cfg.product, self.machine_id()?).as_bytes())))
    }

    /// The key the user activated with, if any.
    pub fn stored_key(&self) -> Option<String> {
        self.load().map(|s| s.license_key).filter(|k| !k.is_empty())
    }

    /// Activate a key on this device (first run, or after "Change license").
    pub async fn activate(&self, license_key: &str) -> Result<LicenseStatus, Error> {
        let key = license_key.trim().to_uppercase();
        let reply = self.call("activate", &key, &[]).await?;
        self.finish(&key, reply)
    }

    /// Check the stored license. No network call until the re-check is due (or `force_online`).
    /// Never returns an error: problems come back as a non-valid status with a message.
    pub async fn check(&self, force_online: bool) -> LicenseStatus {
        let Some(stored) = self.load() else {
            return LicenseStatus::new(false, "no_key", "Enter your license key to activate.", None);
        };
        let local = stored.local_key.as_deref().and_then(|t| self.verify_claims(t));
        let now = now();
        if let Some(c) = &local {
            if !force_online && now < c.next_check {
                return self.status_from_claims(c.clone(), false);
            }
        }
        let endpoint = if local.is_some() { "validate" } else { "activate" };
        let mut reply = self.call(endpoint, &stored.license_key, &[]).await;
        if matches!(&reply, Ok((s, _)) if s == "not_activated") {
            reply = self.call("activate", &stored.license_key, &[]).await;
        }
        match reply {
            Ok(r) => match self.finish(&stored.license_key, r) {
                Ok(s) => s,
                Err(e) => LicenseStatus::new(false, "error", e.to_string(), None),
            },
            Err(e) => match local {
                Some(c) if now < c.grace_until => self.status_from_claims(c, true),
                _ => LicenseStatus::new(false, "error", format!("Could not verify the license: {e}"), None),
            },
        }
    }

    /// Free this device's seat (e.g. a "Deactivate this computer" button) and forget the key.
    pub async fn deactivate(&self) -> Result<LicenseStatus, Error> {
        let key = self.stored_key().ok_or_else(|| Error::Storage("no license stored".into()))?;
        let (status, reply) = self.call("deactivate", &key, &[]).await?;
        let _ = fs::remove_file(&self.cfg.storage_path);
        Ok(LicenseStatus::new(false, &status, reply.message, None))
    }

    /// Ask for a newer build for `platform` ("windows", "macos", "linux"). Everything returned
    /// (version, URL, SHA-256) is taken from the signed answer.
    pub async fn check_update(&self, platform: &str, current_version: &str) -> Result<Option<UpdateInfo>, Error> {
        let key = self.stored_key().ok_or_else(|| Error::Storage("no license stored".into()))?;
        let (status, reply, signed) = self.call_raw("updates", &key, &[("platform", platform), ("current_version", current_version)]).await?;
        if status != "active" && status != "trial" {
            return Ok(None);
        }
        if signed.get("update_available").and_then(|v| v.as_bool()) != Some(true) {
            return Ok(None);
        }
        let s = |k: &str| signed.get(k).and_then(|v| v.as_str()).map(String::from);
        Ok(Some(UpdateInfo {
            version: s("version").ok_or(Error::Untrusted)?,
            url: s("url").ok_or(Error::Untrusted)?,
            sha256: s("sha256"),
            notes: reply.notes,
            released_at: reply.released_at.unwrap_or_default(),
            size: reply.size,
        }))
    }

    /// Check a downloaded file against the SHA-256 from [`check_update`].
    pub fn verify_download(bytes: &[u8], expected_sha256: &str) -> bool {
        hex::encode(Sha256::digest(bytes)).eq_ignore_ascii_case(expected_sha256)
    }

    // ------------------------------------------------------------------ internals

    fn finish(&self, key: &str, (status, reply): (String, ApiReply)) -> Result<LicenseStatus, Error> {
        if let Some(token) = reply.local_key.as_deref() {
            let claims = self.verify_claims(token).ok_or(Error::Untrusted)?;
            self.save(&Stored { license_key: key.to_string(), local_key: Some(token.to_string()) })?;
            return Ok(self.status_from_claims(claims, false));
        }
        // A definite, signed "no": keep the key (so the user sees it) but drop the local key.
        if ["suspended", "expired", "revoked", "invalid"].contains(&status.as_str()) {
            self.save(&Stored { license_key: key.to_string(), local_key: None })?;
        }
        Ok(LicenseStatus::new(false, &status, reply.message, None))
    }

    fn status_from_claims(&self, c: Claims, grace: bool) -> LicenseStatus {
        if c.pid != self.cfg.product {
            return LicenseStatus::new(false, "invalid", "This license is for a different product.", None);
        }
        if c.bind.kind == "machine" && self.bound_value().map(|m| m != c.bind.value).unwrap_or(true) {
            return LicenseStatus::new(false, "invalid", "This license was activated on another device.", None);
        }
        if let Some(exp) = c.exp {
            if now() > exp {
                return LicenseStatus::new(false, "expired", "This license has expired. Please renew.", Some(c));
            }
        }
        if c.status != "active" && c.status != "trial" {
            let s = c.status.clone();
            return LicenseStatus::new(false, &s, "This license is not active.", Some(c));
        }
        if grace {
            return LicenseStatus::new(true, "grace", "Working offline: the license server could not be reached.", Some(c));
        }
        let s = c.status.clone();
        LicenseStatus::new(true, &s, "License is valid.", Some(c))
    }

    async fn call(&self, endpoint: &str, key: &str, extra: &[(&str, &str)]) -> Result<(String, ApiReply), Error> {
        let (status, reply, _) = self.call_raw(endpoint, key, extra).await?;
        Ok((status, reply))
    }

    /// POST, then trust only the signed `response` token (status + our nonce + extras).
    async fn call_raw(&self, endpoint: &str, key: &str, extra: &[(&str, &str)]) -> Result<(String, ApiReply, serde_json::Map<String, serde_json::Value>), Error> {
        let nonce = hex::encode(rand::random::<[u8; 16]>());
        let mut body = serde_json::json!({
            "license_key": key,
            "product": self.cfg.product,
            "machine_id": self.machine_id()?,
            "device_label": self.cfg.device_label.clone().unwrap_or_else(default_label),
            "platform": self.cfg.platform,
            "app_version": self.cfg.app_version,
            "nonce": nonce,
        });
        for (k, v) in extra {
            body[*k] = serde_json::Value::String((*v).to_string());
        }
        let res = self.http.post(format!("{}/{}", self.cfg.api_url, endpoint)).json(&body).send().await
            .map_err(|e| Error::Network(e.to_string()))?;
        if res.status().is_server_error() {
            return Err(Error::Network(format!("server answered {}", res.status())));
        }
        let reply: ApiReply = res.json().await.map_err(|e| Error::Network(e.to_string()))?;
        let signed = reply.response.as_deref().and_then(|t| self.verify_token(t)).ok_or(Error::Untrusted)?;
        if signed.get("nonce").and_then(|v| v.as_str()) != Some(nonce.as_str()) {
            return Err(Error::Untrusted);
        }
        let ts = signed.get("ts").and_then(|v| v.as_u64()).unwrap_or(0);
        if now().abs_diff(ts) > 86_400 {
            return Err(Error::Untrusted);
        }
        if let Some(lk) = &reply.local_key {
            let want = signed.get("local_key_sha256").and_then(|v| v.as_str()).unwrap_or("");
            if hex::encode(Sha256::digest(lk.as_bytes())) != want {
                return Err(Error::Untrusted);
            }
        }
        let status = signed.get("status").and_then(|v| v.as_str()).unwrap_or("error").to_string();
        Ok((status, reply, signed))
    }

    fn verify_claims(&self, token: &str) -> Option<Claims> {
        let map = self.verify_token(token)?;
        serde_json::from_value(serde_json::Value::Object(map)).ok()
    }

    /// Token: v1.<kid>.<base64url payload>.<base64url signature over "v1.<kid>.<payload>">
    fn verify_token(&self, token: &str) -> Option<serde_json::Map<String, serde_json::Value>> {
        let parts: Vec<&str> = token.split('.').collect();
        if parts.len() != 4 || parts[0] != "v1" {
            return None;
        }
        let key = self.keys.get(parts[1])?;
        let sig_bytes: [u8; 64] = URL_SAFE_NO_PAD.decode(parts[3]).ok()?.try_into().ok()?;
        let head = format!("{}.{}.{}", parts[0], parts[1], parts[2]);
        key.verify(head.as_bytes(), &Signature::from_bytes(&sig_bytes)).ok()?;
        let payload = URL_SAFE_NO_PAD.decode(parts[2]).ok()?;
        match serde_json::from_slice(&payload).ok()? {
            serde_json::Value::Object(m) => Some(m),
            _ => None,
        }
    }

    fn load(&self) -> Option<Stored> {
        let raw = fs::read(&self.cfg.storage_path).ok()?;
        serde_json::from_slice(&raw).ok()
    }

    fn save(&self, s: &Stored) -> Result<(), Error> {
        if let Some(dir) = self.cfg.storage_path.parent() {
            fs::create_dir_all(dir).map_err(|e| Error::Storage(e.to_string()))?;
        }
        let tmp = self.cfg.storage_path.with_extension("tmp");
        fs::write(&tmp, serde_json::to_vec(s).unwrap_or_default()).map_err(|e| Error::Storage(e.to_string()))?;
        fs::rename(&tmp, &self.cfg.storage_path).map_err(|e| Error::Storage(e.to_string()))
    }
}

fn now() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0)
}

fn default_label() -> String {
    std::env::var("COMPUTERNAME").or_else(|_| std::env::var("HOSTNAME")).unwrap_or_else(|_| "This computer".into())
}
