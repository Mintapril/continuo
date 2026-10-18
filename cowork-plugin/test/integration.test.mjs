import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { installCowork, renderPhoneResult } from '../src/index.mjs';
import { ALLOWED_TOOLS } from '../src/policy.mjs';
import { defineTool, validateJsonSchemaValue, view, BlockAssembler, toolsSource } from './installed-seams.mjs';

const deps = { defineTool, scopeOf: ctx => ctx.scope, BlockAssembler, createUserMessage: value => value, proxyRouteFor: () => ({ proxied: false }) };
test('real installed defineTool validates all registrations; inherited tools hidden using actual DSH view implementation', async t => {
  const testRoot = fileURLToPath(new URL('./.tmp/', import.meta.url)); await fs.mkdir(testRoot, { recursive: true });
  const dir = await fs.mkdtemp(path.join(testRoot, 'integration-'));
  t.after(async () => { assert.ok(dir.startsWith(testRoot)); await fs.rm(dir, { recursive: true, force: true }); });
  const registrations = new Map(), listeners = new Map(), calls = [], sections = [], restrictions = [];
  let guard;
  const ctx = {
    scope: {}, tools: {
      restrict: () => { throw new Error('Preset standing scope must not install a mask'); },
      guard: value => { guard = value; calls.push('guard'); }, presentAs: value => calls.push(`presentAs:${value}`),
      register: value => { assert.equal(registrations.has(value.name), false); registrations.set(value.name, value); },
    },
    llm: { stream() { throw new Error('Installing plugin must never call a model'); } },
    attachments: { saveImage() { throw new Error('No device action in this test'); } },
    systemPrompt: { section: value => sections.push(value) },
    on: (name, cb) => listeners.set(name, cb),
  };
  let proxied = false;
  const api = await installCowork(ctx, {}, { ...deps, proxyRouteFor: () => ({ proxied }) }, { COWORK_FILES_ROOT: dir, COWORK_BRIDGE_TOKEN: 'test-only-123456789012345678901234567890' });
  assert.deepEqual(calls, ['guard', 'presentAs:native']);
  assert.deepEqual([...registrations.keys()].sort(), ALLOWED_TOOLS.filter(n => !n.startsWith('web_')).sort());
  for (const tool of registrations.values()) {
    assert.equal(validateJsonSchemaValue(tool.output.schema, {} , '').length, 0);
    assert.equal(tool.isConcurrencySafe({}), false);
  }
  assert.equal(sections[0].name, 'deployment:persona-prefix'); assert.equal(sections[0].interpolate, false);
  const agent = { id: 'agent-1', status: 'idle', session: { snapshotEvents: () => [], requestHeader: () => undefined }, ctx: { tools: { restrict: filter => { restrictions.push(filter); return () => {}; } } } };
  listeners.get('agent/created')({ agent });
  assert.deepEqual(restrictions, [{ allow: [...ALLOWED_TOOLS] }]);
  listeners.get('agent/pre-step')({ agent }, () => 'next'); assert.equal(restrictions.length, 1);
  const global = { tools: new Map([['pwsh', { name: 'pwsh' }], ['subagent', { name: 'subagent' }]]) };
  const preset = { tools: new Map([...registrations, ['web_search', { name: 'web_search' }], ['web_fetch', { name: 'web_fetch' }]]), admits: () => true };
  const child = { tools: new Map(), admits: name => restrictions[0].allow.includes(name) };
  const registry = { layers: { global, chainLayers: () => [preset, child], peek: () => child }, modeFor: () => 'native' };
  assert.deepEqual([...view.call(registry, agent).visible.keys()].sort(), [...ALLOWED_TOOLS].sort());
  assert.match(guard({ name: 'pwsh', agent, arguments: {} }), /denies/);
  assert.equal(guard({ name: 'web_fetch', agent, arguments: { url: 'https://example.com/' } }), undefined);
  proxied = true;
  assert.match(guard({ name: 'web_fetch', agent, arguments: { url: 'https://example.com/' } }), /direct public-network/);
  proxied = false;
  child.tools.set('mcp__escape', { name: 'mcp__escape' }); // Own registrations exempt from mask: guard must still deny.
  assert.ok(view.call(registry, agent).visible.has('mcp__escape'));
  assert.match(guard({ name: 'mcp__escape', agent, arguments: {} }), /denies/);
  const signal = new AbortController().signal;
  await assert.rejects(registrations.get('phone_tap').execute({ x: 1, y: 1 }, { agent, signal }), /invalid arguments/);
  await assert.rejects(registrations.get('phone_status').execute({ command: 'id' }, { agent, signal }), /Unexpected/);
  await registrations.get('cowork_write').execute({ path: 'note.md', content: 'OK' }, { agent, signal });
  assert.equal((await registrations.get('cowork_read').execute({ path: 'note.md' }, { agent, signal })).content, 'OK');
  await assert.rejects(registrations.get('cowork_write').execute({ path: 'config.json', content: 'no', sandbox_permissions: 'danger-full-access' }, { agent, signal }), /Unexpected/);
  await listeners.get('dispose')();
});
test('fails closed outside preset and when configuration/host APIs do not match', async () => {
  await assert.rejects(installCowork({}, {}, deps, {}), /inside an agent preset/);
  await assert.rejects(installCowork({ scope: {} }, { shell: true }, deps, {}), /no model-editable config/);
  await assert.rejects(installCowork({ scope: {}, tools: {} }, {}, deps, {}), /tools.restrict/);
});
test('image render uses canonical attachment block', async () => {
  const image = { attachmentId: 'a1', mediaType: 'image/png', bytes: 42, width: 1, height: 1 };
  const blocks = renderPhoneResult({}, { observationId: 'o1', image });
  assert.deepEqual(blocks[1], { type: 'image', attachment: image });
  assert.ok(!blocks[0].text.includes('attachmentId'));
});
test('preset patch is additive and registered package pins real 0.2.0-rc.2 peers', async () => {
  const patch = await fs.readFile(new URL('../cordis.patch.yml', import.meta.url), 'utf8');
  const pkg = JSON.parse(await fs.readFile(new URL('../package.json', import.meta.url), 'utf8'));
  assert.match(patch, /- insert:/); assert.match(patch, /name: '@deepseek-ai\/dsh-agent-preset'/); assert.match(patch, /id: cowork-phone/);
  assert.doesNotMatch(patch, /replace:|remove:|credentials:|apiKey:|provider:|tool-bash|tool-pwsh|tool-subagent|tool-mcp/);
  assert.equal(pkg.dsh.bundle.patch, './cordis.patch.yml'); assert.equal(pkg.peerDependencies['@deepseek-ai/dsh-tools'], '0.2.0-rc.2');
  // Compatibility assertions tied to unchanged installed implementation, not a fake allowlist registry.
  assert.match(toolsSource, /guardReason\(exec\)/); assert.match(toolsSource, /if \(this\.modeFor\(scope\) !== "native"\)/);
});
