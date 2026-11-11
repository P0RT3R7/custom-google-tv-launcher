# Debloat reference (Hisense Google TV, Android 12, MediaTek)

These were disabled on one Hisense set with no problems. **Other models and brands differ**: check each package
exists and what it does before proposing it, and get the user's OK for every one.

| Package | What it is | Risk |
|---|---|---|
| `com.vt.tv.evservice` | Hisense ACR (automatic content recognition = viewing tracking) | Low |
| `com.vt.tv.smartservice` | Hisense telemetry (Firebase log reporting) | Low |
| `com.vt.tv.smartsense`, `com.vt.tv.smartsense.ui` | Hisense analytics / log reporting | Medium (persistent system app; reboot-test it) |
| `net.vidaatv.hisense.android.tv` | VIDAA Channels Plus (ad-supported channels) | Low |
| `com.vidaa.oemtabcontentprovider` | VIDAA sponsored content tab | Low |
| `com.vt.hishow` | Hisense promo/content app | Low |
| `com.tcl.browser` | Preloaded partner browser | Low |
| `com.roku.web.trc` | Preloaded Roku web shell | Low |
| `com.hisense.odinbrowser` | Hisense browser | Low (ask if they use it) |
| `com.vt.tv.protector` | Hisense "protector" app | Low–Medium |
| `com.vt.tv.singlelisten` | Promo feature | Low |
| `com.google.android.play.games` | Play Games | Low |
| `com.google.android.feedback` | Google feedback/log uploader | Low |
| `com.google.android.partnersetup` | Partner preload installer | Low |
| `tv.mopa.ginga` | Brazilian interactive-TV middleware | Low (outside Brazil) |
| `com.vt.tv.aiot`, `com.vt.alexa`, `com.vt.storemode`, `com.vt.udpserver` | AIoT, built-in Alexa, store demo mode, remote UDP server | Low–Medium (the Hisense phone remote app may use udpserver) |

Disabled only after the custom launcher was installed and working:
`com.google.android.apps.tv.launcherx` (stock Google TV home, where the ads live) and
`com.google.android.tungsten.setupwraith` (its RecoveryActivity is a backup Home screen).

There is no separate Google TV "ad engine" package; the sponsored rows are inside `launcherx`.
