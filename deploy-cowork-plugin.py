"""Prepare (default), explicitly stage, or install the offline Cowork bundle.

THIS FILE WAS PREPARED/TESTED ON PC ONLY. Default/--check/--self-test never call ADB.
Future --stage copies only allowlisted public package + pinned scripts into fresh
root-private phone staging. --install requires --confirm-quiescent: the operator
knows sessions are idle, has explicitly stopped web, and holds an exclusive
no-restart maintenance window. This program never stops/starts web, opens apps,
runs npm, grants permission, or removes/restores anything.

Existing native-service status is mandatory but not sufficient. Both wrapper and
installer reject active chroot processes / port 3080; unknown state refuses.
No service-lock protocol exists in native-service.sh, so another root starting it
concurrently remains an explicit limitation. Recheck immediately before commits.

The only modifications after complete private backup are: fresh /opt package;
fresh broker config from EXISTING agent.token over PHONE-LOCAL stdin only; new
0700 /workspace/cowork; additive runtime dependency + web-profile bundle. Root,
workspace and config collision/symlink guards fail closed. No controller token
is ever passed into chroot. Android broker credentials are backed up only in an
Android root-private directory; complete .dsh/env/runtime metadata backup remains
inside chroot. Nothing private is returned to PC/logs. Raw subprocess output and
exceptions are never printed. No automatic restart, restore, deletion or retry.

Rollback is MANUAL narrow inverse, described in runtime/install-cowork-plugin.mjs:
remove only exact added fields after fresh current backup/compare, retain all
current user data and package/config/workspace until separately reviewed. Never
restore a historic .dsh tree over live sessions/providers/Code.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import io
import json
import os
from pathlib import Path
import stat
import subprocess
import sys
import tarfile
import tempfile

HERE = Path(__file__).resolve().parent
ADB = Path(r"C:\Users\rangt\AppData\Local\Microsoft\WinGet\Packages\Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe\platform-tools\adb.exe")
SERIAL = "5b9a17cd"
BASE = "/data/local/dsh-native"
ROOTFS = BASE + "/rootfs"
STAGE = ROOTFS + "/root/.dsh-cowork-install-v2"
ANDROID_BACKUP = BASE + "/cowork-plugin-backup-v2"
LOCK = BASE + "/cowork-plugin-install-v2.lock"
FILES = (
    "package.json", "src/auth.mjs", "src/task-lifecycle.mjs", "src/restricted.mjs",
    "src/validation.mjs", "src/broker.mjs", "src/bounded-files.mjs", "src/web.mjs",
    "src/aux-summary.mjs", "src/summary-transport.mjs", "cordis.patch.yml", "README.md", "PROTOCOL.md",
)
PINS = {
    "cowork-preflight.mjs": "37160f891828cf099b3ecf8a318a1ae3482f5e787d004b75ddce3e521a6e91cb",
    "preflight-v3.json": "bbaa423a8806992968cc58b140d2b9a124e2e408591daba54b3a7122c25c65be",
    "native-service.sh": "26ee735bbb1f7b3e48016e2cb5807ec270442c380d29654ae86f3f6b1f241377",
    "enter-native.sh": "78e9f8fe1498ab14311508aa4fd8b92d1ad1e1b25b9b20e54f700e00584da0b4",
    "start-web.sh": "5267d420163d1e9a56c21b77e3f7daa6f666401a7ea0c4e5420b084c029b9a73",
}
# Verified device bytes equal the public npm release package/package.json exactly.
RUNTIME_PIN = "0a85203e3e907dd2e3bec34324a2494758ca6cdee234b07090a7c8df0b11acac"


class Refused(Exception):
    pass


def require(condition, code):
    if not condition:
        raise Refused(code)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def read_regular(file: Path, anchor: Path) -> bytes:
    # Check every ancestor, including Windows junction/reparse points, before read.
    require(file.is_absolute() and anchor.is_absolute(), "LOCAL_PATH")
    require(file.is_relative_to(anchor), "LOCAL_ESCAPE")
    for part in [file, *file.parents]:
        s = part.lstat()
        require(not stat.S_ISLNK(s.st_mode) and not getattr(s, "st_file_attributes", 0) & 0x400,
                "LOCAL_LINK_OR_REPARSE_POINT")
    before = file.stat()
    require(stat.S_ISREG(before.st_mode) and before.st_nlink == 1 and before.st_size <= 8 * 1024 * 1024,
            "LOCAL_FILE_SHAPE")
    data = file.read_bytes()
    after = file.stat()
    require((before.st_ino, before.st_size, before.st_mtime_ns) == (after.st_ino, after.st_size, after.st_mtime_ns),
            "LOCAL_FILE_CHANGED")
    return data


def canonical_json(value):
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def make_archive(payload: dict[str, bytes]) -> bytes:
    output = io.BytesIO()
    # USTAR only: no PAX/longname entries, links, directories, owner identities or timestamps.
    with tarfile.open(fileobj=output, mode="w", format=tarfile.USTAR_FORMAT) as archive:
        for name, data in sorted(payload.items()):
            require(not name.startswith("/") and ".." not in name.split("/") and "\\" not in name,
                    "ARCHIVE_PATH")
            entry = tarfile.TarInfo(name)
            entry.size, entry.mode, entry.mtime = len(data), 0o600, 0
            entry.uid = entry.gid = 0
            entry.uname = entry.gname = ""
            archive.addfile(entry, io.BytesIO(data))
    packed = output.getvalue()
    with tarfile.open(fileobj=io.BytesIO(packed), mode="r:") as archive:
        members = archive.getmembers()
        require([m.name for m in members] == sorted(payload), "ARCHIVE_CONTENTS")
        require(all(m.isfile() and m.mode == 0o600 for m in members), "ARCHIVE_MEMBER_TYPE")
        for member in members:
            require(archive.extractfile(member).read() == payload[member.name], "ARCHIVE_VERIFY")
    return packed


def prepare(here: Path = HERE):
    package = {f: read_regular(here / "cowork-plugin" / f, here) for f in FILES}
    manifest = json.loads(package["package.json"])
    require(manifest.get("name") == "@local/dsh-cowork-phone" and manifest.get("version") == "0.1.0", "PACKAGE_ID")
    require(manifest.get("files") == list(FILES[1:]), "PACKAGE_ALLOWLIST_DRIFT")
    require(manifest.get("exports", {}).get(".") == "./src/restricted.mjs", "PACKAGE_ENTRY")
    require(manifest.get("dsh", {}).get("bundle", {}).get("patch") == "./cordis.patch.yml", "PACKAGE_PATCH")
    require(not any(manifest.get(k) for k in ("dependencies", "optionalDependencies", "devDependencies")), "OFFLINE_DEPENDENCIES")
    payload = {"package/" + f: data for f, data in package.items()}
    payload["install-cowork-plugin.mjs"] = read_regular(here / "runtime/install-cowork-plugin.mjs", here)
    payload["cowork-private-backup.mjs"] = read_regular(here / "runtime/cowork-private-backup.mjs", here)
    payload["cowork-preflight.mjs"] = read_regular(here / "runtime/cowork-preflight.mjs", here)
    payload["preflight-v3.json"] = read_regular(here / "test-evidence/cowork/preflight-v3.json", here)
    for name in ("cowork-preflight.mjs", "preflight-v3.json"):
        require(sha(payload[name]) == PINS[name], "PIN_CHANGED")
    report = json.loads(payload["preflight-v3.json"])
    require(report.get("status") == "passed-static-only" and not report.get("collisions") and not report.get("refusals"), "BASELINE_REFUSED")
    require(report.get("standardPreset", {}).get("configuredDefaultIsStandard") is True, "STANDARD_NOT_DEFAULT")
    plan = {
        "format": "cowork-offline-install-v1",
        "runtimeManifestHash": RUNTIME_PIN,
        "installerHash": sha(payload["install-cowork-plugin.mjs"]),
        "backupModuleHash": sha(payload["cowork-private-backup.mjs"]),
        "package": {f: sha(data) for f, data in package.items()},
    }
    payload["plan.json"] = canonical_json(plan)
    archive = make_archive(payload)
    require(len(archive) <= 4 * 1024 * 1024, "ARCHIVE_TOO_LARGE")
    return archive, payload


# All shell functions use fixed paths or authenticated, public hex digests.
# No private output can escape: wrapper launches with stdout/stderr /dev/null;
# Python prints only its own fixed status code after subprocess exit.
SHELL_COMMON = r'''set -eu
exec >/dev/null 2>&1
PATH=/system/bin:/system/xbin
export PATH
umask 077
[ "$(id -u)" = 0 ] || exit 20
safe() {
  case "$1" in /*) ;; *) exit 21;; esac
  oldifs=$IFS; IFS=/; set -- $1; IFS=$oldifs; p=
  for component do
    [ -n "$component" ] || continue
    p="$p/$component"
    [ ! -L "$p" ] || exit 22
    if [ -e "$p" ]; then
      # Android /data is system-owned and may be group-writable. It is a fixed
      # platform trust anchor, NOT a selectable destination. The native base and
      # all descendants must be root-owned with no group/world writes.
      case "$p" in /data|/data/local) [ -d "$p" ] || exit 23; continue;; esac
      [ "$(stat -c %u "$p")" = 0 ] || exit 23
      mode=$(stat -c %a "$p")
      # Android mksh treats leading-zero arithmetic as decimal; inspect octal digits.
      case "$mode" in *[2367][0-7]|*[2367]) exit 24;; esac
    fi
  done
}
regular() {
  safe "$1"
  [ -f "$1" ] && [ "$(stat -c %h "$1")" = 1 ] || exit 25
}
absent() { safe "$1"; [ ! -e "$1" ] && [ ! -L "$1" ] || exit 26; }
private() { safe "$1"; [ -d "$1" ] && [ "$(stat -c %a "$1")" = 700 ] || exit 27; }
checkhash() { regular "$1"; [ "$(sha256sum "$1" | cut -d ' ' -f 1)" = "$2" ] || exit 28; }
'''


def stage_script(archive: bytes) -> bytes:
    text = SHELL_COMMON + f'''
safe {ROOTFS}/root
[ -d {ROOTFS}/root ] || exit 29
absent {STAGE}
mkdir -m 700 {STAGE}
private {STAGE}
base64 -d > {STAGE}/bundle.tar <<'COWORK_PUBLIC_PAYLOAD'
{base64.b64encode(archive).decode('ascii')}
COWORK_PUBLIC_PAYLOAD
checkhash {STAGE}/bundle.tar {sha(archive)}
mkdir -m 700 {STAGE}/package {STAGE}/package/src
# Tar is generated exclusively from a checked USTAR regular-file allowlist on PC.
tar -xf {STAGE}/bundle.tar -C {STAGE}
chmod 700 {STAGE} {STAGE}/package {STAGE}/package/src
sync
'''
    return text.encode("ascii")


def install_script(payload: dict[str, bytes]) -> bytes:
    checks = "\n".join(f"checkhash {STAGE}/{name} {sha(data)}" for name, data in sorted(payload.items()))
    return (SHELL_COMMON + f'''
private {STAGE}
{checks}
checkhash {BASE}/native-service.sh {PINS['native-service.sh']}
checkhash {BASE}/enter-native.sh {PINS['enter-native.sh']}
checkhash {ROOTFS}/opt/start-web.sh {PINS['start-web.sh']}
checkhash {ROOTFS}/opt/dsh/node_modules/@deepseek-ai/dsh/package.json {RUNTIME_PIN}
# The user confirmation is mandatory at the Python entry point. No service stop here.
stopped() {{
  [ "$(/system/bin/sh {BASE}/native-service.sh status)" = 'STOPPED (no verified DSH pid)' ] || exit 30
  for process in /proc/[0-9]*; do
    [ -d "$process" ] || continue
    if [ -e "$process/root" ]; then
      if ! resolved=$(readlink "$process/root"); then
        # Ignore only a process proved gone after the race, never unknown access.
        [ ! -d "$process" ] && continue
        exit 31
      fi
      [ "$resolved" != '{ROOTFS}' ] || exit 32
    fi
  done
  for table in /proc/net/tcp /proc/net/tcp6; do
    [ -r "$table" ] || exit 37
    while read -r slot local remote state rest; do
      case "$local:$state" in *:0C08:0A) exit 38;; esac
    done < "$table"
  done
}}
stopped
absent {LOCK}
mkdir -m 700 {LOCK}
# Never remove the lock automatically, including after failure/success.
private {BASE}/cowork
for token in agent.token controller.token; do
  regular {BASE}/cowork/$token
  [ "$(stat -c %a {BASE}/cowork/$token)" = 600 ] || exit 33
  [ -s {BASE}/cowork/$token ] || exit 34
done
absent {ANDROID_BACKUP}
mkdir -m 700 {ANDROID_BACKUP}
# Complete relevant Android broker credentials/config: fixed credential pair and
# lifecycle; never copied into chroot, and never returned through ADB.
for name in agent.token controller.token; do
  cp {BASE}/cowork/$name {ANDROID_BACKUP}/$name
  chmod 600 {ANDROID_BACKUP}/$name
  cmp -s {BASE}/cowork/$name {ANDROID_BACKUP}/$name || exit 35
done
regular {BASE}/cowork-service.sh
cp {BASE}/cowork-service.sh {ANDROID_BACKUP}/cowork-service.sh
chmod 600 {ANDROID_BACKUP}/cowork-service.sh
cmp -s {BASE}/cowork-service.sh {ANDROID_BACKUP}/cowork-service.sh || exit 36
sync
stopped
# CRITICAL: only agent.token goes through this PHONE-LOCAL pipe; no command
# substitution, PC capture, controller token, token environment or token argv.
cat {BASE}/cowork/agent.token | /system/bin/sh {BASE}/enter-native.sh \
  /bin/sh -c 'cd /workspace && exec /usr/bin/env -i HOME=/root DSH_HOME=/root/.dsh PATH=/opt/node/bin:/usr/bin:/bin DSH_TELEMETRY_DISABLED=1 NODE_DISABLE_COMPILE_CACHE=1 /opt/node/bin/node /root/.dsh-cowork-install-v2/install-cowork-plugin.mjs --install' > {ANDROID_BACKUP}/install-result.json
''').encode("ascii")


def phone(script: bytes):
    # Never called by default/check/self-test. Do not capture/relay any private stdout.
    result = subprocess.run([str(ADB), "-s", SERIAL, "shell", "-T", "su", "-c", "/system/bin/sh"],
                            input=script, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                            timeout=1800, check=False)
    require(result.returncode == 0, "PHONE_OPERATION_REFUSED_PRIVATE_STATE_RETAINED")


def self_test():
    archive, payload = prepare()
    require(archive == prepare()[0], "NONDETERMINISTIC_ARCHIVE")
    require(len(payload) == len(FILES) + 5, "UNEXPECTED_PAYLOAD")
    require(not any(any(x in name.split("/") for x in ("test", "research", "node_modules")) for name in payload), "FORBIDDEN_PAYLOAD")
    require(b"controller.token |" not in install_script(payload), "CONTROLLER_PIPE")
    require(b"agent.token |" in install_script(payload), "AGENT_PIPE")
    require(b"native-service.sh stop" not in install_script(payload), "AUTO_STOP")
    require(b"native-service.sh start" not in install_script(payload), "AUTO_START")
    # An isolated temporary PC fixture tests no-link/exact-file ingestion.
    with tempfile.TemporaryDirectory(prefix="cowork-installer-fixture-") as temp:
        root = Path(temp).absolute()
        candidate = root / "public.txt"
        candidate.write_bytes(b"fixture")
        require(read_regular(candidate, root) == b"fixture", "FIXTURE_READ")
        try:
            read_regular(root.parent / "outside.txt", root)
        except Refused:
            pass
        else:
            raise Refused("FIXTURE_ESCAPE_ACCEPTED")
        hard = root / "hard.txt"
        os.link(candidate, hard)
        try:
            read_regular(candidate, root)
        except Refused:
            pass
        else:
            raise Refused("FIXTURE_HARDLINK_ACCEPTED")
        try:
            make_archive({"../bad": b"no"})
        except Refused:
            pass
        else:
            raise Refused("FIXTURE_TAR_TRAVERSAL_ACCEPTED")
    return {"status": "pc-self-test-passed", "phoneCommands": 0, "regularPayloadFiles": len(payload),
            "packageFiles": len(FILES), "archiveBytes": len(archive), "archiveSha256": sha(archive)}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument("--check", action="store_true", help="PC-only default: validate allowlist/pins and build tar in memory")
    modes.add_argument("--self-test", action="store_true", help="PC-only allowlist/archive/temp fixture tests")
    modes.add_argument("--stage", action="store_true", help="EXPLICIT FUTURE PHONE ACTION: fresh root-private staging only")
    modes.add_argument("--install", action="store_true", help="EXPLICIT FUTURE PHONE ACTION: backup/additive stopped install")
    parser.add_argument("--confirm-quiescent", action="store_true", help="I know sessions are idle, web is stopped, and no actor will restart it during installation")
    args = parser.parse_args(argv)
    require(not args.confirm_quiescent or args.install, "CONFIRMATION_WITHOUT_INSTALL")
    if args.self_test:
        result = self_test()
    else:
        archive, payload = prepare()
        if args.stage:
            phone(stage_script(archive))
            result = {"status": "staged-only", "activation": False}
        elif args.install:
            require(args.confirm_quiescent, "ACTIVE_SESSIONS_NOT_KNOWN_QUIESCENT")
            phone(install_script(payload))
            result = {"status": "installed-stopped", "restartRequired": True, "automaticRestore": False}
        else:
            result = {"status": "pc-prepared-only", "phoneCommands": 0, "packageFiles": len(FILES),
                      "regularPayloadFiles": len(payload), "archiveBytes": len(archive), "archiveSha256": sha(archive),
                      "activation": False, "limits": ["phone deployment not performed", "exclusive maintenance window required",
                      "nested links remain inert backup data; special files/mount crossings refuse", "runtime manifest pin must match on phone",
                      "no service lock protocol; concurrent root restart cannot be made atomic"]}
    print(json.dumps(result, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Refused as error:
        print(json.dumps({"status": "refused", "code": str(error), "automaticRestore": False}))
        sys.exit(2)
    except (Exception, KeyboardInterrupt):
        # Includes TimeoutExpired/OS errors: never print captured command, stdin,
        # parser error, private output, or traceback. Inspect retained state manually.
        print('{"status":"refused","code":"OPERATION_FAILED_PRIVATE_STATE_RETAINED","automaticRestore":false}')
        sys.exit(2)
