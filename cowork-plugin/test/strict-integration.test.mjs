import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import { Context } from '../research/installed/dsh/node_modules/@deepseek-ai/cordis/lib/index.js';
import ToolRuntime from '../research/installed/dsh/node_modules/@deepseek-ai/dsh-tools/lib/index.js';
import * as scope from '../research/installed/dsh/node_modules/@deepseek-ai/dsh-scope/lib/index.js';
import * as llm from '../research/installed/dsh/node_modules/@deepseek-ai/dsh-llm/lib/index.js';
import { Session } from '../research/installed/dsh/node_modules/@deepseek-ai/dsh-session/lib/index.js';
import { installRestrictedCowork, ALLOWED_TOOLS, CONTEXT_TOOLS, MEMORY_TOOLS, SCHEDULE_TOOLS, phoneTools, reframeReminder } from '@local/dsh-cowork-phone';
import { tool,object } from '../src/validation.mjs';
import { createTaskLifecycle, PRIORITY } from '../src/task-lifecycle.mjs';
import { agentEvents } from '../research/installed/dsh/node_modules/@deepseek-ai/dsh-agent/lib/types/dispatch.js';

test('actual DSH registry: Cowork allowlist enforced, Code unchanged, injected MCP denied',async()=>{
  const ctx=new Context();
  ctx.provide('systemPrompt',{tools:()=>{},section:()=>{},getSectionOrder:()=>0});
  ctx.provide('attachments',{saveImage:async()=>({attachmentId:'sha256:test',mediaType:'image/png',bytes:1,width:1,height:1})});
  ctx.provide('web',{search:async()=>({content:'fact',sources:[]})});
  ctx.provide('llm',{stream:()=>{throw Error('No model calls during tests');}});
  const liveAgents=new Map();ctx.provide('agents',{get:id=>liveAgents.get(id),roots:()=>[...liveAgents.values()]});
  const runtimeFiber=ctx.plugin(ToolRuntime,{mode:'native'});
  if(runtimeFiber.inertia) await runtimeFiber.inertia;
  let escaped=0;
  ctx.tools.register(tool('shell','forbidden',object(),()=>{escaped++;return {};}));
  ctx.tools.register(tool('subagent','forbidden',object(),()=>{escaped++;return {};}));
  let owner;
  const ownerFiber=ctx.inject(['tools','systemPrompt','attachments','web','llm','agents'],c=>{owner=c;});
  if(ownerFiber.inertia) await ownerFiber.inertia;
  const key={}, preset=scope.createScope(owner,key), agent={id:'cowork',session:{id:'cowork',snapshotEvents:()=>[],requestHeader:()=>undefined}};
  const child=scope.createScope(owner,agent,{parent:key});agent.ctx=child.ctx;
  const code={id:'code',session:{id:'code'}}; const codeScope=scope.createScope(owner,code);code.ctx=codeScope.ctx;
  const nativeCalls=[];let native={instanceId:'test-native',executionId:'',taskId:'',revision:0,state:'idle',command:'',commandId:''};
  const lifecycle=createTaskLifecycle({transport:async options=>{
    const body=options.body.length?JSON.parse(options.body):{};nativeCalls.push(body.operation??'state');
    if(body.operation==='begin')native={...native,taskId:body.taskId,executionId:'20000000-0000-4000-8000-000000000001',revision:native.revision+1,state:'running'};
    if(body.operation==='end')native={...native,state:body.outcome,revision:native.revision+1};
    return {status:200,headers:{'content-type':'application/json'},body:Buffer.from(JSON.stringify(native))};
  }});
  const signal=new AbortController().signal;agent.cancel=()=>assert.fail('No cancellation expected');agent.whenIdle=async()=>{};
  const api=installRestrictedCowork(preset.ctx,{}, {...scope,...llm,Session},{lifecycle,bridge:{call:async(_sid,name)=>name==='phone.status'?{state:'ready',accessibilityReady:true,screenCaptureReady:true}:{performed:true},forget:()=>{}}, files:{}, appendEnabled:false});
  liveAgents.set(agent.id,agent);
  ctx.emit('session/event',agent.session,{type:'agent-preset/selected',data:{agentPreset:'cowork-phone'}});
  assert.deepEqual(ctx.tools.schemas(agent).map(t=>t.name).sort(),[...ALLOWED_TOOLS].sort());
  assert.deepEqual(ctx.tools.schemas(code).map(t=>t.name).sort(),['shell','subagent']);
  let serial=0;const execute=(name,target=agent)=>{
    const callId='call-'+serial++;
    ctx.emit('session/event',target.session,{type:'tool/call',data:{turn:1,callId,name,arguments:{}}});
    return ctx.tools.execute({name,arguments:{},agent:target,signal,callId});
  };
  assert.equal((await execute('phone_status')).isError,true,'no tool body before admitted turn');
  assert.deepEqual(nativeCalls,[],'activation and unauthenticated wrapper have no network side effect');
  ctx.emit('session/event',agent.session,{type:'turn/start',data:{turn:1}});
  const decision=await agentEvents(ctx,agent).waterfall('agent/pre-step',{turn:1,step:1,signal},async()=>({kind:'enter'}));
  assert.equal(decision.kind,'enter');assert.deepEqual(nativeCalls,['state','begin']);
  const status=await execute('phone_status');assert.equal(status.isError,false,status.error?.message);
  for(const name of ['shell','subagent','run_code','mcp__escape','workflow','plugin_manager']) assert.equal((await execute(name)).isError,true,name);
  agent.ctx.tools.register(tool('mcp__escape','late injection',object(),()=>{escaped++;return {};}));
  assert.equal((await execute('mcp__escape')).isError,true); assert.equal(escaped,0);
  await execute('shell',code);assert.equal(escaped,1);
  // An extensible allow gate cannot overturn the monotonic guard.
  ctx.on('tools/pre-execute',(_exec,_next)=>({kind:'allow'}));
  assert.equal((await execute('mcp__escape')).isError,true);
  // Same-name definition from an agent-child scope cannot impersonate an owned tool.
  const descendant={id:'desc',session:{id:'desc'}}, descendantScope=scope.createScope(owner,descendant,{parent:agent});descendant.ctx=descendantScope.ctx;
  descendant.ctx.tools.register(tool('phone_status','shadow',object(),()=>{escaped++;return {};}));
  assert.equal((await execute('phone_status',descendant)).isError,true);assert.equal(escaped,1);
  await assert.rejects(agentEvents(ctx,descendant).waterfall('agent/pre-step',{turn:1,step:1,signal},async()=>({kind:'enter'})),/top-level/);
  ctx.emit('session/event',agent.session,{type:'turn/end',data:{turn:1,reason:{kind:'completed'}}});
  await lifecycle.flush(agent);assert.equal(native.state,'completed');
  api.remove(descendant);api.remove(agent);lifecycle.dispose();await lifecycle.flush();await descendantScope.dispose();await child.dispose();await preset.dispose();await codeScope.dispose();
});
test('bundle is additive and packages only tested active implementation',async()=>{
  const pkg=JSON.parse(await fs.readFile(new URL('../package.json',import.meta.url),'utf8'));
  const patch=await fs.readFile(new URL('../cordis.patch.yml',import.meta.url),'utf8');
  assert.equal(pkg.exports['.'],'./src/restricted.mjs');
  assert.deepEqual(pkg.files.filter(p=>p.startsWith('src/')).sort(),['src/restricted.mjs','src/validation.mjs','src/broker.mjs','src/auth.mjs','src/task-lifecycle.mjs','src/bounded-files.mjs','src/web.mjs','src/gesture-path.mjs','src/notify.mjs','src/screen-board.mjs'].sort());
  // Every module a packaged one imports is packaged too (a missing file only shows up when DSH loads the bundle).
  for(const file of pkg.files.filter(p=>p.endsWith('.mjs'))){
    const text=await fs.readFile(new URL(`../${file}`,import.meta.url),'utf8');
    for(const [,dep] of text.matchAll(/^import [^;]*? from '\.\/([^']+)';/gm))assert.ok(pkg.files.includes(`src/${dep}`),`${file} imports src/${dep}, which is not packaged`);
  }
  assert.equal(pkg.exports['./notify'],'./src/notify.mjs');assert.match(patch,/name: '\.\/src\/notify\.mjs'/);
  // DSH 0.2.1 installs no dsh-webhook; a row naming it would not resolve.
  assert.doesNotMatch(patch,/dsh-webhook/);assert.match(patch,/name: '@deepseek-ai\/dsh-tool-schedule'/);assert.match(patch,/name: '@deepseek-ai\/dsh-time-context'/);
  assert.match(patch,/- insert:/);assert.match(patch,/id: cowork-phone/);
  assert.doesNotMatch(patch,/replace:|remove:|apiKey:|credentials:|provider:|dsh-tool-web/);
  // DSH 0.2.1 skips a bundle whose @deepseek-ai/dsh-* peers miss the running version, so every pin must name it.
  const runtime=JSON.parse(await fs.readFile(new URL('../research/installed/dsh/node_modules/@deepseek-ai/dsh/package.json',import.meta.url),'utf8')).version;
  for(const version of Object.values(pkg.peerDependencies))assert.equal(version,runtime);
  for(const name of Object.keys(pkg.peerDependencies))await fs.access(new URL(`../research/installed/dsh/node_modules/${name}/package.json`,import.meta.url));
});
test('screenshot stores canonical attachment instead of returning raw base64 to model',async()=>{
  let saved;
  const bridge={call:async()=>({observationId:'o',capturedAt:1,width:1,height:1,mimeType:'image/png',base64:'aGk='}),forget:()=>{}};
  const screenshot=phoneTools(bridge,{saveImage:async input=>{saved=input;return {attachmentId:'test',mediaType:'image/png',width:1,height:1,bytes:2};}}).find(t=>t.name==='phone.screenshot');
  const result=await screenshot.execute({},{agent:{session:{id:'s'}},signal:new AbortController().signal});
  assert.equal(saved.data.toString(),'hi');assert.equal(result.base64,undefined);assert.equal(screenshot.output.render({},result)[1].type,'image');
});

