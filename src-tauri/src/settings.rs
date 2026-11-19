use serde::{Deserialize, Serialize};
use std::path::PathBuf;
use tauri::AppHandle;

use crate::paths;

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Settings {
    pub download_dir: String,
    /// "video" or "audio"
    pub default_mode: String,
    /// Height as text ("1080") or "best".
    pub default_quality: String,
    /// mp3 | m4a | opus | wav
    pub default_audio: String,
    pub subtitles: bool,
    pub subtitle_langs: String,
    pub embed_thumbnail: bool,
    pub keep_chapters: bool,
    pub watch_clipboard: bool,
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            download_dir: paths::default_download_dir().to_string_lossy().into_owned(),
            default_mode: "video".into(),
            default_quality: "1080".into(),
            default_audio: "mp3".into(),
            subtitles: false,
            subtitle_langs: "en.*,en".into(),
            embed_thumbnail: true,
            keep_chapters: true,
            watch_clipboard: true,
        }
    }
}

fn file(app: &AppHandle) -> PathBuf {
    paths::config_dir(app).join("settings.json")
}

pub fn load(app: &AppHandle) -> Settings {
    std::fs::read_to_string(file(app))
        .ok()
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or_default()
}

pub fn save(app: &AppHandle, s: &Settings) -> Result<(), String> {
    let json = serde_json::to_string_pretty(s).map_err(|e| e.to_string())?;
    std::fs::write(file(app), json).map_err(|e| format!("Could not save settings: {e}"))
}
