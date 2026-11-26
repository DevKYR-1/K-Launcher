#!/usr/bin/env bash
# Shared helpers of the K-Projects emulator tests (play.sh and game.sh). Source it after setting
#   OUT  - the output folder          PKG - the package name of the app under test
#   TAG  - the name shown in the log lines
# It talks to the emulator with adb, so it only works inside the emulator step of the workflow.
# A script that uses must_tap has to define finish() itself (the verdict is the script's business).

GAME_HOME="/sdcard/Android/data/$PKG/files"
PROFILES="$GAME_HOME/.minecraft/launcher_profiles.json"
LAUNCHER_LOG="$GAME_HOME/launcher-log.txt"
mkdir -p "$OUT"

log() { echo "[$TAG $(date +%H:%M:%S)] $*"; }
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
  if ! adb shell uiautomator dump /sdcard/kp-ui.xml > "$OUT/uidump-last.txt" 2>&1; then
    head -c 400 "$OUT/uidump-last.txt" >> "$OUT/uidump-errors.txt"
    return 1
  fi
  # the redirect empties ui.xml first, so a failed dump never leaves an old screen behind
  adb exec-out cat /sdcard/kp-ui.xml > "$OUT/ui.xml" 2>/dev/null
  if [ ! -s "$OUT/ui.xml" ]; then
    head -c 400 "$OUT/uidump-last.txt" >> "$OUT/uidump-errors.txt"
    return 1
  fi
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
# "x y|message" of an open dialog of the app under test (its first button and its text), from the last ui_dump
ui_app_dialog() {
  python3 - "$OUT/ui.xml" "$PKG" <<'PY'
import re, sys
xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
pkg = sys.argv[2]
button = message = None
for m in re.finditer(r"<node [^>]*>", xml):
    node = m.group(0)
    if 'package="%s"' % pkg not in node:
        continue
    rid = re.search(r'resource-id="([^"]*)"', node)
    if not rid:
        continue
    if rid.group(1) == "android:id/button1" and button is None:
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
        l, t, r, bt = map(int, b.groups())
        button = "%d %d" % ((l + r) // 2, (t + bt) // 2)
    if rid.group(1) == "android:id/message" and message is None:
        text = re.search(r'\btext="([^"]*)"', node)
        message = text.group(1) if text else ""
if button is None:
    sys.exit(1)
print("%s|%s" % (button, (message or "").replace("\n", " ")[:200]))
PY
}

# A busy emulator makes some process (here: the system launcher, "Pixel Launcher isn't responding") show an ANR dialog.
# It is drawn over the app and swallows every tap, so no UI step can work while it is there. (The first version of the
# play test never noticed it: every tap was ignored and the test still ended without an error.) The dialog is closed
# with "Wait"; the ANR lines of the log say afterwards which process it was, the launcher of the system or the app.
anr_dialog_close() {
  ui_has "responding" || return 1
  local x y
  log "an 'isn't responding' dialog is in the way: $(grep -o -E 'text="[^"]*responding[^"]*"' "$OUT/ui.xml" | head -1)"
  if read -r x y <<< "$(ui_center_of 'Wait')" && [ -n "${x:-}" ]; then
    adb shell input tap "$x" "$y"
    log "closed it with 'Wait' at $x,$y"
  else
    adb shell input keyevent 4
    log "no 'Wait' button found, sent BACK"
  fi
}
dismiss_system_dialogs() {
  local round
  for round in 1 2 3 4 5 6; do
    if ! ui_dump; then sleep 2; continue; fi
    anr_dialog_close || return 0
    sleep 3
  done
}

# Evidence for the report: the logs of the app, what is on the device, what crashed, which process hung.
collect() {
  adb shell cat "$LAUNCHER_LOG" > "$OUT/launcher-log.txt" 2>&1
  adb shell cat "$GAME_HOME/latestlog.txt" > "$OUT/latestlog.txt" 2>&1
  adb shell ls -la "$GAME_HOME" "$GAME_HOME/.minecraft" > "$OUT/files.txt" 2>&1
  # a debug build lets us look into the private folder (the runtimes that were unpacked from the APK)
  adb shell run-as "$PKG" ls -la files files/runtimes >> "$OUT/files.txt" 2>&1
  adb shell cat "$PROFILES" > "$OUT/launcher_profiles.json" 2>&1
  adb shell dumpsys window windows 2>/dev/null | tr -d '\r' | grep -E 'Window #|mCurrentFocus|mFocusedApp' > "$OUT/windows.txt" 2>&1
  adb logcat -d -v threadtime > "$OUT/logcat-full.txt" 2>&1
  grep -E 'ANR in |Application Not Responding|am_anr' "$OUT/logcat-full.txt" | head -20 > "$OUT/anr.txt"
  grep -E 'FATAL EXCEPTION|Fatal signal|SIGSEGV|SIGABRT|hs_err|UnsatisfiedLinkError|ShowErrorActivity|Process: net\.kdt' -A6 "$OUT/logcat-full.txt" \
    | head -150 > "$OUT/problems.txt"
  grep -E "pojav|kdt|LWJGL|Minecraft|JREUtils|libjvm|MinecraftDownloader|NewMCDownloader|MicrosoftLogin|KPSTUB|${KP_LOG_EXTRA:-NOTHING-EXTRA}" "$OUT/logcat-full.txt" \
    | grep -v -E 'AppsFilter|SafetyLabel|SdkSandbox|GameManager|avc:' | head -c 250000 > "$OUT/logcat-app.txt"
  rm -f "$OUT/logcat-full.txt"
}

must_tap() {
  dismiss_system_dialogs
  if ! tap_id "$1"; then
    shot "fail-$1"
    dump_views | grep -o -E '[0-9]+,[0-9]+-[0-9]+,[0-9]+ #[0-9a-f]+ app:id/[A-Za-z0-9_]+' | sort -u > "$OUT/views-fail-$1.txt"
    log "giving up: view $1 missing (top: $(top_activity))"
    finish "ui_step_failed_$1"
  fi
}

# Wait until the emulator has booted and make the screen quiet
kp_boot() {
  adb wait-for-device
  for _ in $(seq 1 90); do
    [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
    sleep 2
  done
  adb shell input keyevent 82 || true
  adb shell settings put global window_animation_scale 0 || true
  adb shell settings put global transition_animation_scale 0 || true
  adb shell settings put global animator_duration_scale 0 || true
}

kp_install() {
  log "installing $1"
  adb install -r -g "$1" 2>&1 | tee "$OUT/install.txt"
  if ! grep -q Success "$OUT/install.txt"; then
    log "INSTALL FAILED"
    echo "result=install_failed" > "$OUT/summary.txt"
    exit 1
  fi
}

kp_start_app() {
  adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 2>&1 | tail -1
}

# Create a Local account the way a user does: account list -> Local account -> name -> log in
kp_local_account() {
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
}

# Select the version of the default profile. The app has to be stopped, it writes the file itself.
kp_set_profile_version() {
  local tmp
  adb shell am force-stop "$PKG"
  sleep 2
  tmp="$(mktemp)"
  if adb pull "$PROFILES" "$tmp" > /dev/null 2>&1; then
    python3 - "$tmp" "$1" <<'PY'
import json, sys
path, ver = sys.argv[1], sys.argv[2]
data = json.load(open(path))
for prof in data.get("profiles", {}).values():
    if isinstance(prof, dict) and "lastVersionId" in prof:
        prof["lastVersionId"] = ver
json.dump(data, open(path, "w"))
PY
    adb push "$tmp" "$PROFILES" > /dev/null 2>&1
    log "profile version set to $1"
  else
    log "could not read $PROFILES (continuing with the default version)"
  fi
  rm -f "$tmp"
}
