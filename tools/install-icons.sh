#!/usr/bin/env bash
# Installs white app marks for Quiet Home's Quiet style on an Android TV device over adb.
#
# Usage:
#   tools/install-icons.sh [-s SERIAL]                     Simple Icons marks for every app in
#                                                          tools/icons.tsv that is installed
#   tools/install-icons.sh [-s SERIAL] PACKAGE=SOURCE...   SOURCE: a Simple Icons name (netflix)
#                                                          or a local .svg or .png file
#   tools/install-icons.sh [-s SERIAL] --list
#   tools/install-icons.sh [-s SERIAL] --remove PACKAGE...
#
# Marks are downloaded from Simple Icons (CC0 drawings; the brands stay trademarks of their
# owners) for your own device. Quiet Home picks them up the next time Home is shown.
set -euo pipefail

package="io.github.nicolaspogorzelski.quiethome"
remote="/sdcard/Android/data/$package/files/icons"
simple_icons="https://cdn.jsdelivr.net/npm/simple-icons@16.34.0/icons"
mapping="$(dirname "$0")/icons.tsv"
adb_args=()
mode="install"
sources=()

usage() { sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }

while [ $# -gt 0 ]; do
    case "$1" in
        -s) adb_args=(-s "$2"); shift 2 ;;
        --list) mode="list"; shift ;;
        --remove) mode="remove"; shift ;;
        -h|--help) usage ;;
        *) sources+=("$1"); shift ;;
    esac
done

adb_() { adb "${adb_args[@]}" "$@"; }

case "$mode" in
    list)
        adb_ shell ls -1 "$remote" 2>/dev/null || echo "No icons installed."
        exit 0 ;;
    remove)
        [ ${#sources[@]} -gt 0 ] || usage 1
        for name in "${sources[@]}"; do
            adb_ shell rm -f "$remote/$name.svg" "$remote/$name.png"
            echo "Removed $name"
        done
        exit 0 ;;
esac

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

if [ ${#sources[@]} -eq 0 ]; then
    installed="$(adb_ shell pm list packages | sed 's/^package://' | tr -d '\r')"
    while IFS=$'\t' read -r app slug; do
        # Skip empty lines and comments.
        if [ -z "$app" ] || [ "${app:0:1}" = "#" ]; then continue; fi
        grep -qx "$app" <<< "$installed" && sources+=("$app=$slug")
    done < "$mapping"
    [ ${#sources[@]} -gt 0 ] || { echo "None of the apps in $mapping is installed."; exit 0; }
fi

adb_ shell mkdir -p "$remote"

for source in "${sources[@]}"; do
    app="${source%%=*}"
    from="${source#*=}"
    if [ "$app" = "$source" ] || [ -z "$from" ]; then
        echo "Skipping '$source': expected PACKAGE=SOURCE." >&2
        continue
    fi
    if [ -f "$from" ]; then
        case "${from,,}" in
            *.svg) ext="svg" ;;
            *.png) ext="png" ;;
            *) echo "Skipping '$from': only .svg and .png are supported." >&2; continue ;;
        esac
        file="$from"
    else
        ext="svg"
        file="$work/$app.svg"
        if ! curl -fsSL -o "$file" "$simple_icons/$from.svg"; then
            echo "Skipping '$app': no Simple Icons mark named '$from'." >&2
            continue
        fi
    fi
    # An .svg wins over a .png, so remove the other kind before installing.
    adb_ shell rm -f "$remote/$app.svg" "$remote/$app.png"
    adb_ push "$file" "$remote/$app.$ext" >/dev/null 2>&1
    echo "Installed $app ($from)"
done
