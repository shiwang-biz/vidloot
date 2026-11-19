//! Download queue: runs yt-dlp per job, parses its progress, and emits
//! `job-updated` events to the UI.

use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};
use tauri::{AppHandle, Emitter, Manager};
use tokio::io::{AsyncBufReadExt, BufReader};
use tokio::sync::oneshot;

use crate::{info, paths, settings::Settings, AppState};

const HISTORY_LIMIT: usize = 300;

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Job {
    pub id: String,
    pub url: String,
    pub title: String,
    pub thumbnail: Option<String>,
    /// video | audio
    pub mode: String,
    /// "1080", "best", or an audio format ("mp3").
    pub quality: String,
    pub label: String,
    pub playlist: bool,
    pub subtitles: bool,
    pub embed_thumbnail: bool,
    pub chapters: bool,
    pub dir: String,
    /// queued | downloading | processing | paused | done | error | canceled
    pub status: String,
    pub stage: String,
    pub percent: f64,
    pub speed: String,
    pub eta: String,
    pub downloaded: u64,
    pub total: u64,
    pub item_index: u32,
    pub item_count: u32,
    pub file_path: Option<String>,
    pub error: Option<String>,
    pub created_at: u64,
    pub finished_at: Option<u64>,
    #[serde(skip)]
    part_file: Option<String>,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DownloadRequest {
    pub url: String,
    pub title: String,
    pub thumbnail: Option<String>,
    pub mode: String,
    pub quality: String,
    pub playlist: bool,
    pub subtitles: bool,
    pub embed_thumbnail: bool,
    pub chapters: bool,
}

pub struct Engine {
    pub jobs: Mutex<Vec<Job>>,
    kills: Mutex<HashMap<String, oneshot::Sender<()>>>,
    pub ytdlp: Mutex<PathBuf>,
}

fn now() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0)
}

fn is_active(status: &str) -> bool {
    matches!(status, "downloading" | "processing")
}

fn is_finished(status: &str) -> bool {
    matches!(status, "done" | "error" | "canceled")
}

impl Engine {
    pub fn new(ytdlp: PathBuf, history: Vec<Job>) -> Self {
        Self { jobs: Mutex::new(history), kills: Mutex::new(HashMap::new()), ytdlp: Mutex::new(ytdlp) }
    }

    pub fn ytdlp(&self) -> PathBuf {
        self.ytdlp.lock().unwrap().clone()
    }

    pub fn list(&self) -> Vec<Job> {
        self.jobs.lock().unwrap().clone()
    }
}

// ------------------------------------------------------------------ history

fn history_file(app: &AppHandle) -> PathBuf {
    paths::data_dir(app).join("history.json")
}

pub fn load_history(app: &AppHandle) -> Vec<Job> {
    std::fs::read_to_string(history_file(app))
        .ok()
        .and_then(|s| serde_json::from_str::<Vec<Job>>(&s).ok())
        .unwrap_or_default()
        .into_iter()
        .map(|mut j| {
            // Anything unfinished when the app closed comes back paused.
            if !is_finished(&j.status) {
                j.status = "paused".into();
                j.stage = String::new();
                j.speed = String::new();
                j.eta = String::new();
            }
            j
        })
        .collect()
}

fn save_history(app: &AppHandle) {
    let state = app.state::<AppState>();
    let mut jobs = state.engine.list();
    if jobs.len() > HISTORY_LIMIT {
        let cut = jobs.len() - HISTORY_LIMIT;
        jobs.drain(0..cut);
    }
    if let Ok(json) = serde_json::to_string(&jobs) {
        let _ = std::fs::write(history_file(app), json);
    }
}

// ------------------------------------------------------------------ queue ops

fn emit(app: &AppHandle, job: &Job) {
    let _ = app.emit("job-updated", job);
}

fn update<F: FnOnce(&mut Job)>(app: &AppHandle, id: &str, f: F) -> Option<Job> {
    let state = app.state::<AppState>();
    let job = {
        let mut jobs = state.engine.jobs.lock().unwrap();
        let j = jobs.iter_mut().find(|j| j.id == id)?;
        f(j);
        j.clone()
    };
    emit(app, &job);
    Some(job)
}

