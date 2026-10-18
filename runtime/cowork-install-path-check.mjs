// Fixed read-only path metadata diagnosis; no secrets or arbitrary directory listings.
import fs from 'node:fs';import path from 'node:path';import {createRequire} from 'node:module';
const r='/opt/dsh/node_modules/@deepseek-ai/dsh/package.json',p='/root/.dsh/profiles/web/package.json';
const files=['/root/.dsh-cowork-install-v1','/root/.dsh-cowork-install-v1/plan.json','/root/.dsh-cowork-install-v1/install-cowork-plugin.mjs','/root/.config/dsh/cowork-broker.json','/workspace/cowork','/root/.dsh-cowork-backup-v1',r,p,'/root/.dsh/profiles/web/cordis.yml','/root/.dsh/profiles/web/cordis.patch.yml','/root/.dsh/.env','/workspace/.env'];
for(const a of [r,p])for(const s of createRequire(a).resolve.paths('@local/dsh-cowork-phone')??[])files.push(path.join(s,'@local/dsh-cowork-phone'));
const boot=await import(createRequire(r).resolve('@deepseek-ai/dsh-app-boot'));
for(const l of boot.loadProfileDirectory('check','/root/.dsh/profiles/web',r).layers)for(const f of l.patchPaths)files.push(fs.realpathSync(f));
const failures=[];for(const f of files){let c='/';for(const part of f.split('/').filter(Boolean)){c=path.join(c,part);let s;try{s=fs.lstatSync(c);}catch(e){if(e.code==='ENOENT')break;throw e;}if(s.isSymbolicLink()||s.uid!==0||(s.isDirectory()&&(s.mode&0o022)!==0)){failures.push({path:c,link:s.isSymbolicLink(),uid:s.uid,mode:(s.mode&0o777).toString(8)});break;}}}
console.log(JSON.stringify({kind:'fixed-install-path-metadata',failures}));
