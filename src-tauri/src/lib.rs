mod engine;
mod info;
mod license;
mod paths;
mod settings;

use std::path::{Path, PathBuf};
use std::sync::Mutex;
use tauri::{AppHandle, Manager, State};
use tauri_plugin_clipboard_manager::ClipboardExt;
use tauri_plugin_dialog::DialogExt;
use tauri_plugin_opener::OpenerExt;

use engine::{DownloadRequest, Engine, Job};
use license::{License, LicenseInfo};
use settings::Settings;

pub struct AppState {
    pub engine: Engine,
    pub settings: Mutex<Settings>,
    pub license: License,
}

// ------------------------------------------------------------------ commands

#[tauri::command]
async fn fetch_info(state: State<'_, AppState>, url: String) -> Result<info::MediaInfo, String> {
    let ytdlp = state.engine.ytdlp();
    info::fetch(&ytdlp, &url).await
}

#[tauri::command]
fn start_download(app: AppHandle, state: State<'_, AppState>, request: DownloadRequest) -> Result<Job, String> {
    let settings = state.settings.lock().unwrap().clone();
    engine::enqueue(&app, request, &settings)
}

#[tauri::command]
fn list_jobs(state: State<'_, AppState>) -> Vec<Job> {
    state.engine.list()
}

#[tauri::command]
fn job_action(app: AppHandle, id: String, action: String) -> Result<(), String> {
    match action.as_str() {
        "pause" => engine::pause(&app, &id),
        "resume" | "retry" => engine::resume(&app, &id),
        "cancel" => engine::cancel(&app, &id),
        "remove" => engine::remove(&app, &id),
        _ => return Err("Unknown action".into()),
    }
    Ok(())
}

#[tauri::command]
fn queue_action(app: AppHandle, action: String) -> Result<(), String> {
    match action.as_str() {
        "pause-all" => engine::pause_all(&app),
        "clear-finished" => engine::clear_finished(&app),
        _ => return Err("Unknown action".into()),
    }
    Ok(())
}

#[tauri::command]
fn get_settings(state: State<'_, AppState>) -> Settings {
    state.settings.lock().unwrap().clone()
}

#[tauri::command]
fn save_settings(app: AppHandle, state: State<'_, AppState>, settings: Settings) -> Result<Settings, String> {
    settings::save(&app, &settings)?;
    *state.settings.lock().unwrap() = settings.clone();
    Ok(settings)
}

#[tauri::command]
async fn pick_folder(app: AppHandle, state: State<'_, AppState>) -> Result<Option<Settings>, String> {
    let (tx, rx) = tokio::sync::oneshot::channel();
    let current = state.settings.lock().unwrap().download_dir.clone();
    app.dialog().file().set_directory(&current).pick_folder(move |p| {
        let _ = tx.send(p);
    });
    let Some(folder) = rx.await.ok().flatten() else { return Ok(None) };
    let path = folder.into_path().map_err(|e| e.to_string())?;
    let mut s = state.settings.lock().unwrap().clone();
    s.download_dir = path.to_string_lossy().into_owned();
    settings::save(&app, &s)?;
    *state.settings.lock().unwrap() = s.clone();
    Ok(Some(s))
}

#[tauri::command]
fn read_clipboard(app: AppHandle) -> Option<String> {
    app.clipboard().read_text().ok().map(|t| t.trim().to_string()).filter(|t| !t.is_empty())
}

#[tauri::command]
fn show_in_folder(app: AppHandle, path: String) -> Result<(), String> {
    let p = PathBuf::from(&path);
    if p.exists() {
        app.opener().reveal_item_in_dir(&p).map_err(|e| e.to_string())
    } else if let Some(dir) = p.parent().filter(|d| d.exists()) {
        app.opener().open_path(dir.to_string_lossy(), None::<&str>).map_err(|e| e.to_string())
    } else {
        Err("That file was moved or deleted.".into())
    }
}

#[tauri::command]
fn open_file(app: AppHandle, path: String) -> Result<(), String> {
    if !Path::new(&path).exists() {
        return Err("That file was moved or deleted.".into());
    }
    app.opener().open_path(path, None::<&str>).map_err(|e| e.to_string())
}

#[tauri::command]
fn open_folder(app: AppHandle, state: State<'_, AppState>) -> Result<(), String> {
    let dir = state.settings.lock().unwrap().download_dir.clone();
    let _ = std::fs::create_dir_all(&dir);
    app.opener().open_path(dir, None::<&str>).map_err(|e| e.to_string())
}

#[tauri::command]
fn open_link(app: AppHandle, url: String) -> Result<(), String> {
    if !url.starts_with("https://") {
        return Err("Only https links can be opened.".into());
    }
    app.opener().open_url(url, None::<&str>).map_err(|e| e.to_string())
}

