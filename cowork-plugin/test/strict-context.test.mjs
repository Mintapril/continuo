import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import { registerHooks } from 'node:module';
import { PHONE_RESULT_PREFIX, PRUNED_MARK, KEEP_SCREENS, phoneResult, kindOf, prunePlan, SUMMARY_INSTRUCTION, compactAt } from '../src/context-plan.mjs';
import { renderPhoneResult } from '../src/restricted.mjs';

const phone = value => renderPhoneResult(value);
const tree = (title, n = 3) => ({ title, package: 'com.tencent.mobileqq', nodes: Array.from({ length: n }, (_, i) => ({ id: `n${i}`, text: `第${i}行`, bounds: [0, i, 10, i + 1] })),
  complete: true, advice: '', width: 1080, height: 2414, capturedAt: 1, observationId: `o-${title}` });
const tap = title => phone({ performed: true, changed: true, observation: tree(title) });
const shot = () => phone({ width: 1080, height: 2414, capturedAt: 1, observationId: 'o-shot', image: { attachmentId: 'sha256:x', mediaType: 'image/png', width: 1080, height: 2414, bytes: 9 } });
const page = () => phone({ performed: true, text: '一篇文章'.repeat(50), pages: 3, reachedEnd: false, title: 'Browser' });
const other = text => [{ type: 'text', text }];

test('phone results are recognised by the format restricted.mjs renders them in', () => {
  assert.deepEqual(phoneResult(tap('聊天')).observation.title, '聊天');
  assert.equal(phoneResult(other('{"a":1}')), undefined);
  assert.equal(phoneResult(other(`${PHONE_RESULT_PREFIX}not json`)), undefined);
  assert.equal(kindOf(tap('a')).kind, 'screen');
  assert.equal(kindOf(phone(tree('a'))).kind, 'screen');
  assert.equal(kindOf(shot()).kind, 'image');
  assert.equal(kindOf(page()).kind, 'page');
  assert.equal(kindOf(phone({ performed: true })).kind, 'other');
  assert.equal(kindOf(other('memories')).kind, 'other');
});

test('old screens give way to one line each; the last four screens and all after them stay word for word', () => {
  const entries = [
    { seq: 1, turn: 1, content: tap('首页') },
    { seq: 2, turn: 1, content: page() },
    { seq: 3, turn: 2, content: other('x'.repeat(20_000)) },
    { seq: 4, turn: 2, content: shot() },
    { seq: 5, turn: 2, content: phone({ performed: true }) },
    { seq: 6, turn: 2, content: tap('群聊') },
    { seq: 7, turn: 2, content: page() },
    { seq: 8, turn: 2, content: tap('聊天') },
    { seq: 9, turn: 2, content: tap('设置') },
    { seq: 10, turn: 2, content: tap('我的') },
  ];
  assert.equal(KEEP_SCREENS, 4);
  const plan = prunePlan(entries);
  assert.deepEqual(plan.map(p => p.seq), [1, 2, 4], 'screens before the last four, and page text of an earlier turn');
  const first = phoneResult(plan[0].content);
  assert.deepEqual(Object.keys(first).sort(), ['changed', 'performed', 'removed'], 'what the input did stays, the screen goes');
  assert.ok(first.removed.startsWith(PRUNED_MARK));
  assert.match(first.removed, /^\[older screen removed\] screen 首页 \/ com\.tencent\.mobileqq, 3 elements: observe again/);
  assert.equal(JSON.stringify(plan[0].content).includes('observationId'), false);
  assert.match(phoneResult(plan[1].content).removed, /page text of Browser, 200 characters/);
  assert.equal(phoneResult(plan[1].content).pages, 3);
  assert.equal(plan[2].content.length, 1, 'the screenshot image is dropped');
  assert.match(phoneResult(plan[2].content).removed, /screenshot/);
  // Pruned results are never pruned again, nor counted as screens.
  const again = entries.map(e => ({ ...e, content: plan.find(p => p.seq === e.seq)?.content ?? e.content }));
  assert.deepEqual(prunePlan(again), []);
  // Fewer screens than kept: nothing before the first screen is a screen, so nothing goes.
  assert.deepEqual(prunePlan([{ seq: 1, turn: 1, content: tap('a') }, { seq: 2, turn: 1, content: page() }]), []);
  assert.deepEqual(prunePlan([]), []);
  assert.deepEqual(prunePlan(undefined), []);
});

