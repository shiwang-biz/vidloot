//! "Fetch info": ask yt-dlp what a link contains and turn it into the choices
//! the Download screen shows (qualities with estimated sizes, audio formats).

use serde::Serialize;
use serde_json::Value;
use std::path::Path;
use std::time::Duration;

use crate::paths;

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct QualityOption {
    /// "2160", "1080" … or "best" when the site lists no heights.
    pub id: String,
    pub label: String,
    pub height: u32,
    pub size: Option<u64>,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AudioOption {
    pub id: String,
    pub label: String,
    pub note: String,
    pub size: Option<u64>,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MediaInfo {
    pub url: String,
    pub is_playlist: bool,
    pub title: String,
    pub uploader: Option<String>,
    pub site: String,
    pub duration: Option<f64>,
    pub thumbnail: Option<String>,
    pub entry_count: Option<u64>,
    /// A single video whose link also points at a playlist (…&list=…).
    pub in_playlist: bool,
    pub max_height: u32,
    pub has_subtitles: bool,
    pub video_options: Vec<QualityOption>,
    pub audio_options: Vec<AudioOption>,
}

const BANDS: [(u32, u32, &str); 6] = [
    (2160, 1800, "4K"),
    (1440, 1300, "1440p"),
    (1080, 1000, "1080p"),
    (720, 700, "720p"),
    (480, 440, "480p"),
    (360, 0, "360p"),
];

fn band_of(h: u32) -> u32 {
    for (std, min, _) in BANDS {
        if h >= min {
            return std;
        }
    }
    360
}

fn num(v: &Value, k: &str) -> Option<f64> {
    v.get(k).and_then(|x| x.as_f64())
}

fn text(v: &Value, k: &str) -> Option<String> {
    v.get(k).and_then(|x| x.as_str()).filter(|s| !s.is_empty()).map(String::from)
}

fn is_none(v: &Value, k: &str) -> bool {
    matches!(v.get(k).and_then(|x| x.as_str()), Some("none"))
}

/// Size of one format in bytes, exact or estimated.
fn fmt_size(f: &Value, duration: Option<f64>) -> Option<u64> {
    num(f, "filesize")
        .or_else(|| num(f, "filesize_approx"))
        .or_else(|| match (num(f, "tbr"), duration) {
            (Some(tbr), Some(d)) => Some(tbr * 125.0 * d),
            _ => None,
        })
        .map(|b| b as u64)
}

pub async fn fetch(ytdlp: &Path, url: &str) -> Result<MediaInfo, String> {
    let url = url.trim();
    if !(url.starts_with("http://") || url.starts_with("https://")) {
        return Err("That doesn't look like a link. It should start with https://".into());
    }
    let mut cmd = paths::command(ytdlp);
    // --no-playlist: a "watch?v=…&list=…" link shows the one video; the UI
    // then offers "whole playlist" because in_playlist is set.
    cmd.args(["-J", "--flat-playlist", "--no-playlist", "--no-warnings", "--color", "never", "--encoding", "utf-8", "--", url])
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::piped())
        .kill_on_drop(true);
    let out = tokio::time::timeout(Duration::from_secs(90), cmd.output())
        .await
        .map_err(|_| "The site took too long to answer. Try again.".to_string())?
        .map_err(|e| format!("Could not start the download engine: {e}"))?;
    if !out.status.success() {
        return Err(clean_error(&String::from_utf8_lossy(&out.stderr)));
    }
    let v: Value = serde_json::from_slice(&out.stdout)
        .map_err(|_| "The engine returned something unexpected for this link.".to_string())?;
    Ok(parse(url, &v))
}

pub fn parse(url: &str, v: &Value) -> MediaInfo {
    let is_playlist = matches!(v.get("_type").and_then(|t| t.as_str()), Some("playlist" | "multi_video"));
    let title = text(v, "title").unwrap_or_else(|| "Untitled".into());
    let uploader = text(v, "uploader").or_else(|| text(v, "channel")).or_else(|| text(v, "playlist_uploader"));
    let site = text(v, "extractor_key")
        .or_else(|| text(v, "webpage_url_domain"))
        .unwrap_or_else(|| "Web".into());
    let duration = num(v, "duration");
    let mut thumbnail = text(v, "thumbnail");

    if is_playlist {
        let entries = v.get("entries").and_then(|e| e.as_array());
        let count = num(v, "playlist_count").map(|c| c as u64).or_else(|| entries.map(|e| e.len() as u64));
        if thumbnail.is_none() {
            thumbnail = entries
                .and_then(|e| e.first())
                .and_then(|first| first.get("thumbnails"))
                .and_then(|t| t.as_array())
                .and_then(|t| t.last())
                .and_then(|t| text(t, "url"));
        }
        let video_options = BANDS
            .iter()
            .map(|(h, _, l)| QualityOption { id: h.to_string(), label: l.to_string(), height: *h, size: None })
            .collect();
        return MediaInfo {
            url: url.into(),
            is_playlist: true,
            title,
            uploader,
            site,
            duration: None,
            thumbnail,
            entry_count: count,
            in_playlist: false,
            max_height: 2160,
            has_subtitles: true,
            video_options,
            audio_options: audio_options(None, None, None),
        };
    }

    let formats: Vec<&Value> = v.get("formats").and_then(|f| f.as_array()).map(|a| a.iter().collect()).unwrap_or_default();

    // Best audio-only stream (for size estimates).
    let best_audio = formats
        .iter()
        .filter(|f| is_none(f, "vcodec") && !is_none(f, "acodec"))
        .max_by(|a, b| num(a, "abr").or(num(a, "tbr")).unwrap_or(0.0).total_cmp(&num(b, "abr").or(num(b, "tbr")).unwrap_or(0.0)));
    let audio_size = best_audio.and_then(|f| fmt_size(f, duration));
    let opus_size = formats
        .iter()
        .filter(|f| is_none(f, "vcodec") && f.get("acodec").and_then(|a| a.as_str()).map_or(false, |a| a.starts_with("opus")))
        .filter_map(|f| fmt_size(f, duration))
        .max();

    // Best video stream per height band.
    let mut bands: Vec<(u32, Option<u64>)> = Vec::new();
    for f in formats.iter().filter(|f| !is_none(f, "vcodec")) {
        let Some(h) = num(f, "height").map(|h| h as u32).filter(|h| *h > 0) else { continue };
        let band = band_of(h);
        let has_audio = !is_none(f, "acodec") && f.get("acodec").is_some();
        let size = fmt_size(f, duration).map(|s| if has_audio { s } else { s + audio_size.unwrap_or(0) });
        match bands.iter_mut().find(|(b, _)| *b == band) {
            Some(entry) => {
                if size > entry.1 {
                    entry.1 = size;
                }
            }
            None => bands.push((band, size)),
        }
    }
    bands.sort_by(|a, b| b.0.cmp(&a.0));
    let max_height = bands.first().map(|b| b.0).unwrap_or(0);

    let video_options: Vec<QualityOption> = if bands.is_empty() {
        vec![QualityOption { id: "best".into(), label: "Best available".into(), height: 0, size: num(v, "filesize").or(num(v, "filesize_approx")).map(|s| s as u64) }]
    } else {
        bands
            .iter()
            .map(|(h, size)| {
                let label = BANDS.iter().find(|b| b.0 == *h).map(|b| b.2).unwrap_or("Video");
                QualityOption { id: h.to_string(), label: label.into(), height: *h, size: *size }
            })
            .collect()
    };

    let has_subtitles = v.get("subtitles").and_then(|s| s.as_object()).map_or(false, |o| !o.is_empty());

    MediaInfo {
        url: url.into(),
        is_playlist: false,
        title,
        uploader,
        site,
        duration,
        thumbnail,
        entry_count: None,
        in_playlist: url.contains("list="),
        max_height,
        has_subtitles,
        video_options,
        audio_options: audio_options(duration, audio_size, opus_size),
    }
}

fn audio_options(duration: Option<f64>, best: Option<u64>, opus: Option<u64>) -> Vec<AudioOption> {
    let est = |bytes_per_sec: f64| duration.map(|d| (d * bytes_per_sec) as u64);
    vec![
        AudioOption { id: "mp3".into(), label: "MP3".into(), note: "320 kbps · plays everywhere".into(), size: est(40_000.0) },
        AudioOption { id: "m4a".into(), label: "M4A".into(), note: "Best quality".into(), size: best.or(est(16_000.0)) },
        AudioOption { id: "opus".into(), label: "Opus".into(), note: "Smallest file".into(), size: opus.or(est(16_000.0)) },
        AudioOption { id: "wav".into(), label: "WAV".into(), note: "Lossless, very large".into(), size: est(176_400.0) },
    ]
}

/// Turn yt-dlp's error output into one readable sentence.
pub fn clean_error(stderr: &str) -> String {
    let line = stderr
        .lines()
        .rev()
        .find(|l| l.starts_with("ERROR:"))
        .or_else(|| stderr.lines().rev().find(|l| !l.trim().is_empty()))
        .unwrap_or("Something went wrong.")
        .trim_start_matches("ERROR:")
        .trim();
    let lower = line.to_lowercase();
    if lower.contains("unsupported url") {
        "This site isn't supported, or the link isn't a video page.".into()
    } else if lower.contains("private video") || lower.contains("sign in") || lower.contains("login") {
        "This video needs a sign-in (private, members-only or age-restricted).".into()
    } else if lower.contains("unable to connect") || lower.contains("timed out") || lower.contains("name resolution") || lower.contains("getaddrinfo") {
        "Couldn't reach the site. Check your internet connection.".into()
    } else if lower.contains("http error 404") || lower.contains("not available") || lower.contains("removed") {
        "This video isn't available anymore.".into()
    } else {
        // Drop the "[extractor] id:" prefix yt-dlp adds.
        let msg = match line.find("]") {
            Some(i) if line.starts_with('[') => line[i + 1..].splitn(2, ':').last().unwrap_or(line).trim(),
            _ => line,
        };
        msg.chars().take(220).collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bands_and_sizes() {
        let v: Value = serde_json::json!({
            "title": "Clip", "duration": 100.0, "extractor_key": "Youtube",
            "formats": [
                {"format_id": "a", "vcodec": "none", "acodec": "opus", "abr": 130.0, "filesize": 1_000_000},
                {"format_id": "v1", "vcodec": "avc1", "acodec": "none", "height": 1080, "filesize": 50_000_000},
                {"format_id": "v2", "vcodec": "vp9", "acodec": "none", "height": 2160, "filesize": 200_000_000},
                {"format_id": "v3", "vcodec": "avc1", "acodec": "none", "height": 720, "tbr": 1000.0},
                {"format_id": "c", "vcodec": "avc1", "acodec": "mp4a", "height": 360, "filesize": 5_000_000}
            ]
        });
        let i = parse("https://x", &v);
        assert_eq!(i.max_height, 2160);
        let ids: Vec<_> = i.video_options.iter().map(|o| o.id.as_str()).collect();
        assert_eq!(ids, ["2160", "1080", "720", "360"]);
        assert_eq!(i.video_options[1].size, Some(51_000_000));
        assert_eq!(i.video_options[2].size, Some(12_500_000 + 1_000_000));
        assert_eq!(i.video_options[3].size, Some(5_000_000));
        assert_eq!(i.audio_options[0].size, Some(4_000_000));
    }

    #[test]
    fn errors_are_readable() {
        assert_eq!(clean_error("ERROR: Unsupported URL: https://a"), "This site isn't supported, or the link isn't a video page.");
        assert_eq!(clean_error("WARNING: x\nERROR: [generic] abc: Something odd happened"), "Something odd happened");
    }
}
