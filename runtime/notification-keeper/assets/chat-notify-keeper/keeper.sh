#!/system/bin/sh
# Trusted fixed lifecycle; root-owned, no model arguments or app private data.
set -eu
BASE=/data/local/dsh-native/chat-notify-keeper
CLASS=dev.dsh.notifykeep.ChatNotificationKeeper
[ "$(id -u)" = 0 ] || exit 1
umask 077
LOCK=$BASE/lifecycle.lock
if ! mkdir "$LOCK" 2>/dev/null; then
    if [ -r "$LOCK/pid" ]; then
        OWNER=$(head -n 1 "$LOCK/pid")
        case "$OWNER" in ''|*[!0-9]*) ;; *) if kill -0 "$OWNER" 2>/dev/null; then echo BUSY; exit 0; fi;; esac
    else
        echo BUSY; exit 0
    fi
    rm -f "$LOCK/pid"; rmdir "$LOCK" 2>/dev/null || exit 1
    mkdir "$LOCK" 2>/dev/null || exit 1
fi
printf '%s\n' "$$" > "$LOCK/pid"
trap 'rm -f "$LOCK/pid"; rmdir "$LOCK" 2>/dev/null || true' EXIT
verified() {
    [ -r "$BASE/keeper.pid" ] || return 1
    IFS= read -r PID < "$BASE/keeper.pid"
    case "$PID" in ''|*[!0-9]*) return 1;; esac
    [ -r "/proc/$PID/cmdline" ] || return 1
    timeout -k 1 3 head -c 4096 "/proc/$PID/cmdline" 2>/dev/null | tr '\000' ' ' | grep -F "$CLASS" >/dev/null
}
stop() {
    if verified; then
        kill -TERM "$PID"
        i=0
        while kill -0 "$PID" 2>/dev/null; do
            i=$((i+1)); [ "$i" -lt 30 ] || { echo STOP_TIMEOUT; return 1; }
            sleep 0.1
        done
    fi
    rm -f "$BASE/keeper.pid" "$BASE/apk.sha256"
}
case "${1:-status}" in
    start)
        [ ! -e "$BASE/disabled" ] || { echo DISABLED; exit 0; }
        APK=$(pm path --user 0 dev.dsh.pocket | sed -n 's/^package://p' | head -n 1)
        if [ ! -f "$APK" ]; then echo APP_UNAVAILABLE; exit 0; fi
        # The app list is read once at keeper start, so a changed list restarts it like a changed APK.
        LIST=$( (sha256sum "$BASE/packages" 2>/dev/null || echo default) | cut -d ' ' -f 1)
        HASH="$APK $(sha256sum "$APK" | cut -d ' ' -f 1) list=$LIST"
        OLD=$(head -n 1 "$BASE/apk.sha256" 2>/dev/null || true)
        if verified; then
            if [ "$HASH" = "$OLD" ]; then echo "RUNNING pid=$PID"; exit 0; fi
            stop
        fi
        CLASSPATH="$APK" /system/bin/nohup /system/bin/app_process /system/bin "$CLASS" </dev/null >"$BASE/keeper.log" 2>&1 &
        echo $! > "$BASE/keeper.pid"
        sleep 0.3
        verified || { echo START_FAILED; exit 1; }
        printf '%s\n' "$HASH" > "$BASE/apk.sha256"
        echo START_REQUESTED
        ;;
    stop) stop; echo STOPPED;;
    status) if verified; then echo "RUNNING pid=$PID"; else echo STOPPED; fi;;
    disable) touch "$BASE/disabled"; stop; echo DISABLED;;
    uninstall)
        touch "$BASE/disabled"; stop
        rm -f /data/adb/service.d/continuo-chat-notify.sh
        echo UNINSTALLED
        ;;
    *) echo 'start|stop|status|disable|uninstall'; exit 2;;
esac
