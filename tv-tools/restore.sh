#!/usr/bin/env bash
# One-command restore for the TV.  Usage: ./restore.sh TV_IP[:PORT]   (reads ../backup/ made during setup, or $BACKUP_DIR)
#  - re-enables every package disabled by our debloat (backup/disabled_by_us.txt, written during debloat)
#  - re-enables the stock Google TV launcher and the home-screen/provider packages
#  - removes the custom launcher's home role
#  - reverts every setting we change to its backed-up value (or deletes it if it didn't exist)
# Packages that were ALREADY disabled before we started are never re-enabled or touched.
set -u
DIR="${BACKUP_DIR:-$(cd "$(dirname "$0")/../backup" && pwd)}"
TV="${1:?usage: ./restore.sh TV_IP[:5555]}"; case "$TV" in *:*) ;; *) TV="$TV:5555";; esac
ADB="${ADB:-adb}"
command -v "$ADB" >/dev/null 2>&1 || ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
STOCK_LAUNCHER="com.google.android.apps.tv.launcherx"
CUSTOM_LAUNCHER="com.custom.hisenselauncher"

"$ADB" connect "$TV" >/dev/null 2>&1
"$ADB" -s "$TV" get-state >/dev/null 2>&1 || { echo "Cannot reach $TV (is network debugging on and authorized?)"; exit 1; }
sh() { "$ADB" -s "$TV" shell "$@" | tr -d '\r'; }

echo "== Re-enabling stock launcher =="
sh pm enable "$STOCK_LAUNCHER"
sh pm enable com.google.android.tvlauncher 2>/dev/null
sh pm enable com.google.android.leanbacklauncher 2>/dev/null

echo "== Re-enabling packages disabled by debloat =="
if [ -f "$DIR/disabled_by_us.txt" ]; then
  while read -r pkg; do
    pkg="${pkg#package:}"; [ -z "$pkg" ] && continue
    # skip anything that was already disabled before we started
    grep -qx "package:$pkg" "$DIR/packages_disabled_before.txt" 2>/dev/null && continue
    echo "enable $pkg"; sh pm enable --user 0 "$pkg"
  done < "$DIR/disabled_by_us.txt"
else
  echo "(no disabled_by_us.txt yet - nothing was debloated)"
fi

echo "== Removing custom launcher home role =="
sh pm clear-home-activity 2>/dev/null
sh cmd package set-home-activity "$STOCK_LAUNCHER/.home.HomeActivity" 2>/dev/null
sh pm disable-user --user 0 "$CUSTOM_LAUNCHER" 2>/dev/null   # only parks it; uninstall with: adb uninstall $CUSTOM_LAUNCHER

echo "== Restoring settings =="
restore() { # namespace key
  local ns="$1" key="$2" val
  val="$(grep -m1 "^$key=" "$DIR/settings_$ns.txt" 2>/dev/null | sed "s/^$key=//" | tr -d '\r')"
  if [ -n "$val" ] && [ "$val" != "null" ]; then echo "$ns $key=$val"; sh settings put "$ns" "$key" "$val"
  else echo "$ns $key (unset originally) -> delete"; sh settings delete "$ns" "$key"; fi
}
for k in window_animation_scale transition_animation_scale animator_duration_scale private_dns_mode private_dns_specifier; do
  restore global "$k"
done
# screensaver + notification listener (jellyfish screensaver / media access, added in v2)
for k in screensaver_components screensaver_enabled enabled_notification_listeners; do
  restore secure "$k"
done
sh appops set "$CUSTOM_LAUNCHER" GET_USAGE_STATS default 2>/dev/null

echo "== Done. Rebooting recommended: $ADB -s $TV reboot =="