test('model-visible tool names match the provider pattern (no dots) while wire names stay dotted',()=>{
  for(const name of ALLOWED_TOOLS) assert.match(name,/^[a-zA-Z0-9_-]+$/,name);
  assert.ok(ALLOWED_TOOLS.includes('phone_ui_tree')&&ALLOWED_TOOLS.includes('cowork_files_read')&&ALLOWED_TOOLS.includes('web_search'));
  assert.ok(!ALLOWED_TOOLS.some(n=>n.includes('.')));
});

test('host context tools (billion-context-dsh) are exposed to Cowork, only as the global definition and without file arguments',async()=>{
  const ctx=new Context();
  ctx.provide('systemPrompt',{tools:()=>{},section:()=>{},getSectionOrder:()=>0});
  ctx.provide('attachments',{saveImage:async()=>({})});
  ctx.provide('web',{search:async()=>({content:'fact',sources:[]})});
  ctx.provide('llm',{stream:()=>{throw Error('No model calls during tests');}});
  const liveAgents=new Map();ctx.provide('agents',{get:id=>liveAgents.get(id),roots:()=>[...liveAgents.values()]});
  const runtimeFiber=ctx.plugin(ToolRuntime,{mode:'native'});if(runtimeFiber.inertia)await runtimeFiber.inertia;
  let ran=0;
  for(const n of CONTEXT_TOOLS)ctx.tools.register(tool(n,'host '+n,object(),()=>{ran++;return {};}));
  ctx.tools.register(tool('shell','forbidden',object(),()=>{ran+=100;return {};}));
  let owner;const ownerFiber=ctx.inject(['tools','systemPrompt','attachments','web','llm','agents'],c=>{owner=c;});if(ownerFiber.inertia)await ownerFiber.inertia;
  const key={},preset=scope.createScope(owner,key),agent={id:'cowork',session:{id:'cowork',snapshotEvents:()=>[],requestHeader:()=>undefined}};
  const child=scope.createScope(owner,agent,{parent:key});agent.ctx=child.ctx;
  const lifecycle=createTaskLifecycle({transport:async()=>({status:200,headers:{'content-type':'application/json'},body:Buffer.from('{}')})});
  const api=installRestrictedCowork(preset.ctx,{},{...scope,...llm,Session},{lifecycle,bridge:{call:async()=>({}),forget:()=>{}},files:{}});
  liveAgents.set(agent.id,agent);
  ctx.emit('session/event',agent.session,{type:'agent-preset/selected',data:{agentPreset:'cowork-phone'}});
  const visible=ctx.tools.schemas(agent).map(t=>t.name);
  for(const n of CONTEXT_TOOLS)assert.ok(visible.includes(n),n+' visible');
  assert.ok(!visible.includes('shell'));
  const signal=new AbortController().signal;let serial=0;
  const guarded=(name,args={},target=agent)=>ctx.tools.guardReason?ctx.tools.guardReason({name,agent:target,arguments:args}):undefined;
  const execute=(name,args={},target=agent)=>{const callId='c'+serial++;ctx.emit('session/event',target.session,{type:'tool/call',data:{turn:1,callId,name,arguments:args}});return ctx.tools.execute({name,arguments:args,agent:target,signal,callId});};
  ctx.emit('session/event',agent.session,{type:'turn/start',data:{turn:1}});
  // Host tools that do not touch the phone run without an admitted lifecycle turn only if the wrapper allows it; here only the guard matters.
  for(const n of CONTEXT_TOOLS){const r=await execute(n);assert.equal(r.isError,false,n+': '+JSON.stringify(r.error));}
  assert.equal(ran,CONTEXT_TOOLS.length,'host tool bodies actually ran');
  const toFile=await execute('decompress',{blockId:'b1',toFile:'/tmp/x.txt'});assert.equal(toFile.isError,true);assert.match(JSON.stringify(toFile),/may not write files/);
  const wrapped=await execute('decompress',{arguments:'{"blockId":"b1","toFile":"/tmp/x"}'});assert.equal(wrapped.isError,true);assert.match(JSON.stringify(wrapped),/may not write files/);
  assert.equal((await execute('shell')).isError,true);assert.ok(ran<100);
  // A same-name definition from a descendant scope cannot impersonate the host tool.
  const desc={id:'desc',session:{id:'desc'}},descScope=scope.createScope(owner,desc,{parent:agent});desc.ctx=descScope.ctx;
  desc.ctx.tools.register(tool('compress','shadow',object(),()=>{ran+=100;return {};}));
  assert.equal((await execute('compress',{},desc)).isError,true);assert.ok(ran<100);
  api.remove(agent);lifecycle.dispose();await descScope.dispose();await child.dispose();await preset.dispose();
});

