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
  # Reading the cmdline of a broker that just exited once spun at full CPU for minutes.
  # Bound the read by size and time; a vanished process or a timed-out read counts as not running.
  timeout -k 1 3 head -c 4096 "/proc/$PID/cmdline" 2>/dev/null | tr '\000' ' ' | grep -F 'dev.dsh.pocket.cowork.broker.BrokerMain' >/dev/null
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
    if verified; then kill -TERM "$PID"; i=0; while [ "$i" -lt 10 ]; do if ! verified; then rm -f "$BASE/broker.pid"; echo STOPPED; exit 0; fi; sleep 1; i=$((i+1)); done; echo 'Broker did not stop; no forced kill'; exit 1; fi
    # The process is gone: drop its pid so a later start or status never reads a stale /proc entry.
    rm -f "$BASE/broker.pid"
    echo STOPPED
    ;;
  controller-token) cat "$BASE/controller.token";;
  agent-token) cat "$BASE/agent.token";;
  notify-token)
    # Shared secret of Continuo's notification webhook (cowork-plugin/src/notify.mjs reads it inside the rootfs).
    # Created on first use; the umask above keeps it 0600. Separate from the agent and controller tokens.
    NOTIFY=/data/local/dsh-native/rootfs/root/.config/dsh/cowork-notify.token
    [ -d "${NOTIFY%/*}" ] || { echo 'DSH config directory missing'; exit 1; }
    if [ ! -s "$NOTIFY" ]; then
      TOKEN=$(head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n')
      case "$TOKEN" in *[!0-9a-f]*|'') echo 'Cannot create notification token'; exit 1;; esac
      [ "${#TOKEN}" -eq 64 ] || { echo 'Cannot create notification token'; exit 1; }
      printf '%s' "$TOKEN" > "$NOTIFY.new"
      mv "$NOTIFY.new" "$NOTIFY"
    fi
    cat "$NOTIFY";;
  *) echo 'Unsupported lifecycle action'; exit 2;;
esac
