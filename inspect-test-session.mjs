// Read-only: inspect only the explicitly created native-app acceptance session.
import fs from 'node:fs';
import {createRequire} from 'node:module';
import {randomUUID} from 'node:crypto';
import assert from 'node:assert/strict';
const require=createRequire(new URL('../dsh-android/repair-model-rpc.mjs',import.meta.url));
const WebSocket=require('ws');
const token=fs.readFileSync(0,'utf8').trim();
assert(/^[A-Za-z0-9_-]{16,}$/.test(token));
const origin='http://127.0.0.1:3080';
const auth=await fetch(`${origin}/?token=${token}`,{redirect:'manual'});
const cookie=auth.headers.getSetCookie().map(v=>v.split(';',1)[0]).join('; ');
assert(cookie);
async function rpc(method,args){const rpcId=randomUUID();const r=await fetch(`${origin}/api/${method}`,{method:'POST',headers:{cookie,origin,'content-type':'application/json'},body:JSON.stringify({type:'client-request',rpcId,method,payload:{args}})});const b=await r.json();assert(b.rpcId===rpcId&&b.result.ok);return b.result.value;}
const list=await rpc('session/list',{_request:{}});
const session=list.items.find(s=>s.projections?.values?.title==='DSH Pocket connected reply');
assert(session,'Dedicated acceptance title not found');
const snapshot=await new Promise((resolve,reject)=>{const id=randomUUID();const ws=new WebSocket(`${origin.replace('http','ws')}/api/remote.mux`,{headers:{cookie,origin}});const timer=setTimeout(()=>{ws.close();reject(Error('timeout'));},15000);ws.on('error',reject);ws.on('open',()=>ws.send(JSON.stringify({type:'open',streamId:id,endpoint:'session/follow',payload:{args:{request:{address:{kind:'session',sessionId:session.sessionId},assistantStream:true,maxMessages:100}}}})));ws.on('message',raw=>{const frame=JSON.parse(raw);if(frame.streamId===id&&frame.type==='item'&&frame.value.type==='snapshot'){clearTimeout(timer);ws.close();resolve(frame.value);}});});
console.log(JSON.stringify({sessionId:session.sessionId,running:session.running,keys:Object.keys(snapshot),snapshot}, (key,value)=>key==='content'?value.map(b=>({type:b.type,chars:b.text?.length})):value,2));
