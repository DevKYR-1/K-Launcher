#!/usr/bin/env bash
# K-Projects emulator test of the PLAY button with a Local account (see kp-play.yml).
# Runs inside the emulator step (adb is available).
#
# The launcher rule: a Local account never downloads game files, it can only start versions that are
# already installed (otherwise it would work like a Microsoft account that owns the game). So with a version
# that is NOT installed, pressing PLAY has to end in a clear message, not in a crash or a raw stack trace.
# This test:
#   1. creates a Local account, selects the given (not installed) Minecraft version and presses PLAY.
#      Before the message, the launcher downloads the version file from Mojang and prepares the Java runtime
#      that the version needs (Java 8 / 17 / 21), so those steps are part of what is tested;
#   2. waits for the "Microsoft account required" dialog, and checks that there is no crash and that the
#      technical details were written to launcher-log.txt;
#   3. presses the "Microsoft login" button of the dialog and checks that the login page opens.
# Screenshots, the UI dumps and the logs are written to <output-dir>; summary.txt has the verdict.
# Usage: play.sh <apk> <output-dir> <minecraft-version>
set -u
APK="$1"
OUT="$2"
VER="$3"
PKG=net.kdt.pojavlaunch.debug
GAME_HOME="/sdcard/Android/data/$PKG/files"
PROFILES="$GAME_HOME/.minecraft/launcher_profiles.json"
LAUNCHER_LOG="$GAME_HOME/launcher-log.txt"
MAX_WAIT="${MAX_WAIT:-420}"
mkdir -p "$OUT"

log() { echo "[play $(date +%H:%M:%S)] $*"; }
shot() { adb exec-out screencap -p > "$OUT/$1.png" || true; }
pids() { adb shell pidof "$@" 2>/dev/null | tr -d '\r'; }
top_activity() {
  adb shell dumpsys activity activities 2>/dev/null | grep -m1 -E "topResumedActivity|mResumedActivity" \
    | sed -E 's/.* u0 //; s/ t[0-9]+\}.*//'
}
dump_views() { adb shell dumpsys activity top 2>/dev/null | tr -d '\r'; }
# "l t r b" of the first non-empty view with the given resource id in the top activity
find_bounds() {
  dump_views | grep -E "app:id/$1( |\})" | grep -o -E '[0-9]+,[0-9]+-[0-9]+,[0-9]+' \
    | grep -v '^0,0-0,0$' | head -1 | tr ',-' '  '
}
tap_id() {
  local b l t r bt
  b=$(find_bounds "$1")
  if [ -z "$b" ]; then
    log "view $1 not found"
    return 1
  fi
  read -r l t r bt <<< "$b"
  adb shell input tap $(( (l + r) / 2 )) $(( (t + bt) / 2 ))
  log "tapped $1 at $(( (l + r) / 2 )),$(( (t + bt) / 2 ))"
}

