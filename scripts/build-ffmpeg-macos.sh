#!/usr/bin/env bash
# Builds a small, static, LGPL-only FFmpeg + ffprobe for macOS (Apple Silicon
# and Intel combined into one "universal" binary) and puts them in
# src-tauri/binaries as ffmpeg-universal-apple-darwin / ffprobe-universal-apple-darwin.
#
# Why build it: there is no trustworthy prebuilt LGPL FFmpeg for macOS, and a GPL
# build would force Vidloot to be GPL. This build has no --enable-gpl and no
# --enable-nonfree; LAME (LGPL) and Opus (BSD) are the only extra libraries.
#
# Runs on any Apple Silicon Mac or the GitHub "macos-latest" runner (~10 minutes).
# Needs Xcode command line tools and nasm (`brew install nasm pkg-config`).

set -euo pipefail

FFMPEG_VERSION="${FFMPEG_VERSION:-7.1.1}"
LAME_VERSION="3.100"
OPUS_VERSION="1.5.2"
MIN_MACOS="11.0"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/src-tauri/binaries"
WORK="${WORK:-$ROOT/.ffmpeg-build}"
JOBS="$(sysctl -n hw.ncpu)"
mkdir -p "$WORK/src" "$OUT"

fetch() { # url file
  [ -f "$WORK/src/$2" ] || curl -fsSL --retry 3 -o "$WORK/src/$2" "$1"
}
fetch "https://ffmpeg.org/releases/ffmpeg-$FFMPEG_VERSION.tar.xz" "ffmpeg.tar.xz"
fetch "https://downloads.sourceforge.net/project/lame/lame/$LAME_VERSION/lame-$LAME_VERSION.tar.gz" "lame.tar.gz"
fetch "https://downloads.xiph.org/releases/opus/opus-$OPUS_VERSION.tar.gz" "opus.tar.gz"

build_arch() {
  local arch="$1" host
  case "$arch" in
    arm64) host="aarch64-apple-darwin" ;;
    x86_64) host="x86_64-apple-darwin" ;;
  esac
  local prefix="$WORK/$arch"
  if [ -x "$prefix/bin/ffmpeg" ] && [ -x "$prefix/bin/ffprobe" ]; then
    echo "== $arch already built"; return
  fi
  rm -rf "$WORK/build-$arch" && mkdir -p "$WORK/build-$arch" "$prefix"
  local cc="clang -arch $arch -mmacosx-version-min=$MIN_MACOS"
  export CC="$cc" CFLAGS="-O2" PKG_CONFIG_LIBDIR="$prefix/lib/pkgconfig" PKG_CONFIG_PATH=""

  echo "== $arch: LAME"
  tar -xzf "$WORK/src/lame.tar.gz" -C "$WORK/build-$arch"
  ( cd "$WORK/build-$arch/lame-$LAME_VERSION"
    # Known LAME 3.100 build break on macOS: an exported symbol that no longer exists.
    sed -i '' '/lame_init_old/d' include/libmp3lame.sym
    ./configure --host="$host" --prefix="$prefix" --disable-shared --enable-static \
      --disable-frontend --disable-decoder --disable-dependency-tracking >/dev/null
    make -j"$JOBS" >/dev/null && make install >/dev/null )

  echo "== $arch: Opus"
  tar -xzf "$WORK/src/opus.tar.gz" -C "$WORK/build-$arch"
  ( cd "$WORK/build-$arch/opus-$OPUS_VERSION"
    ./configure --host="$host" --prefix="$prefix" --disable-shared --enable-static \
      --disable-doc --disable-extra-programs --disable-dependency-tracking >/dev/null
    make -j"$JOBS" >/dev/null && make install >/dev/null )

  echo "== $arch: FFmpeg $FFMPEG_VERSION"
  tar -xJf "$WORK/src/ffmpeg.tar.xz" -C "$WORK/build-$arch"
  local cross=()
  [ "$arch" != "$(uname -m)" ] && cross=(--enable-cross-compile --target-os=darwin)
  ( cd "$WORK/build-$arch/ffmpeg-$FFMPEG_VERSION"
    ./configure "${cross[@]}" \
      --prefix="$prefix" --arch="$arch" --cc="$cc" \
      --extra-cflags="-I$prefix/include" --extra-ldflags="-L$prefix/lib" \
      --pkg-config-flags=--static \
      --enable-static --disable-shared --disable-debug --disable-doc --disable-ffplay \
      --disable-autodetect \
      --enable-zlib --enable-bzlib --enable-iconv \
      --enable-audiotoolbox --enable-videotoolbox \
      --enable-libmp3lame --enable-libopus \
      >/dev/null
    make -j"$JOBS" >/dev/null && make install >/dev/null )
}

build_arch arm64
build_arch x86_64

for tool in ffmpeg ffprobe; do
  lipo -create "$WORK/arm64/bin/$tool" "$WORK/x86_64/bin/$tool" -output "$OUT/$tool-universal-apple-darwin"
  chmod 755 "$OUT/$tool-universal-apple-darwin"
  # Must only link against macOS itself, never Homebrew.
  if otool -L "$OUT/$tool-universal-apple-darwin" | tail -n +2 | grep -vE '^\s*(/usr/lib/|/System/Library/)'; then
    echo "ERROR: $tool links to a non-system library (listed above)." >&2; exit 1
  fi
done

# Prove it's an LGPL build.
"$WORK/$(uname -m)/bin/ffmpeg" -hide_banner -L | grep -qi "lesser general public" \
  || { echo "ERROR: FFmpeg did not build as LGPL." >&2; exit 1; }

echo "Done:"
ls -lh "$OUT"/*-universal-apple-darwin
