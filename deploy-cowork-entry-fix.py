"""One-shot fixed-path Cordis return-value repair; no config or credential changes."""
import argparse
import base64
import hashlib
import runpy
import subprocess
from pathlib import Path

ROOT=Path(__file__).resolve().parent
m=runpy.run_path(str(ROOT/'deploy-cowork-plugin.py'))
OLD='749ed6037e6da268379e21f79243fc8c8d7f01fe4942bebe9ed09126288d1ffd'
BASE=m['BASE'];ROOTFS=m['ROOTFS']
TARGET=ROOTFS+'/opt/dsh/node_modules/@local/dsh-cowork-phone/src/restricted.mjs'
PATCH=BASE+'/cowork-entry-fix-v1'

def script(data):
    sha=hashlib.sha256(data).hexdigest()
    return (m['SHELL_COMMON']+f'''
checkhash {BASE}/native-service.sh {m['PINS']['native-service.sh']}
stopped() {{
 [ "$(/system/bin/sh {BASE}/native-service.sh status)" = 'STOPPED (no verified DSH pid)' ] || exit 30
 for process in /proc/[0-9]*; do
  [ -d "$process" ] || continue
  if [ -e "$process/root" ]; then
   if ! resolved=$(readlink "$process/root"); then [ ! -d "$process" ] && continue; exit 31; fi
   [ "$resolved" != '{ROOTFS}' ] || exit 32
  fi
 done
 for table in /proc/net/tcp /proc/net/tcp6; do
  [ -r "$table" ] || exit 37
  while read -r slot local remote state rest; do case "$local:$state" in *:0C08:0A) exit 38;; esac; done < "$table"
 done
}}
stopped
checkhash {TARGET} {OLD}
absent {PATCH}
mkdir -m 700 {PATCH}
cp {TARGET} {PATCH}/restricted.before.mjs
chmod 600 {PATCH}/restricted.before.mjs
checkhash {PATCH}/restricted.before.mjs {OLD}
base64 -d > {PATCH}/restricted.after.mjs <<'PUBLIC_ENTRY'
{base64.b64encode(data).decode()}
PUBLIC_ENTRY
chmod 600 {PATCH}/restricted.after.mjs
checkhash {PATCH}/restricted.after.mjs {sha}
# Keep hashes of config/manifest bytes only; no contents leave the phone.
sha256sum {ROOTFS}/opt/dsh/node_modules/@deepseek-ai/dsh/package.json {ROOTFS}/root/.dsh/profiles/web/package.json {ROOTFS}/root/.dsh/profiles/web/cordis.yml {ROOTFS}/root/.dsh/profiles/web/cordis.patch.yml > {PATCH}/unchanged.sha256
sync
stopped
checkhash {TARGET} {OLD}
# Fixed checked destination, never a computed or user-selectable path.
[ "$(readlink -f {TARGET})" = '{TARGET}' ] || exit 40
absent {TARGET}.cowork-entry-fix-v1
cp {PATCH}/restricted.after.mjs {TARGET}.cowork-entry-fix-v1
chmod 600 {TARGET}.cowork-entry-fix-v1
checkhash {TARGET}.cowork-entry-fix-v1 {sha}
mv {TARGET}.cowork-entry-fix-v1 {TARGET}
checkhash {TARGET} {sha}
sha256sum -c {PATCH}/unchanged.sha256 >/dev/null
sync
''').encode('ascii')

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--apply',action='store_true');a=p.parse_args()
    data=m['read_regular'](ROOT/'cowork-plugin/src/restricted.mjs',ROOT/'cowork-plugin')
    oldline=b'  return installRestrictedCowork(ctx,config,{...scope,...llm,...session},{secret});'
    newline=b'  // Cordis async effects may resolve only void or a disposer, never our test API.\n  installRestrictedCowork(ctx,config,{...scope,...llm,...session},{secret});'
    assert data.count(newline)==1 and hashlib.sha256(data.replace(newline,oldline)).hexdigest()==OLD
    payload=script(data)
    if a.apply:
        r=subprocess.run([str(m['ADB']),'-s',m['SERIAL'],'shell','-T','su','-c','/system/bin/sh'],input=payload,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=120)
        if r.returncode:raise SystemExit('Fixed-entry repair refused; retained private state requires review')
    print('entry-fixed-stopped' if a.apply else 'pc-verified-exact-single-change')

if __name__=='__main__':main()
