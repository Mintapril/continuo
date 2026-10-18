"""Trusted fixed-device deployment. Does not enable permissions or start a virtual display."""
from pathlib import Path
import secrets
import subprocess

ADB = r"C:\Users\rangt\AppData\Local\Microsoft\WinGet\Packages\Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe\platform-tools\adb.exe"
SERIAL = "5b9a17cd"
ROOT = Path(__file__).resolve().parent

def run(*args, **kwargs):
    return subprocess.run([ADB,"-s",SERIAL,*args],check=True,**kwargs)

for apk in [ROOT/"app/build/outputs/apk/debug/app-debug.apk",ROOT/"fixture/build/outputs/apk/debug/fixture-debug.apk"]:
    run("install","-r",str(apk))
run("push",str(ROOT/"runtime/cowork-service.sh"),"/data/local/tmp/dsh-cowork-service.sh")
# Random credentials only in stdin/private root files. Never echo/copy them to logs or public storage.
agent,controller=secrets.token_urlsafe(48),secrets.token_urlsafe(48)
script=f'''set -eu
umask 077
base=/data/local/dsh-native/cowork
mkdir -p "$base"
chmod 700 "$base"
if [ ! -s "$base/agent.token" ]; then printf '%s' '{agent}' > "$base/agent.token"; fi
if [ ! -s "$base/controller.token" ]; then printf '%s' '{controller}' > "$base/controller.token"; fi
chmod 600 "$base/agent.token" "$base/controller.token"
cp /data/local/tmp/dsh-cowork-service.sh /data/local/dsh-native/cowork-service.sh
chmod 700 /data/local/dsh-native/cowork-service.sh
/system/bin/sh /data/local/dsh-native/cowork-service.sh start
'''
run("shell","su","-c","/system/bin/sh",input=script.encode())
print("Cowork broker installed; no display started; no system permission auto-granted.")
