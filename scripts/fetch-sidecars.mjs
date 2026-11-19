#!/usr/bin/env node
// Puts yt-dlp, ffmpeg and ffprobe into src-tauri/binaries with the target-triple
// suffix Tauri expects (e.g. ffmpeg-x86_64-pc-windows-msvc.exe).
//
//   node scripts/fetch-sidecars.mjs                         # this computer's target
//   node scripts/fetch-sidecars.mjs x86_64-pc-windows-msvc
//   node scripts/fetch-sidecars.mjs universal-apple-darwin  # needs scripts/build-ffmpeg-macos.sh first
//
// FFmpeg is always an LGPL build (no --enable-gpl / --enable-nonfree), shipped as a
// separate program, so Vidloot itself can stay closed-source. See LICENSES.md.

import { execSync } from 'node:child_process';
import { chmodSync, copyFileSync, existsSync, mkdirSync, readdirSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { tmpdir } from 'node:os';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const binDir = join(root, 'src-tauri', 'binaries');
mkdirSync(binDir, { recursive: true });

const target = process.argv[2] || execSync('rustc -vV').toString().match(/host: (\S+)/)[1];
const isWin = target.includes('windows');
const isMac = target.includes('apple-darwin');
const exe = isWin ? '.exe' : '';
const dest = (name) => join(binDir, `${name}-${target}${exe}`);

const YTDLP = 'https://github.com/yt-dlp/yt-dlp/releases/latest/download/';
const WIN_FFMPEG = 'https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-lgpl.zip';

async function download(url, file) {
  process.stdout.write(`Downloading ${url}\n`);
  const res = await fetch(url, { redirect: 'follow' });
  if (!res.ok) throw new Error(`${url} → HTTP ${res.status}`);
  writeFileSync(file, Buffer.from(await res.arrayBuffer()));
  if (!isWin) chmodSync(file, 0o755);
}

function findFile(dir, name) {
  for (const entry of readdirSync(dir)) {
    const p = join(dir, entry);
    if (statSync(p).isDirectory()) {
      const hit = findFile(p, name);
      if (hit) return hit;
    } else if (entry === name) return p;
  }
  return null;
}

// ---- yt-dlp (official standalone builds)
const ytdlpAsset = isWin ? 'yt-dlp.exe' : isMac ? 'yt-dlp_macos' : target.startsWith('aarch64') ? 'yt-dlp_linux_aarch64' : 'yt-dlp_linux';
await download(YTDLP + ytdlpAsset, dest('yt-dlp'));

// ---- ffmpeg + ffprobe
if (isWin) {
  if (!target.startsWith('x86_64')) throw new Error('Only x86_64 Windows is set up. Add an arm64 LGPL build for other targets.');
  const tmp = join(tmpdir(), `vidloot-ffmpeg-${Date.now()}`);
  mkdirSync(tmp, { recursive: true });
  const zip = join(tmp, 'ffmpeg.zip');
  await download(WIN_FFMPEG, zip);
  // Windows' built-in tar (bsdtar) unpacks zip files; elsewhere use unzip.
  const unzipCmd = process.platform === 'win32' ? `tar -xf "${zip}" -C "${tmp}"` : `unzip -q -o "${zip}" -d "${tmp}"`;
  execSync(unzipCmd, { stdio: 'inherit' });
  for (const tool of ['ffmpeg', 'ffprobe']) {
    const found = findFile(tmp, `${tool}.exe`);
    if (!found) throw new Error(`${tool}.exe not found in the FFmpeg zip`);
    copyFileSync(found, dest(tool));
  }
  rmSync(tmp, { recursive: true, force: true });
} else if (isMac) {
  // Built from source by scripts/build-ffmpeg-macos.sh (LGPL, static, universal).
  for (const tool of ['ffmpeg', 'ffprobe']) {
    if (!existsSync(dest(tool))) {
      throw new Error(`Missing ${dest(tool)}. Run: bash scripts/build-ffmpeg-macos.sh`);
    }
  }
  // A universal build compiles each architecture separately first, and Tauri
  // looks for per-architecture sidecars then. The universal files run on both.
  if (target === 'universal-apple-darwin') {
    for (const tool of ['yt-dlp', 'ffmpeg', 'ffprobe']) {
      for (const arch of ['aarch64-apple-darwin', 'x86_64-apple-darwin']) {
        copyFileSync(dest(tool), join(binDir, `${tool}-${arch}`));
        chmodSync(join(binDir, `${tool}-${arch}`), 0o755);
      }
    }
  }
} else {
  // Linux: development only. Use the system ffmpeg.
  for (const tool of ['ffmpeg', 'ffprobe']) {
    const sys = execSync(`command -v ${tool} || true`, { shell: '/bin/sh' }).toString().trim();
    if (!sys) throw new Error(`Install ${tool} (e.g. sudo apt install ffmpeg) for Linux development builds.`);
    copyFileSync(sys, dest(tool));
    chmodSync(dest(tool), 0o755);
  }
}

console.log(`\nSidecars ready for ${target}:`);
for (const f of readdirSync(binDir).filter((f) => f.includes(target))) {
  console.log(`  ${f}  ${(statSync(join(binDir, f)).size / 1e6).toFixed(1)} MB`);
}
