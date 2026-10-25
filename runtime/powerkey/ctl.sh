#!/system/bin/sh
# Long-press power -> Continuo's 叫它, instead of Breeno (小布).
#   start   turn off the system's long-press -> Breeno (secure speech_assist_power_wakeup=0) and start the watcher
#   stop    stop the watcher and give the long press back to Breeno (speech_assist_power_wakeup=1)
#   status  whether the watcher runs, and the setting
# Installed at /data/adb/continuo-powerkey; /data/adb/service.d/continuo-powerkey.sh runs `start` at boot.
# The watcher only reads the key (it never grabs it), so the power button keeps working whatever happens to it.
D=/data/adb/continuo-powerkey
PIDF=$D/run/pid
running() { [ -f "$PIDF" ] && kill -0 "$(cat "$PIDF")" 2>/dev/null; }
case "$1" in
  start)
    mkdir -p "$D/run"
    if running; then echo "already running (pid $(cat "$PIDF"))"; exit 0; fi
    settings put secure speech_assist_power_wakeup 0
    # Own session, so `stop` can end the watcher together with its getevent and subshells.
    setsid sh "$D/continuo-powerkey.sh" >> "$D/run/watcher.log" 2>&1 < /dev/null &
    echo $! > "$PIDF"
    echo "started (pid $!)"
    ;;
  stop)
    if [ -f "$PIDF" ]; then kill -- "-$(cat "$PIDF")" 2>/dev/null || kill "$(cat "$PIDF")" 2>/dev/null; fi
    pkill -f "$D/continuo-powerkey.sh" 2>/dev/null
    rm -f "$PIDF"
    settings put secure speech_assist_power_wakeup 1
    echo "stopped; long press is Breeno's again"
    ;;
  status)
    if running; then echo "running (pid $(cat "$PIDF"))"; else echo "not running"; fi
    echo "speech_assist_power_wakeup=$(settings get secure speech_assist_power_wakeup)"
    ;;
  *) echo "usage: $0 start|stop|status"; exit 2 ;;
esac