// ---- engine (yt-dlp) version and updates

async fn version_of(bin: &Path) -> Option<String> {
    if !bin.exists() {
        return None;
    }
    let out = paths::command(bin).arg("--version").output().await.ok()?;
    out.status.success().then(|| String::from_utf8_lossy(&out.stdout).trim().to_string())
}

#[derive(serde::Serialize)]
#[serde(rename_all = "camelCase")]
struct EngineInfo {
    version: Option<String>,
    ffmpeg: bool,
    updatable: bool,
}

#[tauri::command]
async fn engine_info(state: State<'_, AppState>) -> Result<EngineInfo, String> {
    let bin = state.engine.ytdlp();
    let ffmpeg = paths::ffmpeg_dir().join(format!("ffmpeg{}", paths::EXE)).exists();
    Ok(EngineInfo { version: version_of(&bin).await, ffmpeg, updatable: true })
}

#[tauri::command]
async fn update_engine(app: AppHandle, state: State<'_, AppState>) -> Result<String, String> {
    let copy = paths::engine_copy(&app);
    if !copy.exists() {
        let bundled = paths::bundled_ytdlp();
        std::fs::create_dir_all(copy.parent().unwrap()).map_err(|e| e.to_string())?;
        std::fs::copy(&bundled, &copy).map_err(|e| format!("Couldn't prepare the engine copy: {e}"))?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let _ = std::fs::set_permissions(&copy, std::fs::Permissions::from_mode(0o755));
        }
    }
    let out = paths::command(&copy)
        .args(["-U", "--color", "never"])
        .output()
        .await
        .map_err(|e| format!("Couldn't run the updater: {e}"))?;
    let text = format!("{}{}", String::from_utf8_lossy(&out.stdout), String::from_utf8_lossy(&out.stderr));
    if !out.status.success() && !text.to_lowercase().contains("up to date") {
        return Err(info::clean_error(&text));
    }
    *state.engine.ytdlp.lock().unwrap() = copy.clone();
    let v = version_of(&copy).await.unwrap_or_default();
    Ok(if text.contains("Updated yt-dlp") { format!("Updated to {v}") } else { format!("Already up to date ({v})") })
}

// ---- licensing

#[tauri::command]
fn license_status(state: State<'_, AppState>) -> LicenseInfo {
    state.license.info()
}

#[tauri::command]
async fn license_activate(app: AppHandle, state: State<'_, AppState>, key: String) -> Result<LicenseInfo, String> {
    let info = state.license.activate(key.trim()).await?;
    engine::pump(&app);
    Ok(info)
}

#[tauri::command]
async fn license_deactivate(state: State<'_, AppState>) -> Result<LicenseInfo, String> {
    state.license.deactivate().await
}

/// `force: true` asks the server now ("Check now"); otherwise the SDK decides.
#[tauri::command]
async fn license_refresh(state: State<'_, AppState>, force: Option<bool>) -> Result<LicenseInfo, String> {
    Ok(state.license.refresh(force.unwrap_or(false)).await)
}

// ------------------------------------------------------------------ startup

/// Prefer the updatable engine copy when it is at least as new as the
/// bundled yt-dlp (after an app update the bundled one may be newer).
async fn choose_engine(app: &AppHandle) -> PathBuf {
    let bundled = paths::bundled_ytdlp();
    let copy = paths::engine_copy(app);
    match (version_of(&copy).await, version_of(&bundled).await) {
        (Some(c), Some(b)) if c >= b => copy,
        (Some(_), None) => copy,
        _ => bundled,
    }
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_clipboard_manager::init())
        .setup(|app| {
            let handle = app.handle().clone();
            let ytdlp = tauri::async_runtime::block_on(choose_engine(&handle));
            let history = engine::load_history(&handle);
            let version = app.package_info().version.to_string();
            app.manage(AppState {
                engine: Engine::new(ytdlp, history),
                settings: Mutex::new(settings::load(&handle)),
                license: License::new(paths::data_dir(&handle), &version),
            });
            // Check the stored license at start-up (no network until a re-check is due).
            let h = handle.clone();
            tauri::async_runtime::spawn(async move {
                let state = h.state::<AppState>();
                let info = state.license.refresh(false).await;
                let _ = tauri::Emitter::emit(&h, "license-updated", &info);
            });
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            fetch_info,
            start_download,
            list_jobs,
            job_action,
            queue_action,
            get_settings,
            save_settings,
            pick_folder,
            read_clipboard,
            show_in_folder,
            open_file,
            open_folder,
            open_link,
            engine_info,
            update_engine,
            license_status,
            license_activate,
            license_deactivate,
            license_refresh,
        ])
        .run(tauri::generate_context!())
        .expect("error while running Vidloot");
}