test('host memory tools (dsh-mneme) are exposed to Cowork; destructive and path-taking ones stay out',async()=>{
  const ctx=new Context();
  ctx.provide('systemPrompt',{tools:()=>{},section:()=>{},getSectionOrder:()=>0});
  ctx.provide('attachments',{saveImage:async()=>({})});
  ctx.provide('web',{search:async()=>({content:'fact',sources:[]})});
  ctx.provide('llm',{stream:()=>{throw Error('No model calls during tests');}});
  const liveAgents=new Map();ctx.provide('agents',{get:id=>liveAgents.get(id),roots:()=>[...liveAgents.values()]});
  const runtimeFiber=ctx.plugin(ToolRuntime,{mode:'native'});if(runtimeFiber.inertia)await runtimeFiber.inertia;
  let ran=0;
  for(const n of [...MEMORY_TOOLS,'memory_delete','memory_forget','memory_register_document','memory_runtime'])
    ctx.tools.register(tool(n,'host '+n,object(),()=>{ran++;return {};}));
  let owner;const ownerFiber=ctx.inject(['tools','systemPrompt','attachments','web','llm','agents'],c=>{owner=c;});if(ownerFiber.inertia)await ownerFiber.inertia;
  const key={},preset=scope.createScope(owner,key),agent={id:'cowork',session:{id:'cowork',snapshotEvents:()=>[],requestHeader:()=>undefined}};
  const child=scope.createScope(owner,agent,{parent:key});agent.ctx=child.ctx;
  const lifecycle=createTaskLifecycle({transport:async()=>({status:200,headers:{'content-type':'application/json'},body:Buffer.from('{}')})});
  const api=installRestrictedCowork(preset.ctx,{},{...scope,...llm,Session},{lifecycle,bridge:{call:async()=>({}),forget:()=>{}},files:{}});
  liveAgents.set(agent.id,agent);
  ctx.emit('session/event',agent.session,{type:'agent-preset/selected',data:{agentPreset:'cowork-phone'}});
  const visible=ctx.tools.schemas(agent).map(t=>t.name);
  for(const n of MEMORY_TOOLS)assert.ok(visible.includes(n),n+' visible');
  for(const n of ['memory_delete','memory_forget','memory_register_document','memory_runtime'])
    assert.ok(!visible.includes(n),n+' must stay out of Cowork');
  const signal=new AbortController().signal;let serial=0;
  const execute=(name,args={})=>{const callId='m'+serial++;ctx.emit('session/event',agent.session,{type:'tool/call',data:{turn:1,callId,name,arguments:args}});return ctx.tools.execute({name,arguments:args,agent,signal,callId});};
  ctx.emit('session/event',agent.session,{type:'turn/start',data:{turn:1}});
  for(const n of MEMORY_TOOLS){const r=await execute(n);assert.equal(r.isError,false,n+': '+JSON.stringify(r.error));}
  assert.equal(ran,MEMORY_TOOLS.length,'host memory tool bodies actually ran');
  for(const n of ['memory_delete','memory_register_document']){const r=await execute(n,{id:'x'});assert.equal(r.isError,true,n+' refused');}
  const pathed=await execute('memory_save',{memory:{path:'/data/x'}});assert.equal(pathed.isError,true);assert.match(JSON.stringify(pathed),/may not write files/);
  api.remove(agent);lifecycle.dispose();await child.dispose();await preset.dispose();
});
test('who helps when a step needs the owner: a turn the owner started takes over the screen; a run nobody watches notifies once instead',async()=>{
  const ctx=new Context();
  ctx.provide('systemPrompt',{tools:()=>{},section:()=>{},getSectionOrder:()=>0});
  ctx.provide('attachments',{saveImage:async()=>({})});
  ctx.provide('web',{search:async()=>({content:'fact',sources:[]})});
  ctx.provide('llm',{stream:()=>{throw Error('No model calls during tests');}});
  const liveAgents=new Map();ctx.provide('agents',{get:id=>liveAgents.get(id),roots:()=>[...liveAgents.values()]});
  const runtimeFiber=ctx.plugin(ToolRuntime,{mode:'native'});if(runtimeFiber.inertia)await runtimeFiber.inertia;
  let owner;const ownerFiber=ctx.inject(['tools','systemPrompt','attachments','web','llm','agents'],c=>{owner=c;});if(ownerFiber.inertia)await ownerFiber.inertia;
  const key={},preset=scope.createScope(owner,key),agent={id:'cowork',session:{id:'cowork',snapshotEvents:()=>[],requestHeader:()=>undefined}};
  const child=scope.createScope(owner,agent,{parent:key});agent.ctx=child.ctx;
  // Native admission is not under test here: tools run directly; the bridge records what reached the broker.
  const lifecycle={runTool:(a,exec,body)=>body(exec),preStep:async()=>{},observe(){},remove(){},dispose(){},flush:async()=>{}};
  const sent=[];
  const bridge={call:async(sid,name)=>{sent.push(name);return name==='phone.request_intervention'?{performed:true,interventionId:'i'}:name==='phone.intervention_status'?{active:false,interventionId:'i',outcome:'handed_back'}:{performed:true};},forget:()=>{}};
  const api=installRestrictedCowork(preset.ctx,{},{...scope,...llm,Session},{lifecycle,bridge,files:{}});
  liveAgents.set(agent.id,agent);
  ctx.emit('session/event',agent.session,{type:'agent-preset/selected',data:{agentPreset:'cowork-phone'}});
  const signal=new AbortController().signal;let serial=0;
  const execute=(name,args)=>{const callId='o'+serial++;ctx.emit('session/event',agent.session,{type:'tool/call',data:{turn:1,callId,name,arguments:args}});return ctx.tools.execute({name,arguments:args,agent,signal,callId});};
  const turn=(n,...kinds)=>{ctx.emit('session/event',agent.session,{type:'turn/start',data:{turn:n}});for(const kind of kinds)ctx.emit('session/event',agent.session,{type:'user/message',data:{source:{kind}}});};
  const notify={text:'有人发来 200 元转账，需要你决定是否收款'};
  // The owner wrote this turn: take the screen over as before; a notification is for runs nobody watches.
  turn(1,'user','time-context');
  {const r=await execute('phone_request_intervention',{reason:'password'});assert.equal(r.isError,false,JSON.stringify(r.error));}
  {const r=await execute('phone_notify_owner',notify);assert.equal(r.isError,true);assert.match(JSON.stringify(r),/gave this task in the chat/);}
  assert.deepEqual(sent,['phone.request_intervention','phone.intervention_status']);
  // A notification trigger and a due reminder are unattended: no takeover, exactly one notification per run.
  for(const [n,kind] of [[2,'webhook'],[3,'schedule']]){
    sent.length=0;turn(n,kind,'time-context');
    {const r=await execute('phone_request_intervention',{});assert.equal(r.isError,true);assert.match(JSON.stringify(r),/Nobody is watching this run/);}
    {const r=await execute('phone_notify_owner',notify);assert.equal(r.isError,false,JSON.stringify(r.error));assert.match(JSON.stringify(r),/End the task now/);}
    {const r=await execute('phone_notify_owner',notify);assert.equal(r.isError,true,'a second notification in the same run');assert.match(JSON.stringify(r),/already notified/);}
    assert.deepEqual(sent,['phone.notify_owner'],kind);
  }
  // No turn/start seen (plugin reloaded mid-turn): treated as unattended, like the schedule rule.
  sent.length=0;api.remove(agent);ctx.emit('session/event',agent.session,{type:'agent-preset/selected',data:{agentPreset:'cowork-phone'}});
  assert.equal((await execute('phone_request_intervention',{})).isError,true);
  assert.equal((await execute('phone_notify_owner',notify)).isError,false);
  assert.equal((await execute('phone_notify_owner',notify)).isError,true);
  assert.deepEqual(sent,['phone.notify_owner']);
  // A new owner turn resets it.
  sent.length=0;turn(4,'user');
  assert.equal((await execute('phone_notify_owner',notify)).isError,true);
  assert.equal((await execute('phone_request_intervention',{})).isError,false);
  api.remove(agent);await child.dispose();await preset.dispose();
});
test('host automation tools (dsh-schedule) are admitted for the top-level Cowork agent; a turn without a user message may only list tasks and leave itself notes',async()=>{
  const ctx=new Context();
  ctx.provide('systemPrompt',{tools:()=>{},section:()=>{},getSectionOrder:()=>0});
  ctx.provide('attachments',{saveImage:async()=>({})});
  ctx.provide('web',{search:async()=>({content:'fact',sources:[]})});
  ctx.provide('llm',{stream:()=>{throw Error('No model calls during tests');}});
  const liveAgents=new Map();ctx.provide('agents',{get:id=>liveAgents.get(id),roots:()=>[...liveAgents.values()]});
  const runtimeFiber=ctx.plugin(ToolRuntime,{mode:'native'});if(runtimeFiber.inertia)await runtimeFiber.inertia;
  let owner;const ownerFiber=ctx.inject(['tools','systemPrompt','attachments','web','llm','agents'],c=>{owner=c;});if(ownerFiber.inertia)await ownerFiber.inertia;
  const key={},preset=scope.createScope(owner,key),agent={id:'cowork',session:{id:'cowork',snapshotEvents:()=>[],requestHeader:()=>undefined}};
  const child=scope.createScope(owner,agent,{parent:key});agent.ctx=child.ctx;
  // DSH 0.2.1: the Cowork preset declares dsh-tool-schedule, which registers its four tools in the preset's scope
  // (the next test mounts the real plugin there; stubs here record which calls the guard let through).
  const ran=[];
  // The create stub takes its arguments as optional fields, like the real tool's schema.
  const createArgs=object({title:{type:'string'},prompt:{type:'string'},after_seconds:{type:'number'},at:{type:'string'},every_seconds:{type:'number'},cron:{type:'object'}},[]);
  for(const n of SCHEDULE_TOOLS)preset.ctx.tools.register(tool(n,'host '+n,n==='schedule_create'?createArgs:object(),()=>{ran.push(n);return {};}));
  const lifecycle=createTaskLifecycle({transport:async()=>({status:200,headers:{'content-type':'application/json'},body:Buffer.from('{}')})});
  let clock=Date.parse('2026-10-24T00:00:00Z');
  const api=installRestrictedCowork(preset.ctx,{},{...scope,...llm,Session},{lifecycle,bridge:{call:async()=>({}),forget:()=>{}},files:{},now:()=>clock});
  liveAgents.set(agent.id,agent);
  ctx.emit('session/event',agent.session,{type:'agent-preset/selected',data:{agentPreset:'cowork-phone'}});
  const visible=ctx.tools.schemas(agent).map(t=>t.name);
  for(const n of SCHEDULE_TOOLS)assert.ok(visible.includes(n),n+' visible');
  const signal=new AbortController().signal;let serial=0;
  const execute=(name,target=agent,args={})=>{const callId='s'+serial++;ctx.emit('session/event',target.session,{type:'tool/call',data:{turn:1,callId,name,arguments:args}});return ctx.tools.execute({name,arguments:args,agent:target,signal,callId});};
  const turn=(n,...kinds)=>{ctx.emit('session/event',agent.session,{type:'turn/start',data:{turn:n}});for(const kind of kinds)ctx.emit('session/event',agent.session,{type:'user/message',data:{source:{kind}}});};
  // A turn the user started (plus the clock reading time-context injects) may manage tasks.
  turn(1,'user','time-context');
  for(const n of SCHEDULE_TOOLS){const r=await execute(n);assert.equal(r.isError,false,n+': '+JSON.stringify(r.error));}
  assert.deepEqual(ran,[...SCHEDULE_TOOLS]);
  // A turn only a due reminder started: list and leave itself notes, never anything else.
  ran.length=0;turn(2,'schedule','time-context');
  {const r=await execute('schedule_create');assert.equal(r.isError,true);assert.match(JSON.stringify(r),/can only leave itself a note/);}
  for(const n of ['schedule_update','schedule_delete']){const r=await execute(n);assert.equal(r.isError,true,n);assert.match(JSON.stringify(r),/cannot change or delete notes/);}
  {const r=await execute('schedule_list');assert.equal(r.isError,false,'schedule_list: '+JSON.stringify(r.error));}
  assert.deepEqual(ran,['schedule_list']);
  // A note: title 便条：, prompt [便条]…, after_seconds alone within 10 minutes..7 days. Anything else is refused.
  const note={title:'便条：问问小王',prompt:'[便条] 问问小王面试结果怎么样',after_seconds:3600};
  for(const bad of [{...note,title:'问问小王'},{...note,prompt:'问问小王'},{...note,after_seconds:599},{...note,after_seconds:604801},{...note,after_seconds:3600.5},
    {title:note.title,prompt:note.prompt,every_seconds:3600},{...note,cron:{expression:'0 9 * * *',time_zone:'Asia/Shanghai'}},{title:note.title,prompt:note.prompt,at:'2026-10-25T09:00:00+08:00'}]){
    const r=await execute('schedule_create',agent,bad);assert.equal(r.isError,true,JSON.stringify(bad));assert.match(JSON.stringify(r),/can only leave itself a note/);}
  assert.deepEqual(ran,['schedule_list']);
  ran.length=0;
  for(let i=0;i<5;i++){const r=await execute('schedule_create',agent,note);assert.equal(r.isError,false,JSON.stringify(r.error));}
  {const r=await execute('schedule_create',agent,note);assert.equal(r.isError,true,'a sixth note within 24 hours');assert.match(JSON.stringify(r),/at most 5 in 24 hours/);}
  // A day later the window has moved on. The guard may be asked twice about one call: that call counts once.
  clock+=24*3_600_000+1;
  {const exec={name:'schedule_create',agent,arguments:note,callId:'asked-twice'};
    assert.equal(ctx.tools.guardReason(exec),undefined);assert.equal(ctx.tools.guardReason(exec),undefined);}
  for(let i=0;i<4;i++){const r=await execute('schedule_create',agent,note);assert.equal(r.isError,false,'a day later the window has moved on');}
  assert.equal((await execute('schedule_create',agent,note)).isError,true,'the call asked about twice counted once: 1 + 4 = 5');
  assert.equal(ran.length,9);
  // A turn a notification webhook started is just as unattended.
  ran.length=0;turn(5,'webhook','time-context');
  for(const n of ['schedule_create','schedule_update','schedule_delete'])assert.equal((await execute(n)).isError,true,n);
  assert.equal((await execute('schedule_create',agent,{...note,title:'便条：另一件'})).isError,true,'the 24-hour count is per session, not per turn');
  assert.equal((await execute('schedule_list')).isError,false);assert.deepEqual(ran,['schedule_list']);
  // No turn/start seen (plugin reloaded mid-turn): the origin is unknown, so writes fail closed.
  ran.length=0;api.remove(agent);ctx.emit('session/event',agent.session,{type:'agent-preset/selected',data:{agentPreset:'cowork-phone'}});
  assert.equal((await execute('schedule_create')).isError,true);assert.equal((await execute('schedule_list')).isError,false);assert.deepEqual(ran,['schedule_list']);
  // The user wrote during the reminder's turn: their message may ask for a change.
  ran.length=0;turn(3,'schedule','user');
  assert.equal((await execute('schedule_create')).isError,false);assert.deepEqual(ran,['schedule_create']);
  // A child scope inherits the definition but is no top-level session.
  const desc={id:'desc',session:{id:'desc'}},descScope=scope.createScope(owner,desc,{parent:agent});desc.ctx=descScope.ctx;
  ran.length=0;assert.equal((await execute('schedule_list',desc)).isError,true);assert.deepEqual(ran,[]);
  // A same-name definition in the agent's own scope shadows the preset's and is refused as well.
  {const undo=agent.ctx.tools.register(tool('schedule_list','agent impostor',object(),()=>{ran.push('agent-impostor');return {};}));
    turn(6,'user');const r=await execute('schedule_list');assert.equal(r.isError,true);assert.match(JSON.stringify(r),/host schedule definition/);undo();}
  assert.deepEqual(ran,[]);
  // A global definition of the same name is not the preset-scoped tool either.
  ctx.tools.register(tool('schedule_list','global impostor',object(),()=>{ran.push('impostor');return {};}));
  turn(4,'user');
  const shadowed=await execute('schedule_list');assert.equal(shadowed.isError,true);assert.match(JSON.stringify(shadowed),/host schedule definition/);
  assert.deepEqual(ran,[]);
  api.remove(agent);lifecycle.dispose();await descScope.dispose();await child.dispose();await preset.dispose();
});
test('the real dsh-tool-schedule declared in the Cowork preset registers preset-scoped tools the guard admits',async()=>{
  const ScheduleTools=await import('../research/installed/dsh/node_modules/@deepseek-ai/dsh-tool-schedule/lib/index.js');
  const ctx=new Context();
  ctx.provide('systemPrompt',{tools:()=>{},section:()=>{},getSectionOrder:()=>0});
  ctx.provide('attachments',{saveImage:async()=>({})});
  ctx.provide('web',{search:async()=>({content:'fact',sources:[]})});
  ctx.provide('llm',{stream:()=>{throw Error('No model calls during tests');}});
  const listed=[],created=[];ctx.provide('schedule',{list:async filter=>{listed.push(filter.sessionId);return [];},create:async(sessionId,args)=>{created.push([sessionId,args.title]);throw Error('not stored in this test');}});
  const liveAgents=new Map();ctx.provide('agents',{get:id=>liveAgents.get(id),roots:()=>[...liveAgents.values()]});
  const runtimeFiber=ctx.plugin(ToolRuntime,{mode:'native'});if(runtimeFiber.inertia)await runtimeFiber.inertia;
  let owner;const ownerFiber=ctx.inject(['tools','systemPrompt','attachments','web','llm','agents','schedule'],c=>{owner=c;});if(ownerFiber.inertia)await ownerFiber.inertia;
  const key={},preset=scope.createScope(owner,key),agent={id:'cowork',options:{},session:{id:'cowork',header:{},snapshotEvents:()=>[],requestHeader:()=>undefined}};
  const child=scope.createScope(owner,agent,{parent:key});agent.ctx=child.ctx;
  const mounted=preset.ctx.plugin(ScheduleTools,{});await mounted.await();
  const lifecycle=createTaskLifecycle({transport:async()=>({status:200,headers:{'content-type':'application/json'},body:Buffer.from('{}')})});
  const api=installRestrictedCowork(preset.ctx,{},{...scope,...llm,Session},{lifecycle,bridge:{call:async()=>({}),forget:()=>{}},files:{}});
  liveAgents.set(agent.id,agent);
  ctx.emit('session/event',agent.session,{type:'agent-preset/selected',data:{agentPreset:'cowork-phone'}});
  for(const n of SCHEDULE_TOOLS){assert.equal(ctx.tools.get(n),undefined,n+' is not global');assert.ok(ctx.tools.get(n,agent),n+' resolves for the agent');}
  const visible=ctx.tools.schemas(agent).map(t=>t.name);for(const n of SCHEDULE_TOOLS)assert.ok(visible.includes(n),n+' visible');
  ctx.emit('session/event',agent.session,{type:'turn/start',data:{turn:1}});
  ctx.emit('session/event',agent.session,{type:'user/message',data:{source:{kind:'schedule'}}});
  const signal=new AbortController().signal;let serial=0;
  const execute=(name,args={})=>{const callId='r'+serial++;ctx.emit('session/event',agent.session,{type:'tool/call',data:{turn:1,callId,name,arguments:args}});return ctx.tools.execute({name,arguments:args,agent,signal,callId});};
  {const r=await execute('schedule_list');assert.equal(r.isError,false,JSON.stringify(r.error));}
  assert.deepEqual(listed,['cowork']);
  {const r=await execute('schedule_create',{title:'t',prompt:'p',after_seconds:60});assert.equal(r.isError,true);assert.match(JSON.stringify(r),/can only leave itself a note/);}
  assert.deepEqual(created,[]);
  // A note passes the guard and reaches the real tool body (the stub store then refuses it).
  await execute('schedule_create',{title:'便条：问问小王',prompt:'[便条] 问问小王面试结果',after_seconds:3600});
  assert.deepEqual(created,[['cowork','便条：问问小王']]);
  api.remove(agent);lifecycle.dispose();await mounted.dispose();await child.dispose();await preset.dispose();
});
test('the reminder framing the installed dsh-schedule delivers is the one the note reframing parses',async()=>{
  const source=await fs.readFile(new URL('../research/installed/dsh/node_modules/@deepseek-ai/dsh-schedule/lib/index.js',import.meta.url),'utf8');
  assert.match(source,/const SCHEDULED_MESSAGE_FRAMING = "This is a scheduled message from the user";/);
  const single=source.match(/function renderReminderFraming\(record\) \{[\s\S]*?\n\}/)?.[0]??'';
  for(const piece of ['"[SCHEDULE REMINDER]",','SCHEDULED_MESSAGE_FRAMING,','`schedule_id_json: ${JSON.stringify(record.id)}`,','`occurrence_at: ${record.scheduledAt}`,','`reminder_prompt_json: ${JSON.stringify(record.prompt)}`','].join("\\n");'])
    assert.ok(single.includes(piece),piece);
  const batch=source.match(/function renderRecurringReminderBatchFraming\(reminders\) \{[\s\S]*?\n\}/)?.[0]??'';
  for(const piece of ['schedule_id: record.id,','occurrence_at: occurrenceAt,','reminder_prompt: record.prompt','"[SCHEDULE REMINDER BATCH]",','SCHEDULED_MESSAGE_FRAMING,','`reminders_json: ${JSON.stringify(payload)}`','].join("\\n");'])
    assert.ok(batch.includes(piece),piece);
  assert.match(source,/source: \{ kind: "schedule" \}/,'a due reminder is a user message from source kind schedule');
  const text=['[SCHEDULE REMINDER]','This is a scheduled message from the user','schedule_id_json: "s1"','occurrence_at: 2026-10-24T01:00:00.000Z',`reminder_prompt_json: ${JSON.stringify('[便条] 问问小王')}`].join('\n');
  const out=reframeReminder(text);
  assert.match(out,/^\[SELF NOTE\]\n/);assert.match(out,/\nnote_id_json: "s1"\ndue_at: 2026-10-24T01:00:00\.000Z\nnote_json: "问问小王"$/);
});
test('a due note reaches the model, and the session log, as its own note; a turn waits in line by what started it; an owner in line asks the holder to wrap up',async()=>{
  const ctx=new Context();
  ctx.provide('systemPrompt',{tools:()=>{},section:()=>{},getSectionOrder:()=>0});
  ctx.provide('attachments',{saveImage:async()=>({})});
  ctx.provide('web',{search:async()=>({content:'fact',sources:[]})});
  ctx.provide('llm',{stream:()=>{throw Error('No model calls during tests');}});
  const liveAgents=new Map();ctx.provide('agents',{get:id=>liveAgents.get(id),roots:()=>[...liveAgents.values()]});
  const runtimeFiber=ctx.plugin(ToolRuntime,{mode:'native'});if(runtimeFiber.inertia)await runtimeFiber.inertia;
  let owner;const ownerFiber=ctx.inject(['tools','systemPrompt','attachments','web','llm','agents'],c=>{owner=c;});if(ownerFiber.inertia)await ownerFiber.inertia;
  const key={},preset=scope.createScope(owner,key),agent={id:'cowork',session:{id:'cowork',snapshotEvents:()=>[],requestHeader:()=>undefined}};
  const child=scope.createScope(owner,agent,{parent:key});agent.ctx=child.ctx;
  const priorities=[];
  const lifecycle={runTool:(a,exec,body)=>body(exec),preStep:async(_agent,_turn,_signal,priority)=>{priorities.push(priority);},observe(){},remove(){},dispose(){},flush:async()=>{}};
  const api=installRestrictedCowork(preset.ctx,{},{...scope,...llm,Session},{lifecycle,bridge:{call:async()=>({}),forget:()=>{}},files:{}});
  liveAgents.set(agent.id,agent);
  ctx.emit('session/event',agent.session,{type:'agent-preset/selected',data:{agentPreset:'cowork-phone'}});
  const signal=new AbortController().signal;
  const reminder=(prompt,id)=>llm.createUserMessage({content:[{type:'text',text:['[SCHEDULE REMINDER]','This is a scheduled message from the user',`schedule_id_json: ${JSON.stringify(id)}`,'occurrence_at: 2026-10-24T01:00:00.000Z',`reminder_prompt_json: ${JSON.stringify(prompt)}`].join('\n')}],source:{kind:'schedule'}});
  const context=llm.createUserMessage({content:[{type:'text',text:'current time'}],source:{kind:'runtime-context'}});
  // What the loop would enter (the claimed messages plus its runtime context), as the default next() hands it over.
  const step=messages=>agentEvents(ctx,agent).waterfall('agent/pre-step',{turn:1,step:1,signal,messages},async()=>({kind:'enter',messages:[...messages,context]}));
  const note=reminder('[便条] 问问小王面试结果','s1'),task=reminder('每天签到','s2');
  {const decision=await step([note]);const [own,clock]=decision.messages;
    assert.equal(decision.kind,'enter');assert.equal(own.id,note.id,'the delivery record still names it');assert.equal(own.role,'user');assert.deepEqual(own.source,{kind:'schedule'});assert.equal(clock,context);
    assert.match(own.content[0].text,/^\[SELF NOTE\]\nThis is a note you left for yourself earlier, not a message of the owner/);
    assert.match(own.content[0].text,/\nnote_id_json: "s1"\ndue_at: 2026-10-24T01:00:00\.000Z\nnote_json: "问问小王面试结果"$/);
    assert.doesNotMatch(own.content[0].text,/from the user|\[便条\]/);
    assert.ok(Object.isFrozen(own)&&Object.isFrozen(own.content)&&Object.isFrozen(own.content[0]));
    assert.match(note.content[0].text,/from the user/,'the delivered message object itself is untouched');}
  {const decision=await step([task]);const [kept]=decision.messages;
    assert.equal(kept.id,task.id);assert.deepEqual(kept.source,{kind:'schedule'});
    assert.match(kept.content[0].text,/^\[SELF NOTE\]\nThis is a note you wrote in this conversation while the owner was talking with you/,'what the owner asked for is a note too, kept in their words');
    assert.match(kept.content[0].text,/\nnote_json: "每天签到"$/);assert.doesNotMatch(kept.content[0].text,/from the user/);}
  const mine=llm.createUserMessage({content:[{type:'text',text:'帮我看看 QQ'}],source:{kind:'user'}});
  const hook=llm.createUserMessage({content:[{type:'text',text:'[NOTIFICATION TRIGGER]'}],source:{kind:'webhook',provider:'cowork-notify',source:'continuo',deliveryId:'d',ruleId:'cowork-notify'}});
  const other=llm.createUserMessage({content:[{type:'text',text:'x'}],source:{kind:'webhook',ruleId:'something-else'}});
  for(const messages of [[mine],[hook],[hook,mine],[other],[]])await step(messages);
  assert.deepEqual(priorities,[PRIORITY.scheduled,PRIORITY.scheduled,PRIORITY.owner,PRIORITY.notification,PRIORITY.owner,PRIORITY.scheduled,PRIORITY.scheduled]);
  // Making way: a notice steered into the running turn; it is no message of the owner, so the run stays unattended.
  const steered=[];agent.steer=m=>steered.push(m);agent.status='running';
  api.yieldTo(agent);
  assert.equal(steered.length,1);
  assert.match(steered[0].content[0].text,/^\[OWNER WAITING\]\nThe owner just gave you a task in another conversation and is waiting for the AI screen\./);
  assert.equal(steered[0].role,'user');assert.deepEqual({...steered[0].source},{kind:'cowork-screen',form:'notice',summary:'主人在等副屏：请尽快收尾，结束这一轮'});
  agent.status='idle';api.yieldTo(agent);assert.equal(steered.length,1,'a turn already over gets nothing');
  // Other runs waiting a while (task-lifecycle's nudge) get their own wording.
  agent.status='running';api.yieldTo(agent,'queue');
  assert.equal(steered.length,2);assert.match(steered[1].content[0].text,/^\[OTHERS WAITING\]\nOther runs \(a notification trigger or a due note\) have been waiting/);
  assert.equal(steered[1].source.summary,'有其他任务在等副屏：请尽快收尾');
  api.yieldTo(agent,'bogus');assert.equal(steered.length,2,'an unknown reason sends nothing');
  api.remove(agent);await child.dispose();await preset.dispose();
});
