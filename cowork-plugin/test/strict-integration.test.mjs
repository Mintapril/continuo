import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import { Context } from '../research/installed/dsh/node_modules/@deepseek-ai/cordis/lib/index.js';
import ToolRuntime from '../research/installed/dsh/node_modules/@deepseek-ai/dsh-tools/lib/index.js';
import * as scope from '../research/installed/dsh/node_modules/@deepseek-ai/dsh-scope/lib/index.js';
import * as llm from '../research/installed/dsh/node_modules/@deepseek-ai/dsh-llm/lib/index.js';
import { Session } from '../research/installed/dsh/node_modules/@deepseek-ai/dsh-session/lib/index.js';
import { installRestrictedCowork, ALLOWED_TOOLS, CONTEXT_TOOLS, MEMORY_TOOLS, phoneTools } from '@local/dsh-cowork-phone';
import { tool,object } from '../src/validation.mjs';
import { createTaskLifecycle } from '../src/task-lifecycle.mjs';
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
  assert.deepEqual(pkg.files.filter(p=>p.startsWith('src/')).sort(),['src/restricted.mjs','src/validation.mjs','src/broker.mjs','src/auth.mjs','src/task-lifecycle.mjs','src/bounded-files.mjs','src/web.mjs'].sort());
  assert.match(patch,/- insert:/);assert.match(patch,/id: cowork-phone/);
  assert.doesNotMatch(patch,/replace:|remove:|apiKey:|credentials:|provider:|dsh-tool-web/);
  for(const version of Object.values(pkg.peerDependencies))assert.equal(version,'0.2.0-rc.2');
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