test('the last full screen stays while changes against it follow; a newer full one lets them all go', () => {
  const change = title => phone({ performed: true, changed: true, observation: { title, package: 'com.tencent.mobileqq', base: `o-${title}`, added: [tree('x', 1).nodes[0]],
    changed: [], removed: ['n2'], unchanged: 2, complete: true, advice: '', width: 1080, height: 2414, capturedAt: 1, observationId: 'd' } });
  assert.equal(kindOf(change('基准')).kind, 'screen');
  assert.equal(kindOf(change('基准')).full, false);
  const entries = [{ seq: 1, turn: 1, content: tap('旧') }, { seq: 2, turn: 1, content: phone(tree('基准')) },
    { seq: 3, turn: 1, content: change('基准') }, { seq: 4, turn: 1, content: change('基准') }, { seq: 5, turn: 1, content: change('基准') }];
  assert.deepEqual(prunePlan(entries).map(p => p.seq), [1], 'the base of the changes after it stays');
  entries.push({ seq: 6, turn: 1, content: tap('新') }, { seq: 7, turn: 1, content: change('新') }, { seq: 8, turn: 1, content: change('新') },
    { seq: 9, turn: 1, content: change('新') });
  const plan = prunePlan(entries);
  assert.deepEqual(plan.map(p => p.seq), [1, 2, 3, 4, 5]);
  assert.match(phoneResult(plan[2].content).removed, /change of screen 基准 \/ com\.tencent\.mobileqq: 1 added, 0 changed, 1 removed/);
});

test('the summary instruction asks for what a phone session must not lose', () => {
  for (const part of ['## 这个会话', '## 做过的事（不要重复）', '## 正在进行', '## 应用里的门道', '## 便条和待办', '## 现在', '简体中文',
    'untrusted data', '<compacted-summary>', 'do not call any tool', '继续操作前先 phone_ui_tree'])
    assert.ok(SUMMARY_INSTRUCTION.includes(part), part);
});

// The two DSH classes, extended against the installed DSH copy (research/, absent on a clean checkout).
const DSH = new URL('../research/installed/dsh/node_modules/', import.meta.url);
const PACKAGES = ['@deepseek-ai/dsh-compaction-basic', '@deepseek-ai/dsh-compaction-tool-result-pruner', '@deepseek-ai/dsh-llm'];
let hooks;
async function load() {
  hooks ??= registerHooks({ resolve(specifier, context, next) {
    if (PACKAGES.includes(specifier)) return { url: new URL(`${specifier}/lib/index.js`, DSH).href, shortCircuit: true };
    return next(specifier, context);
  } });
  const [{ default: CoworkPruner }, { default: CoworkCompaction }, { Context }] = await Promise.all([
    import('../src/context-pruner.mjs'), import('../src/context.mjs'), import(new URL('@deepseek-ai/cordis/lib/index.js', DSH).href)]);
  return { CoworkPruner, CoworkCompaction, Context };
}

function fakeSession(results) {
  const events = new Map(), appended = [];
  let next = 100;
  const nodes = results.map(([seq, turn, content]) => {
    events.set(seq, { type: 'tool/result', seq, data: { turn, step: 1, message: { role: 'tool', source: { kind: 'tool', callId: `c${seq}` }, toolCallId: `c${seq}`, content, isError: false, id: `m${seq}` } } });
    return seq;
  });
  const session = {
    surface: { nodes },
    eventAt: seq => events.get(seq),
    deriveEventMessage: event => event.data.message,
    append(type, data, options) {
      const seq = next++;
      appended.push({ type, data, options, seq });
      events.set(seq, { type, seq, data });
      if (options?.surfaceOp?.op === 'replace') nodes.splice(nodes.indexOf(options.surfaceOp.startSeq), 1, seq);
      return { seq };
    },
  };
  return { session, appended };
}

