#!/usr/bin/env bash
# K-Projects emulator test: does the launcher start a game process, and does that process stay alive? (see kp-game.yml)
# Runs inside the emulator step (adb is available). The helpers are in lib.sh.
#
# A Local account cannot download game files, but it can start a version that is already installed. This test
# installs a tiny stand-in "version" (stub/KPStub.java, built here; it is NOT Minecraft) in the folder of the
# launcher, creates a Local account, presses PLAY and watches what happens:
#   - the launcher accepts the installed version (no "Microsoft account required" dialog),
#   - the Java runtime of the requested major version (8 / 17 / 21) is unpacked from the APK and started,
#   - the game process (":game") appears and keeps running while the stub works (it opens a graphics window the way
#     the game does, then idles for a minute),
#   - when the stub ends, the launcher ends cleanly: no crash, no fatal signal.
# The same script runs against the original PojavLauncher APK as a control (package net.kdt.pojavlaunch).
# Screenshots, UI dumps and logs are written to <output-dir>; summary.txt has the verdict.
# Usage: game.sh <apk> <output-dir> <package> <java-major-version>
set -u
APK="$1"
OUT="$2"
PKG="$3"
JAVA="$4"
TAG=game
VERSION_ID=kp-stub
MAX_START="${MAX_START:-720}"   # seconds to wait for the game process after PLAY (the Java runtime is unpacked first)
HOLD="${HOLD:-240}"             # seconds to watch the game process
KP_LOG_EXTRA='KPSTUB|GameService|ExitActivity|VMLauncher'
HERE="$(cd "$(dirname "$0")" && pwd)"
. "$HERE/lib.sh"

# The stand-in game: a jar and the version file that describes it
build_stub() {
  local work="$OUT/stub-build"
  rm -rf "$work"
  mkdir -p "$work/classes"
  javac --release 8 -nowarn -cp "$HERE/../../app_pojavlauncher/src/main/assets/components/lwjgl3/lwjgl-glfw-classes.jar" \
      -d "$work/classes" "$HERE/stub/KPStub.java" "$HERE/stub/KPGl.java" || return 1
  (cd "$work/classes" && jar cf "../$VERSION_ID.jar" .) || return 1
  python3 - "$work" "$VERSION_ID" "$JAVA" <<'PY'
import hashlib, json, os, sys
work, vid, java = sys.argv[1], sys.argv[2], int(sys.argv[3])
jar = open(os.path.join(work, vid + ".jar"), "rb").read()
doc = {
    "id": vid,
    "type": "release",
    "mainClass": "KPStub",
    "minecraftArguments": "--username ${auth_player_name} --version ${version_name} --gameDir ${game_directory} --assetsDir ${assets_root}",
    "assets": vid,
    "libraries": [],
    "javaVersion": {"component": "kp-stub", "majorVersion": java},
    "downloads": {"client": {"sha1": hashlib.sha1(jar).hexdigest(), "size": len(jar),
                             "url": "https://example.invalid/" + vid + ".jar"}},
    "time": "2021-01-01T00:00:00+00:00",
    "releaseTime": "2021-01-01T00:00:00+00:00",
    "minimumLauncherVersion": 21,
}
json.dump(doc, open(os.path.join(work, vid + ".json"), "w"), indent=1)
PY
}

push_stub() {
  local dir="$GAME_HOME/.minecraft/versions/$VERSION_ID"
  adb shell mkdir -p "$dir"
  adb push "$OUT/stub-build/$VERSION_ID.jar" "$dir/$VERSION_ID.jar" > /dev/null
  adb push "$OUT/stub-build/$VERSION_ID.json" "$dir/$VERSION_ID.json" > /dev/null
  adb shell ls -la "$dir"
}