pub fn enqueue(app: &AppHandle, req: DownloadRequest, settings: &Settings) -> Result<Job, String> {
    let state = app.state::<AppState>();
    let ent = state.license.entitlements();

    // Plan checks happen here, so a modified UI can't skip them.
    let video = req.mode == "video";
    if video {
        if let Ok(h) = req.quality.parse::<u32>() {
            if !ent.allows_height(h) {
                return Err(format!("PRO:quality:{} plan downloads up to {}p.", ent.plan, ent.max_height));
            }
        }
    } else if !matches!(req.quality.as_str(), "mp3" | "m4a" | "opus" | "wav") {
        return Err("Unknown audio format.".into());
    }
    if req.playlist && !ent.playlists {
        return Err(format!("PRO:playlists:Whole playlists need Pro. The {} plan downloads one video at a time.", ent.plan));
    }
    if req.subtitles && video && !ent.subtitles {
        return Err(format!("PRO:subtitles:Subtitles need Pro."));
    }

    std::fs::create_dir_all(&settings.download_dir)
        .map_err(|e| format!("Can't use the download folder ({}): {e}", settings.download_dir))?;

    let label = if video {
        match req.quality.as_str() {
            "best" => "Best · MP4".to_string(),
            "2160" => "4K · MP4".to_string(),
            q => format!("{q}p · MP4"),
        }
    } else {
        req.quality.to_uppercase()
    };

    let job = Job {
        id: uuid::Uuid::new_v4().to_string(),
        url: req.url.trim().to_string(),
        title: req.title,
        thumbnail: req.thumbnail,
        mode: req.mode,
        quality: req.quality,
        label,
        playlist: req.playlist,
        subtitles: req.subtitles && video,
        embed_thumbnail: req.embed_thumbnail,
        chapters: req.chapters,
        dir: settings.download_dir.clone(),
        status: "queued".into(),
        stage: "Waiting in queue".into(),
        percent: 0.0,
        speed: String::new(),
        eta: String::new(),
        downloaded: 0,
        total: 0,
        item_index: 0,
        item_count: 0,
        file_path: None,
        error: None,
        created_at: now(),
        finished_at: None,
        part_file: None,
    };
    state.engine.jobs.lock().unwrap().push(job.clone());
    emit(app, &job);
    pump(app);
    Ok(job)
}

/// Start queued jobs while there's room under the plan's parallel limit.
pub fn pump(app: &AppHandle) {
    let state = app.state::<AppState>();
    let limit = state.license.entitlements().parallel_downloads.max(1) as usize;
    let to_start: Vec<String> = {
        let mut jobs = state.engine.jobs.lock().unwrap();
        let running = jobs.iter().filter(|j| is_active(&j.status)).count();
        let free = limit.saturating_sub(running);
        let mut ids = Vec::new();
        for j in jobs.iter_mut().filter(|j| j.status == "queued").take(free) {
            j.status = "downloading".into();
            j.stage = "Starting…".into();
            ids.push(j.id.clone());
        }
        ids
    };
    for id in to_start {
        let app = app.clone();
        tauri::async_runtime::spawn(async move { run(app, id).await });
    }
}

pub fn pause(app: &AppHandle, id: &str) {
    let was_queued = {
        let state = app.state::<AppState>();
        let jobs = state.engine.jobs.lock().unwrap();
        jobs.iter().find(|j| j.id == id).map(|j| j.status == "queued").unwrap_or(false)
    };
    update(app, id, |j| {
        if !is_finished(&j.status) {
            j.status = "paused".into();
            j.stage = "Paused".into();
            j.speed.clear();
            j.eta.clear();
        }
    });
    if !was_queued {
        kill(app, id);
    }
    save_history(app);
}

pub fn resume(app: &AppHandle, id: &str) {
    update(app, id, |j| {
        if j.status == "paused" || j.status == "error" {
            j.status = "queued".into();
            j.stage = "Waiting in queue".into();
            j.error = None;
        }
    });
    pump(app);
}

