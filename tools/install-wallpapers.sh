#!/usr/bin/env bash
# Copies wallpapers to Quiet Home on an Android TV device over adb.
#
# Usage:
#   tools/install-wallpapers.sh [-s SERIAL] [--keep DIR] SOURCE...
#   tools/install-wallpapers.sh [-s SERIAL] --list
#   tools/install-wallpapers.sh [-s SERIAL] --remove NAME...
#
# SOURCE is either a local image file (jpg, jpeg, png, webp) or an Unsplash photo ID,
# optionally with a display name: rr2EF3J5F2Y=Forest_mist. Underscores show as spaces in
# Quiet Home's settings. Unsplash photos are downloaded at 2560 px width into the --keep
# directory (default: a temporary directory that is deleted afterwards).
set -euo pipefail

package="io.github.nicolaspogorzelski.quiethome"
remote="/sdcard/Android/data/$package/files/wallpapers"
adb_args=()
keep=""
mode="install"
sources=()

usage() { sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }

while [ $# -gt 0 ]; do
    case "$1" in
        -s) adb_args=(-s "$2"); shift 2 ;;
        --keep) keep="$2"; shift 2 ;;
        --list) mode="list"; shift ;;
        --remove) mode="remove"; shift ;;
        -h|--help) usage ;;
        *) sources+=("$1"); shift ;;
    esac
done

adb_() { adb "${adb_args[@]}" "$@"; }

case "$mode" in
    list)
        adb_ shell ls -1 "$remote" 2>/dev/null || echo "No wallpapers installed."
        exit 0 ;;
    remove)
        [ ${#sources[@]} -gt 0 ] || usage 1
        for name in "${sources[@]}"; do adb_ shell rm -f "$remote/$name"; echo "Removed $name"; done
        exit 0 ;;
esac

[ ${#sources[@]} -gt 0 ] || usage 1

if [ -n "$keep" ]; then
    mkdir -p "$keep"
    work="$keep"
else
    work="$(mktemp -d)"
    trap 'rm -rf "$work"' EXIT
fi

adb_ shell mkdir -p "$remote"

for source in "${sources[@]}"; do
    if [ -f "$source" ]; then
        file="$source"
    else
        id="${source%%=*}"
        name="${source#*=}"
        [ "$name" != "$source" ] || name="$id"
        if ! [[ "$id" =~ ^[A-Za-z0-9_-]{11}$ ]]; then
            echo "Skipping '$source': neither a file nor an Unsplash photo ID." >&2
            continue
        fi
        # The download endpoint redirects to the image CDN; w= limits the size.
        url="$(curl -fsS -o /dev/null -w '%{redirect_url}' "https://unsplash.com/photos/$id/download?force=true")"
        if [ -z "$url" ]; then
            echo "Skipping '$id': Unsplash returned no download." >&2
            continue
        fi
        file="$work/$name.jpg"
        curl -fsSL -o "$file" "$url&w=2560"
        echo "Downloaded $id as $(basename "$file")"
    fi
    adb_ push "$file" "$remote/" >/dev/null 2>&1
    echo "Installed $(basename "$file")"
done

echo "Done. Choose the wallpaper in Quiet Home under Settings > Hintergrund."
