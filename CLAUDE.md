# Setup guide for Claude: custom Google TV launcher

You are helping someone install a custom home screen (`com.custom.hisenselauncher`) on their Google TV
or Android TV. It was built for a Hisense Google TV and should work on most Google TV / Android TV 12+ sets.
Work through the phases in order. **Ask the user before each phase that changes the TV**, and explain
what will happen in plain words.

## Ground rules
- **Back up before changing anything** (Phase 2). Every change must be undoable with `tv-tools/restore.sh`.
- **Never send key presses or input events** (`input keyevent`, `am start` of other apps, etc.) while someone
  may be watching. Prefer passive checks: `dumpsys`, `logcat`, `screencap`. Check what's on screen first:
  `adb shell "dumpsys activity activities | grep -m1 mResumedActivity"`.
- **Never disable a package the user didn't approve.** Never touch anything in the "never touch" list below.
- Don't install apps from untrusted sources. The only APK here is `app-release.apk`, or one you build from `launcher/`.
- Nothing in this folder contains anyone's accounts, keys or passwords. Keep it that way: don't write the user's
  Trakt client ID, keystore passwords or similar into files that might be shared.

## Phase 1: Connect to the TV
1. Make sure `adb` is available. On Windows, if missing, download Android SDK Platform-Tools from
   https://developer.android.com/tools/releases/platform-tools (official Google zip) and unzip it, e.g. to
   `%LOCALAPPDATA%\Android\Sdk\platform-tools`.
2. Have the user enable developer mode on the TV: **Settings → System → About → Android TV OS build**, press OK
   7 times. Then **Settings → System → Developer options → USB debugging: on** (some TVs also have
   "Network debugging"/"Wireless debugging": turn that on too).
3. Find the TV's IP: **Settings → Network & Internet → (network name)**, or ask the user.
4. `adb connect <TV_IP>:5555`. The TV shows an "Allow USB debugging?" prompt: the user must pick
   **Always allow** and OK. Then `adb devices` should show `device` (not `unauthorized`).

## Phase 2: Back up
Create `backup/` next to this file and save:
```bash
T=<TV_IP>:5555
adb -s $T shell pm list packages -s          > backup/packages_system.txt
adb -s $T shell pm list packages -3          > backup/packages_third_party.txt
adb -s $T shell pm list packages -d          > backup/packages_disabled_before.txt
adb -s $T shell pm list packages -e          > backup/packages_enabled_before.txt
adb -s $T shell settings list global         > backup/settings_global.txt
adb -s $T shell settings list secure         > backup/settings_secure.txt
adb -s $T shell settings list system         > backup/settings_system.txt
adb -s $T shell getprop                      > backup/getprop.txt
adb -s $T shell cmd package resolve-activity -c android.intent.category.HOME -a android.intent.action.MAIN > backup/home_activity_before.txt
```
Then create an empty `backup/disabled_by_us.txt` and append `package:<name>` for **every** package you
disable later. `tv-tools/restore.sh <TV_IP>` reads these files to undo everything (run it from Git Bash on Windows).
Strip Windows `\r` if needed. Tell the user that the restore script exists.

## Phase 3: Debloat (optional, needs approval)
List the system and third-party packages, group them (never touch / keep / ads & tracking), and show the user
a table of what you propose to disable with a one-line reason and risk for each. Disable only what they approve:
`adb shell pm disable-user --user 0 <pkg>` and record it in `backup/disabled_by_us.txt`.
See `tv-tools/debloat-reference.md` for what was safe on a Hisense; other brands have different packages, so
verify each one exists and what it does (`dumpsys package <pkg>`) instead of copying the list blindly.

