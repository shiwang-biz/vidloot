# Third-party software in Vidloot

Vidloot ships two separate programs next to the app. They run as their own
processes; Vidloot's code is not linked with them.

| Component | License | What you must do |
| --- | --- | --- |
| **yt-dlp** (standalone build) | The Unlicense (public domain) | Nothing required. Crediting it is good manners. |
| **FFmpeg / ffprobe** | **LGPL v2.1 or later** (no `--enable-gpl`, no `--enable-nonfree`) | Credit FFmpeg and its LGPL license in the app (Settings → About does this) and in your website/installer notes; say where the source is (ffmpeg.org, and the exact version/build script in this repo); let users replace the FFmpeg binary (it is a separate file, so they can). |
| **LAME** (inside the macOS FFmpeg build) | LGPL | Covered by the FFmpeg notes above. |
| **Opus** (inside FFmpeg) | BSD | Keep its copyright notice in your third-party notices. |
| **Tauri** and Rust crates | MIT / Apache-2.0 | Keep their notices (run `cargo about` or similar to generate a list). |

**Never** swap in a GPL FFmpeg build (for example one built with libx264). That
would make the whole Vidloot app GPL, which you can't sell closed-source.

* Windows uses BtbN's `win64-lgpl` build: <https://github.com/BtbN/FFmpeg-Builds>
* macOS uses `scripts/build-ffmpeg-macos.sh`, which checks the result is LGPL.

This is a practical summary, not legal advice.
