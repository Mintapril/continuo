#!/system/bin/sh
# Fixed-scope UI control bridge. Install root-owned 0700; no arbitrary command input.
set -eu
BASE=/data/local/dsh-native
ROOT=$BASE/rootfs
SERVICE=$BASE/native-service.sh
[ "$(id -u)" = 0 ] || { echo 'ROOT_REQUIRED'; exit 1; }
[ ! -L "$BASE" ] && [ "$(readlink -f "$BASE")" = "$BASE" ] || exit 1
[ -x "$ROOT/opt/node/bin/node" ] && [ -f "$SERVICE" ] || { echo 'RUNTIME_MISSING'; exit 1; }
case "${1:-status}" in
  auth)
    # Token stays on stdout of the private su pipe, never in argv/logcat/public files.
    sed -n 's/.*http:\/\/127\.0\.0\.1:3080\/[?]token=\([A-Za-z0-9_-]*\).*/\1/p' "$BASE/logs/web-current.log" | tail -n 1
    exit 0 ;;
  status)
    /system/bin/sh "$SERVICE" status
    echo "SELINUX=$(getenforce)"
    echo 'ROOTFS=/data/local/dsh-native/rootfs'
    echo 'RUNTIME=Node + Ubuntu chroot (shared Android kernel)'
    exit 0 ;;
  start|stop|restart|restart-environment) ;;
  *) echo 'INVALID_ACTION'; exit 2 ;;
esac
LOCK=$BASE/.pocket-control-lock
mkdir "$LOCK" 2>/dev/null || { echo 'CONTROL_BUSY: another operation is in progress'; exit 1; }
trap 'rmdir "$LOCK"' EXIT
case "$1" in
  start) /system/bin/sh "$SERVICE" start ;;
  stop) /system/bin/sh "$SERVICE" stop ;;
  restart) /system/bin/sh "$SERVICE" stop; /system/bin/sh "$SERVICE" start ;;
  restart-environment)
    /system/bin/sh "$SERVICE" stop
    # A chroot is not a VM. Namespace teardown requires all its processes to exit.
    # Refuse surviving jobs; never kill unrelated Node, Android, or shell processes.
    for proc in /proc/[0-9]*; do
      [ "$(readlink "$proc/root" 2>/dev/null || true)" != "$ROOT" ] || {
        echo "ENVIRONMENT_BUSY: process ${proc##*/} still has the dedicated chroot root; close its job before retrying"
        exit 1
      }
    done
    /system/bin/sh "$SERVICE" start
    echo 'ENVIRONMENT_RECREATED: private mounts recreated; files and credentials preserved'
    ;;
esac
