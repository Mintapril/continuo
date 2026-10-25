#!/system/bin/sh
# KernelSU late-start script (install as /data/adb/service.d/continuo-powerkey.sh): once Android has booted, long-press
# power opens Continuo's 叫它 instead of Breeno. Remove this file to stop doing it at boot;
# `sh /data/adb/continuo-powerkey/ctl.sh stop` stops it now and gives the long press back to Breeno.
D=/data/adb/continuo-powerkey
until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 5; done
sleep 10
mkdir -p $D/run
{ date; sh $D/ctl.sh start; } >> $D/run/boot.log 2>&1
