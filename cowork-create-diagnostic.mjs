// Idempotent creation diagnostic for the acceptance-owned empty session only; no model call.
import fs from 'node:fs';import {randomUUID} from 'node:crypto';
const origin='http://127.0.0.1:3080',token=fs.readFileSync(0,'utf8').trim();
const a=await fetch(`${origin}/?token=${token}`,{redirect:'manual'}),cookie=a.headers.getSetCookie().map(x=>x.split(';',1)[0]).join('; ');
const method='session/create',rpcId=randomUUID();
const r=await fetch(`${origin}/api/${method}`,{method:'POST',redirect:'error',headers:{cookie,origin,'content-type':'application/json'},body:JSON.stringify({type:'client-request',rpcId,method,payload:{args:{request:{sessionId:'cowork-test-b8971a96-1a8a-4eda-80f6-e919299b0f8b',agentPreset:'cowork-phone',cwd:'/workspace/cowork'}}}}),signal:AbortSignal.timeout(15000)});
const d=await r.json();let text=JSON.stringify(d.result??{}).replaceAll(token,'[redacted]').replace(/[A-Za-z0-9_=-]{40,}/g,'[long-value-redacted]').replace(/https?:\/\/[^\s"<>]+/g,'[url-redacted]');
console.log(JSON.stringify({kind:'test-owned-create-only',modelCalled:false,httpStatus:r.status,result:text.slice(0,1800)}));
