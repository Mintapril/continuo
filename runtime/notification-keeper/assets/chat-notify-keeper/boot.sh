#!/system/bin/sh
# One detached monitor; disabled pauses protection without ending this monitor.
set -eu
BASE=/data/local/dsh-native/chat-notify-keeper
[ "$(id -u)" = 0 ] || exit 1
if [ -r "$BASE/monitor.pid" ]; then
    OLD=$(head -n 1 "$BASE/monitor.pid")
    case "$OLD" in ''|*[!0-9]*) ;; *)
        if [ "$OLD" != "$$" ] && [ -r "/proc/$OLD/cmdline" ] && timeout -k 1 3 head -c 4096 "/proc/$OLD/cmdline" | tr '\000' ' ' | grep -F "$BASE/boot.sh" >/dev/null; then exit 0; fi;;
    esac
fi
# mkdir serializes simultaneous boot/install invocations; kernel death is also covered by PID check above.
LOCK=$BASE/monitor.lock
if ! mkdir "$LOCK" 2>/dev/null; then
    if [ -r "$LOCK/pid" ]; then
        OLD=$(head -n 1 "$LOCK/pid")
        case "$OLD" in ''|*[!0-9]*) ;; *)
            if [ -r "/proc/$OLD/cmdline" ] && timeout -k 1 3 head -c 4096 "/proc/$OLD/cmdline" | tr '\000' ' ' | grep -F "$BASE/boot.sh" >/dev/null; then exit 0; fi;;
        esac
    fi
    rm -f "$LOCK/pid"; rmdir "$LOCK" 2>/dev/null || exit 0
    mkdir "$LOCK" 2>/dev/null || exit 0
fi
printf '%s\n' "$$" > "$LOCK/pid"
printf '%s\n' "$$" > "$BASE/monitor.pid"
trap 'rm -f "$BASE/monitor.pid" "$LOCK/pid"; rmdir "$LOCK" 2>/dev/null || true' EXIT
until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 5; done
MISSING=0
while [ -f /data/adb/service.d/continuo-chat-notify.sh ]; do
    PACKAGES=$(pm list packages --user 0 2>/dev/null || true)
    if printf '%s\n' "$PACKAGES" | grep -q '^package:dev.dsh.pocket$'; then
        MISSING=0
    elif printf '%s\n' "$PACKAGES" | grep -q '^package:android$'; then
        MISSING=$((MISSING+1))
        if [ "$MISSING" -ge 3 ]; then
            sh "$BASE/keeper.sh" stop || true
            rm -f /data/adb/service.d/continuo-chat-notify.sh
            exit 0
        fi
    fi
    sh "$BASE/keeper.sh" start >/dev/null 2>&1 || true
    sleep 30
done
