"""Provision only DSH's local login token through private pipes; never log it."""
from pathlib import Path
import re
import subprocess

ADB = r"C:\Users\rangt\AppData\Local\Microsoft\WinGet\Packages\Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe\platform-tools\adb.exe"
SERIAL = "5b9a17cd"
APP = "dev.dsh.pocket"

def call(*args, **kwargs):
    return subprocess.run([ADB, "-s", SERIAL, *args], check=True, **kwargs)

token = call("shell", "su", "-c", "/system/bin/sh /data/local/dsh-native/pocket-control.sh auth", stdout=subprocess.PIPE).stdout.strip()
if not re.fullmatch(rb"[A-Za-z0-9_-]{16,}", token):
    raise RuntimeError("Local token unavailable; no credential was written")
# run-as writes inside this debug app's own sandbox, mode 0600, no public staging.
# The instrumentation test consumes/deletes this seed and writes Android Keystore ciphertext.
call("shell", "run-as", APP, "sh", "-c", '"umask 077; mkdir -p files; cat > files/deployment-login.seed"', input=token)
print("Private local-login seed installed; token not printed. Run LocalContractTest to consume it.")