test('the pruner replaces old screens the way DSH\'s pruner replaces a result, trims only non-phone results', async () => {
  const { CoworkPruner, Context } = await load();
  const ctx = new Context();
  ctx.provide('tokenMeter', { estimateMessage: () => 42 });
  const pruner = new CoworkPruner(ctx, {});
  const bigScreen = phone({ ...tree('长列表', 400) });
  assert.ok(bigScreen[0].text.length > 8192);
  const { session, appended } = fakeSession([[1, 1, tap('首页')], [2, 1, shot()], [3, 1, other('y'.repeat(20_000))], [4, 2, tap('群聊')],
    [5, 2, tap('设置')], [6, 2, tap('我的')], [7, 2, bigScreen]]);
  const result = pruner.pruneSession(session);
  assert.deepEqual(result.pruned.map(p => p.originalSeq), [1, 2, 3]);
  assert.deepEqual(appended.map(a => a.type), ['compaction/prune', 'tool/result', 'compaction/prune', 'tool/result', 'compaction/prune', 'tool/result']);
  const [price, replacement] = appended;
  assert.deepEqual(price.data, { shadowedRange: { start: 1, end: 1 }, shadowedSeqs: [1], shadowedTokenCount: 42 });
  assert.deepEqual(replacement.options, { surfaceOp: { op: 'replace', startSeq: 1, endSeq: 1 }, sourceEventSeqs: [1] });
  assert.equal(replacement.data.turn, 1);
  assert.equal(replacement.data.message.toolCallId, 'c1');
  assert.match(phoneResult(replacement.data.message.content).removed, /^\[older screen removed\] screen 首页/);
  assert.ok(appended[5].data.message.content[0].text.length < 20_000, 'the oversized non-phone result is trimmed as DSH does');
  assert.equal(session.eventAt(session.surface.nodes.at(-1)).data.message.content, bigScreen, 'the latest screen is never cut');
  assert.ok(result.charsRemoved > 0);
  assert.deepEqual(pruner.pruneSession(session).pruned, [], 'a second pass changes nothing');
});

test('the engine asks the session\'s own model for the summary with Cowork\'s instruction', async () => {
  const { CoworkCompaction } = await load();
  let sent;
  const chunks = [{ type: 'block-start', index: 0, blockType: 'text' }, { type: 'text-delta', index: 0, text: '## 这个会话\n- 巡视' },
    { type: 'block-end', index: 0, block: { type: 'text', text: '## 这个会话\n- 巡视' } },
    { type: 'finish', reason: { kind: 'stop' } }];
  const self = { config: { summarizationProvider: '', summarizationModel: '', maxTokens: 16000 },
    ctx: { llm: { stream: options => { sent = options; return (async function* () { yield* chunks; })(); } } } };
  const agent = { options: {}, session: { id: 'cowork-app-patrol', requestHeader: () => ({ config: { provider: 'lithosai', model: 'deepseek-ai/DeepSeek-V4.1-Flash' } }), toolHistory: () => ({}) } };
  const input = { messages: [{ role: 'system', content: [{ type: 'text', text: 'persona' }] }], tools: [{ name: 'phone_tap' }] };
  let result;
  try { result = await CoworkCompaction.prototype.summarize.call(self, input, agent); }
  catch (error) { assert.fail(`chunk shapes of this DSH: ${error.message}`); }
  assert.equal(sent.provider, 'lithosai');
  assert.equal(sent.purpose, 'compaction');
  assert.equal(sent.maxTokens, 16000);
  assert.equal(sent.sessionId, 'cowork-app-patrol');
  assert.deepEqual(sent.tools, [{ name: 'phone_tap' }]);
  assert.equal(sent.messages.length, 2);
  assert.equal(sent.messages[1].content[0].text, SUMMARY_INSTRUCTION);
  assert.deepEqual(result.summary.map(b => b.text).join(''), '## 这个会话\n- 巡视');
  assert.equal(result.llmStreamCall, true);
  assert.equal(result.model, 'deepseek-ai/DeepSeek-V4.1-Flash');
});

