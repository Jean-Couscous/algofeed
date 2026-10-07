#!/usr/bin/env bash
# Builds Algofeed-<version>-x86_64.AppImage from the Compose app image (bundled Java runtime).
# Usage: build-appimage.sh <app-image-dir> <version> [output-dir]
#   app-image-dir: desktopApp/build/compose/binaries/main/app/algofeed (from createDistributable)
set -euo pipefail

app_image=$(realpath "$1")
version=$2
out=$(realpath "${3:-.}")
here=$(dirname "$(realpath "$0")")
repo=$(realpath "$here/../..")

# Pinned so a new appimagetool release can't change the output unnoticed.
tool_url=https://github.com/AppImage/appimagetool/releases/download/1.9.1/appimagetool-x86_64.AppImage

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
appdir="$work/Algofeed.AppDir"
mkdir -p "$appdir/usr"
cp -a "$app_image/." "$appdir/usr/"
ln -s usr/bin/algofeed "$appdir/AppRun"
cp "$repo/packaging/linux/algofeed.desktop" "$appdir/algofeed.desktop"
cp "$repo/desktopApp/icon.png" "$appdir/algofeed.png"

curl -fsSL -o "$work/appimagetool" "$tool_url"
chmod +x "$work/appimagetool"
# --appimage-extract-and-run: CI machines have no FUSE.
ARCH=x86_64 "$work/appimagetool" --appimage-extract-and-run "$appdir" "$out/Algofeed-$version-x86_64.AppImage"
