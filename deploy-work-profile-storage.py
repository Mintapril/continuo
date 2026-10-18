"""One fixed-device storage remap. Preserves old files; no profile/system permission changes."""
from pathlib import Path
import hashlib
import json
import subprocess
import sys

ROOT = Path(__file__).resolve().parent
ADB = r"C:\Users\rangt\AppData\Local\Microsoft\WinGet\Packages\Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe\platform-tools\adb.exe"
SERIAL = "5b9a17cd"
SOURCE = ROOT.parent / "dsh-android" / "enter-native.sh"
EXPECTED = "78e9f8fe1498ab14311508aa4fd8b92d1ad1e1b25b9b20e54f700e00584da0b4"
BACKUP = "/data/local/dsh-native/storage-profile-migration-v1"


def adb(*args, data=None):
    r = subprocess.run([ADB, "-s", SERIAL, *args], input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=90)
    if r.returncode:
        # This script runs no secret-reading commands. Errors remain bounded.
        raise RuntimeError((r.stdout + r.stderr).decode("utf-8", "replace")[-2500:])
    return r.stdout.decode("utf-8", "replace")


def root(script):
    return adb("shell", "su", "-c", "/system/bin/sh", data=script.encode())


def main():
    # Read-only authenticated metadata only; no model request or old transcript read.
    subprocess.run([sys.executable, "-B", str(ROOT / "cowork-runtime-test.py"), "inspect"], check=True)
    report = json.loads((ROOT / "test-evidence/cowork/runtime-inspection.json").read_text("utf-8"))
    if not report.get("quiescent") or report.get("runningCount") != 0 or report.get("unknownRunningCount") != 0:
        raise RuntimeError("Active/unknown DSH sessions: storage change refused")
    payload = SOURCE.read_bytes()
    digest = hashlib.sha256(payload).hexdigest()
    adb("push", str(SOURCE), "/data/local/tmp/dsh-enter-work-profile-v1.sh")
    script = r'''set -eu
umask 077
B=/data/local/dsh-native
R=$B/rootfs
D=/data/local/dsh-native/storage-profile-migration-v1
S=/data/local/tmp/dsh-enter-work-profile-v1.sh
[ "$(id -u)" = 0 ]
[ "$(readlink -f "$B")" = "$B" ] && [ ! -L "$B" ]
[ "$(readlink -f "$R")" = "$R" ] && [ ! -L "$R" ]
[ ! -e "$D" ] && [ ! -L "$D" ] || { echo MIGRATION_ALREADY_EXISTS; exit 20; }
[ "$(sha256sum "$B/enter-native.sh" | cut -d ' ' -f1)" = '__OLD__' ] || { echo ENTRY_CHANGED; exit 21; }
[ "$(sha256sum "$S" | cut -d ' ' -f1)" = '__NEW__' ] || exit 22
/system/bin/sh -n "$S"
dumpsys user | grep -E '^  UserInfo\{10:.*\} serialNo=10 isPrimary=false parentId=0$' >/dev/null || exit 23
dpm list-owners | grep -Fx 'User 10: admin=dev.dsh.pocket.isolation/.ProfileAdminReceiver,ManagedProfileOwner(parentUserId=0)' >/dev/null || exit 24
[ "$(readlink -f /mnt/user/10/emulated/10)" = /mnt/user/10/emulated/10 ] && [ -d /mnt/user/10/emulated/10 ] || exit 25
awk '$5=="/mnt/user/10/emulated" && $0 ~ / - fuse / {ok=1} END {exit !ok}' /proc/self/mountinfo || exit 26
/system/bin/sh "$B/native-service.sh" stop
# Old private mount namespaces must no longer be held by a chroot process.
for p in /proc/[0-9]*; do
  actual=$(readlink "$p/root" 2>/dev/null || true)
  [ "$actual" != "$R" ] || { echo CHROOT_PROCESS_STILL_ALIVE; exit 27; }
done
for p in "$R/workspace" "$R/workspace/phone" "$R/workspace/cowork"; do
  [ -d "$p" ] && [ ! -L "$p" ] && [ "$(readlink -f "$p")" = "$p" ] || exit 28
done
# All destructive-looking paths are fixed, canonical, and checked before use.
# rmdir only removes an EMPTY old mount point; no shared-storage files are deleted.
[ -z "$(ls -A "$R/workspace/phone")" ] || { echo LEGACY_DIRECTORY_NOT_EMPTY; exit 29; }
awk -v a="$R/workspace/phone" -v b="$R/workspace/cowork" '$5==a || $5==b {bad=1} END {exit bad}' /proc/self/mountinfo || exit 30
mkdir -m 700 "$D"
cp -p "$B/enter-native.sh" "$D/enter-native.before.sh"
cp -p "$S" "$D/enter-native.after.sh"
chmod 600 "$D/enter-native.before.sh" "$D/enter-native.after.sh"
mv "$R/workspace/cowork" "$D/cowork-files.before"
mkdir -m 700 "$R/workspace/cowork"
rmdir "$R/workspace/phone"
[ ! -e "$B/enter-native.work-profile.new" ] && [ ! -L "$B/enter-native.work-profile.new" ] || exit 31
cp "$S" "$B/enter-native.work-profile.new"
chmod 700 "$B/enter-native.work-profile.new"
sync
mv "$B/enter-native.work-profile.new" "$B/enter-native.sh"
sync
/system/bin/sh "$B/native-service.sh" start
IFS= read -r P < "$R/run/dsh-web.pid"
case "$P" in ''|*[!0-9]*) exit 32;; esac
[ "$(readlink /proc/$P/root)" = "$R" ] || exit 33
[ ! -e "/proc/$P/root/workspace/phone" ] && [ ! -L "/proc/$P/root/workspace/phone" ] || exit 34
EXPECTED_DEVICE=$(stat -c '%d' /mnt/user/10/emulated/10)
[ "$(stat -c '%d' /proc/$P/root/workspace/cowork)" = "$EXPECTED_DEVICE" ] || exit 35
[ "$(stat -c '%i' /proc/$P/root/workspace/cowork)" = "$(stat -c '%i' /mnt/user/10/emulated/10)" ] || exit 36
awk '$5=="/workspace/phone" {bad=1} $5=="/workspace/cowork" && $4=="/10" && $0 ~ / - fuse / && $6 ~ /nosuid/ && $6 ~ /nodev/ && $6 ~ /noexec/ {good=1;print} END {exit bad || !good}' "/proc/$P/mountinfo"
echo STORAGE_REMAP_VERIFIED
'''.replace("__OLD__", EXPECTED).replace("__NEW__", digest)
    print(root(script))
    evidence = {"kind": "work-profile-storage-remap", "serial": SERIAL, "profileUser": 10,
                "profileSerial": 10, "parentUser": 0, "source": "/mnt/user/10/emulated/10",
                "target": "/workspace/cowork", "removedTarget": "/workspace/phone",
                "oldEntrySha256": EXPECTED, "newEntrySha256": digest, "privateBackup": BACKUP,
                "oldCoworkFilesPreserved": True, "personalFilesDeleted": False,
                "mountIdentityVerified": True, "runtimeRestarted": True,
                "scope": "normal shared-storage mount; root/chroot is not a hostile-code sandbox"}
    (ROOT / "test-evidence/cowork/work-profile-storage.json").write_text(json.dumps(evidence, ensure_ascii=False, indent=2)+"\n", "utf-8")
    subprocess.run([sys.executable, "-B", str(ROOT / "cowork-runtime-test.py"), "inspect"], check=True)


if __name__ == "__main__":
    main()
