#!/system/bin/sh
# continuo-powerkey: long-press the power key -> open Continuo's 叫它.
#
# Read-only (non-exclusive) input monitor. It never grabs the key device, so a
# crash or exit can NEVER break the power button: short press (screen off),
# double-press (camera), hardware shutdown all keep working untouched. The
# system's own long-press->Breeno is turned off separately by setting
# `secure speech_assist_power_wakeup` to 0, so a long press shows nothing from
# the system and this daemon opens Continuo instead.
#
# Debounce without killing `am`: a long press is "still held when the timer
# expires". On DOWN we clear a released-marker and start a delayed launcher; on
# UP we create the marker. The launcher fires only if the marker is absent when
# its timer elapses (i.e. the key is still down), so a short press (UP before
# the timer) never launches and a long press never kills a running `am`.

PKG=dev.dsh.pocket
HOLD=0.45                     # seconds held before it counts as a long press
MARK=/dev/.continuo-pwr-released   # tmpfs: gone after a reboot, which is what we want
TAG=continuo-powerkey

launch() {
  am start -n "$PKG/.cowork.CoworkShadeActivity" -a "$PKG.shade.CALL" >/dev/null 2>&1
}

# The power-key input node, resolved by name (event numbers can change on boot).
power_dev() {
  getevent -pl 2>/dev/null | awk '
    /^add device/ { d = $NF }
    /name: +"pmic_pwrkey"/ { print d; exit }'
}

watch() {
  dev=$(power_dev)
  [ -n "$dev" ] || { log -t "$TAG" "power key device not found"; return 1; }
  log -t "$TAG" "watching $dev"
  getevent -l "$dev" | while read -r _type code value _rest; do
    [ "$code" = "KEY_POWER" ] || continue
    case "$value" in
      DOWN) rm -f "$MARK"; ( sleep "$HOLD"; [ -f "$MARK" ] || launch ) & ;;
      UP)   : > "$MARK" ;;
    esac
  done
}

# getevent ending means the device was re-enumerated; re-resolve and carry on.
while true; do
  watch
  sleep 2
done
