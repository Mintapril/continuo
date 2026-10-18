// Fixed, operator-only live acceptance: NEW Cowork sessions and non-sensitive test data.
// Never reads other transcripts or provider config. Credentials arrive only on stdin.
import fs from 'node:fs';
import http from 'node:http';
import {createRequire} from 'node:module';
import {randomUUID} from 'node:crypto';
import {setTimeout as delay} from 'node:timers/promises';
import {authenticatedRequest} from './cowork-plugin/src/auth.mjs';
import {ALLOWED_TOOLS} from './cowork-plugin/src/restricted.mjs';
const require=createRequire(new URL('../dsh-android/repair-model-rpc.mjs',import.meta.url));
const WebSocket=require('ws');
const origin='http://127.0.0.1:3080';
const runId=randomUUID();
const out=new URL(`./test-evidence/cowork/runtime-tools-${runId}.json`,import.meta.url);
const report={kind:'live-phone-DSH-session-acceptance',modelCalled:false,stage:'authentication',cases:[]};
const created=new Set();
const check=(v,m)=>{if(!v)throw Error(m);};
async function main(){
  const secrets=JSON.parse(fs.readFileSync(0,'utf8'));
  check(/^[A-Za-z0-9_-]{16,256}$/.test(secrets.login),'Private login missing');
  const auth=await fetch(`${origin}/?token=${secrets.login}`,{redirect:'manual',signal:AbortSignal.timeout(10000)});
  const cookie=auth.headers.getSetCookie().map(x=>x.split(';',1)[0]).join('; ');
  check(cookie,'Authentication failed');
  async function rpc(method,args={}){
    const rpcId=randomUUID(),r=await fetch(`${origin}/api/${method}`,{method:'POST',redirect:'error',headers:{cookie,origin,'content-type':'application/json'},body:JSON.stringify({type:'client-request',rpcId,method,payload:{args}}),signal:AbortSignal.timeout(15000)});
    check(r.ok,`RPC unavailable: ${method}`);const data=await r.json();
    check(data.rpcId===rpcId&&data.result?.ok===true,`RPC refused: ${method}`);return data.result.value;
  }
  const call=(method,request)=>rpc(method,{request});
  async function native(){
    const r=await authenticatedRequest({secret:secrets.controller,role:'controller',method:'GET',path:'/v1/state',timeoutMs:5000,maxResponseBytes:262144,request:(options,cb)=>http.request({...options,port:13091},cb)});
    check(r.status===200,'Native controller unavailable');return JSON.parse(r.body);
  }
  const save=()=>fs.writeFileSync(out,JSON.stringify(report,null,2)+'\n');
  const roster=await rpc('agentPresets/list');check(roster.presets?.some(p=>p.id==='cowork-phone'),'Cowork preset is not activated');
  const list=await rpc('session/list',{_request:{}});check(list.items?.every(s=>s.running===false),'Existing runtime is not quiescent');
  const baseline=await native();check(baseline.displayId===-1&&!['running','pausing','stopping','waiting_user'].includes(baseline.state),'Native workspace is not idle');
  check(baseline.summaryEnabled===false,'Baseline requires summaries disabled by the user');
  const stamp=randomUUID(),filename=`acceptance-${stamp}.txt`,text=`Cowork native file acceptance 中文 ${stamp}`;
  const cases=[
    {name:'cowork.files.write',args:{path:filename,text},expect:v=>v?.created===true&&v.bytesWritten===Buffer.byteLength(text)},
    {name:'cowork.files.read',args:{path:filename},expect:v=>v?.text===text&&v.bytesRead===Buffer.byteLength(text)&&v.truncated===false},
    {name:'cowork.files.list',args:{path:'.'},expect:v=>v?.entries?.some(e=>e.name===filename&&e.kind==='file')},
    {name:'web_fetch',args:{url:'https://httpbin.org/robots.txt'},expect:v=>typeof v?.text==='string'&&v.text.includes('User-agent')&&v.notice?.includes('untrusted')},
    {name:'web_search',args:{queries:['Android developer virtual display documentation']},expect:v=>v?.results?.length===1&&v.results[0].sources?.length>0&&v.notice?.includes('untrusted')},
  ];
  let heartbeatError,currentEvidence,failCurrent;const stopHeartbeat=new AbortController();
  function recordState(s,evidence){if(!evidence||s.taskId!==evidence.sessionId)return;const state={taskId:s.taskId,executionId:s.executionId,revision:s.revision,state:s.state,displayId:s.displayId};if(JSON.stringify(evidence.nativeStates.at(-1))!==JSON.stringify(state)&&evidence.nativeStates.length<200)evidence.nativeStates.push(state);}
  const heartbeat=(async()=>{while(!stopHeartbeat.signal.aborted){try{const s=await native();if(s.taskId&&['running','pausing','stopping'].includes(s.state)&&!created.has(s.taskId))throw Error('Another native task began');recordState(s,currentEvidence);}catch{heartbeatError=Error('Controller heartbeat failed');failCurrent?.(heartbeatError);return;}await delay(1000,undefined,{signal:stopHeartbeat.signal}).catch(()=>{});}})();
  async function waitIdle(sessionId){
    for(let i=0;i<30;i++){const s=(await rpc('session/list',{_request:{}})).items?.find(x=>x.sessionId===sessionId);if(s?.running===false)return;await delay(500);}
    throw Error('Acceptance session failed to settle');
  }
  try{
    for(const item of cases){
      if(heartbeatError)throw heartbeatError;
      const sessionId=`cowork-test-${randomUUID()}`;created.add(sessionId);
      const evidence={tool:item.name,sessionId,arguments:item.args,toolResults:[],toolSchemas:[],nativeStates:[],passed:false};currentEvidence=evidence;report.stage='create';report.cases.push(evidence);save();
      const result=await call('session/create',{sessionId,agentPreset:'cowork-phone',cwd:'/workspace/cowork'});
      check(result.sessionId===sessionId&&result.agentPreset==='cowork-phone','Acceptance session preset mismatch');
      const ws=new WebSocket('ws://127.0.0.1:3080/api/remote.mux',{headers:{cookie,origin},maxPayload:4*1024*1024});
      const streamId=randomUUID(),calls=new Map();let finish,fail,ended=false;
      const done=new Promise((resolve,reject)=>{finish=resolve;fail=reject;});done.catch(()=>{});failCurrent=fail;
      let ready,readyFail;const opened=new Promise((resolve,reject)=>{ready=resolve;readyFail=reject;});
      const timeout=setTimeout(()=>fail(Error('Acceptance turn timeout')),150000);
      ws.on('error',()=>{readyFail(Error('Acceptance stream failed'));fail(Error('Acceptance stream failed'));});
      ws.on('close',()=>{if(!ended)fail(Error('Acceptance stream closed before turn settlement'));});
      ws.on('open',()=>ws.send(JSON.stringify({type:'open',streamId,endpoint:'session/follow',payload:{args:{request:{address:{kind:'session',sessionId},maxMessages:100}}}})));
      ws.on('message',raw=>{try{
        const f=JSON.parse(raw);if(f.streamId!==streamId)return;
        if(f.type==='error')throw Error('Acceptance follow refused');if(f.type!=='item')return;
        if(f.value.type==='snapshot'){ready();return;}if(f.value.type!=='event')return;
        const e=f.value.event;
        if(e.type==='request/header'){
          const names=(e.data.header.tools??[]).map(t=>t.name).sort();evidence.toolSchemas.push(names);
          check(JSON.stringify(names)===JSON.stringify([...ALLOWED_TOOLS].sort()),'Unexpected live tool schema');
        }
        if(e.type==='tool/call'){
          calls.set(e.data.callId,e.data);check(calls.size===1&&e.data.name===item.name,'Model called a tool outside this test case or repeated a mutation');
          const args=typeof e.data.arguments==='string'?JSON.parse(e.data.arguments):e.data.arguments;
          check(Object.keys(args).length===Object.keys(item.args).length&&Object.entries(item.args).every(([k,v])=>JSON.stringify(args[k])===JSON.stringify(v)),'Model altered test arguments');
        }
        if(e.type==='tool/result'){
          const c=calls.get(e.data.message.source?.callId);check(c,'Uncorrelated tool result');
          const texts=e.data.message.content?.filter(p=>p.type==='text').map(p=>p.text).join('\n')??'';
          evidence.toolResults.push({name:c.name,isError:e.data.message.isError===true,text:texts.slice(0,40000)});
        }
        if(e.type==='turn/end'){ended=true;evidence.turnOutcome=e.data.reason?.kind;finish();}
      }catch(error){const allowed=['Acceptance follow refused','Unexpected live tool schema','Model called a tool outside this test case or repeated a mutation','Model altered test arguments','Uncorrelated tool result'];evidence.validationFailure=allowed.includes(error?.message)?error.message:'Invalid event shape';fail(Error('Acceptance event validation failed'));}});
      try{
        report.stage='follow';await Promise.race([opened,done]);
        report.stage='prompt';report.modelCalled=true;
        const prompt=`这是经授权的逐工具验收，仅操作此新测试会话。请严格只调用一次 ${item.name}，参数原样为 ${JSON.stringify(item.args)}。不要调用任何其他工具，不要操作手机屏幕，不要自行重试或修复。完成或失败后只报告结果并结束本轮。`;
        const ack=await call('session/prompt',{sessionId,requestId:randomUUID(),mode:'queue',clientTimeZone:'UTC',content:[{type:'text',text:prompt}]});check(ack.accepted===true,'Prompt not accepted');
        await done;await waitIdle(sessionId);
        for(let i=0;i<30;i++){
          const s=await native();recordState(s,evidence);
          if(s.taskId===sessionId&&!['running','pausing','stopping'].includes(s.state))break;await delay(500);
        }
        const last=evidence.nativeStates.at(-1);check(last?.taskId===sessionId&&last.state==='completed'&&last.displayId===-1,'Native lifecycle did not settle completed');
        check(evidence.toolSchemas.length>0&&evidence.toolResults.length===1,'Test did not execute exactly one tool');
        const output=evidence.toolResults[0];let value;try{value=JSON.parse(output.text);}catch{}
        evidence.passed=!output.isError&&item.expect(value)&&evidence.turnOutcome==='completed';
      }finally{
        clearTimeout(timeout);failCurrent=undefined;
        try{if(ws.readyState===WebSocket.OPEN)ws.send(JSON.stringify({type:'cancel',streamId}));}catch{}
        try{ws.close();}catch{}
        if(!ended){await call('session/cancel',{sessionId});await waitIdle(sessionId);}
        report.stage='settled';save();
      }
      console.log(JSON.stringify({tool:item.name,passed:evidence.passed,sessionId}));
    }
  }finally{
    stopHeartbeat.abort();await heartbeat;save();
  }
  report.passed=report.cases.length===cases.length&&report.cases.every(c=>c.passed);save();
  if(!report.passed)process.exitCode=1;
}
main().catch(error=>{report.failure='Live acceptance incomplete; inspect bounded evidence, no private diagnostics emitted';if(/^(RPC (?:unavailable|refused): [a-zA-Z/]+|Acceptance [a-zA-Z ]+|Native [a-zA-Z ]+|Test [a-zA-Z ]+|Unexpected live tool schema|Model [a-zA-Z ]+|Prompt not accepted)$/.test(error?.message??''))report.reason=error.message;fs.writeFileSync(out,JSON.stringify(report,null,2)+'\n');console.error(report.failure);console.error(`Evidence: ${out.pathname}`);process.exitCode=1;});
