# Quiet Home

A small, ad-free home screen for the NVIDIA SHIELD TV (2019, Android 11). It replaces the stock
launcher with a clock, a glass dock of app banners and nothing else: no recommendations, no
channels, no network access.

The look follows [CleanFrame](https://github.com/nthnclrk/CleanFrame) by Nathan Clark, a home
screen for Samsung's The Frame TV. CleanFrame is a Tizen web app; this is a separate Android
implementation of its Dock layout, not a port of its code.

## Features

- **Home:** clock top left, *All apps* and *Settings* top right, a glass dock with the apps on Home.
  A long press on OK picks a dock app up, ◀ ▶ moves it, OK places it.
- **All apps:** every launchable app in a five-column grid. OK opens an app; a long press on OK
  adds it to Home or removes it. (The SHIELD keeps the remote's ☰ key for its own menu, so it
  cannot be used here.)
- **Settings:** wallpaper (your own photos or a built-in gradient), optional rotation on each
  return to Home, and the app the remote's Netflix key starts.
- **Netflix key remap:** an accessibility service catches the SHIELD remote's Netflix key
  (`BUTTON_12` in `Vendor_0955_Product_7217.kl`) and starts the chosen app instead.
- **Readable text on any photo:** Quiet Home measures the blurred photo behind the clock, the
  controls and the app name, and picks light or dark text by WCAG contrast. Where neither reaches
  4.5:1, the text gets a tight halo in the opposite tone.
- **Glass without live blur:** when a wallpaper loads, Quiet Home blurs a 160×90 copy of it once;
  that copy backs the glass surfaces, so nothing is blurred while drawing.

## Performance

Measured on a SHIELD TV Pro 2019 (Android 11, build 7825230_4387.0822) with `dumpsys meminfo`:

| | Total PSS | Swap PSS | Cold start |
| --- | --- | --- | --- |
| Quiet Home 0.1.0 | 112–118 MB | 0.3 MB | 890 ms |
| Projectivy Launcher 4.72 | 173–210 MB | 55–59 MB | not measured |

About 60 MB of native heap is the graphics stack's baseline on this device: an empty test screen
used 17 MB, any screen with text and shapes about 60 MB, and Projectivy has the same allocation
(about 70 MB), most of it swapped out. The app uses only framework views (no Compose, no AndroidX)
to keep the rest small.

## Requirements

- An Android TV device with Android 11 (API 30) or newer. Only the SHIELD TV 2019 has been tested.
- To build: JDK 17 or 21 and the Android SDK (platform 35, build-tools 35.0.0). Gradle comes with
  the wrapper.
- ADB access to the device (USB or network debugging enabled).

## Build

```bash
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew lintRelease assembleRelease
```

The APK is written to `app/build/outputs/apk/release/app-release.apk`. Release builds are signed
with the debug key, because the app is only sideloaded.

## Install and make it the home screen

```bash
adb connect <device-ip>:5555
adb install -r app/build/outputs/apk/release/app-release.apk

# Make it the home screen
adb shell cmd package set-home-activity io.github.nicolaspogorzelski.quiethome/.HomeActivity

# Enable the Netflix key remap (this replaces any other enabled accessibility services)
adb shell settings put secure enabled_accessibility_services \
  io.github.nicolaspogorzelski.quiethome/io.github.nicolaspogorzelski.quiethome.KeyRemapService
adb shell settings put secure accessibility_enabled 1
```

Android drops the chosen home app whenever another app that can be a home screen is installed.
The next press on Home then asks once; choose Quiet Home and *Always*.

## Wallpapers

Quiet Home ships without photos. Until you add some, it shows a built-in dark gradient. It reads
wallpapers from its own folder on the device:

```
/sdcard/Android/data/io.github.nicolaspogorzelski.quiethome/files/wallpapers/
```

The folder exists once Quiet Home has been started. Supported formats are JPEG, PNG and WebP.
Any size works; photos are centre-cropped to 16:9 and scaled to 1920×1080, so 16:9 photos of at
least 1920×1080 look best. The file name is the name in Settings, with underscores shown as
spaces (`Blue_peaks.jpg` appears as "Blue peaks").

### With the script

[`tools/install-wallpapers.sh`](tools/install-wallpapers.sh) needs `adb` and `curl`. It takes local
files, Unsplash photo IDs, or both:

```bash
# Local photos
tools/install-wallpapers.sh ~/Pictures/beach.jpg ~/Pictures/Night_sky.png

# Photos from Unsplash: the ID is the last part of the photo's URL,
# unsplash.com/photos/<ID>; "=Name" sets the file name. Downloads are 2560 px wide.
tools/install-wallpapers.sh rQqWOHZ96OM=Blue_peaks M6XC789HLe8=Mountain_valley

# Keep the downloaded files in a folder instead of a temporary directory
tools/install-wallpapers.sh --keep ~/Pictures/quiet-home rQqWOHZ96OM=Blue_peaks

# Show and remove installed wallpapers
tools/install-wallpapers.sh --list
tools/install-wallpapers.sh --remove Blue_peaks.jpg
```

If more than one device is connected, select one with `-s <device-ip>:5555`, the address shown by
`adb devices`. Then choose the photo in Quiet Home under *Einstellungen → Hintergrund* (Settings →
Wallpaper; the interface is German) with ◀ ▶.

### By hand

```bash
adb shell mkdir -p /sdcard/Android/data/io.github.nicolaspogorzelski.quiethome/files/wallpapers
adb push Blue_peaks.jpg /sdcard/Android/data/io.github.nicolaspogorzelski.quiethome/files/wallpapers/
```

A photo replaced under the same file name shows after Quiet Home restarts. Uninstalling Quiet
Home deletes the folder with the photos in it.

### Suggested photos

The design reference, CleanFrame, uses Unsplash photos. These five look good with the dock. All
five pages showed the [Unsplash License](https://unsplash.com/license) when checked on 2026-10-24;
check the page yourself before you use a photo, since the terms are between you, Unsplash and the
photographer.

| ID | Photo | Photographer |
| --- | --- | --- |
| `rr2EF3J5F2Y` | [Misty evergreen forest](https://unsplash.com/photos/rr2EF3J5F2Y) | Dejan Zakic |
| `M6XC789HLe8` | [Granite cliffs and waterfall in Yosemite](https://unsplash.com/photos/M6XC789HLe8) | Aniket Deole |
| `rQqWOHZ96OM` | [Snow capped mountains](https://unsplash.com/photos/rQqWOHZ96OM) | John Rodenn Castillo |
| `pbp_Ww2cewA` | [Golden Gate Bridge](https://unsplash.com/photos/pbp_Ww2cewA) | Justin Wolff |
| `tmtkhWbgMu8` | [Ocean with rocks, Big Sur](https://unsplash.com/photos/tmtkhWbgMu8) | Karthik Sreenivas |

```bash
tools/install-wallpapers.sh rr2EF3J5F2Y=Forest_mist M6XC789HLe8=Mountain_valley \
  rQqWOHZ96OM=Blue_peaks pbp_Ww2cewA=Bridge_at_dusk tmtkhWbgMu8=Golden_hour
```

## Undo

```bash
# Back to another launcher, for example Projectivy
adb shell cmd package set-home-activity com.spocky.projengmenu/.ui.home.MainActivity
# Turn the key remap off (or set the previous value of enabled_accessibility_services)
adb shell settings delete secure enabled_accessibility_services
adb uninstall io.github.nicolaspogorzelski.quiethome
```

## Ideas

Possible later improvements, not planned yet:

- **Heavier clock on busy photos.** The clock uses a light weight. On mid-toned, detailed photos
  (for example the granite cliffs in *Mountain valley*) its thin strokes stay hard to read even
  with the halo. A regular weight where the halo is active, or as a setting, would help, at the
  cost of some of the light look.

## How this repository is built

The launcher was specified, tested on the device and reviewed by Nicolas Pogorzelski. The code,
the measurements and this README were written with an LLM-based coding assistant (Claude Code)
working under his direction; every change reaches the device and the repository only after his
approval.

## Credits

- Design: [CleanFrame](https://github.com/nthnclrk/CleanFrame), © 2026 Nathan Clark, MIT License.
  Colours, timings and the dock geometry follow its `style.css`.
- No photos, logos or fonts are bundled. Wallpapers are added by the user (see
  [Wallpapers](#wallpapers)); app banners are read from the installed apps at runtime.

NVIDIA and SHIELD are trademarks of NVIDIA Corporation. This project is not affiliated with or
endorsed by NVIDIA.

## License

[MIT](LICENSE). Third-party material and its terms are listed in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
