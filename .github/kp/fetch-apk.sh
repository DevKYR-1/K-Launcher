#!/usr/bin/env bash
# Downloads the APK that an emulator test installs: the pre-release "k-projects-latest" of this repository, or any URL.
# For the pre-release it first waits for the builds of the Android CI that are still running on k-projects-ui, so the
# test gets the APK of the newest commit and not the one of an older build. A build that failed means: no new APK.
# Usage: fetch-apk.sh <owner/repo> <output-file> [url]
# Environment: GH_TOKEN (to ask for the builds), KP_FIRST_WAIT (seconds to wait before the first look, default 0:
# the Android CI of a push takes a few seconds to show up)
set -u
repo="$1"
out="$2"
url="${3:-}"

if [ -z "$url" ]; then
  sleep "${KP_FIRST_WAIT:-0}"
  unknown=0
  for i in $(seq 1 90); do
    running="$(gh run list -R "$repo" --workflow android.yml --branch k-projects-ui --limit 5 \
                 --json status --jq '[.[] | select(.status != "completed")] | length' 2>/dev/null || echo unknown)"
    echo "Android CI builds still running on k-projects-ui: $running (check $i)"
    if [ "$running" = 0 ]; then break; fi
    if [ "$running" = unknown ]; then
      unknown=$((unknown + 1))
      [ "$unknown" -ge 3 ] && break
    fi
    sleep 30
  done
  last="$(gh run list -R "$repo" --workflow android.yml --branch k-projects-ui --limit 1 \
            --json conclusion --jq '.[0].conclusion' 2>/dev/null || echo unknown)"
  echo "conclusion of the latest Android CI build: $last"
  if [ "$last" != success ] && [ "$last" != unknown ]; then
    echo "the latest build did not succeed, so there is no new APK to test"
    exit 1
  fi
  url="https://github.com/$repo/releases/download/k-projects-latest/K-Projects-debug.apk"
fi

curl -fL --retry 3 -o "$out" "$url"
ls -lh "$out"
