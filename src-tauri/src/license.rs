//! Licensing adapter around the Bizfunnel License Manager SDK (crates/bizfunnel-license).
//!
//! Everything the rest of the app needs from licensing goes through this file:
//! the current status and the plan's entitlements. Entitlements are enforced in
//! Rust (engine.rs), not only in the UI.
//!
//! Server settings are baked in at build time from environment variables, which
//! the GitHub workflow fills from repository variables:
//!   VIDLOOT_LICENSE_API      e.g. https://licenses.example.com/api/v1
//!   VIDLOOT_LICENSE_PUBKEY   the base64 public key from Settings & API
//!   VIDLOOT_LICENSE_KID      key id (default "k1")
//!   VIDLOOT_LICENSE_PRODUCT  product slug (default "vidloot")
//! Without them the app still runs, on the Free plan, and activation explains why.
//!
//! How a plan maps to Vidloot features (set these on the plan in the panel):
//!   feature  "playlists"            → whole playlists
//!   feature  "subtitles"            → subtitles
//!   limit    "max_height"           → highest video height (0 or missing = no limit)
//!   limit    "parallel_downloads"   → downloads at once (missing = 3)

use bizfunnel_license::{Config, LicenseClient, LicenseStatus};
use serde::Serialize;
use std::path::PathBuf;
use std::sync::Mutex;

const API: Option<&str> = option_env!("VIDLOOT_LICENSE_API");
const PUBKEY: Option<&str> = option_env!("VIDLOOT_LICENSE_PUBKEY");
const KID: Option<&str> = option_env!("VIDLOOT_LICENSE_KID");
const PRODUCT: Option<&str> = option_env!("VIDLOOT_LICENSE_PRODUCT");

/// Plan limits.
#[derive(Clone, Debug, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Entitlements {
    pub plan: String,
    /// Highest video height allowed; 0 = no limit.
    pub max_height: u32,
    pub playlists: bool,
    pub subtitles: bool,
    pub parallel_downloads: u32,
}

impl Entitlements {
    /// What everyone gets without a license.
    pub fn free() -> Self {
        Self { plan: "Free".into(), max_height: 720, playlists: false, subtitles: false, parallel_downloads: 1 }
    }

    pub fn allows_height(&self, h: u32) -> bool {
        self.max_height == 0 || h <= self.max_height
    }

    /// Entitlements from a valid license.
    pub fn from_status(s: &LicenseStatus) -> Self {
        let Some(c) = &s.claims else { return Self::free() };
        let limit = |name: &str| s.limit(name).filter(|v| *v >= 0).map(|v| v as u32);
        Self {
            plan: if c.plan.trim().is_empty() { "Pro".into() } else { c.plan.clone() },
            max_height: limit("max_height").unwrap_or(0),
            playlists: s.has_feature("playlists"),
            subtitles: s.has_feature("subtitles"),
            parallel_downloads: limit("parallel_downloads").unwrap_or(3).clamp(1, 8),
        }
    }
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LicenseInfo {
    /// free | active | grace | invalid
    pub status: String,
    /// The SDK's own status (active, expired, suspended, limit_reached, …).
    pub detail: String,
    pub connected: bool,
    pub key_hint: Option<String>,
    pub licensed_to: Option<String>,
    pub expires_at: Option<String>,
    pub updates_until: Option<String>,
    pub message: Option<String>,
    pub entitlements: Entitlements,
}

impl LicenseInfo {
    fn free(connected: bool, message: Option<String>) -> Self {
        Self {
            status: "free".into(),
            detail: "no_key".into(),
            connected,
            key_hint: None,
            licensed_to: None,
            expires_at: None,
            updates_until: None,
            message,
            entitlements: Entitlements::free(),
        }
    }
}

pub struct License {
    client: Option<LicenseClient>,
    setup_error: Option<String>,
    info: Mutex<LicenseInfo>,
}

/// "VLT-AB12C-…-KQRJB" → "VLT-…-KQRJB"
fn key_hint(key: &str) -> String {
    let parts: Vec<&str> = key.split('-').collect();
    match parts.as_slice() {
        [first, .., last] if parts.len() > 2 => format!("{first}-…-{last}"),
        _ if key.len() > 4 => format!("…{}", &key[key.len() - 4..]),
        _ => key.to_string(),
    }
}

/// Unix seconds → YYYY-MM-DD (UTC).
fn date(secs: u64) -> String {
    let days = (secs / 86_400) as i64;
    // Howard Hinnant's civil-from-days.
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = yoe + era * 400 + if m <= 2 { 1 } else { 0 };
    format!("{y:04}-{m:02}-{d:02}")
}

impl License {
    pub fn new(data_dir: PathBuf, app_version: &str) -> Self {
        let (Some(api), Some(pubkey)) = (API.filter(|s| !s.is_empty()), PUBKEY.filter(|s| !s.is_empty())) else {
            return Self {
                client: None,
                setup_error: Some("Licensing isn't set up in this build (no license server configured).".into()),
                info: Mutex::new(LicenseInfo::free(false, None)),
            };
        };
        let kid = KID.filter(|s| !s.is_empty()).unwrap_or("k1");
        let product = PRODUCT.filter(|s| !s.is_empty()).unwrap_or("vidloot");
        let cfg = Config::new(api, product, [(kid, pubkey)], data_dir.join("license.json"))
            .app_version(app_version)
            .timeout(std::time::Duration::from_secs(15));
        match LicenseClient::new(cfg) {
            Ok(client) => Self { client: Some(client), setup_error: None, info: Mutex::new(LicenseInfo::free(true, None)) },
            Err(e) => Self {
                client: None,
                setup_error: Some(format!("Licensing is misconfigured in this build: {e}")),
                info: Mutex::new(LicenseInfo::free(false, None)),
            },
        }
    }

