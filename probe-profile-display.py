"""Fixed networkless fixture-only profile display experiment; no input or production deployment."""
import base64
import hashlib
import json
import subprocess
from pathlib import Path
from cowork_auth import request

ROOT=Path(__file__).resolve().parent
ADB=r'C:\Users\rangt\AppData\Local\Microsoft\WinGet\Packages\Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe\platform-tools\adb.exe'
SERIAL='5b9a17cd'

def adb(*args,stdin=None,timeout=30):
    r=subprocess.run([ADB,'-s',SERIAL,*args],input=stdin,capture_output=True,timeout=timeout)
    if r.returncode: raise RuntimeError(r.stderr.decode('utf-8','replace')+r.stdout.decode('utf-8','replace'))
    return r.stdout.decode('utf-8','replace')

def main():
    forwards=[line.split() for line in adb('forward','--list').splitlines()]
    expected=[SERIAL,'tcp:13091','tcp:3091']
    if any('tcp:13091' in row and row!=expected for row in forwards):raise RuntimeError('Forward conflict')
    if expected not in forwards:adb('forward','--no-rebind','tcp:13091','tcp:3091')
    token=adb('shell','su','-c','/system/bin/sh /data/local/dsh-native/cowork-service.sh controller-token').strip()
    state=request('/v1/state',token,role='controller',port=13091)
    if state.get('displayId',-1)>0 or state.get('state') not in ('idle','stopped','completed','paused','error') or state.get('pendingApproval'):
        raise RuntimeError('Native task not idle; experiment refused')
    # Read-only attestation selects the already consented profile; no new profile/user can be created.
    metadata=json.loads((ROOT/'test-evidence/cowork/profile-boundary-probe.json').read_text(encoding='utf-8'))
    if metadata.get('userId')!=10 or metadata.get('serial')!=10 or not metadata.get('ownerVerified'):raise RuntimeError('Profile baseline missing')
    # Enable only our already installed, no-permission fixture in the consented test profile.
    # This does not copy its personal-user data. Java revalidates profile, fixture signer and permissions.
    adb('shell','su','-c','/system/bin/sh',stdin=b'set -eu\n[ "$(id -u)" = 0 ]\ncmd package install-existing --user 10 dev.dsh.pocket.fixture\n')
    apk=ROOT/'app/build/outputs/apk/debug/app-debug.apk';digest=hashlib.sha256(apk.read_bytes()).hexdigest()
    source='/data/local/tmp/cowork-focus-probe-'+digest+'.apk'
    target='/data/local/dsh-native/cowork/focus-probe-'+digest+'.apk'
    adb('push',str(apk),source,timeout=60)
    script=f'''set -eu
[ "$(id -u)" = 0 ]
[ ! -L /data/local/dsh-native ] && [ ! -L /data/local/dsh-native/cowork ]
[ ! -e {target} ]
[ "$(sha256sum {source} | cut -d ' ' -f 1)" = {digest} ]
umask 077
cp {source} {target}
chmod 600 {target}
[ "$(sha256sum {target} | cut -d ' ' -f 1)" = {digest} ]
CLASSPATH={target} /system/bin/app_process /system/bin dev.dsh.pocket.cowork.broker.ProfileDisplayProbeMain
'''
    try:
        process=subprocess.run([ADB,'-s',SERIAL,'shell','su','-c','/system/bin/sh'],input=script.encode(),capture_output=True,timeout=30)
        output=process.stdout.decode('utf-8','replace')
    finally:
        # Read-only teardown evidence; never kill an arbitrary process or display on failure.
        displays=adb('shell','dumpsys','SurfaceFlinger','--display-id')
        (ROOT/'test-evidence/cowork/profile-display-teardown.txt').write_text(displays,encoding='utf-8')
    lines=[line for line in output.splitlines() if line.startswith('{')]
    if len(lines)!=1:raise RuntimeError('Expected one experiment result')
    result=json.loads(lines[0]);result['diagnosticApkSha256']=digest;result['installedPocketChanged']=False
    png=result.pop('fixturePng',None)
    if png:
        content=base64.b64decode(png,validate=True)
        if len(content)>8*1024*1024 or not content.startswith(b'\x89PNG\r\n\x1a\n'):raise RuntimeError('Invalid fixture capture')
        (ROOT/'test-evidence/cowork/profile-fixture-uid.png').write_bytes(content)
    display_lines=[line for line in displays.splitlines() if line.strip()]
    result['onlyPhysicalDisplayAfter']=len(display_lines)==1 and '(HWC display ' in display_lines[0]
    result['processExitCode']=process.returncode
    (ROOT/'test-evidence/cowork/profile-display-probe.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(result,ensure_ascii=False))
    if not result.get('ok') or not result.get('onlyPhysicalDisplayAfter'):raise RuntimeError('Profile experiment incomplete')

if __name__=='__main__':main()
