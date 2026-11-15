#!/usr/bin/env bash
# K-Projects emulator smoke test. Runs inside the emulator step (adb is available).
# Installs the APK, starts the launcher, takes screenshots, and scans the log for crashes.
# Usage: smoke.sh <apk> <output-dir>
set -u
APK="$1"
OUT="$2"
PKG=net.kdt.pojavlaunch.debug
mkdir -p "$OUT"

log() { echo "[smoke $(date +%H:%M:%S)] $*"; }
shot() {
  adb exec-out screencap -p > "$OUT/$1.png" || true
  log "screenshot $1 ($(stat -c %s "$OUT/$1.png" 2>/dev/null || echo 0) bytes)"
}
pids() { adb shell pidof "$PKG:launcher" "$PKG" 2>/dev/null | tr -d '\r'; }

adb wait-for-device
for _ in $(seq 1 90); do
  [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
  sleep 2
done
adb shell input keyevent 82 || true
{
  echo "sdk=$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
  echo "abi=$(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
  adb shell wm size | tr -d '\r'
  adb shell wm density | tr -d '\r'
} | tee "$OUT/device.txt"

log "installing $APK"
adb install -r -g "$APK" 2>&1 | tee "$OUT/install.txt"
if ! grep -q Success "$OUT/install.txt"; then
  log "INSTALL FAILED"
  exit 1
fi

adb logcat -c
log "starting the launcher"
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 2>&1 | tail -2
sleep 8
shot 01-after-8s
log "pids: $(pids)"
sleep 22
shot 02-after-30s
log "pids: $(pids)"
adb shell dumpsys activity activities 2>/dev/null | grep -E "topResumedActivity|mResumedActivity" | head -3 | tee "$OUT/resumed.txt"

# which of our own views are really on screen
adb shell dumpsys activity top > "$OUT/activity-top.txt" 2>&1
grep -o -E 'net\.kdt\.pojavlaunch\.kprojects\.[A-Za-z]+\{[^}]*\}' "$OUT/activity-top.txt" | head -10 | tee "$OUT/kp-views.txt"

log "rotating to landscape"
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1
sleep 8
shot 03-landscape
adb shell dumpsys activity top 2>/dev/null | grep -o -E 'net\.kdt\.pojavlaunch\.kprojects\.[A-Za-z]+\{[^}]*\}' | head -10 | tee "$OUT/kp-views-landscape.txt"
adb shell settings put system user_rotation 0
sleep 4
shot 04-back-to-portrait

adb logcat -d -v threadtime > "$OUT/logcat-full.txt" 2>&1
fatal=$(grep -c -E 'FATAL EXCEPTION' "$OUT/logcat-full.txt" || true)
grep -E 'FATAL EXCEPTION|Process: net\.kdt|ShowErrorActivity|UnsatisfiedLinkError|Resources\$NotFoundException|InflateException|Failed to unpack|Failed to install' -A8 "$OUT/logcat-full.txt" | head -120 > "$OUT/problems.txt"
grep -E 'pojav|kdt|kprojects|K-Projects' "$OUT/logcat-full.txt" | head -c 200000 > "$OUT/logcat-app.txt"
rm -f "$OUT/logcat-full.txt" "$OUT/activity-top.txt"

alive_now="$(pids)"
log "RESULT alive_pids='${alive_now}' fatal_exceptions=${fatal}"
echo "alive_pids='${alive_now}' fatal_exceptions=${fatal}" > "$OUT/summary.txt"
echo "----- problems.txt"
cat "$OUT/problems.txt"
exit 0