pub fn cancel(app: &AppHandle, id: &str) {
    let part = update(app, id, |j| {
        if !is_finished(&j.status) {
            j.status = "canceled".into();
            j.stage = "Canceled".into();
            j.finished_at = Some(now());
        }
    })
    .and_then(|j| j.part_file.clone());
    kill(app, id);
    // Remove leftovers of the unfinished file.
    if let Some(p) = part {
        let app = app.clone();
        tauri::async_runtime::spawn(async move {
            tokio::time::sleep(std::time::Duration::from_millis(800)).await;
            for f in [p.clone(), format!("{p}.ytdl")] {
                let _ = std::fs::remove_file(&f);
            }
            save_history(&app);
        });
    } else {
        save_history(app);
    }
}

pub fn remove(app: &AppHandle, id: &str) {
    let state = app.state::<AppState>();
    state.engine.jobs.lock().unwrap().retain(|j| j.id != id || !is_finished(&j.status));
    save_history(app);
}

pub fn clear_finished(app: &AppHandle) {
    let state = app.state::<AppState>();
    state.engine.jobs.lock().unwrap().retain(|j| !is_finished(&j.status));
    save_history(app);
}

pub fn pause_all(app: &AppHandle) {
    let ids: Vec<String> = {
        let state = app.state::<AppState>();
        let jobs = state.engine.jobs.lock().unwrap();
        jobs.iter().filter(|j| !is_finished(&j.status) && j.status != "paused").map(|j| j.id.clone()).collect()
    };
    for id in ids {
        pause(app, &id);
    }
}

fn kill(app: &AppHandle, id: &str) {
    let state = app.state::<AppState>();
    let tx = state.engine.kills.lock().unwrap().remove(id);
    if let Some(tx) = tx {
        let _ = tx.send(());
    }
}

// ------------------------------------------------------------------ running

pub fn build_args(job: &Job, settings: &Settings) -> Vec<String> {
    let mut a: Vec<String> = Vec::new();
    let push = |a: &mut Vec<String>, s: &str| a.push(s.to_string());

    for s in ["--newline", "--progress", "--color", "never", "--encoding", "utf-8", "--no-mtime", "--embed-metadata", "--continue"] {
        push(&mut a, s);
    }
    push(&mut a, "--ffmpeg-location");
    a.push(paths::ffmpeg_dir().to_string_lossy().into_owned());
    push(&mut a, "--progress-template");
    push(
        &mut a,
        "download:VLPROG|%(progress.status)s|%(progress._percent_str)s|%(progress._speed_str)s|%(progress._eta_str)s|%(progress.downloaded_bytes)s|%(progress.total_bytes)s|%(progress.total_bytes_estimate)s|%(info.playlist_index)s|%(info.n_entries)s|%(info.vcodec)s|%(info.acodec)s|%(progress.tmpfilename)s",
    );
    push(&mut a, "--progress-template");
    push(&mut a, "postprocess:VLPP|%(progress.postprocessor)s|%(progress.status)s");
    push(&mut a, "--print");
    push(&mut a, "after_move:VLFILE|%(filepath)s");

    push(&mut a, "-P");
    a.push(job.dir.clone());
    push(&mut a, "-o");
    if job.playlist {
        push(&mut a, "%(playlist_title,playlist_id|Playlist).80B/%(playlist_index)03d - %(title).120B.%(ext)s");
        push(&mut a, "--yes-playlist");
    } else {
        push(&mut a, "%(title).150B [%(id)s].%(ext)s");
        push(&mut a, "--no-playlist");
    }

    if job.mode == "video" {
        push(&mut a, "-f");
        push(&mut a, "bv*+ba/b");
        push(&mut a, "-S");
        match job.quality.parse::<u32>() {
            Ok(h) => a.push(format!("res:{h},vcodec:h264,acodec:aac")),
            Err(_) => push(&mut a, "vcodec:h264,acodec:aac"),
        }
        push(&mut a, "--merge-output-format");
        push(&mut a, "mp4");
        if job.subtitles {
            push(&mut a, "--write-subs");
            push(&mut a, "--sub-langs");
            a.push(if settings.subtitle_langs.trim().is_empty() { "en.*,en".into() } else { settings.subtitle_langs.clone() });
            push(&mut a, "--embed-subs");
        }
        if job.chapters {
            push(&mut a, "--embed-chapters");
        }
        if job.embed_thumbnail {
            push(&mut a, "--embed-thumbnail");
        }
    } else {
        push(&mut a, "-f");
        push(&mut a, "ba/b");
        push(&mut a, "-x");
        push(&mut a, "--audio-format");
        a.push(job.quality.clone());
        push(&mut a, "--audio-quality");
        push(&mut a, if job.quality == "mp3" { "320K" } else { "0" });
        if job.embed_thumbnail && job.quality != "wav" {
            push(&mut a, "--embed-thumbnail");
        }
        if job.chapters {
            push(&mut a, "--embed-chapters");
        }
    }
    push(&mut a, "--");
    a.push(job.url.clone());
    a
}

