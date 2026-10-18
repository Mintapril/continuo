import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {registerHooks} from 'node:module';
import {fileURLToPath} from 'node:url';
import {Context} from '../research/installed/dsh/node_modules/@deepseek-ai/cordis/lib/index.js';
import ToolRuntime from '../research/installed/dsh/node_modules/@deepseek-ai/dsh-tools/lib/index.js';
import * as scope from '../research/installed/dsh/node_modules/@deepseek-ai/dsh-scope/lib/index.js';
import * as production from '../src/restricted.mjs';

test('production async apply mounts through actual Cordis without returning an invalid effect',async()=>{
  // Only import resolution and the trusted config location are fixture seams.
  // Run the exported production apply through the unchanged Cordis plugin loader.
  const old=process.env.DSH_COWORK_BROKER_CONFIG;
  const dir=await fs.mkdtemp(path.join(os.tmpdir(),'cowork-activation-'));
  const file=path.join(dir,'broker.json');await fs.writeFile(file,JSON.stringify({agentSecret:'a'.repeat(48)}),{mode:0o600});
  process.env.DSH_COWORK_BROKER_CONFIG=file;
  const hooks=registerHooks({resolve(specifier,context,next){
    if(['@deepseek-ai/dsh-scope','@deepseek-ai/dsh-llm','@deepseek-ai/dsh-session'].includes(specifier))
      return {url:new URL(`../research/installed/dsh/node_modules/${specifier}/lib/index.js`,import.meta.url).href,shortCircuit:true};
    return next(specifier,context);
  }});
  const ctx=new Context();let calls=0,sections=0;
  ctx.provide('systemPrompt',{tools:()=>{},section:()=>{sections++;},getSectionOrder:()=>0});
  ctx.provide('attachments',{saveImage:async()=>{calls++;throw Error('No image I/O at activation');}});
  ctx.provide('web',{search:async()=>{calls++;throw Error('No web I/O at activation');}});
  ctx.provide('llm',{stream:()=>{calls++;throw Error('No model at activation');}});
  ctx.provide('agents',{get:()=>undefined,roots:()=>[]});ctx.provide('sessions',{});
  try{
    const tools=ctx.plugin(ToolRuntime,{mode:'native'});if(tools.inertia)await tools.inertia;
    let owner;const ownerFiber=ctx.inject(['tools','systemPrompt','attachments','web','llm','agents','sessions'],c=>{owner=c;});
    if(ownerFiber.inertia)await ownerFiber.inertia;
    const preset=scope.createScope(owner,{});
    const fiber=preset.ctx.plugin(production,{});
    await fiber.await();
    assert.equal(sections,1,'Production apply must actually reach policy installation');
    assert.equal(calls,0);
    await fiber.dispose();
    const bad=preset.ctx.plugin(async()=>({testApi:true}));
    await assert.rejects(bad.await(),/Invalid effect/);
    await bad.dispose();await preset.dispose();
  }finally{
    hooks.deregister();
    if(old===undefined)delete process.env.DSH_COWORK_BROKER_CONFIG;else process.env.DSH_COWORK_BROKER_CONFIG=old;
    // The fresh test-owned temp directory is verified before cleanup.
    assert.equal(path.dirname(file),dir);assert.ok(dir.startsWith(path.join(os.tmpdir(),'cowork-activation-')));
    await fs.rm(dir,{recursive:true});
  }
});
