// Read-only: print each session's agentPreset/title hint from session/list; no mutation.
import fs from 'node:fs';
import {randomUUID} from 'node:crypto';
import assert from 'node:assert/strict';
const token=fs.readFileSync(0,'utf8').trim();
assert(/^[A-Za-z0-9_-]{16,}$/.test(token));
const origin='http://127.0.0.1:3080';
const auth=await fetch(`${origin}/?token=${token}`,{redirect:'manual'});
const cookie=auth.headers.getSetCookie().map(v=>v.split(';',1)[0]).join('; ');
assert(cookie);
async function rpc(method,args){const rpcId=randomUUID();const r=await fetch(`${origin}/api/${method}`,{method:'POST',headers:{cookie,origin,'content-type':'application/json'},body:JSON.stringify({type:'client-request',rpcId,method,payload:{args}})});const b=await r.json();assert(b.rpcId===rpcId);return b.result;}
const list=await rpc('session/list',{_request:{}});
assert(list.ok, JSON.stringify(list));
for(const s of list.value.items){const v=s.projections?.values??{};console.log(JSON.stringify({id:s.sessionId.slice(0,8),blank:s.blank,origin:s.origin,kind:s.projections?.kind,keys:Object.keys(v),agentPreset:v.agentPreset,title:v.title,cwd:s.cwd}));}