fn parse_num(s: &str) -> Option<u64> {
    s.trim().parse::<f64>().ok().map(|f| f as u64)
}

fn clean(s: &str) -> String {
    let t = s.trim();
    if t == "NA" || t == "Unknown" || t.contains("Unknown") {
        String::new()
    } else {
        t.to_string()
    }
}

fn stage_name(pp: &str) -> &'static str {
    match pp {
        "Merger" => "Merging audio + video",
        "ExtractAudio" => "Converting audio",
        "EmbedSubtitle" => "Adding subtitles",
        "EmbedThumbnail" => "Adding cover image",
        "FFmpegMetadata" | "Metadata" => "Saving chapters and info",
        "VideoConvertor" | "VideoRemuxer" => "Converting video",
        "MoveFiles" => "Finishing",
        _ => "Finishing",
    }
}

/// Apply one line of yt-dlp output to the job. Returns true when the UI should be told.
pub fn apply_line(j: &mut Job, line: &str) -> bool {
    if let Some(rest) = line.strip_prefix("VLPROG|") {
        let p: Vec<&str> = rest.split('|').collect();
        // status|percent|speed|eta|downloaded|total|total_est|playlist_index|n_entries|vcodec|acodec|tmpfile
        if p.len() < 12 {
            return false;
        }
        let pct: f64 = p[1].trim().trim_end_matches('%').trim().parse().unwrap_or(0.0);
        let video_only = p[10].trim() == "none";
        let audio_only = p[9].trim() == "none";
        // Video downloads arrive as a video stream then an audio stream:
        // show them as one bar (video ≈ 90 %, audio ≈ 10 %).
        let item_pct = if j.mode == "video" && video_only {
            pct * 0.9
        } else if j.mode == "video" && audio_only {
            90.0 + pct * 0.1
        } else {
            pct
        };
        j.stage = if j.mode == "video" && audio_only { "Downloading audio".into() } else { "Downloading".into() };
        if let (Some(i), Some(n)) = (parse_num(p[7]), parse_num(p[8])) {
            j.item_index = i as u32;
            j.item_count = n as u32;
        }
        j.percent = if j.item_count > 1 && j.item_index > 0 {
            (((j.item_index - 1) as f64 + item_pct / 100.0) / j.item_count as f64) * 100.0
        } else {
            item_pct
        }
        .clamp(0.0, 100.0);
        j.status = "downloading".into();
        j.speed = clean(p[2]);
        j.eta = clean(p[3]);
        j.downloaded = parse_num(p[4]).unwrap_or(j.downloaded);
        j.total = parse_num(p[5]).or_else(|| parse_num(p[6])).unwrap_or(j.total);
        let tmp = clean(p[11]);
        if !tmp.is_empty() {
            j.part_file = Some(tmp);
        }
        return true;
    }
    if let Some(rest) = line.strip_prefix("VLPP|") {
        let pp = rest.split('|').next().unwrap_or("");
        j.status = "processing".into();
        j.stage = stage_name(pp).into();
        j.speed.clear();
        j.eta.clear();
        return true;
    }
    if let Some(path) = line.strip_prefix("VLFILE|") {
        j.file_path = Some(path.trim().to_string());
        return true;
    }
    false
}

