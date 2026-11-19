//! Where the bundled tools live, and the updatable copy of yt-dlp.
//!
//! Tauri copies `externalBin` sidecars next to the main executable (also in
//! `tauri dev`), so the folder of the running program is where yt-dlp,
//! ffmpeg and ffprobe are.

use std::path::PathBuf;
use tauri::{AppHandle, Manager};

pub const EXE: &str = std::env::consts::EXE_SUFFIX;

pub fn exe_dir() -> PathBuf {
    std::env::current_exe()
        .ok()
        .and_then(|p| p.parent().map(|d| d.to_path_buf()))
        .unwrap_or_else(|| PathBuf::from("."))
}

/// yt-dlp shipped inside the app.
pub fn bundled_ytdlp() -> PathBuf {
    exe_dir().join(format!("yt-dlp{EXE}"))
}

/// Folder holding ffmpeg and ffprobe (passed to yt-dlp as --ffmpeg-location).
pub fn ffmpeg_dir() -> PathBuf {
    exe_dir()
}

/// A writable copy of yt-dlp in the app's data folder. "Update engine"
/// updates this copy, so the engine can stay current between app releases
/// without touching the (signed) app bundle.
pub fn engine_copy(app: &AppHandle) -> PathBuf {
    data_dir(app).join("engine").join(format!("yt-dlp{EXE}"))
}

pub fn data_dir(app: &AppHandle) -> PathBuf {
    let dir = app
        .path()
        .app_data_dir()
        .unwrap_or_else(|_| std::env::temp_dir().join("vidloot"));
    let _ = std::fs::create_dir_all(&dir);
    dir
}

pub fn config_dir(app: &AppHandle) -> PathBuf {
    let dir = app
        .path()
        .app_config_dir()
        .unwrap_or_else(|_| data_dir(app));
    let _ = std::fs::create_dir_all(&dir);
    dir
}

pub fn default_download_dir() -> PathBuf {
    dirs::download_dir()
        .or_else(dirs::home_dir)
        .unwrap_or_else(|| PathBuf::from("."))
        .join("Vidloot")
}

/// Build a tokio Command that never flashes a console window on Windows.
pub fn command(program: &std::path::Path) -> tokio::process::Command {
    #[allow(unused_mut)]
    let mut cmd = tokio::process::Command::new(program);
    #[cfg(windows)]
    {
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        cmd.creation_flags(CREATE_NO_WINDOW);
    }
    cmd.stdin(std::process::Stdio::null());
    cmd
}
