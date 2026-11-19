//! Licensing adapter.
//!
//! Everything the rest of the app needs from licensing goes through this file:
//! the current status and the plan's entitlements. The Bizfunnel License
//! Manager Tauri SDK is connected here (see `connect_sdk` notes below); until
//! then the app runs on the Free plan and activation reports that licensing
//! isn't connected.
//!
//! Entitlements are enforced in Rust (engine.rs), not only in the UI.

use serde::Serialize;
use std::sync::Mutex;

/// Plan limits. Feature flags on a License Manager plan map onto these fields.
#[derive(Clone, Debug, Serialize)]
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
        Self {
            plan: "Free".into(),
            max_height: 720,
            playlists: false,
            subtitles: false,
            parallel_downloads: 1,
        }
    }

    pub fn allows_height(&self, h: u32) -> bool {
        self.max_height == 0 || h <= self.max_height
    }
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LicenseInfo {
    /// free | active | grace | invalid
    pub status: String,
    pub connected: bool,
    pub key_hint: Option<String>,
    pub expires_at: Option<String>,
    pub updates_until: Option<String>,
    pub message: Option<String>,
    pub entitlements: Entitlements,
}

impl LicenseInfo {
    pub fn free() -> Self {
        Self {
            status: "free".into(),
            connected: false,
            key_hint: None,
            expires_at: None,
            updates_until: None,
            message: None,
            entitlements: Entitlements::free(),
        }
    }
}

pub struct License {
    info: Mutex<LicenseInfo>,
}

impl License {
    pub fn new() -> Self {
        Self { info: Mutex::new(LicenseInfo::free()) }
    }

    pub fn info(&self) -> LicenseInfo {
        self.info.lock().unwrap().clone()
    }

    pub fn entitlements(&self) -> Entitlements {
        self.info.lock().unwrap().entitlements.clone()
    }

    pub async fn activate(&self, _key: &str) -> Result<LicenseInfo, String> {
        Err("Licensing isn't connected in this build yet.".into())
    }

    pub async fn deactivate(&self) -> Result<LicenseInfo, String> {
        let mut g = self.info.lock().unwrap();
        *g = LicenseInfo::free();
        Ok(g.clone())
    }

    pub async fn refresh(&self) -> LicenseInfo {
        self.info()
    }
}