async fn run(app: AppHandle, id: String) {
    let state = app.state::<AppState>();
    let settings = state.settings.lock().unwrap().clone();
    let Some(job) = state.engine.list().into_iter().find(|j| j.id == id) else { return };
    let args = build_args(&job, &settings);
    let ytdlp = state.engine.ytdlp();

    let mut cmd = paths::command(&ytdlp);
    cmd.args(&args)
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::piped())
        .kill_on_drop(true);
    let mut child = match cmd.spawn() {
        Ok(c) => c,
        Err(e) => {
            update(&app, &id, |j| {
                j.status = "error".into();
                j.error = Some(format!("Could not start the download engine: {e}"));
                j.stage = "Failed".into();
                j.finished_at = Some(now());
            });
            save_history(&app);
            pump(&app);
            return;
        }
    };

    let (tx, mut rx) = oneshot::channel::<()>();
    state.engine.kills.lock().unwrap().insert(id.clone(), tx);

    // yt-dlp prints download progress and the final path on stdout, but
    // post-processing progress (merging, converting) and errors on stderr.
    let mut out_lines = child.stdout.take().map(|o| BufReader::new(o).lines());
    let mut err_lines = child.stderr.take().map(|e| BufReader::new(e).lines());
    let (mut out_done, mut err_done) = (out_lines.is_none(), err_lines.is_none());
    let mut stderr_text = String::new();
    let mut killed = false;
    let mut last_emit = std::time::Instant::now() - std::time::Duration::from_secs(1);

    let handle = |line: &str, last_emit: &mut std::time::Instant| {
        let (changed, job) = {
            let mut jobs = state.engine.jobs.lock().unwrap();
            match jobs.iter_mut().find(|j| j.id == id) {
                Some(j) if !is_finished(&j.status) && j.status != "paused" => (apply_line(j, line), Some(j.clone())),
                _ => (false, None),
            }
        };
        // Keep the UI smooth without flooding it: ~6 updates a second.
        if let (true, Some(job)) = (changed, job) {
            if job.status != "downloading" || job.percent >= 100.0 || last_emit.elapsed().as_millis() > 150 {
                emit(&app, &job);
                *last_emit = std::time::Instant::now();
            }
        }
    };

    while !(out_done && err_done) {
        tokio::select! {
            line = async { out_lines.as_mut().unwrap().next_line().await }, if !out_done => match line {
                Ok(Some(l)) => handle(&l, &mut last_emit),
                _ => out_done = true,
            },
            line = async { err_lines.as_mut().unwrap().next_line().await }, if !err_done => match line {
                Ok(Some(l)) => {
                    if l.starts_with("VLPP|") {
                        handle(&l, &mut last_emit);
                    } else if l.starts_with("ERROR:") || stderr_text.len() < 4000 {
                        stderr_text.push_str(&l);
                        stderr_text.push('\n');
                    }
                }
                _ => err_done = true,
            },
            _ = &mut rx => { killed = true; let _ = child.kill().await; break; }
        }
    }
    let status = child.wait().await;
    state.engine.kills.lock().unwrap().remove(&id);

    update(&app, &id, |j| {
        if j.status == "paused" || j.status == "canceled" || killed {
            return;
        }
        match status {
            Ok(s) if s.success() => {
                j.status = "done".into();
                j.stage = "Finished".into();
                j.percent = 100.0;
                j.speed.clear();
                j.eta.clear();
            }
            _ => {
                j.status = "error".into();
                j.stage = "Failed".into();
                j.error = Some(info::clean_error(&stderr_text));
            }
        }
        j.finished_at = Some(now());
    });
    save_history(&app);
    pump(&app);
}

#[cfg(test)]
mod tests {
    use super::*;

