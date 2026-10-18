"""Compare installed public package metadata with the same public npm release; no device writes."""
import io,json,hashlib,tarfile,urllib.request
from pathlib import Path
root=Path(__file__).resolve().parent.parent
url='https://registry.npmjs.org/@deepseek-ai/dsh/-/dsh-0.2.0-rc.2.tgz'
with urllib.request.urlopen(url,timeout=30) as r: data=r.read(32*1024*1024+1)
assert len(data)<=32*1024*1024
with tarfile.open(fileobj=io.BytesIO(data),mode='r:gz') as tar: original=tar.extractfile('package/package.json').read()
current=(root/'test-evidence/cowork/runtime-package-current.json').read_text(encoding='utf-8-sig')
a=json.loads(original);b=json.loads(current)
report={'source':url,'publicHash':hashlib.sha256(original).hexdigest(),'semanticallyIdentical':a==b,'differentKeys':[k for k in set(a)|set(b) if a.get(k)!=b.get(k)]}
(root/'test-evidence/cowork/runtime-manifest-comparison.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
print(json.dumps(report))