    pub fn info(&self) -> LicenseInfo {
        self.info.lock().unwrap().clone()
    }

    pub fn entitlements(&self) -> Entitlements {
        self.info.lock().unwrap().entitlements.clone()
    }

    fn apply(&self, s: &LicenseStatus) -> LicenseInfo {
        let client = self.client.as_ref();
        let key = client.and_then(|c| c.stored_key());
        let claims = s.claims.as_ref();
        let status = if s.valid {
            if s.in_grace() { "grace" } else { "active" }
        } else if s.status == "no_key" || key.is_none() {
            "free"
        } else {
            "invalid"
        };
        let info = LicenseInfo {
            status: status.into(),
            detail: s.status.clone(),
            connected: true,
            key_hint: key.as_deref().map(key_hint),
            licensed_to: claims.map(|c| c.licensed_to.clone()).filter(|v| !v.is_empty()),
            expires_at: claims.and_then(|c| c.exp).map(date),
            updates_until: claims.and_then(|c| c.updates_until.clone()),
            message: if s.valid && !s.in_grace() || status == "free" { None } else { Some(s.message.clone()) },
            entitlements: if s.valid { Entitlements::from_status(s) } else { Entitlements::free() },
        };
        *self.info.lock().unwrap() = info.clone();
        info
    }

    /// Re-check the license. Cheap: the SDK only calls the server when its re-check is due.
    pub async fn refresh(&self, force_online: bool) -> LicenseInfo {
        match &self.client {
            Some(c) => {
                let s = c.check(force_online).await;
                self.apply(&s)
            }
            None => self.info(),
        }
    }

    pub async fn activate(&self, key: &str) -> Result<LicenseInfo, String> {
        let Some(c) = &self.client else {
            return Err(self.setup_error.clone().unwrap_or_default());
        };
        if key.trim().len() < 6 {
            return Err("Please enter the full license key.".into());
        }
        let s = c.activate(key).await.map_err(|e| match e {
            bizfunnel_license::Error::Network(_) => "Couldn't reach the license server. Check your internet connection and try again.".to_string(),
            other => other.to_string(),
        })?;
        if !s.valid {
            let msg = if s.message.trim().is_empty() { friendly(&s.status) } else { s.message.clone() };
            // Keep showing Free if this key isn't usable; don't leave a half-activated state.
            let _ = self.refresh(false).await;
            return Err(msg);
        }
        Ok(self.apply(&s))
    }

    pub async fn deactivate(&self) -> Result<LicenseInfo, String> {
        let Some(c) = &self.client else {
            return Err(self.setup_error.clone().unwrap_or_default());
        };
        c.deactivate().await.map_err(|e| format!("Couldn't deactivate: {e}"))?;
        let info = LicenseInfo::free(true, None);
        *self.info.lock().unwrap() = info.clone();
        Ok(info)
    }
}

fn friendly(status: &str) -> String {
    match status {
        "limit_reached" => "This key is already active on the maximum number of devices. Deactivate it on another device first.",
        "expired" => "This license has expired. Please renew.",
        "suspended" => "This license is suspended. Please contact support.",
        "revoked" => "This license was revoked.",
        "invalid" => "That license key isn't valid for Vidloot.",
        "rate_limited" => "Too many attempts. Please wait a few minutes and try again.",
        _ => "This license key couldn't be activated.",
    }
    .into()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hint_and_date() {
        assert_eq!(key_hint("VLT-80W7B-AD8XX-KQRJB"), "VLT-…-KQRJB");
        assert_eq!(date(0), "1970-01-01");
        assert_eq!(date(1_795_000_000), "2026-11-18");
    }

    #[test]
    fn entitlements_from_claims() {
        let claims: bizfunnel_license::Claims = serde_json::from_value(serde_json::json!({
            "lid": 1, "pid": "vidloot-desktop", "plan": "Pro", "features": ["playlists", "subtitles"],
            "limits": {"parallel_downloads": 3}, "status": "active", "exp": null, "updates_until": null,
            "iat": 0, "next_check": 0, "grace_until": 0, "bind": {"type": "machine", "value": "x"}
        }))
        .unwrap();
        let s = LicenseStatus { valid: true, status: "active".into(), message: String::new(), claims: Some(claims) };
        let e = Entitlements::from_status(&s);
        assert_eq!(e, Entitlements { plan: "Pro".into(), max_height: 0, playlists: true, subtitles: true, parallel_downloads: 3 });
        assert!(e.allows_height(2160));
    }

    #[test]
    fn not_configured_is_free() {
        // Test builds have no license server baked in unless the env vars are set.
        if API.is_none() {
            let l = License::new(std::env::temp_dir(), "1.0.0");
            assert_eq!(l.info().status, "free");
            assert_eq!(l.entitlements(), Entitlements::free());
        }
    }
}
