#!/usr/bin/env bash
# K-Projects emulator "play" test. Runs inside the emulator step (adb is available).
# Creates a local account, presses PLAY on the default profile and waits for the Java game
# process (":game") to start, then collects screenshots and the game log.
# Usage: play.sh <apk> <output-dir>
set -u
APK="$1"
OUT="$2"
PKG=net.kdt.pojavlaunch.debug
GAME_HOME="/sdcard/Android/data/$PKG/files"
MAX_WAIT="${MAX_WAIT:-1200}"
mkdir -p "$OUT"

log() { echo "[play $(date +%H:%M:%S)] $*"; }
shot() { adb exec-out screencap -p > "$OUT/$1.png" || true; }
pids() { adb shell pidof "$@" 2>/dev/null | tr -d '\r'; }
top_activity() {
  adb shell dumpsys activity activities 2>/dev/null | grep -m1 -E "topResumedActivity|mResumedActivity" \
    | sed -E 's/.* u0 //; s/ t[0-9]+\}.*//'
}
# tap the centre of the view with the given resource id (looked up in the top activity's hierarchy)
tap_id() {
  local line b l t r bt
  line=$(adb shell dumpsys activity top 2>/dev/null | tr -d '\r' | grep -m1 "app:id/$1}")
  b=$(echo "$line" | grep -o -E '[0-9]+,[0-9]+-[0-9]+,[0-9]+' | head -1)
  if [ -z "$b" ]; then
    log "view $1 not found"
    return 1
  fi
  IFS=',-' read -r l t r bt <<< "$b"
  adb shell input tap $(( (l + r) / 2 )) $(( (t + bt) / 2 ))
  log "tapped $1 at $(( (l + r) / 2 )),$(( (t + bt) / 2 ))"
}
view_ids() { adb shell dumpsys activity top 2>/dev/null | tr -d '\r' | grep -o -E '[0-9]+,[0-9]+-[0-9]+,[0-9]+ #[0-9a-f]+ app:id/[A-Za-z0-9_]+' | sort -u; }

adb wait-for-device
for _ in $(seq 1 90); do
  [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
  sleep 2
done
adb shell input keyevent 82 || true
adb shell settings put global window_animation_scale 0 || true
adb shell settings put global transition_animation_scale 0 || true

log "installing $APK"
adb install -r -g "$APK" 2>&1 | tee "$OUT/install.txt"
grep -q Success "$OUT/install.txt" || { log "INSTALL FAILED"; exit 1; }

adb logcat -c
log "starting the launcher"
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 2>&1 | tail -1
sleep 30
shot p01-main
log "top activity: $(top_activity)"
view_ids > "$OUT/views-main.txt"

tap_id account_spinner || true
sleep 4
shot p02-after-account-tap
log "top activity: $(top_activity)"
view_ids > "$OUT/views-account.txt"

tap_id button_local_authentication || true
sleep 4
shot p03-local-login
view_ids > "$OUT/views-local.txt"

tap_id login_edit_email || true
sleep 2
adb shell input text KProjectsTest
sleep 2
shot p04-typed
tap_id login_button || true
sleep 5
shot p05-after-login
log "top activity: $(top_activity)"

tap_id play_button || true
log "PLAY pressed"
start=$(date +%s)
i=0
result=timeout
while :; do
  sleep 15
  i=$((i + 1))
  elapsed=$(( $(date +%s) - start ))
  game="$(pids "$PKG:game")"
  top="$(top_activity)"
  [ $((i % 4)) -eq 0 ] && shot "p06-wait-$(printf %03d "$elapsed")s"
  log "t=${elapsed}s game_pid='${game}' top='${top}'"
  if [ -n "$game" ]; then result=game_started; break; fi
  case "$top" in *ShowErrorActivity*) result=error_screen; break;; esac
  [ "$elapsed" -ge "$MAX_WAIT" ] && break
done
log "wait result: $result"

if [ "$result" = game_started ]; then
  sleep 45
  shot p07-game-45s
  log "game_pid after 45s: '$(pids "$PKG:game")'"
  sleep 60
  shot p08-game-105s
  log "game_pid after 105s: '$(pids "$PKG:game")'"
else
  shot p09-final
fi

adb shell ls -la "$GAME_HOME" "$GAME_HOME/.minecraft" "$GAME_HOME/runtimes" > "$OUT/files.txt" 2>&1
adb shell tail -n 200 "$GAME_HOME/latestlog.txt" > "$OUT/latestlog-tail.txt" 2>&1
adb logcat -d -v threadtime > "$OUT/logcat-full.txt" 2>&1
grep -E 'FATAL EXCEPTION|Fatal signal|SIGSEGV|SIGABRT|hs_err|UnsatisfiedLinkError|ShowErrorActivity|Process: net\.kdt' -A6 "$OUT/logcat-full.txt" | head -150 > "$OUT/problems.txt"
grep -E 'pojav|kdt|LWJGL|Minecraft|JREUtils|libjvm|java' "$OUT/logcat-full.txt" | grep -v -E 'AppsFilter|SafetyLabel|SdkSandbox|GameManager|avc:' | head -c 250000 > "$OUT/logcat-app.txt"
rm -f "$OUT/logcat-full.txt"

echo "result=$result game_pid='$(pids "$PKG:game")' launcher_pid='$(pids "$PKG:launcher")'" | tee "$OUT/summary.txt"
echo "----- latestlog tail"
tail -n 60 "$OUT/latestlog-tail.txt"
echo "----- problems"
cat "$OUT/problems.txt"
exit 0
