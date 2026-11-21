# Vidloot — desktop downloader (Mac & Windows)

Vidloot is a Tauri 2 app around **yt-dlp** and **FFmpeg**. You paste a link, pick
Video or Audio and a quality, and download. There's a queue with pause, resume and
cancel, and licensing runs through the Bizfunnel License Manager.

```
vidloot/
├─ ui/                       The interface (plain HTML/CSS/JS, no build step)
│  ├─ index.html, styles.css
│  └─ app.js                 BUY_URL (your checkout page) is at the top
├─ src-tauri/
│  ├─ src/
│  │  ├─ lib.rs              Commands the UI calls; app startup
│  │  ├─ engine.rs           Download queue: runs yt-dlp, parses progress, pause/resume/cancel
│  │  ├─ info.rs             "Fetch info": qualities, sizes, readable errors
│  │  ├─ license.rs          Licensing adapter + plan limits (Free / Pro)
│  │  ├─ settings.rs         Saved preferences
│  │  └─ paths.rs            Where yt-dlp / ffmpeg live; the updatable engine copy
│  ├─ binaries/              yt-dlp, ffmpeg, ffprobe per platform (fetched, not committed)
│  └─ tauri.conf.json        App name, window, bundle (dmg, nsis, msi)
├─ scripts/
│  ├─ fetch-sidecars.mjs     Downloads yt-dlp + LGPL FFmpeg for a target
│  └─ build-ffmpeg-macos.sh  Builds the LGPL, universal FFmpeg for macOS
├─ .github/workflows/build.yml   Builds the Windows installer + macOS .dmg
└─ LICENSES.md               What you must credit (FFmpeg is LGPL)
```

## Run it on your computer

Needs Node 20+, Rust (rustup.rs), and on Windows the WebView2 runtime (already on Windows 10/11).

```bash
npm install
node scripts/fetch-sidecars.mjs        # Windows/Linux: downloads everything
# macOS only, once (≈10 min):  brew install nasm pkg-config && bash scripts/build-ffmpeg-macos.sh
#                              then: node scripts/fetch-sidecars.mjs universal-apple-darwin
npm run dev
```

## Build the installers

Push the project to a GitHub repo and run **Actions → Build Vidloot** (or push a tag such as `v1.0.0`).
You get:

* **Windows:** `Vidloot_1.0.0_x64-setup.exe` (and an `.msi`)
* **macOS:** `Vidloot_1.0.0_universal.dmg` (Apple Silicon + Intel)

**Signing (recommended before selling):**

* *macOS:* add the `APPLE_*` secrets listed in the workflow (Developer ID certificate + notarization). Without them, users right-click → Open the first time.
* *Windows:* unsigned installers show a SmartScreen warning until the app builds reputation. A code-signing certificate removes it. Tauri's docs cover `signCommand` / Azure Trusted Signing.

## Plans: what Free and Pro get

Plan limits are enforced in Rust (`engine.rs` → `enqueue` and `pump`), so editing the UI can't bypass them.

| | Free | Pro (from your license plan) |
|---|---|---|
| Max video quality | 720p | `max_height` feature (0 = no limit) |
| Whole playlists | no | `playlists: true` |
| Subtitles | no | `subtitles: true` |
| Downloads at once | 1 | `parallel_downloads` (e.g. 3) |

Change the Free limits in `license.rs → Entitlements::free()`.

## Licensing (Bizfunnel License Manager)

Desktop uses your Rust SDK as is (`crates/bizfunnel-license`). Android uses a Kotlin port of the same
client (`android/.../license/LicenseClient.kt`, same protocol and checks, with tests).

**Set up once in the License Manager panel:** a product with binding **Device**, slug `vidloot-desktop`
(one key then works on PC, Mac and phone; each device uses one seat). On the Pro plan add:

| Panel setting | Type | Vidloot meaning |
|---|---|---|
| `playlists` | feature | whole playlists |
| `subtitles` | feature | subtitles |
| `max_height` | limit | highest video height; 0 or not set = no limit (4K) |
| `parallel_downloads` | limit | downloads at once; not set = 3 |

**Set up once in GitHub:** repo → Settings → Secrets and variables → Actions → **Variables** tab:

| Variable | Value |
|---|---|
| `LICENSE_API_URL` | e.g. `https://licenses.yourdomain.com/api/v1` |
| `LICENSE_PUBLIC_KEY` | Settings & API → Public key (never the private key) |
| `LICENSE_KEY_ID` | optional, default `k1` |
| `LICENSE_PRODUCT` | optional, default `vidloot-desktop` |
| `LICENSE_PRODUCT_ANDROID` | optional: a separate Android product slug |

Builds without `LICENSE_API_URL` and `LICENSE_PUBLIC_KEY` run on the Free plan and are **not** published as releases.

## Things to set before release

1. `ui/app.js` → `BUY_URL`: your checkout page. While it's empty, buy buttons are hidden.
2. `src-tauri/tauri.conf.json` → `identifier`, `copyright`, version.
3. Licensing: see the section above.

## Keeping downloads working

Sites change often. **Settings → Download engine → Update engine** updates a private copy of yt-dlp in the app's data
folder, so users can fix a broken site without waiting for a new Vidloot release. Ship a new Vidloot build when
you want everyone on a newer bundled yt-dlp.

## Tests

```bash
cd src-tauri && cargo test     # argument building, progress parsing, quality/size parsing, error messages
```

## Legal

Only promote Vidloot for content users own or may save (their own uploads, Creative Commons, public domain).
Downloading from YouTube breaks YouTube's Terms of Service, so app stores won't list this app. Distribute it from
your own site. See LICENSES.md for FFmpeg's LGPL duties. This isn't legal advice.
