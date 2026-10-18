// Read-only: list DSH agent presets (modes) and the current default; no prompt, no mutation.
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
const roster=await rpc('agentPresets/list',{});
console.log(JSON.stringify(roster,null,2));
if(roster.ok){for(const p of roster.value.presets){const doc=await rpc('agentPresets/read',{agentPreset:p.id});console.log(`--- ${p.id} ---`);console.log(doc.ok?JSON.stringify({name:doc.value.name,description:doc.value.description,chars:doc.value.content.length,head:doc.value.content.slice(0,600)},null,2):JSON.stringify(doc));}}
