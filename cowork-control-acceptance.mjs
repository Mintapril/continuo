// Real DSH cancellation acceptance; NEW test-owned sessions only, no display or files.
import fs from 'node:fs';import http from 'node:http';import {randomUUID} from 'node:crypto';import {createRequire} from 'node:module';import {setTimeout as delay} from 'node:timers/promises';
import {authenticatedRequest} from './cowork-plugin/src/auth.mjs';import {ALLOWED_TOOLS} from './cowork-plugin/src/restricted.mjs';
const require=createRequire(new URL('../dsh-android/repair-model-rpc.mjs',import.meta.url)),WebSocket=require('ws');
const origin='http://127.0.0.1:3080',out=new URL(`./test-evidence/cowork/runtime-control-${randomUUID()}.json`,import.meta.url);
const report={kind:'live-phone-whole-task-control',modelCalled:false,cases:[],passed:false};const check=(v,m)=>{if(!v)throw Error(m);};
const save=()=>fs.writeFileSync(out,JSON.stringify(report,null,2)+'\n');
async function main(){
 const secret=JSON.parse(fs.readFileSync(0,'utf8'));
 const a=await fetch(`${origin}/?token=${secret.login}`,{redirect:'manual',signal:AbortSignal.timeout(10000)}),cookie=a.headers.getSetCookie().map(v=>v.split(';',1)[0]).join('; ');check(cookie,'Login unavailable');
 async function rpc(method,args={}){const rpcId=randomUUID(),r=await fetch(`${origin}/api/${method}`,{method:'POST',redirect:'error',headers:{cookie,origin,'content-type':'application/json'},body:JSON.stringify({type:'client-request',rpcId,method,payload:{args}}),signal:AbortSignal.timeout(15000)});check(r.ok,'RPC unavailable');const d=await r.json();check(d.rpcId===rpcId&&d.result?.ok===true,'RPC refused');return d.result.value;}
 const call=(method,request)=>rpc(method,{request});
 async function native(body){const r=await authenticatedRequest({secret:secret.controller,role:'controller',method:body?'POST':'GET',path:body?'/v1/control':'/v1/state',...(body?{body:Buffer.from(JSON.stringify(body))}:{}),timeoutMs:5000,maxResponseBytes:262144,request:(options,cb)=>http.request({...options,port:13091},cb)});check(r.status===200,'Native refused');return JSON.parse(r.body);}
 const brief=s=>Object.fromEntries(['taskId','executionId','revision','state','displayId'].map(k=>[k,s[k]]));
 const control=(s,action)=>native({action,sessionId:s.taskId,instanceId:s.instanceId,executionId:s.executionId,revision:s.revision});
 async function idle(id){for(let i=0;i<40;i++){const s=(await rpc('session/list',{_request:{}})).items?.find(s=>s.sessionId===id);if(s?.running===false)return;await delay(250);}throw Error('Test session not idle');}
 check((await rpc('session/list',{_request:{}})).items.every(s=>s.running===false),'Runtime busy');let s=await native();check(s.displayId===-1&&['idle','stopped','completed','error'].includes(s.state)&&s.summaryEnabled===false,'Native not idle');
 for(const action of ['pause','stop']){
  const sessionId=`cowork-control-${randomUUID()}`,item={sessionId,action,states:[],toolCalls:0,passed:false};report.cases.push(item);save();
  const created=await call('session/create',{sessionId,agentPreset:'cowork-phone',cwd:'/workspace/cowork'});check(created.sessionId===sessionId,'Create mismatch');
  const streamId=randomUUID(),ws=new WebSocket('ws://127.0.0.1:3080/api/remote.mux',{headers:{cookie,origin},maxPayload:4*1024*1024});
  let ready,readyFail,resolve,reject,header,headerReject,ended=false;
  const opened=new Promise((r,j)=>{ready=r;readyFail=j;}),done=new Promise((r,j)=>{resolve=r;reject=j;}),admitted=new Promise((r,j)=>{header=r;headerReject=j;});done.catch(()=>{});opened.catch(()=>{});admitted.catch(()=>{});
  const timeout=setTimeout(()=>{reject(Error('Turn timeout'));headerReject(Error('Admission timeout'));readyFail(Error('Stream timeout'));},45000);
  ws.on('error',()=>{reject(Error('Stream unavailable'));readyFail(Error('Stream unavailable'));headerReject(Error('Stream unavailable'));});
  ws.on('close',()=>{if(!ended){reject(Error('Early close'));headerReject(Error('Early close'));}});
  ws.on('open',()=>ws.send(JSON.stringify({type:'open',streamId,endpoint:'session/follow',payload:{args:{request:{address:{kind:'session',sessionId},maxMessages:100}}}})));
  ws.on('message',raw=>{try{const f=JSON.parse(raw);if(f.streamId!==streamId)return;if(f.type==='error')throw Error('Follow refused');if(f.type!=='item')return;if(f.value.type==='snapshot'){ready();return;}if(f.value.type!=='event')return;const e=f.value.event;
   if(e.type==='request/header'){const names=(e.data.header.tools??[]).map(t=>t.name).sort();check(JSON.stringify(names)===JSON.stringify([...ALLOWED_TOOLS].sort()),'Tool schema mismatch');item.schemaCount=names.length;header();}
   if(e.type==='tool/call'){item.toolCalls++;throw Error('Unexpected tool call');}
   if(e.type==='turn/end'){ended=true;item.turnOutcome=e.data.reason?.kind;resolve();}
  }catch{reject(Error('Event validation failed'));headerReject(Error('Event validation failed'));}});
  const abort=new AbortController();let heartbeatError;
  const heartbeat=(async()=>{while(!abort.signal.aborted){try{const n=await native();if(n.taskId===sessionId){const b=brief(n);if(JSON.stringify(b)!==JSON.stringify(item.states.at(-1)))item.states.push(b);}else if(['running','pausing','stopping','waiting_user'].includes(n.state))throw Error('Other task active');}catch{heartbeatError=true;reject(Error('Heartbeat failed'));headerReject(Error('Heartbeat failed'));return;}await delay(500,undefined,{signal:abort.signal}).catch(()=>{});}})();
  try{
   await Promise.race([opened,done]);report.modelCalled=true;
   const ack=await call('session/prompt',{sessionId,requestId:randomUUID(),mode:'queue',clientTimeZone:'UTC',content:[{type:'text',text:'这是经授权的取消验收。不要调用任何工具。请逐行输出从1到10000的整数，不要省略，等待操作者从原生任务控制台取消。'}]});check(ack.accepted===true,'Prompt rejected');
   await Promise.race([admitted,done]);check(!ended&&!heartbeatError,'Task ended before control');
   s=await native();check(s.taskId===sessionId&&s.state==='running'&&s.displayId===-1,'Missing running lease');item.before=brief(s);
   const requested=await control(s,action);item.requested=brief(requested);check(requested.state===(action==='pause'?'pausing':'stopping'),'Control falsely reported settled');
   await done;await idle(sessionId);
   for(let i=0;i<40;i++){s=await native();if(s.taskId===sessionId&&s.state===(action==='pause'?'paused':'stopped'))break;await delay(250);}
   item.final=brief(s);check(s.taskId===sessionId&&s.displayId===-1&&s.state===(action==='pause'?'paused':'stopped'),'Native settlement mismatch');check(item.turnOutcome==='aborted'&&item.toolCalls===0,'DSH did not cancel');
   if(action==='pause'){s=await control(s,'stop');check(s.state==='stopped','Paused cleanup failed');}
   item.passed=true;
  }finally{
   clearTimeout(timeout);if(!ended){await call('session/cancel',{sessionId});await idle(sessionId);}abort.abort();await heartbeat;
   try{if(ws.readyState===WebSocket.OPEN)ws.send(JSON.stringify({type:'cancel',streamId}));ws.close();}catch{}save();
  }
  console.log(JSON.stringify({action,passed:item.passed,sessionId}));
 }
 report.passed=report.cases.length===2&&report.cases.every(x=>x.passed);save();
}
main().catch(error=>{if(/^(?:Login unavailable|RPC unavailable|RPC refused|Native refused|Runtime busy|Native not idle|Create mismatch|Prompt rejected|Task ended before control|Missing running lease|Control falsely reported settled|Native settlement mismatch|DSH did not cancel|Paused cleanup failed|Native authentication unavailable|Turn timeout|Admission timeout|Stream timeout|Heartbeat failed|Event validation failed|Early close|Test session not idle)$/.test(error?.message??''))report.reason=error.message;report.failure='Whole-task control acceptance incomplete; private diagnostics withheld';save();console.error(report.failure);process.exitCode=1;});
