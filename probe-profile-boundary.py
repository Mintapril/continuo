"""Fixed-device metadata-only Android adapter probe; no APK installation or display/input."""
import hashlib
import json
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent
ADB = Path(r'C:\Users\rangt\AppData\Local\Microsoft\WinGet\Packages\Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe\platform-tools\adb.exe')
SERIAL = '5b9a17cd'
APK = ROOT / 'app/build/outputs/apk/debug/app-debug.apk'

def adb(*args, stdin=None, timeout=30):
    result = subprocess.run([str(ADB), '-s', SERIAL, *args], input=stdin, capture_output=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(result.stderr.decode('utf-8', 'replace') + result.stdout.decode('utf-8', 'replace'))
    return result.stdout.decode('utf-8', 'replace')

def main():
    digest = hashlib.sha256(APK.read_bytes()).hexdigest()
    # Content-addressed diagnostic copy does not overwrite the installed APK or broker image.
    source = '/data/local/tmp/cowork-profile-probe-' + digest + '.apk'
    target = '/data/local/dsh-native/cowork/profile-probe-' + digest + '.apk'
    adb('push', str(APK), source, timeout=60)
    script = f'''set -eu
[ "$(id -u)" = 0 ]
[ ! -L /data/local/dsh-native ]
[ ! -L /data/local/dsh-native/cowork ]
[ ! -e {target} ]
[ "$(sha256sum {source} | cut -d ' ' -f 1)" = {digest} ]
umask 077
cp {source} {target}
chmod 600 {target}
[ "$(sha256sum {target} | cut -d ' ' -f 1)" = {digest} ]
CLASSPATH={target} /system/bin/app_process /system/bin dev.dsh.pocket.cowork.broker.ProfileProbeMain
'''
    output = adb('shell', 'su', '-c', '/system/bin/sh', stdin=script.encode(), timeout=30)
    lines = [line for line in output.splitlines() if line.startswith('{')]
    if len(lines) != 1:
        raise RuntimeError('Profile metadata probe did not return one result')
    result = json.loads(lines[0])
    assert result.get('ok') is True and result.get('displayCreated') is False and result.get('inputSent') is False
    result['diagnosticApkSha256'] = digest
    result['installedAppChanged'] = False
    (ROOT / 'test-evidence/cowork/profile-boundary-probe.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(result, ensure_ascii=False))

if __name__ == '__main__':
    main()
