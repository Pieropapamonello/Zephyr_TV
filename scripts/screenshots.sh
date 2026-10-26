#!/usr/bin/env bash
# Takes the README screenshots on a running Android TV emulator (see .github/workflows/screenshots.yml).
# Usage: scripts/screenshots.sh <debug.apk> <out dir>. TMDB_KEY in the environment turns Spotlight on.
set -u
APK=$1
OUT=$2
PKG=link.mccoy88f.zephyr
mkdir -p "$OUT"

shot() { # shot <name>: the screen as a 1280x720 JPEG
    sleep "${WAIT:-3}"
    adb exec-out screencap -p > "$OUT/$1.png"
    $(command -v magick || echo convert) "$OUT/$1.png" -resize 1280x720 -quality 86 "$OUT/$1.jpg" && rm "$OUT/$1.png"
    echo "shot $1"
}

key() { for k in "$@"; do adb shell input keyevent "$k"; sleep 0.6; done; }

tap() { # tap <id, text or description>... [@x,y]: taps the middle of the first view found, else the point given
    adb shell uiautomator dump /sdcard/ui.xml > /dev/null
    adb exec-out cat /sdcard/ui.xml > /tmp/ui.xml
    local want xy
    for want in "$@"; do
        case $want in @*) xy=${want#@}; xy=${xy/,/ }; break ;; esac
        xy=$(python3 - "$want" <<'PY'
import re, sys
want = sys.argv[1]
for node in re.findall(r"<node [^>]*>", open("/tmp/ui.xml").read()):
    attrs = dict(re.findall(r'([\w-]+)="([^"]*)"', node))
    if want in (attrs.get("resource-id"), "link.mccoy88f.zephyr:id/" + want, attrs.get("text"), attrs.get("content-desc")):
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", attrs["bounds"]))
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
PY
)
        [ -n "$xy" ] && break
    done
    if [ -z "$xy" ]; then echo "not found: $*"; return 1; fi
    adb shell input tap $xy
    sleep 1
}

prefs() { # prefs <theme> [extra xml]: Zephyr TV's settings, with the setup and notes popups already seen
    adb shell am force-stop $PKG
    local key=""
    # With a key, both wallpapers are the Spotlight backdrop, as when the key is typed in.
    [ -n "${TMDB_KEY:-}" ] && key="<string name=\"tmdb_api_key\">$TMDB_KEY</string>
<string name=\"wp_light\">tmdb:trending</string><string name=\"wp_dark\">tmdb:trending</string>"
    printf '%s' "<?xml version='1.0' encoding='utf-8' standalone='yes' ?><map>
<boolean name=\"setup_shown\" value=\"true\" /><boolean name=\"tip_move\" value=\"true\" />
<string name=\"seen_notes\">$VERSION</string><string name=\"upd_told\">$VERSION</string>
<string name=\"theme\">$1</string><string name=\"clock_24\">24</string>
<int name=\"tmdb_cycle\" value=\"${CYCLE:-0}\" />$key ${2:-}</map>" |
        adb shell "run-as $PKG sh -c 'mkdir -p shared_prefs && cat > shared_prefs/home.xml'"
}

start() { adb shell am start -W -n $PKG/.MainActivity > /dev/null; sleep "${SETTLE:-12}"; }

home() { start; key KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN; } # the home screen with an app focused

VERSION=$(aapt dump badging "$APK" 2>/dev/null | sed -n "s/.*versionName='\([^']*\)'.*/\1/p")
[ -z "$VERSION" ] && VERSION=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)

adb install -r -g "$APK"
adb shell pm grant $PKG android.permission.READ_TV_LISTINGS 2>/dev/null
adb shell pm grant $PKG com.android.providers.tv.permission.READ_EPG_DATA 2>/dev/null
adb shell cmd package set-home-activity $PKG/.MainActivity
adb shell settings put system screen_off_timeout 1800000
adb shell settings put secure screensaver_enabled 0

for lang in en it; do
    if [ $lang = it ]; then loc=it-IT; else loc=en-US; fi
    adb shell cmd locale set-app-locales $PKG --locales $loc || echo "couldn't set the app language"

    # Home, dark: Spotlight at rest above the apps row, then focused with its buttons.
    prefs dark; home
    shot $lang-home-dark
    [ -n "${TMDB_KEY:-}" ] && { key KEYCODE_DPAD_UP; WAIT=2 shot $lang-spotlight; }

    # Home, light.
    prefs light; home
    shot $lang-home-light

    # Home, Glass: see-through cards over the wallpaper.
    prefs glass; home
    shot $lang-home-glass

    # The menu of an app (hold OK): open, move, hide, and its visible sections.
    prefs dark; home
    key KEYCODE_DPAD_RIGHT
    adb shell input keyevent --longpress KEYCODE_DPAD_CENTER
    WAIT=2 shot $lang-app-menu
    key KEYCODE_BACK

    # Settings, grouped in sections; then the TMDB Spotlight section.
    prefs dark; start
    if [ $lang = it ]; then d="Impostazioni di Zephyr TV"; else d="Zephyr TV settings"; fi
    tap launcherSettings "$d" @1788,90 && WAIT=2 shot $lang-settings
    prefs dark '<int name="reopen_row" value="300" />'; start
    WAIT=1 shot $lang-settings-tmdb

    # A phone screen: the TMDB key from the phone, with its QR code.
    prefs dark '<int name="reopen_row" value="300" />'; start
    key KEYCODE_DPAD_CENTER
    if [ $lang = it ]; then tap "Inserisci dal telefono…"; else tap "Enter from your phone…"; fi
    WAIT=2 shot $lang-phone
done

# Gallery screensaver from the Spotlight backdrops: let Spotlight turn for a while so a few are on disk first.
if [ -n "${TMDB_KEY:-}" ]; then
    CYCLE=3 prefs dark '<string name="ss_folder">tmdb:trending</string>'; SETTLE=40 start
    adb shell settings put secure screensaver_components $PKG/.GalleryDream
    adb shell cmd dreams start-dreaming 2>/dev/null || adb shell am start -n com.android.systemui/.Somnambulator
    WAIT=8 shot it-screensaver
    adb shell input keyevent KEYCODE_BACK
fi
ls -la "$OUT"
