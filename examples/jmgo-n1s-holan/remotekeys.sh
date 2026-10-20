#!/system/bin/sh
# Make the Chromecast remote's YouTube and Netflix buttons work on the JMGO N1S.
# The remote (Google 18d1:9450) has no key layout on this firmware, so both buttons arrive as
# KEY_UNKNOWN and Android ignores them. This watches the raw HID usage instead and launches the app.
# Runs as the adb shell user (which can read /dev/input); survives standby, not a full reboot.
#
# Start:  adb push remotekeys.sh /data/local/tmp/ && adb shell 'setsid nohup sh /data/local/tmp/remotekeys.sh >/dev/null 2>&1 < /dev/null &'
# Stop:   adb shell 'pkill -f remotekeys.sh; pkill -f "getevent -l"'

YOUTUBE=com.google.android.youtube.tv/com.google.android.apps.youtube.tv.activity.ShellActivity
NETFLIX=com.netflix.ninja/.MainActivity

# Every input device, so the remote is still caught after it reconnects under a new event node.
getevent -l 2>/dev/null | while read -r dev type code value; do
  case "$code" in
    MSC_SCAN) last=$value ;;
    KEY_UNKNOWN)
      [ "$value" = DOWN ] || continue
      case "$last" in
        000c0077) am start -n "$YOUTUBE" >/dev/null 2>&1 ;;
        000c0078) am start -n "$NETFLIX" >/dev/null 2>&1 ;;
      esac ;;
  esac
done
