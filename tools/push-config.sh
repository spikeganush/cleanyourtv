#!/usr/bin/env bash
# Pushes a launcher config file (jellyfin.json or ha.json) to the TV and restarts Home, so the shelf
# rows and the Input-button panel pick it up. The file lives in the launcher's external files dir,
# never in the APK or this repository: keep your filled-in copies out of git.
#
# Usage: TV_HOST=192.168.1.50 tools/push-config.sh my-ha.json ha.json
#        TV_HOST=192.168.1.50 tools/push-config.sh my-jellyfin.json jellyfin.json
set -euo pipefail
SRC="${1:?usage: push-config.sh <local file> <jellyfin.json|ha.json>}"
NAME="${2:?usage: push-config.sh <local file> <jellyfin.json|ha.json>}"
case "$NAME" in jellyfin.json|ha.json) ;; *) echo "the name on the TV must be jellyfin.json or ha.json" >&2; exit 1 ;; esac
python3 -c 'import json,sys; json.load(open(sys.argv[1]))' "$SRC" || { echo "$SRC is not valid JSON" >&2; exit 1; }
ADB="${ADB:-adb} -s ${TV_HOST:?set TV_HOST}:5555"
$ADB push "$SRC" "/sdcard/Android/data/com.gathin.tvhome/files/$NAME" >/dev/null
$ADB shell 'am force-stop com.gathin.tvhome; input keyevent KEYCODE_HOME'
echo "Pushed $NAME; Home restarted."
