<div align="center">

# 🪼 Custom Google TV Launcher

**An ad-free home screen for Google TV and Android TV, with a live jellyfish background, local weather,
Continue Watching, and a screensaver that shows you which stars to look for tonight.**

[![Android TV](https://img.shields.io/badge/Android%20TV-12%2B-3DDC84?logo=android&logoColor=white)](#requirements)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin&logoColor=white)](launcher/)
[![No ads](https://img.shields.io/badge/ads-none-ff5c8a)](#features)
[![Set up with Claude Code](https://img.shields.io/badge/set%20up%20with-Claude%20Code-d97757)](#setup-with-claude-easiest)
[![Download](https://img.shields.io/github/v/release/P0RT3R7/custom-google-tv-launcher?label=download)](https://github.com/P0RT3R7/custom-google-tv-launcher/releases/latest)

<img src="docs/images/home.jpg" alt="Home screen: clock, weather, Continue Watching, Favorites and Recently Watched rows over an animated jellyfish background" width="100%">

<sub>Home screen. The watch-history rows are blurred in this screenshot.</sub>

</div>

## Features

| | |
|---|---|
| 🪼 **Live background** | Glowing sea nettles and moon jellies drawn in OpenGL at 60 fps, or your own video |
| 📺 **Continue Watching** | Resume cards for apps like Hulu, plus episode-level progress from Trakt and apps that share it |
| ▶️ **YouTube history** | A "Recently on YouTube" row; half-watched long videos show up in Continue Watching and reopen the exact video |
| 🔍 **Title reader** (experimental) | Reads the show name off Hulu, Netflix, Prime Video, Disney+ and Max player screens, which don't share it |
| 🆕 **New episodes** | Shows you've been watching that have a new episode out in the last 3 weeks, or one airing this week |
| 🎬 **Recently Watched + suggestions** | "Because you watched…" rows built from your history, opening straight into Nuvio or Stremio |
| ⭐ **Favorites, recent apps, All Apps** | Long-press any tile to reorder, pin or hide |
| 🌤 **Weather** | Current conditions in the top bar; full screen with 24-hour and 7-day forecast and NWS alerts |
| 🔎 **Search with voice** | Apps, movies and shows in one place |
| 🎵 **Now Playing bar** | Shows what's playing in the background |
| ⛷ **Snow report** | New snow, measured snow depth, 3-day snowfall forecast, summit weather and avalanche danger for the nearest ski areas |
| 🌌 **Aurora alerts** | A top-bar alert on nights when NOAA's forecast says the northern lights may be visible where you live, plus a glow on the Night Sky screensaver |
| 🌙 **Night mode** | A warm, dim filter from 8:30 PM to 5 AM so the screen is easy on your eyes |
| 🔌 **HDMI inputs + Settings** | One click from the top bar |

## Screensavers

<img src="docs/images/night-sky.jpg" alt="Night Sky screensaver: constellations with lines and labels over a mountain horizon, with a panel listing where to look" width="100%">

**Night Sky.** The real sky over your location, right now, slowly turning around the horizon. The panel lists the best
constellations to go outside and find, with the direction to face and how high to look. During the day it shows tonight's sky.

<table>
<tr>
<td width="50%"><img src="docs/images/topo.jpg" alt="Topo Glow screensaver: colored relief map from teal valleys to rust peaks with glowing contour lines"></td>
<td width="50%"><img src="docs/images/jellyfish.jpg" alt="Jellyfish Deep screensaver: glowing violet sea nettles and pale moon jellies on black"></td>
</tr>
<tr>
<td><b>Topo Glow.</b> A colored relief map with hillshading and glowing contour lines that drifts slowly north.</td>
<td><b>Jellyfish Deep.</b> Sea nettles and moon jellies drifting through dark water.</td>
</tr>
</table>

Pick one with ☰ → Screensaver theme, or let them rotate.

## Weather

<img src="docs/images/weather.jpg" alt="Weather screen with current conditions, next 24 hours and 7-day forecast" width="100%">

Open it from the top bar: conditions now, the next 24 hours, 7 days, sunrise and sunset, and active alerts.

## Snow report

<img src="docs/images/ski.jpg" alt="Snow report screen for Bridger Bowl and Big Sky: new snow, snow depth, forecast, summit weather and avalanche danger" width="100%">

The ⛷ pill in the top bar shows the headline ("Bridger 6\" new") and opens the full report for the ski areas within about 100 miles.
New snow and the forecast come from Open-Meteo at mid-mountain elevation, snow depth from the nearest NRCS SNOTEL station,
and avalanche danger from avalanche.org. Lift status isn't published in an open format, so it isn't shown.

## Setup with Claude (easiest)

```mermaid
flowchart LR
    A[Download the zip] --> B[Open the folder in Claude Code]
    B --> C["Say: Set up this launcher on my TV"]
    C --> D[Turn on developer mode on the TV]
    D --> E[Claude backs up the TV]
    E --> F[You approve what to remove]
    F --> G[Launcher installed and set as Home]
```

1. Download **hisense-tv-launcher.zip** from the [latest release](https://github.com/P0RT3R7/custom-google-tv-launcher/releases/latest) and unzip it on a PC on the same Wi-Fi as the TV.
2. Open the folder in **Claude Code** (desktop app: Code tab → choose the folder) and say **"Set up this launcher on my TV."**
3. Claude follows [`CLAUDE.md`](CLAUDE.md). It walks you through developer mode, makes a backup first, and asks before every change.

## Manual setup

```bash
adb connect <TV_IP>:5555
adb install -r custom-tv-launcher.apk
P=com.custom.hisenselauncher
adb shell appops set $P GET_USAGE_STATS allow
adb shell cmd notification allow_listener $P/$P.media.MediaListenerService
adb shell settings put secure screensaver_components $P/$P.dream.JellyDreamService
adb shell cmd package set-home-activity $P/.MainActivity
```

On Google TV you'll also need to disable the stock launcher so Home opens this one; see [`CLAUDE.md`](CLAUDE.md) for the
safe way to do that and how to undo it.

**Settings from the PC** (no remote needed):

```bash
adb shell am broadcast -n com.custom.hisenselauncher/.ConfigReceiver --es screensaver_theme NIGHT_SKY   # ROTATE | NIGHT_SKY | TOPO | JELLYFISH
adb shell am broadcast -n com.custom.hisenselauncher/.ConfigReceiver --es night_mode AUTO              # AUTO | ON | OFF
adb shell am broadcast -n com.custom.hisenselauncher/.ConfigReceiver --es set_place "Denver, Colorado" --ef lat 39.7392 --ef lon -104.9903
```

## Requirements

- Google TV or Android TV, Android 8.0+ (built and tested on a Hisense Google TV, Android 12)
- A PC with [adb](https://developer.android.com/tools/releases/platform-tools) on the same network
- Optional: a free [Trakt](https://trakt.tv) account for episode-level Continue Watching and recommendations
- To build from source: JDK 17 and Android SDK 34 (see [`launcher/README.md`](launcher/README.md))

## Undo

```bash
bash tv-tools/restore.sh <TV_IP>
```

This brings back the stock Google TV home screen, re-enables everything that was disabled, and restores the original settings.
