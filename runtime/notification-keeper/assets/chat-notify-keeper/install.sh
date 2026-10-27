#!/system/bin/sh
# Run as root from the app-private extracted asset directory. No credentials needed.
set -eu
[ "$(id -u)" = 0 ] || exit 1
SOURCE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
BASE=/data/local/dsh-native/chat-notify-keeper
umask 077
[ ! -L "$BASE" ] || exit 1
mkdir -p "$BASE" /data/adb/service.d
# Stop only a legacy monitor (the old standalone deployment), never the Broker.
if [ -r /data/adb/service.d/continuo-chat-notify.sh ] && ! grep -q 'INTEGRATED_KEEPER_V2' /data/adb/service.d/continuo-chat-notify.sh; then
    ps -A -o PID,ARGS | while read -r PID ARGS; do
        case "$ARGS" in 'sh /data/adb/service.d/continuo-chat-notify.sh')
            case "$PID" in ''|*[!0-9]*) ;; *) kill -TERM "$PID" 2>/dev/null || true;; esac;;
        esac
    done
fi
for FILE in keeper.sh boot.sh; do
    cp "$SOURCE/$FILE" "$BASE/$FILE.new"
    chmod 700 "$BASE/$FILE.new"
    mv -f "$BASE/$FILE.new" "$BASE/$FILE"
done
cat > /data/adb/service.d/continuo-chat-notify.sh.new <<'BOOT'
#!/system/bin/sh
# INTEGRATED_KEEPER_V2
BASE=/data/local/dsh-native/chat-notify-keeper
/system/bin/nohup /system/bin/sh "$BASE/boot.sh" </dev/null >/dev/null 2>&1 &
BOOT
chmod 700 /data/adb/service.d/continuo-chat-notify.sh.new
mv -f /data/adb/service.d/continuo-chat-notify.sh.new /data/adb/service.d/continuo-chat-notify.sh
# No apk.sha256 in standalone v1: keeper start replaces it once with the installed APK entry.
sh "$BASE/keeper.sh" start
sh /data/adb/service.d/continuo-chat-notify.sh
echo INSTALLED
