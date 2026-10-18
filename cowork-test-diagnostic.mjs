// Read only the single acceptance-owned session. No model or mutation.
import fs from 'node:fs';import {randomUUID} from 'node:crypto';import {createRequire} from 'node:module';
const require=createRequire(new URL('../dsh-android/repair-model-rpc.mjs',import.meta.url)),WebSocket=require('ws');
const token=fs.readFileSync(0,'utf8').trim(),origin='http://127.0.0.1:3080';
const auth=await fetch(`${origin}/?token=${token}`,{redirect:'manual',signal:AbortSignal.timeout(10000)});
const cookie=auth.headers.getSetCookie().map(x=>x.split(';',1)[0]).join('; ');
const ws=new WebSocket('ws://127.0.0.1:3080/api/remote.mux',{headers:{cookie,origin},maxPayload:1024*1024});
const streamId=randomUUID();const timer=setTimeout(()=>{ws.terminate();process.exitCode=1;},15000);
ws.on('error',()=>{clearTimeout(timer);console.error('Owned session diagnostic unavailable');process.exitCode=1;});
ws.on('open',()=>ws.send(JSON.stringify({type:'open',streamId,endpoint:'session/follow',payload:{args:{request:{address:{kind:'session',sessionId:'cowork-test-36816821-a6ab-4477-a275-f66cc9ff2521'},maxMessages:100}}}})));
ws.on('message',raw=>{const f=JSON.parse(raw);if(f.streamId!==streamId||f.type!=='item'||f.value.type!=='snapshot')return;const events=f.value.records.map(r=>r.event);const result=events.map(e=>({type:e.type,...(['tool/call','tool/result','turn/end'].includes(e.type)?{data:e.data}:{})}));console.log(JSON.stringify(result,null,2));ws.send(JSON.stringify({type:'cancel',streamId}));ws.close();clearTimeout(timer);});