    fn job(mode: &str, quality: &str) -> Job {
        Job {
            id: "1".into(), url: "https://example.com/v".into(), title: "t".into(), thumbnail: None,
            mode: mode.into(), quality: quality.into(), label: String::new(), playlist: false,
            subtitles: true, embed_thumbnail: true, chapters: true, dir: "/tmp/dl".into(),
            status: "downloading".into(), stage: String::new(), percent: 0.0, speed: String::new(),
            eta: String::new(), downloaded: 0, total: 0, item_index: 0, item_count: 0,
            file_path: None, error: None, created_at: 0, finished_at: None, part_file: None,
        }
    }

    #[test]
    fn video_args() {
        let a = build_args(&job("video", "1080"), &Settings::default());
        let s = a.join(" ");
        assert!(s.contains("-S res:1080,vcodec:h264,acodec:aac"));
        assert!(s.contains("--merge-output-format mp4"));
        assert!(s.contains("--embed-subs"));
        assert!(s.ends_with("-- https://example.com/v"));
    }

    #[test]
    fn audio_args() {
        let s = build_args(&job("audio", "mp3"), &Settings::default()).join(" ");
        assert!(s.contains("-x --audio-format mp3 --audio-quality 320K"));
        assert!(!s.contains("--embed-subs"));
    }

    #[test]
    fn progress_video_then_audio() {
        let mut j = job("video", "1080");
        apply_line(&mut j, "VLPROG|downloading| 50.0%|1.2MiB/s|00:10|100|200|NA|NA|NA|avc1|none|/tmp/a.part");
        assert!((j.percent - 45.0).abs() < 0.01);
        assert_eq!(j.speed, "1.2MiB/s");
        assert_eq!(j.part_file.as_deref(), Some("/tmp/a.part"));
        apply_line(&mut j, "VLPROG|downloading| 50.0%|1.2MiB/s|00:01|1|2|NA|NA|NA|none|mp4a|/tmp/b.part");
        assert!((j.percent - 95.0).abs() < 0.01);
        assert_eq!(j.stage, "Downloading audio");
        apply_line(&mut j, "VLPP|Merger|started");
        assert_eq!(j.status, "processing");
        assert_eq!(j.stage, "Merging audio + video");
        apply_line(&mut j, "VLFILE|/tmp/dl/t [x].mp4");
        assert_eq!(j.file_path.as_deref(), Some("/tmp/dl/t [x].mp4"));
    }

    #[test]
    fn playlist_progress() {
        let mut j = job("audio", "mp3");
        apply_line(&mut j, "VLPROG|downloading|100%|NA|NA|5|5|NA|3|4|none|opus|NA");
        assert_eq!((j.item_index, j.item_count), (3, 4));
        assert!((j.percent - 75.0).abs() < 0.01);
        assert_eq!(j.part_file, None);
    }
}

#[cfg(test)]
mod live {
    use super::*;
    /// Writes the real argument list for a job to $VL_ARGS_OUT (NUL-separated) so a shell test can run yt-dlp with it.
    #[test]
    #[ignore]
    fn dump_args() {
        let out = std::env::var("VL_ARGS_OUT").unwrap();
        let mut j = tests_job();
        j.url = std::env::var("VL_URL").unwrap();
        j.mode = std::env::var("VL_MODE").unwrap_or("video".into());
        j.quality = std::env::var("VL_Q").unwrap_or("720".into());
        j.dir = std::env::var("VL_DIR").unwrap();
        j.subtitles = false;
        std::fs::write(out, build_args(&j, &Settings::default()).join("\0")).unwrap();
    }
    fn tests_job() -> Job {
        Job {
            id: "1".into(), url: String::new(), title: "t".into(), thumbnail: None,
            mode: "video".into(), quality: "720".into(), label: String::new(), playlist: false,
            subtitles: false, embed_thumbnail: true, chapters: true, dir: String::new(),
            status: "downloading".into(), stage: String::new(), percent: 0.0, speed: String::new(),
            eta: String::new(), downloaded: 0, total: 0, item_index: 0, item_count: 0,
            file_path: None, error: None, created_at: 0, finished_at: None, part_file: None,
        }
    }
}