Optional tweaks (record old values; they're in the backup already):
```bash
adb shell settings put global window_animation_scale 0.5
adb shell settings put global transition_animation_scale 0.5
adb shell settings put global animator_duration_scale 0.5
adb shell settings put global private_dns_mode hostname                # ad-blocking DNS, ask first
adb shell settings put global private_dns_specifier dns.adguard-dns.com
```

## Phase 4: Install the launcher
Use the prebuilt `app-release.apk` (no Trakt built in). Only build from source if the user wants Trakt or changes
(see `launcher/README.md`; needs JDK 17 + Android SDK 34).
```bash
adb install -r app-release.apk
P=com.custom.hisenselauncher
adb shell appops set $P GET_USAGE_STATS allow                                   # "Your Apps Lately"
adb shell cmd notification allow_listener $P/$P.media.MediaListenerService      # Now Playing, Continue Watching
adb shell settings put secure screensaver_components $P/$P.dream.JellyDreamService
adb shell settings put secure screensaver_enabled 1
adb shell cmd package set-home-activity $P/.MainActivity
```
If an install fails with a signature mismatch, an older copy signed with a different key is installed:
`adb uninstall com.custom.hisenselauncher` first.

**Set the location** (weather, weather alerts and the night-sky screensaver). Ask the user for their city, look up its
coordinates, then:
```bash
adb shell am start -n com.custom.hisenselauncher/.MainActivity --es set_place "City, State" --ef lat 39.7392 --ef lon -104.9903
```
(This opens the launcher, so only do it when the user is ready.) It can also be changed on the TV: ☰ → Location.
Default is Bozeman, Montana. Weather is in °F; alerts come from the US National Weather Service (US only).

## Phase 5: Make it the only home screen (needs approval)
Pressing Home must open the custom launcher. On Google TV the stock launcher (`com.google.android.apps.tv.launcherx`)
usually wins, so it has to be disabled. Find every HOME handler first:
```bash
adb shell cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.HOME
```
Then, with the user's OK, disable the stock launcher and any other HOME fallback (on the Hisense this was also
`com.google.android.tungsten.setupwraith`, whose RecoveryActivity otherwise catches the Home button):
```bash
adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx
adb shell pm disable-user --user 0 com.google.android.tungsten.setupwraith   # only if it is a HOME handler
```
Record both in `backup/disabled_by_us.txt`. Ask the user to reboot the TV and press Home to confirm. If Home ever
lands on a blank/setup screen, re-enable the last package (`pm enable`) and investigate before trying again.
**Disabling setupwraith also disables the TV's setup wizard**, so run restore.sh before any factory reset.

## Phase 6: Optional extras
- **Trakt** (better Continue Watching / Recently Watched, recommendations): the user creates their own API app at
  https://trakt.tv/oauth/applications (redirect URI `urn:ietf:wg:oauth:2.0:oob`, no secret needed), puts its
  Client ID in `launcher/trakt.properties` as `trakt.clientId=...`, you rebuild and reinstall, then on the TV:
  ☰ → Link Trakt account. The client ID belongs to that user; don't share it.
- **Video wallpaper**: `adb push some.mp4 /sdcard/Movies/jellyfish_4k.mp4` replaces the animated jellyfish.
- **Night mode** is on by default from 8:30 PM to 5 AM (☰ → Night mode).
- **Screensaver theme**: ☰ → Screensaver theme (Night Sky, Topo Glow, Jellyfish, or rotate).

## Never touch (Hisense/MediaTek names; look for equivalents on other brands)
TvProvider `com.android.providers.tv`, `com.android.tv.settings*`, `com.android.systemui`, `com.android.providers.*`,
Bluetooth/Wi-Fi/network stack, Play Store `com.android.vending`, Google Play services `com.google.android.gms`,
`gsf`, WebView, package installer, permission controller, keyboard, text-to-speech, HDMI/tuner/picture services
(`com.vt.source.*`, `com.vt.tvservice`, `com.vt.tv.tvcenter`, `com.vt.tv.videocolor`…), MediaTek platform
services (`com.mediatek.*`), Dolby audio, OTA/update and factory packages, Chromecast (`mediashell`),
voice search (`katniss`).

## Undo
`bash tv-tools/restore.sh <TV_IP>` (from Git Bash on Windows) re-enables everything in `backup/disabled_by_us.txt`,
brings back the stock launcher, and restores the animation, DNS, screensaver and access settings. Then reboot.
Uninstall the launcher with `adb uninstall com.custom.hisenselauncher`.
