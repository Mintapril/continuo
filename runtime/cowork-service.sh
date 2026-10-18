#!/system/bin/sh
# Trusted lifecycle only. No user arguments interpolated into commands.
set -eu
BASE=/data/local/dsh-native/cowork
[ "$(id -u)" = 0 ] || { echo 'Root is required for the fixed Android broker'; exit 1; }
umask 077
mkdir -p "$BASE"
verified() {
  [ -r "$BASE/broker.pid" ] || return 1
  IFS= read -r PID < "$BASE/broker.pid"
  case "$PID" in ''|*[!0-9]*) return 1;; esac
  [ -r "/proc/$PID/cmdline" ] || return 1
  tr '\000' ' ' < "/proc/$PID/cmdline" | grep -F 'dev.dsh.pocket.cowork.broker.BrokerMain' >/dev/null
}
case "${1:-status}" in
  start)
    if verified; then echo "RUNNING pid=$PID"; exit 0; fi
    [ -r "$BASE/agent.token" ] && [ -r "$BASE/controller.token" ] || { echo 'Credentials must be provisioned by trusted deployment'; exit 1; }
    APK=$(pm path dev.dsh.pocket | sed -n 's/^package://p' | head -n 1)
    [ -f "$APK" ] || { echo 'DSH Pocket APK not found'; exit 1; }
    CLASSPATH="$APK" /system/bin/nohup /system/bin/app_process /system/bin dev.dsh.pocket.cowork.broker.BrokerMain </dev/null >"$BASE/broker.log" 2>&1 &
    echo $! > "$BASE/broker.pid"
    echo 'START_REQUESTED'
    ;;
  status) if verified; then echo "RUNNING pid=$PID"; else echo STOPPED; fi;;
  stop)
    if verified; then kill -TERM "$PID"; i=0; while [ "$i" -lt 10 ]; do if ! verified; then echo STOPPED; exit 0; fi; sleep 1; i=$((i+1)); done; echo 'Broker did not stop; no forced kill'; exit 1; fi
    echo STOPPED
    ;;
  controller-token) cat "$BASE/controller.token";;
  agent-token) cat "$BASE/agent.token";;
  *) echo 'Unsupported lifecycle action'; exit 2;;
esac