# The text of everything on the screen (the system accessibility dump), needed for dialogs.
# It can fail while the screen is busy, so callers just try again later.
ui_dump() {
  adb shell rm -f /sdcard/kp-ui.xml > /dev/null 2>&1
  adb shell uiautomator dump /sdcard/kp-ui.xml > /dev/null 2>&1 || return 1
  adb exec-out cat /sdcard/kp-ui.xml > "$OUT/ui.xml" 2>/dev/null
  [ -s "$OUT/ui.xml" ]
}
ui_has() { grep -q -F "$1" "$OUT/ui.xml" 2>/dev/null; }
# "x y" of the center of the node whose text is exactly $1, from the last ui_dump
ui_center_of() {
  python3 - "$OUT/ui.xml" "$1" <<'PY'
import re, sys
xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
key = sys.argv[2]
for m in re.finditer(r"<node [^>]*>", xml):
    node = m.group(0)
    text = re.search(r'\btext="([^"]*)"', node)
    if text and text.group(1) == key:
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
        l, t, r, bt = map(int, b.groups())
        print((l + r) // 2, (t + bt) // 2)
        sys.exit(0)
sys.exit(1)
PY
}

collect() {
  adb shell cat "$LAUNCHER_LOG" > "$OUT/launcher-log.txt" 2>&1
  adb shell ls -la "$GAME_HOME" "$GAME_HOME/.minecraft" > "$OUT/files.txt" 2>&1
  adb shell cat "$PROFILES" > "$OUT/launcher_profiles.json" 2>&1
  adb logcat -d -v threadtime > "$OUT/logcat-full.txt" 2>&1
  grep -E 'FATAL EXCEPTION|Fatal signal|SIGSEGV|SIGABRT|hs_err|UnsatisfiedLinkError|ShowErrorActivity|Process: net\.kdt' -A6 "$OUT/logcat-full.txt" \
    | head -150 > "$OUT/problems.txt"
  grep -E 'pojav|kdt|LWJGL|Minecraft|JREUtils|libjvm|MinecraftDownloader|NewMCDownloader|MicrosoftLogin' "$OUT/logcat-full.txt" \
    | grep -v -E 'AppsFilter|SafetyLabel|SdkSandbox|GameManager|avc:' | head -c 250000 > "$OUT/logcat-app.txt"
  rm -f "$OUT/logcat-full.txt"
}
finish() {
  collect
  # The verdict: the dialog came up, nothing crashed, and the details are in the launcher log
  verdict="$1"
  crashed=no
  grep -q -E 'FATAL EXCEPTION|Fatal signal' "$OUT/problems.txt" && crashed=yes
  logged=no
  grep -q 'LocalAccountDownloadException' "$OUT/launcher-log.txt" 2>/dev/null && logged=yes
  if [ "$verdict" = local_dialog ] && [ "$crashed" = no ] && [ "$logged" = yes ]; then verdict=ok; fi
  echo "version=$VER result=$verdict crashed=$crashed logged=$logged ms_login=${MS_LOGIN:-not_tested}" | tee "$OUT/summary.txt"
  echo "----- launcher-log.txt (first lines)"
  head -n 25 "$OUT/launcher-log.txt"
  echo "----- problems"
  head -n 60 "$OUT/problems.txt"
  exit 0
}
must_tap() {
  if ! tap_id "$1"; then
    shot "fail-$1"
    dump_views | grep -o -E '[0-9]+,[0-9]+-[0-9]+,[0-9]+ #[0-9a-f]+ app:id/[A-Za-z0-9_]+' | sort -u > "$OUT/views-fail-$1.txt"
    log "giving up: view $1 missing (top: $(top_activity))"
    finish "ui_step_failed_$1"
  fi
}

adb wait-for-device
for _ in $(seq 1 90); do
  [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
  sleep 2
done
adb shell input keyevent 82 || true
adb shell settings put global window_animation_scale 0 || true
adb shell settings put global transition_animation_scale 0 || true
adb shell settings put global animator_duration_scale 0 || true

log "installing $APK"
adb install -r -g "$APK" 2>&1 | tee "$OUT/install.txt"
grep -q Success "$OUT/install.txt" || { log "INSTALL FAILED"; echo "version=$VER result=install_failed" > "$OUT/summary.txt"; exit 1; }

adb logcat -c
log "starting the launcher"
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 2>&1 | tail -1
sleep 30
shot 01-main
log "top activity: $(top_activity)"

# local account
must_tap account_spinner
sleep 4
shot 02-auth-method
must_tap button_local_authentication
sleep 4
shot 03-local-login
must_tap login_edit_email
sleep 2
adb shell input text KProjectsTest
sleep 2
shot 04-typed
must_tap login_button
sleep 6
shot 05-after-login
log "top activity: $(top_activity)"

# choose the Minecraft version for the default profile (needs the app to be stopped)
adb shell am force-stop "$PKG"
sleep 2
tmp="$(mktemp)"
if adb pull "$PROFILES" "$tmp" > /dev/null 2>&1; then
  python3 - "$tmp" "$VER" <<'PY'
import json, sys
path, ver = sys.argv[1], sys.argv[2]
data = json.load(open(path))
for prof in data.get("profiles", {}).values():
    if isinstance(prof, dict) and "lastVersionId" in prof:
        prof["lastVersionId"] = ver
json.dump(data, open(path, "w"))
PY
  adb push "$tmp" "$PROFILES" > /dev/null 2>&1
  log "profile version set to $VER"
else
  log "could not read $PROFILES (continuing with the default version)"
fi
rm -f "$tmp"

adb logcat -c
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 2>&1 | tail -1
sleep 25
shot 06-before-play
must_tap play_button
log "PLAY pressed for $VER"

# Wait for the outcome: the dialog (expected), or the game / the old error screen (both unexpected)
start=$(date +%s)
i=0
result=timeout
while :; do
  sleep 6
  i=$((i + 1))
  elapsed=$(( $(date +%s) - start ))
  game="$(pids "$PKG:game")"
  top="$(top_activity)"
  [ $((i % 10)) -eq 0 ] && shot "07-wait-$(printf %04d "$elapsed")s"
  [ $((i % 5)) -eq 0 ] && log "t=${elapsed}s game_pid='${game}' top='${top##*.}'"
  if [ -n "$game" ]; then result=game_started; break; fi
  case "$top" in *ShowErrorActivity*) result=error_screen; break;; esac
  if ui_dump; then
    if ui_has "Microsoft account required"; then result=local_dialog; break; fi
    # the text of the old error: a raw failure that tells nothing
    if ui_has "Please make sure you are logged in with a Microsoft Account"; then result=old_raw_error; break; fi
    # any other message of the new error handling is a finding as well
    if ui_has "Details:"; then result=other_error_dialog; break; fi
  fi
  [ "$elapsed" -ge "$MAX_WAIT" ] && break
done
log "wait result: $result after $(( $(date +%s) - start ))s"
shot 08-after-wait
cp "$OUT/ui.xml" "$OUT/ui-after-wait.xml" 2>/dev/null || true

if [ "$result" = local_dialog ]; then
  ui_has "file(s) missing or damaged" && log "the dialog says what is missing" || log "WARNING: the dialog text is not the expected one"
  # "Microsoft login" button of the dialog: opens the Microsoft login page of the launcher
  if read -r x y <<< "$(ui_center_of 'Microsoft login')" && [ -n "${x:-}" ]; then
    adb shell input tap "$x" "$y"
    log "tapped 'Microsoft login' at $x,$y"
    sleep 12
    shot 09-microsoft-login
    top="$(top_activity)"
    if dump_views | grep -q -E 'WebView|microsoft_login'; then MS_LOGIN=opened; else MS_LOGIN=not_seen; fi
    log "after the button: top='${top##*.}' login page: $MS_LOGIN"
    adb shell input keyevent 4
    sleep 3
    shot 10-after-back
  else
    MS_LOGIN=button_missing
    log "the 'Microsoft login' button was not found in the dialog"
  fi
  if [ -z "$(pids "$PKG:launcher")" ] && [ -z "$(pids "$PKG")" ]; then MS_LOGIN="${MS_LOGIN:-x}_but_app_died"; fi
fi
finish "$result"
