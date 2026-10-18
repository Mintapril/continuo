"""Fixed phone runtime acceptance launcher. Login token stays in memory/stdin, never logs."""
import argparse
import json
import re
import subprocess
from pathlib import Path

ADB = r"C:\Users\rangt\AppData\Local\Microsoft\WinGet\Packages\Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe\platform-tools\adb.exe"
NODE = r"C:\Users\rangt\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\node\bin\node.exe"
SERIAL = "5b9a17cd"
ROOT = Path(__file__).resolve().parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["inspect", "tools", "control"])
    args = parser.parse_args()
    def adb(*values):
        result = subprocess.run([ADB, "-s", SERIAL, *values], capture_output=True, timeout=20)
        if result.returncode: raise RuntimeError("Fixed runtime inspection operation failed")
        return result.stdout
    rows = [line.split() for line in adb("forward", "--list").decode().splitlines()]
    expected = [SERIAL, "tcp:3080", "tcp:3080"]
    if any("tcp:3080" in row and row != expected for row in rows):
        raise RuntimeError("Port forwarding conflict; no mapping changed")
    if expected not in rows: adb("forward", "--no-rebind", "tcp:3080", "tcp:3080")
    log = adb("shell", "su", "-c", "cat /data/local/dsh-native/logs/web-current.log")
    match = re.search(rb"http://127\.0\.0\.1:3080/\?token=([A-Za-z0-9_-]+)", log)
    if not match: raise RuntimeError("Runtime login unavailable")
    if args.action == "inspect":
        result = subprocess.run([NODE, str(ROOT / "cowork-runtime-test.mjs"), args.action], input=match[1], timeout=45)
    else:
        expected_broker = [SERIAL, "tcp:13091", "tcp:3091"]
        if any("tcp:13091" in row and row != expected_broker for row in rows):
            raise RuntimeError("Broker forwarding conflict; no mapping changed")
        if expected_broker not in rows: adb("forward", "--no-rebind", "tcp:13091", "tcp:3091")
        controller = adb("shell", "su", "-c", "/system/bin/sh /data/local/dsh-native/cowork-service.sh controller-token").decode().strip()
        if re.fullmatch(r"[A-Za-z0-9_-]{40,256}", controller) is None: raise RuntimeError("Controller unavailable")
        # Controller is operator-only; never enters phone chroot, the DSH model or evidence.
        private = json.dumps({"login": match[1].decode(), "controller": controller}).encode()
        script = "cowork-control-acceptance.mjs" if args.action == "control" else "cowork-session-acceptance.mjs"
        result = subprocess.run([NODE, str(ROOT / script)], input=private, timeout=1000)
    raise SystemExit(result.returncode)


if __name__ == "__main__": main()
