# Custom TV Launcher (`com.custom.hisenselauncher`)

Custom Android TV home screen: animated jellyfish (OpenGL, 60 fps), local weather, Continue Watching,
Recently Watched + suggestions, Now Playing, search, jellyfish screensaver.

## Build & install
```bash
# JDK 17 required (newer JDKs aren't supported by this Android Gradle Plugin)
# Android SDK 34: create local.properties with  sdk.dir=C:/Users/<you>/AppData/Local/Android/Sdk  (forward slashes)
export JAVA_HOME="/path/to/jdk-17"
./gradlew assembleRelease        # gradlew.bat on Windows
adb install -r app/build/outputs/apk/release/app-release.apk
```
Signing: optional `keystore.properties` (storeFile, storePassword, keyAlias, keyPassword; git-ignored). Without it the
release build is signed with your local debug key. Updates must use the same key as the installed copy,
otherwise `adb uninstall com.custom.hisenselauncher` first.

## One-time access grants (adb)
```bash
P=com.custom.hisenselauncher
adb shell appops set $P GET_USAGE_STATS allow                                   # "Your Apps Lately"
adb shell cmd notification allow_listener $P/$P.media.MediaListenerService      # Now Playing + local history
adb shell settings put secure screensaver_components $P/$P.dream.JellyDreamService
adb shell settings put secure screensaver_enabled 1
adb shell cmd package set-home-activity $P/.MainActivity
```
`READ_EPG_DATA` is granted automatically at install on Google TV.

## Location
Weather, alerts and the night sky use the saved place (default Bozeman, MT). Set it on the TV with ☰ → Location, or:
```bash
adb shell am start -n com.custom.hisenselauncher/.MainActivity --es set_place "Denver, Colorado" --ef lat 39.7392 --ef lon -104.9903
```

## Trakt (optional, best "Recently Watched" + recommendations)
1. Create your own API app at https://trakt.tv/oauth/applications — redirect URI `urn:ietf:wg:oauth:2.0:oob`.
2. Create `trakt.properties` next to this README (git-ignored; don't share it):
   ```
   trakt.clientId=YOUR_CLIENT_ID
   ```
   A client secret isn't needed (device-code login works without one).
3. Rebuild + install, then on the TV: ☰ → **Link Trakt account** and approve the code at trakt.tv/activate.
4. In Nuvio / Stremio, turn on Trakt scrobbling so your watches reach Trakt.

## Where the rows come from
| Row | Source |
|---|---|
| Continue Watching | Android TV Watch Next provider · Trakt playback · local in-progress titles |
| Recently Watched | Local media-session log (apps that publish titles) · Trakt history |
| Because you watched X / Picked for you | Genre matching on Stremio's free Cinemeta catalog (no key) |
| Recommended on Trakt | Trakt personalized recommendations |
| Your Apps Lately | Android usage stats |

Hulu and Netflix do **not** publish title metadata (they show as app-level "Resume" cards), so only Trakt (or apps like Nuvio/Stremio
that publish media sessions) can feed title-level history.

## Wallpaper
Drop a video at `/sdcard/Movies/jellyfish_4k.mp4` (`adb push file.mp4 /sdcard/Movies/jellyfish_4k.mp4`) to use it
instead of the procedural jellyfish. Delete it to go back.

## Source layout
```
data/       Prefs, AppRepository, HomeFeed (suggestion engine), Catalog (Cinemeta + Nuvio links),
            Trakt, WatchHistory, UsageRepository, WatchNextRepository, WeatherRepository
media/      MediaSessionHub + MediaListenerService (Now Playing, history capture)
wallpaper/  JellyScene (geometry) + JellyfishView (GL renderer) + WallpaperLayer (video / GL switch)
weather/    WeatherActivity (forecast screen) + WeatherDetail (Open-Meteo + NWS alerts)
search/     SearchActivity
dream/      JellyDreamService + Screensavers (theme host) + NightSkyView
wallpaper/  also TopoScene (Topo Glow screensaver)
ui/         Theme, focus glow, adapters, NowPlayingBar, NightMode
```

## Undo everything
`bash restore.sh <TV_IP>` (from the backup/tv-tools folder) re-enables every disabled package, restores the Google TV launcher, screensaver,
DNS, animation and access settings.

## Night mode

`ui/NightMode.kt` lays a warm, dim amber filter over the launcher, its weather and search screens, and the screensaver.
The default is **Auto**: it turns on at 8:30 PM and off at 5:00 AM, fading over 4 seconds.
Change it with ☰ → Night mode (Auto, Always on, Off).
Most TVs have no system-wide Night Light (`dumpsys color_display` reports "Not available"), so other apps aren't filtered.