test('compaction starts at half of a window under 500K and a quarter of a bigger one, at most once a turn', async () => {
  const { CoworkCompaction, Context } = await load();
  assert.equal(compactAt(1_048_576), 262_144);
  assert.equal(compactAt(1_000_000), 250_000);
  assert.equal(compactAt(200_000), 100_000);
  assert.equal(compactAt(499_999), 249_999);
  const base = Object.getPrototypeOf(CoworkCompaction.prototype);
  const original = base.compactIfNeeded;
  const calls = [];
  base.compactIfNeeded = async function (_agent, trigger) { calls.push(trigger); return { trigger }; };
  try {
    let tokens = 0, window = 1_048_576;
    const ctx = new Context();
    ctx.provide('llm', { resolveModelInfo: async (provider, model) => (provider === 'kimi' && model === 'k' ? { context: { contextWindow: window } } : {}) });
    ctx.provide('tokenMeter', { measure: () => ({ totalTokens: tokens }) });
    ctx.provide('sessions', {});
    const engine = new CoworkCompaction(ctx, { thresholdRatio: 0.25, retainRatio: 0.08, maxTokens: 32000, auto: false });
    const session = { id: 'cowork-owner', requestHeader: () => ({ config: { provider: 'kimi', model: 'k' } }) };
    const agent = { session };
    const turn = n => ctx.emit('session/event', session, { type: 'turn/start', data: { turn: n } });
    turn(1);
    tokens = 262_143;
    assert.equal(await engine.compactIfNeeded(agent, 'pressure'), null, 'under a quarter of the 1M window');
    tokens = 262_144;
    assert.deepEqual(await engine.compactIfNeeded(agent, 'pressure'), { trigger: 'pressure' }, 'from a quarter on, DSH\'s engine compacts');
    tokens = 900_000;
    assert.equal(await engine.compactIfNeeded(agent, 'pressure'), null, 'not again in the same turn');
    assert.deepEqual(await engine.compactIfNeeded(agent, 'context-overflow'), { trigger: 'context-overflow' }, 'a refused request still recovers');
    turn(2);
    assert.deepEqual(await engine.compactIfNeeded(agent, 'pressure'), { trigger: 'pressure' }, 'the next turn may compact again');
    turn(3);
    window = 200_000; tokens = 99_999;
    assert.equal(await engine.compactIfNeeded(agent, 'pressure'), null, 'a small window waits for half of it');
    tokens = 100_000;
    assert.deepEqual(await engine.compactIfNeeded(agent, 'pressure'), { trigger: 'pressure' });
    turn(4);
    const unknown = { session: { id: 'other', requestHeader: () => ({ config: { provider: 'x', model: 'y' } }) } };
    assert.deepEqual(await engine.compactIfNeeded(unknown, 'pressure'), { trigger: 'pressure' }, 'no known window: DSH\'s engine reports it as before');
    assert.deepEqual(calls, ['pressure', 'context-overflow', 'pressure', 'pressure', 'pressure']);
  } finally { base.compactIfNeeded = original; }
});

test('the preset\'s compaction config passes DSH\'s own validation', async () => {
  const { CoworkCompaction } = await load();
  const patch = await fs.readFile(new URL('../cordis.patch.yml', import.meta.url), 'utf8');
  const config = Object.fromEntries([...patch.matchAll(/^\s+(thresholdRatio|retainRatio|retainTokens|maxTokens): ([\d.]+)$/gm)].map(([, key, value]) => [key, Number(value)]));
  assert.deepEqual(config, { thresholdRatio: 0.25, retainRatio: 0.08, maxTokens: 32000 });
  assert.doesNotThrow(() => CoworkCompaction.Config(config));
});