finish() {
  collect
  local verdict="$1" crashed=no stub=no finished=no gl=none
  grep -q -E 'FATAL EXCEPTION|Fatal signal' "$OUT/problems.txt" && crashed=yes
  grep -q 'KPSTUB-START' "$OUT/latestlog.txt" 2>/dev/null && stub=yes
  grep -q 'KPSTUB-DONE' "$OUT/latestlog.txt" 2>/dev/null && finished=yes
  if grep -q 'KPSTUB-GL frames ok' "$OUT/latestlog.txt" 2>/dev/null; then gl=ok
  elif grep -q 'KPSTUB-GL-FAILED' "$OUT/latestlog.txt" 2>/dev/null; then gl=failed
  elif grep -q 'STILL-RUNNING' "$OUT/latestlog.txt" 2>/dev/null; then gl=hang; fi
  # ok = the launcher started the stub, the stub ran to its end, the launcher did not crash. The graphics result
  # (gl=) is reported next to it and does not decide: it says more about the test machine than about the launcher.
  if [ "$verdict" = clean_exit ] && [ "$stub" = yes ] && [ "$finished" = yes ] && [ "$crashed" = no ]; then verdict=ok; fi
  echo "java=$JAVA pkg=$PKG result=$verdict started_after=${STARTED_AFTER:-none}s alive_for=${ALIVE_FOR:-0}s stub=$stub finished=$finished gl=$gl crashed=$crashed" | tee "$OUT/summary.txt"
  echo "----- latestlog.txt (KPSTUB lines and the Java exit code)"
  grep -E 'KPSTUB|Java Exit code|exited|Exception' "$OUT/latestlog.txt" | head -n 40
  echo "----- latestlog.txt (last lines)"
  tail -n 25 "$OUT/latestlog.txt"
  echo "----- launcher-log.txt (first lines)"
  head -n 15 "$OUT/launcher-log.txt"
  echo "----- problems"
  head -n 60 "$OUT/problems.txt"
  echo "----- anr"
  head -n 10 "$OUT/anr.txt"
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
build_stub || { log "the stand-in game could not be built"; echo "result=stub_build_failed" > "$OUT/summary.txt"; exit 1; }
kp_set_profile_version "$VERSION_ID"
push_stub

adb logcat -c
kp_start_app
sleep 25
shot 06-before-play
must_tap play_button
log "PLAY pressed (stand-in game for Java $JAVA)"

# Phase 1: wait until the game process exists. Dialogs of the app that only ask for a click (for example the
# memory warning) are answered and written down; anything that looks like an error ends the wait.
start=$(date +%s)
result=timeout
i=0
while :; do
  sleep 5
  i=$((i + 1))
  elapsed=$(( $(date +%s) - start ))
  if [ "$elapsed" -ge "$MAX_START" ]; then break; fi
  game="$(pids "$PKG:game")"
  if [ -n "$game" ]; then result=game_started; STARTED_AFTER=$elapsed; break; fi
  top="$(top_activity)"
  if [ $((i % 6)) -eq 0 ]; then shot "07-start-$(printf %04d "$elapsed")s"; log "t=${elapsed}s top='${top##*.}'"; fi
  case "$top" in *ShowErrorActivity*|*FatalErrorActivity*) result=error_screen; break;; esac
  if ui_dump; then
    if anr_dialog_close; then continue; fi
    if ui_has "Microsoft account required"; then result=fixture_rejected; break; fi
    if ui_has "Details:" || ui_has "exited with code" || ui_has "fatal signal"; then result=error_dialog; break; fi
    if dialog="$(ui_app_dialog)"; then
      xy="${dialog%%|*}"
      log "a dialog of the app is open: '${dialog#*|}' -> pressing its first button"
      echo "${dialog#*|}" >> "$OUT/dialogs.txt"
      adb shell input tap ${xy}
      continue
    fi
  fi
done
log "start result: $result after $(( $(date +%s) - start ))s"
shot 08-after-start
cp "$OUT/ui.xml" "$OUT/ui-after-start.xml" 2>/dev/null || true

# Phase 2: the game process has to stay alive until the stand-in ends by itself
if [ "$result" = game_started ]; then
  seen=$(date +%s)
  while :; do
    sleep 3
    ALIVE_FOR=$(( $(date +%s) - seen ))
    if [ -z "$(pids "$PKG:game")" ]; then
      if adb shell grep -q KPSTUB-DONE "$GAME_HOME/latestlog.txt" 2>/dev/null; then result=clean_exit; else result=game_died; fi
      break
    fi
    if [ $((ALIVE_FOR % 30)) -lt 3 ]; then shot "09-alive-$(printf %04d "$ALIVE_FOR")s"; log "game process alive for ${ALIVE_FOR}s"; fi
    if [ "$ALIVE_FOR" -ge "$HOLD" ]; then result=still_running_after_hold; break; fi
  done
  log "game phase result: $result after ${ALIVE_FOR}s"
  sleep 5
  shot 10-after-game
  log "top activity after the game: $(top_activity) | launcher process: '$(pids "$PKG:launcher")' app: '$(pids "$PKG")'"
fi
finish "$result"
