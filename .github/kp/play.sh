#!/usr/bin/env bash
# K-Projects emulator test of the PLAY button with a Local account (see kp-play.yml).
# Runs inside the emulator step (adb is available). The helpers are in lib.sh.
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
TAG=play
MAX_WAIT="${MAX_WAIT:-420}"
. "$(dirname "$0")/lib.sh"

finish() {
  collect
  # The verdict: the dialog came up, nothing crashed, and the details are in the launcher log
  local verdict="$1" crashed=no logged=no
  grep -q -E 'FATAL EXCEPTION|Fatal signal' "$OUT/problems.txt" && crashed=yes
  grep -q 'LocalAccountDownloadException' "$OUT/launcher-log.txt" 2>/dev/null && logged=yes
  if [ "$verdict" = local_dialog ] && [ "$crashed" = no ] && [ "$logged" = yes ]; then verdict=ok; fi
  echo "version=$VER result=$verdict crashed=$crashed logged=$logged ms_login=${MS_LOGIN:-not_tested}" | tee "$OUT/summary.txt"
  echo "----- launcher-log.txt (first lines)"
  head -n 25 "$OUT/launcher-log.txt"
  echo "----- problems"
  head -n 60 "$OUT/problems.txt"
  exit 0
}

kp_boot
kp_install "$APK"

adb logcat -c
log "starting the launcher"
kp_start_app
sleep 30
dismiss_system_dialogs
shot 01-main
log "top activity: $(top_activity)"

kp_local_account
kp_set_profile_version "$VER"

adb logcat -c
kp_start_app
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
  if [ "$elapsed" -ge "$MAX_WAIT" ]; then break; fi
  game="$(pids "$PKG:game")"
  top="$(top_activity)"
  [ $((i % 10)) -eq 0 ] && shot "07-wait-$(printf %04d "$elapsed")s"
  [ $((i % 5)) -eq 0 ] && log "t=${elapsed}s game_pid='${game}' top='${top##*.}'"
  if [ -n "$game" ]; then result=game_started; break; fi
  case "$top" in *ShowErrorActivity*) result=error_screen; break;; esac
  if ui_dump; then
    # an "isn't responding" dialog of the system covers everything, including the answer we wait for
    if anr_dialog_close; then continue; fi
    if ui_has "Microsoft account required"; then result=local_dialog; break; fi
    # the text of the old error: a raw failure that tells nothing
    if ui_has "Please make sure you are logged in with a Microsoft Account"; then result=old_raw_error; break; fi
    # any other message of the new error handling is a finding as well
    if ui_has "Details:"; then result=other_error_dialog; break; fi
  fi
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
