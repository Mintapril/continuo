import test from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { setImmediate as tick, setTimeout as delay } from 'node:timers/promises';
import { createTaskLifecycle } from '../src/task-lifecycle.mjs';

const ERROR = { message: 'Native task lifecycle unavailable' };
// A verified native snapshot that still shows a retained approval/display refuses the turn with an
// actionable message instead of the generic unavailable error: same fail-closed outcome, no begin.
const WAITING_USER = { message: /Cowork 原生任务尚未结束/ };
// Sticky-fault recovery may re-read native ground truth (a bodyless GET /v1/task-state) and, only
// when that verified snapshot advertises a pending command, settle it with exactly one ack. It never
// begins, polls or ends again, and never replays the mutation that failed.
function assertNoMutation(server, before, maxProbes, maxAcks = 0) {
  const after = server.calls.slice(before), writes = after.filter(call => call.body);
  const operations = writes.map(call => call.body.operation);
  assert.ok(operations.every(operation => operation === 'ack'), `unexpected native writes: ${operations.join(',')}`);
  assert.ok(operations.length <= maxAcks, `too many native writes: ${operations.join(',')}`);
  assert.ok(after.length <= maxProbes + maxAcks, `unbounded recovery traffic: ${after.length}`);
}
const secret = 'test-only-secret-never-cross-the-wire';
const defer = () => Promise.withResolvers();
const response = (value, status = 200) => ({ status, headers: { 'content-type': 'application/json' }, body: Buffer.from(JSON.stringify(value)) });
const start = (life, agent, turn = 1) => life.observe(agent, { type: 'turn/start', data: { turn } });
const end = (life, agent, kind = 'completed', turn = 1) => life.observe(agent, { type: 'turn/end', data: { turn, reason: { kind, error: 'PRIVATE MESSAGE' } } });
function agent(id = 'session-id') {
  const turnAbort = new AbortController();
  return { session: { id }, turnAbort, cancelled: 0, idleCalls: 0, emitting: false,
    cancel(cause) { assert.equal(this.emitting, false, 'cancel must not run in Session.append'); assert.deepEqual(cause, { kind: 'user' }); this.cancelled++; turnAbort.abort(); },
    async whenIdle() { this.idleCalls++; },
  };
}
function native() {
  const calls = [];
  const state = { instanceId: 'native-instance', executionId: '', taskId: '', revision: 0, state: 'idle', command: '', commandId: '' };
  const assign = next => { for (const key of Object.keys(state)) delete state[key]; Object.assign(state, next); };
  // Offline native-contract fixture only; no device operation or validation.
  const api = { calls, polls: 0, maxPolls: 0, pollGate: undefined, endGate: undefined, fail: undefined,
    active: false, workspaceOpen: false, pendingApproval: false, nativePaused: false, approvedActions: 0,
    state,
    control(command) { assign({ ...state, revision: state.revision + 1, state: command === 'pause' ? 'pausing' : 'stopping', command, commandId: randomUUID() }); return state.commandId; },
    reserveApproval() { api.workspaceOpen = api.pendingApproval = api.nativePaused = true; assign({ ...state, revision: state.revision + 1, state: 'waiting_user' }); },
    approve() { assert.equal(api.pendingApproval, true); api.pendingApproval = false; api.approvedActions++; api.nativePaused = !api.active; assign({ ...state, revision: state.revision + 1, state: api.active ? 'running' : 'waiting_user' }); },
    stopWorkspace() { assert.equal(api.active, false); api.workspaceOpen = api.pendingApproval = api.nativePaused = false; assign({ ...state, revision: state.revision + 1, state: 'stopped' }); },
    replace() { assign({ ...state, revision: state.revision + 1, executionId: randomUUID(), state: 'running', command: '', commandId: '' }); },
    async transport(options) {
      assert.equal(options.secret, secret); assert.equal(options.role, 'agent'); assert.equal(options.timeoutMs, 6000);
      assert.equal(options.maxResponseBytes, 16 * 1024); assert.equal(options.maxRequestBytes, 16 * 1024);
      assert.equal(options.path, options.method === 'GET' ? '/v1/task-state' : '/v1/task');
      const body = options.body.length ? JSON.parse(options.body) : undefined;
      calls.push({ method: options.method, body, signal: options.signal });
      if (api.fail) return api.fail(options);
      if (options.method === 'GET') { assert.equal(options.body.length, 0); return response(state); }
      if (!(body.operation === 'ack' && body.taskId === state.taskId && state.taskId !== 'session-id')) assert.equal(body.taskId, 'session-id');
      if (body.operation === 'begin') {
        assert.deepEqual(Object.keys(body).sort(), ['instanceId', 'operation', 'revision', 'taskId']);
        if (body.instanceId !== state.instanceId || body.revision !== state.revision || api.active || api.workspaceOpen) return response({ ok: false }, 409);
        api.active = true;
        assign({ ...state, taskId: body.taskId, executionId: randomUUID(), revision: state.revision + 1, state: 'running', command: '', commandId: '' });
        return response(state);
      }
      if (body.instanceId !== state.instanceId || body.executionId !== state.executionId || body.taskId !== state.taskId) return response({ ok: false }, 409);
      if (body.operation === 'poll') {
        api.polls++; api.maxPolls = Math.max(api.maxPolls, api.polls);
        try { if (api.pollGate) await api.pollGate.promise; return response(state); }
        finally { api.polls--; }
      }
      if (body.operation === 'end') {
        if (api.endGate) await api.endGate.promise;
        if (state.command) return response(state);
        assert.equal(api.active, true, 'end must not replay an already-settled execution');
        api.active = false;
        assign({ ...state, revision: state.revision + 1, state: api.pendingApproval || api.nativePaused ? 'waiting_user' : body.outcome });
        if (!api.pendingApproval) api.workspaceOpen = api.nativePaused = false;
        return response(state);
      }
      if (body.operation === 'ack') {
        assert.equal(body.commandId, state.commandId);
        api.active = api.workspaceOpen = api.pendingApproval = api.nativePaused = false;
        assign({ ...state, revision: state.revision + 1, state: state.command === 'pause' ? 'paused' : 'stopped', command: '', commandId: '' });
        return response(state);
      }
      throw Error('Unexpected operation');
    },
  };
  return api;
}
async function setup(t) {
  const server = native(), a = agent();
  const life = createTaskLifecycle({ secret, transport: options => server.transport(options) });
  t.after(async () => { life.dispose(); await life.flush().catch(() => {}); });
  start(life, a); await life.preStep(a, 1, a.turnAbort.signal);
  return { server, a, life };
}
function tool(life, a, callId = randomUUID(), turn = 1, signal = a.turnAbort.signal) {
  life.observe(a, { type: 'tool/call', data: { turn, callId } });
  return { agent: a, callId, signal };
}

for (const name of ['cowork.files.read', 'web_search']) test(`${name}: pre-step admits file/web-only tasks; tools never self-admit`, async t => {
  const server = native(), a = agent(), life = createTaskLifecycle({ secret, transport: options => server.transport(options) });
  t.after(async () => { life.dispose(); await life.flush(); });
  let ran = 0;
  await assert.rejects(life.runTool(a, { agent: a, signal: a.turnAbort.signal }, () => ran++), ERROR);
  start(life, a);
  const early = tool(life, a, 'early');
  await assert.rejects(life.runTool(a, early, () => ran++), ERROR);
  assert.equal(server.calls.length, 0);
  const admitted = await life.preStep(a, 1, a.turnAbort.signal);
  assert.equal(admitted.state, 'running'); assert.equal(admitted.taskId, a.session.id);
  assert.deepEqual(Object.keys(admitted).sort(), ['instanceId', 'executionId', 'taskId', 'revision', 'state', 'command', 'commandId'].sort());
  await assert.rejects(life.runTool(a, early, () => ran++), ERROR);
  const exec = { ...tool(life, a), name };
  assert.equal(await life.runTool(a, exec, value => { ran++; assert.notEqual(value.signal, exec.signal); return 'ok'; }), 'ok');
  await assert.rejects(life.runTool(a, exec, () => ran++), ERROR);
  assert.equal(ran, 1); assert.equal(server.calls.filter(c => c.body?.operation === 'begin').length, 1);
  assert.equal(server.calls.at(-1).body.operation, 'poll');
  assert.doesNotMatch(JSON.stringify(server.calls), /PRIVATE MESSAGE|user prompt/);
  end(life, a); await life.flush(a);
});

for (const [kind, outcome] of Object.entries({ completed: 'completed', aborted: 'stopped', error: 'error', blocked: 'waiting_user', 'max-tokens': 'waiting_user', 'tool-concluded': 'waiting_user', constructor: 'waiting_user' })) {
  test(`only turn/end maps ${kind} to ${outcome}, deferring native finish`, async t => {
    const { life, a, server } = await setup(t);
    life.observe(a, { type: 'turn/error', data: { turn: 1 } }); await tick();
    assert.equal(server.calls.some(c => c.body?.operation === 'end'), false);
    a.emitting = true; end(life, a, kind);
    assert.equal(server.calls.some(c => c.body?.operation === 'end'), false); a.emitting = false;
    const result = await life.flush(a);
    assert.equal(result.state, outcome); assert.equal(server.state.state, outcome);
    assert.equal(server.calls.find(c => c.body?.operation === 'end').body.outcome, outcome);
  });
}

test('a fresh process confirms one validated orphan stop, then begins', async t => {
  const server = native(), a = agent();
  Object.assign(server.state, { taskId: 'old-session', executionId: randomUUID(), revision: 4 });
  server.control('stop');
  const life = createTaskLifecycle({ secret, transport: options => server.transport(options) });
  t.after(async () => { life.dispose(); await life.flush().catch(() => {}); });
  start(life, a);
  const admitted = await life.preStep(a, 1, a.turnAbort.signal);
  assert.equal(server.calls[0].method, 'GET');
  assert.equal(server.calls[1].body.operation, 'ack');
  assert.equal(server.calls[1].body.taskId, 'old-session');
  assert.equal(server.calls.filter(c => c.body?.operation === 'ack').length, 1);
  assert.equal(server.state.state, 'running');
  assert.equal(admitted.taskId, a.session.id);
  assert.equal(server.calls.filter(c => c.body?.operation === 'begin').length, 1);
});

test('an orphan pause is settled once, then the turn begins', async t => {
  const server = native(), a = agent();
  Object.assign(server.state, { taskId: 'old-session', executionId: randomUUID(), revision: 4 });
  server.control('pause');
  const life = createTaskLifecycle({ secret, transport: options => server.transport(options) });
  t.after(async () => { life.dispose(); await life.flush().catch(() => {}); });
  start(life, a);
  const admitted = await life.preStep(a, 1, a.turnAbort.signal);
  assert.equal(server.calls.filter(c => c.body?.operation === 'ack').length, 1);
  assert.equal(server.state.state, 'running');
  assert.equal(admitted.taskId, a.session.id);
  assert.equal(server.calls.filter(c => c.body?.operation === 'begin').length, 1);
});

// A verified non-terminal snapshot without a command belongs to another owner: refuse this turn
// with the actionable state, never ack, never begin, and never poison the whole host with a fault.
for (const [label, mutate] of [
  ['running', state => ({ ...state, state: 'running' })],
  ['waiting_user', state => ({ ...state, state: 'waiting_user' })],
]) test(`an owned ${label} state refuses the turn without acking or beginning`, async t => {
  const server = native(), a = agent();
  Object.assign(server.state, { ...mutate({ taskId: 'old-session', executionId: randomUUID(), revision: 4 }), command: '', commandId: '' });
  const life = createTaskLifecycle({ secret, transport: options => server.transport(options) });
  t.after(async () => { life.dispose(); await life.flush().catch(() => {}); });
  start(life, a);
  await assert.rejects(life.preStep(a, 1, a.turnAbort.signal), { code: 'COWORK_TASK_BUSY' });
  assert.equal(server.calls.some(c => c.body?.operation === 'ack'), false);
  assert.equal(server.calls.some(c => c.body?.operation === 'begin'), false);
});

test('a session whose turn/start was never observed is still admitted', async t => {
  const server = native(), a = agent(), life = createTaskLifecycle({ secret, transport: options => server.transport(options) });
  t.after(async () => { life.dispose(); await life.flush().catch(() => {}); });
  const admitted = await life.preStep(a, 1, a.turnAbort.signal); // no start(): the notification was missed
  assert.equal(admitted.taskId, a.session.id);
  assert.equal(server.calls.filter(c => c.body?.operation === 'begin').length, 1);
  end(life, a); await life.flush(a);
  assert.equal(server.state.state, 'completed');
});

test('a task left behind by a missed turn/end does not block the next turn', async t => {
  const { life, a, server } = await setup(t); // turn 1 admitted
  const admitted = await life.preStep(a, 2, a.turnAbort.signal); // turn 2 starts with no turn/end for turn 1
  assert.equal(admitted.taskId, a.session.id);
  assert.equal(server.calls.filter(c => c.body?.operation === 'end').length, 1);
  assert.equal(server.calls.filter(c => c.body?.operation === 'begin').length, 2);
  assert.deepEqual(server.calls.filter(c => c.body?.operation === 'end').map(c => c.body.outcome), ['stopped']);
  end(life, a, 'completed', 2); await life.flush(a);
});

test('turn/start never acquires; end without a pre-step sends nothing', async () => {
  const server = native(), a = agent(), life = createTaskLifecycle({ secret, transport: options => server.transport(options) });
  start(life, a); end(life, a); await life.flush(a); life.dispose(); await life.flush();
  assert.equal(server.calls.length, 0);
});

for (const command of ['pause', 'stop']) test(`${command}: independent polling cancels, waits whenIdle AND every wrapper, then acknowledges exact ID`, async t => {
  const { life, a, server } = await setup(t);
  const idle = defer(), body = defer(); let toolSignal, bodyStarted = false;
  a.whenIdle = async () => { a.idleCalls++; await idle.promise; };
  const running = life.runTool(a, tool(life, a), async exec => { toolSignal = exec.signal; bodyStarted = true; await body.promise; return 'late'; });
  const rejected = assert.rejects(running, ERROR);
  await tick(); assert.equal(bodyStarted, true);
  const commandId = server.control(command);
  await delay(560);
  assert.equal(a.cancelled, 1); assert.equal(toolSignal.aborted, true); assert.equal(a.idleCalls, 1);
  assert.equal(server.calls.some(c => c.body?.operation === 'ack'), false);
  await assert.rejects(life.runTool(a, tool(life, a), () => assert.fail('body after control')), ERROR);
  idle.resolve(); await tick(); assert.equal(server.calls.some(c => c.body?.operation === 'ack'), false);
  a.emitting = true; end(life, a, 'aborted'); a.emitting = false;
  body.resolve(); await rejected; await life.flush(a);
  assert.equal(server.calls.find(c => c.body?.operation === 'ack').body.commandId, commandId);
  assert.equal(server.calls.some(c => c.body?.operation === 'end'), false);
  assert.equal(server.state.state, command === 'pause' ? 'paused' : 'stopped');
});

test('active exact approval waiting_user cancels asynchronously, drains idle AND wrappers, ends once, and retains approval', async t => {
  const { life, a, server } = await setup(t), idle = defer(), body = defer(); let combined;
  a.whenIdle = async () => { a.idleCalls++; await idle.promise; };
  const exec = tool(life, a, 'reserved-action');
  const running = life.runTool(a, exec, async value => { combined = value.signal; server.reserveApproval(); await body.promise; return 'USER_REQUIRED'; });
  const rejected = assert.rejects(running, ERROR);
  await tick(); assert.equal(server.active, true); assert.equal(server.state.state, 'waiting_user');
  await delay(560); // Independent polling discovers active waiting_user, without turn/end.
  assert.equal(a.cancelled, 1); assert.equal(combined.aborted, true); assert.equal(a.idleCalls, 1);
  assert.equal(server.calls.some(c => c.body?.operation === 'end'), false);
  idle.resolve(); await tick(); assert.equal(server.calls.some(c => c.body?.operation === 'end'), false);
  a.emitting = true; end(life, a, 'aborted'); a.emitting = false;
  body.resolve(); await rejected; const result = await life.flush(a);
  assert.equal(result.state, 'waiting_user'); assert.equal(server.active, false);
  assert.equal(server.pendingApproval, true); assert.equal(server.workspaceOpen, true);
  assert.deepEqual(server.calls.filter(c => c.body?.operation === 'end').map(c => c.body.outcome), ['waiting_user']);
  assert.equal(server.calls.some(c => c.body?.operation === 'ack'), false);
  await assert.rejects(life.runTool(a, exec, () => assert.fail('approved action replayed')), ERROR);
  server.approve(); assert.equal(server.approvedActions, 1); assert.equal(server.state.state, 'waiting_user');
  await assert.rejects(life.preStep(a, 1, new AbortController().signal), ERROR);
  start(life, a, 2); await assert.rejects(life.preStep(a, 2, new AbortController().signal), WAITING_USER);
  assert.equal(server.calls.filter(c => c.body?.operation === 'begin').length, 1);
  end(life, a, 'error', 2); await life.flush(a); server.stopWorkspace();
  start(life, a, 3); await life.preStep(a, 3, new AbortController().signal);
  assert.equal(server.calls.filter(c => c.body?.operation === 'begin').length, 2);
  end(life, a, 'completed', 3); await life.flush(a);
});

for (const command of ['pause', 'stop']) test(`waiting_user settlement honors racing ${command} only after idle AND wrapper bodies`, async t => {
  const { life, a, server } = await setup(t), idle = defer(), body = defer(); let combined;
  a.whenIdle = async () => { a.idleCalls++; await idle.promise; };
  const running = life.runTool(a, tool(life, a), async exec => { combined = exec.signal; await body.promise; return 'late'; });
  const rejected = assert.rejects(running, ERROR); await tick(); server.reserveApproval();
  await assert.rejects(life.preStep(a, 1, a.turnAbort.signal), ERROR);
  assert.equal(a.cancelled, 1); assert.equal(combined.aborted, true);
  const commandId = server.control(command);
  idle.resolve(); await tick(); assert.equal(server.calls.some(c => c.body?.operation === 'ack'), false);
  a.emitting = true; end(life, a, 'aborted'); a.emitting = false;
  body.resolve(); await rejected; await life.flush(a);
  assert.deepEqual(server.calls.filter(c => c.body?.operation === 'ack').map(c => c.body.commandId), [commandId]);
  assert.equal(server.calls.some(c => c.body?.operation === 'end'), false);
  assert.equal(server.state.state, command === 'pause' ? 'paused' : 'stopped');
});

test('command racing waiting_user end is acknowledged after prior local settlement', async t => {
  const { life, a, server } = await setup(t); server.reserveApproval(); server.endGate = defer();
  await assert.rejects(life.preStep(a, 1, a.turnAbort.signal), ERROR); await tick();
  assert.equal(server.calls.at(-1).body.operation, 'end'); assert.equal(a.idleCalls, 1);
  const commandId = server.control('stop'); server.endGate.resolve(); await life.flush(a);
  assert.equal(server.calls.at(-1).body.commandId, commandId); assert.equal(server.state.state, 'stopped');
});

test('exact approval during cancellation never resumes or replays the captured turn', async t => {
  const { life, a, server } = await setup(t), idle = defer();
  a.whenIdle = () => idle.promise; server.reserveApproval();
  await assert.rejects(life.preStep(a, 1, a.turnAbort.signal), ERROR);
  server.approve(); assert.equal(server.state.state, 'running');
  idle.resolve(); const result = await life.flush(a);
  assert.equal(result.state, 'waiting_user'); assert.equal(server.approvedActions, 1);
  assert.equal(server.calls.at(-1).body.outcome, 'waiting_user');
  await assert.rejects(life.runTool(a, tool(life, a), () => assert.fail('no resumed tools')), ERROR);
});

for (const kind of ['completed', 'aborted', 'error']) test(`native end waiting_user overrides ${kind} without false completion or sticky fault`, async t => {
  const { life, a, server } = await setup(t); server.endGate = defer();
  end(life, a, kind); await tick();
  server.nativePaused = server.workspaceOpen = true; server.endGate.resolve();
  const result = await life.flush(a);
  assert.equal(result.state, 'waiting_user'); assert.equal(a.cancelled, 0); assert.equal(server.active, false);
  assert.equal(server.calls.filter(c => c.body?.operation === 'end').length, 1);
  start(life, a, 2); await assert.rejects(life.preStep(a, 2, new AbortController().signal), WAITING_USER);
  end(life, a, 'error', 2); await life.flush(a); server.stopWorkspace();
  start(life, a, 3); await life.preStep(a, 3, new AbortController().signal);
  end(life, a, 'completed', 3); await life.flush(a);
});

test('pause upgraded to stop while cancellation drains ACKs only the current command ID', async t => {
  const { life, a, server } = await setup(t), idle = defer(), body = defer();
  a.whenIdle = () => idle.promise;
  const running = life.runTool(a, tool(life, a), () => body.promise); const rejected = assert.rejects(running, ERROR); await tick();
  const stale = server.control('pause');
  await assert.rejects(life.preStep(a, 1, a.turnAbort.signal), ERROR);
  idle.resolve(); await tick(); const current = server.control('stop');
  assert.notEqual(current, stale); assert.equal(server.calls.some(c => c.body?.operation === 'ack'), false);
  body.resolve(); await rejected; await life.flush(a);
  assert.deepEqual(server.calls.filter(c => c.body?.operation === 'ack').map(c => c.body.commandId), [current]);
  assert.equal(server.state.state, 'stopped'); assert.equal(a.cancelled, 1);
});

test('waiting_user seen in wrapper admission never runs its body or waits on its own promise', async t => {
  const { life, a, server } = await setup(t); server.reserveApproval();
  await assert.rejects(life.runTool(a, tool(life, a), () => assert.fail('waiting approval cannot run a tool')), ERROR);
  const result = await life.flush(a); assert.equal(result.state, 'waiting_user');
  assert.equal(server.active, false); assert.equal(a.cancelled, 1); assert.equal(a.idleCalls, 1);
});

test('waiting_user found by deferred turn/end performs no synchronous observer I/O or cancel', async t => {
  const { life, a, server } = await setup(t); server.reserveApproval(); const before = server.calls.length;
  a.emitting = true; end(life, a); assert.equal(server.calls.length, before); assert.equal(a.cancelled, 0); a.emitting = false;
  await life.flush(a); assert.equal(server.state.state, 'waiting_user'); assert.equal(a.cancelled, 1);
  assert.equal(server.calls.at(-1).body.outcome, 'waiting_user');
});

test('late waiting_user response cannot cancel a replacement turn and blocks its admission until old settlement', async t => {
  const { life, a, server } = await setup(t), idle = defer(); server.pollGate = defer(); a.whenIdle = () => idle.promise;
  const old = life.preStep(a, 1, a.turnAbort.signal), oldRejected = assert.rejects(old, ERROR); await tick();
  server.reserveApproval(); end(life, a); start(life, a, 2);
  const next = life.preStep(a, 2, new AbortController().signal), nextRejected = assert.rejects(next, ERROR);
  server.pollGate.resolve(); await oldRejected; await nextRejected;
  assert.equal(a.cancelled, 0); assert.equal(server.calls.some(c => c.body?.operation === 'end'), false);
  end(life, a, 'error', 2); idle.resolve(); await life.flush(a);
  assert.equal(server.state.state, 'waiting_user'); assert.equal(server.active, false);
  assert.equal(server.calls.filter(c => c.body?.operation === 'begin').length, 1);
});

test('finish already draining a wrapper joins concurrent waiting_user settlement without duplicate end', async t => {
  const { life, a, server } = await setup(t), idle = defer(), body = defer(); a.whenIdle = () => idle.promise;
  const running = life.runTool(a, tool(life, a), () => body.promise), rejected = assert.rejects(running, ERROR); await tick();
  server.pollGate = defer(); const polling = life.preStep(a, 1, a.turnAbort.signal), pollingRejected = assert.rejects(polling, ERROR); await tick();
  end(life, a); await tick(); server.reserveApproval(); server.pollGate.resolve(); await pollingRejected;
  body.resolve(); await rejected; await tick();
  assert.equal(server.calls.some(c => c.body?.operation === 'end'), false);
  idle.resolve(); await life.flush(a);
  assert.equal(server.calls.filter(c => c.body?.operation === 'end').length, 1); assert.equal(server.active, false);
});

test('failed local idle proof never settles native waiting_user; the next turn sees the actionable state', async t => {
  const { life, a, server } = await setup(t); a.whenIdle = async () => { throw Error('PRIVATE idle failure'); }; server.reserveApproval();
  await assert.rejects(life.preStep(a, 1, a.turnAbort.signal), ERROR); await assert.rejects(life.flush(a), ERROR);
  assert.equal(server.calls.some(c => ['ack', 'end'].includes(c.body?.operation)), false); assert.equal(server.active, true);
  const count = server.calls.length; end(life, a, 'aborted'); start(life, a, 2);
  await assert.rejects(life.preStep(a, 2, new AbortController().signal), WAITING_USER); assertNoMutation(server, count, 2);
});

test('409 after final poll: no blind end; recovery settles the pending command at most once', async t => {
  const { life, a, server } = await setup(t); server.control('pause');
  server.fail = async options => {
    const request = JSON.parse(options.body);
    if (request.operation === 'ack') { server.control('stop'); return response({ ok: false, error: { code: 'EXECUTION_STALE', message: 'PRIVATE' } }, 409); }
    return response(server.state);
  };
  await assert.rejects(life.preStep(a, 1, a.turnAbort.signal), ERROR); await assert.rejects(life.flush(a), ERROR);
  assert.equal(server.calls.filter(c => c.body?.operation === 'ack').length, 1);
  assert.equal(server.calls.some(c => c.body?.operation === 'end'), false);
  const count = server.calls.length; end(life, a, 'aborted'); start(life, a, 2);
  await assert.rejects(life.preStep(a, 2, new AbortController().signal), ERROR); assertNoMutation(server, count, 1, 1);
});

test('poll is bounded single-flight even while tool and pre-step callers join it', async t => {
  const { life, a, server } = await setup(t);
  server.pollGate = defer();
  const one = life.preStep(a, 1, a.turnAbort.signal);
  const two = life.runTool(a, tool(life, a), () => 2);
  await delay(1050);
  assert.equal(server.polls, 1); assert.equal(server.maxPolls, 1);
  server.pollGate.resolve(); assert.equal((await one).state, 'running'); assert.equal(await two, 2);
});

test('serializes old native end before acquiring next turn', async t => {
  const { life, a, server } = await setup(t);
  server.endGate = defer(); const oldExecution = server.state.executionId;
  end(life, a); start(life, a, 2);
  const next = life.preStep(a, 2, new AbortController().signal);
  await tick(); assert.equal(server.calls.at(-1).body.operation, 'end');
  assert.equal(server.calls.filter(c => c.body?.operation === 'begin').length, 1);
  server.endGate.resolve(); const result = await next;
  assert.notEqual(result.executionId, oldExecution);
  assert.deepEqual(server.calls.map(c => c.body?.operation ?? 'GET'), ['GET', 'begin', 'poll', 'end', 'GET', 'begin']);
  await assert.rejects(life.runTool(a, tool(life, a, 'old-call', 1), () => assert.fail()), ERROR);
  end(life, a, 'completed', 2); await life.flush(a);
});

test('late command for old turn never cancels replacement turn; its barrier exits so whenIdle can settle', async t => {
  const { life, a, server } = await setup(t);
  server.pollGate = defer(); const idle = defer();
  a.whenIdle = () => idle.promise;
  const oldPoll = life.preStep(a, 1, a.turnAbort.signal);
  const oldRejected = assert.rejects(oldPoll, ERROR);
  await tick(); server.control('pause'); end(life, a); start(life, a, 2);
  const next = life.preStep(a, 2, new AbortController().signal);
  const nextRejected = assert.rejects(next, ERROR);
  server.pollGate.resolve(); await oldRejected; await nextRejected;
  assert.equal(a.cancelled, 0, 'old identity cannot cancel a replacement turn');
  end(life, a, 'error', 2); idle.resolve(); await life.flush(a);
  assert.equal(server.calls.filter(c => c.body?.operation === 'begin').length, 1);
  assert.equal(server.state.state, 'paused');
});

test('remove during late response cancels only captured old agent and prevents replacement overlap', async t => {
  const { life, a, server } = await setup(t);
  server.pollGate = defer(); const polling = life.preStep(a, 1, a.turnAbort.signal);
  const rejected = assert.rejects(polling, ERROR); await tick();
  const replacement = agent();
  a.emitting = true; life.remove(a); assert.equal(a.cancelled, 0); a.emitting = false;
  start(life, replacement);
  const next = life.preStep(replacement, 1, replacement.turnAbort.signal);
  const nextRejected = assert.rejects(next, ERROR);
  server.control('stop'); server.pollGate.resolve();
  await rejected; await nextRejected; await life.flush(a);
  assert.equal(a.cancelled, 1); assert.equal(replacement.cancelled, 0);
  assert.equal(server.calls.filter(c => c.body?.operation === 'begin').length, 1);
});

test('control appearing between finish poll and end is acknowledged, not falsely completed', async t => {
  const { life, a, server } = await setup(t);
  server.endGate = defer(); end(life, a); await tick(); server.control('stop'); server.endGate.resolve();
  await life.flush(a); assert.equal(server.state.state, 'stopped'); assert.equal(a.cancelled, 1);
  assert.equal(server.calls.at(-1).body.operation, 'ack');
});

for (const failure of ['disconnect', '409', 'ok:false', 'malformed', 'oversized', 'extra', 'duplicate', 'wrong-identity']) test(`${failure}: sanitized fail-closed, no retry or replacement begin`, async t => {
  const { life, a, server } = await setup(t);
  const good = server.state;
  server.fail = async () => {
    if (failure === 'disconnect') throw Error('PRIVATE MESSAGE socket bearer secret');
    if (failure === '409') return response({ ok: false, error: { code: 'NATIVE_PRIVATE' } }, 409);
    if (failure === 'ok:false') return response({ ok: false, error: { code: 'NATIVE_PRIVATE' } });
    if (failure === 'malformed') return { ...response(good), body: Buffer.from('{PRIVATE MESSAGE') };
    if (failure === 'oversized') return { ...response(good), body: Buffer.alloc(16 * 1024 + 1, 32) };
    if (failure === 'extra') return response({ ...good, private: 'PRIVATE MESSAGE' });
    if (failure === 'duplicate') return { ...response(good), body: Buffer.from(JSON.stringify(good).replace('{', '{"revision":0,')) };
    return response({ ...good, executionId: randomUUID(), command: 'pause', commandId: 'cmd', state: 'pausing' });
  };
  await assert.rejects(life.runTool(a, tool(life, a), () => assert.fail('native failed')), ERROR);
  assert.equal(a.cancelled, 1);
  const count = server.calls.length, replacement = agent(); start(life, replacement);
  await assert.rejects(life.preStep(replacement, 1, replacement.turnAbort.signal), ERROR);
  await assert.rejects(life.preStep(a, 1, a.turnAbort.signal), ERROR);
  end(life, a, 'aborted'); await assert.rejects(life.flush(a), ERROR);
  assertNoMutation(server, count, 2, 2); assert.equal(replacement.cancelled, 0);
});

test('uncertain begin is never replayed; while native is unreachable the next turn fails without new begins', async t => {
  const server = native(), a = agent();
  const life = createTaskLifecycle({ secret, transport: async options => {
    const result = await server.transport(options);
    if (options.method === 'POST') throw Error('lost signed begin response');
    return result;
  } });
  t.after(async () => { life.dispose(); await life.flush().catch(() => {}); });
  start(life, a); await assert.rejects(life.preStep(a, 1, a.turnAbort.signal), ERROR);
  assert.equal(server.state.state, 'running');
  end(life, a, 'aborted'); await assert.rejects(life.flush(a), ERROR);
  const replacement = agent(); start(life, replacement);
  await assert.rejects(life.preStep(replacement, 1, replacement.turnAbort.signal), ERROR);
  // begin is sent once; recovery only tries to retire the abandoned execution (idempotent end, one retry).
  assert.deepEqual(server.calls.map(c => c.body?.operation ?? 'GET').filter(operation => operation !== 'GET'), ['begin', 'end', 'end']);
  assert.ok(server.calls.filter(c => c.body?.operation === 'end').every(c => c.body.outcome === 'stopped'));
});

test('after an uncertain begin the same session retires its abandoned execution and begins fresh', async t => {
  const server = native(), a = agent(); let lose = true;
  const life = createTaskLifecycle({ secret, transport: async options => {
    const result = await server.transport(options);
    if (lose && options.method === 'POST') { lose = false; throw Error('lost signed begin response'); }
    return result;
  } });
  t.after(async () => { life.dispose(); await life.flush().catch(() => {}); });
  start(life, a); await assert.rejects(life.preStep(a, 1, a.turnAbort.signal), ERROR);
  const abandoned = server.state.executionId; assert.equal(server.state.state, 'running');
  end(life, a, 'aborted'); await life.flush(a).catch(() => {});
  start(life, a, 2);
  const admitted = await life.preStep(a, 2, new AbortController().signal);
  assert.equal(admitted.state, 'running'); assert.notEqual(admitted.executionId, abandoned);
  assert.deepEqual(server.calls.map(c => c.body?.operation ?? 'GET'), ['GET', 'begin', 'GET', 'end', 'GET', 'begin']);
  end(life, a, 'completed', 2); assert.equal((await life.flush(a)).state, 'completed');
});

test('a fault left by another session never retires that session\'s live execution', async t => {
  const server = native(), a = agent(); let lose = true;
  const life = createTaskLifecycle({ secret, transport: async options => {
    const result = await server.transport(options);
    if (lose && options.method === 'POST') { lose = false; throw Error('lost signed begin response'); }
    return result;
  } });
  t.after(async () => { life.dispose(); await life.flush().catch(() => {}); });
  start(life, a); await assert.rejects(life.preStep(a, 1, a.turnAbort.signal), ERROR);
  // Native now runs an execution this host never began (another session/host owns it).
  Object.assign(server.state, { taskId: 'foreign-session', executionId: randomUUID(), revision: server.state.revision + 1 });
  end(life, a, 'aborted'); await life.flush(a).catch(() => {}); start(life, a, 2);
  await assert.rejects(life.preStep(a, 2, new AbortController().signal), { code: 'COWORK_TASK_BUSY' });
  assert.equal(server.calls.some(c => c.body?.operation === 'end'), false); assert.equal(server.state.taskId, 'foreign-session');
});
test('combined tool signal also respects caller abort and waits for body settlement', async t => {
  const { life, a } = await setup(t); const caller = new AbortController(), body = defer(); let combined;
  const promise = life.runTool(a, tool(life, a, 'caller', 1, caller.signal), async exec => { combined = exec.signal; await body.promise; return 1; });
  const rejected = assert.rejects(promise, ERROR); await tick(); caller.abort();
  assert.equal(combined.aborted, true); assert.equal(a.cancelled, 0);
  body.resolve(); await rejected;
});

test('skipped/resulted call IDs cannot later execute, even within the same admitted turn', async t => {
  const { life, a } = await setup(t); const exec = tool(life, a, 'skipped');
  life.observe(a, { type: 'tool/result', data: { turn: 1, message: { callId: exec.callId } } });
  await assert.rejects(life.runTool(a, exec, () => assert.fail('already settled call')), ERROR);
});

test('admission canceled while GET is pending never sends begin', async t => {
  const server = native(), a = agent(), gate = defer();
  const life = createTaskLifecycle({ secret, transport: async options => { const result = await server.transport(options); await gate.promise; return result; } });
  t.after(async () => { life.dispose(); await life.flush(); });
  start(life, a); const admission = life.preStep(a, 1, a.turnAbort.signal); const rejected = assert.rejects(admission, ERROR);
  await tick(); a.turnAbort.abort(); end(life, a, 'aborted'); gate.resolve(); await rejected; await life.flush(a);
  assert.deepEqual(server.calls.map(c => c.body?.operation ?? 'GET'), ['GET']);
});

test('removal while begin is pending cleans up exact returned execution without admitting tools', async t => {
  const server = native(), a = agent(), gate = defer();
  const life = createTaskLifecycle({ secret, transport: async options => {
    const result = await server.transport(options);
    if (options.method === 'POST' && JSON.parse(options.body).operation === 'begin') await gate.promise;
    return result;
  } });
  t.after(async () => { life.dispose(); await life.flush(); });
  start(life, a); const admission = life.preStep(a, 1, a.turnAbort.signal); const rejected = assert.rejects(admission, ERROR);
  await tick(); life.remove(a); gate.resolve(); await rejected; await life.flush(a);
  assert.equal(a.cancelled, 1); assert.equal(server.state.state, 'stopped');
  assert.deepEqual(server.calls.map(c => c.body?.operation ?? 'GET'), ['GET', 'begin', 'poll', 'end']);
});

test('normal finish waits active wrapper settlement before closing the native display', async t => {
  const { life, a, server } = await setup(t), body = defer();
  const running = life.runTool(a, tool(life, a), () => body.promise); await tick();
  end(life, a); await tick(); assert.equal(server.calls.some(c => c.body?.operation === 'end'), false);
  body.resolve('done'); await running; await life.flush(a); assert.equal(server.state.state, 'completed');
});

test('transport timeout aborts the request and late success cannot revive the lease', async t => {
  const { life, a, server } = await setup(t); const never = defer(); let requestSignal;
  const good = server.state;
  server.fail = async options => { requestSignal = options.signal; return never.promise; };
  const keepAlive = delay(12500); // Timeout x2 (one idempotent retry); keep the offline injected transport alive too.
  const started = performance.now();
  await assert.rejects(life.preStep(a, 1, a.turnAbort.signal), ERROR);
  assert.ok(performance.now() - started >= 11900); assert.equal(requestSignal.aborted, true); assert.equal(a.cancelled, 1);
  const count = server.calls.length;
  never.resolve(response(good)); await tick();
  await assert.rejects(life.preStep(a, 1, new AbortController().signal), ERROR);
  // The late snapshot never revives the failed lease: no begin and no poll; only retirement of its own execution.
  assert.ok(server.calls.slice(count).every(c => !c.body || (c.body.operation === 'end' && c.body.outcome === 'stopped')));
  assert.equal(server.calls.filter(c => c.body?.operation === 'begin').length, 1); await keepAlive;
});

test('end immediately followed by removal preserves completed outcome and does not cancel closed task', async t => {
  const { life, a, server } = await setup(t);
  end(life, a); await life.flush(a); life.remove(a); await life.flush(a);
  assert.equal(a.cancelled, 0); assert.equal(server.state.state, 'completed');
});

for (const mutation of [
  value => ({ ...value, instanceId: ' ' }), value => ({ ...value, taskId: ' ' }),
  value => ({ ...value, executionId: 'not-native-uuid' }), value => ({ ...value, revision: -1 }),
  value => ({ ...value, revision: 0.5 }), value => ({ ...value, state: 'unknown' }),
  value => ({ ...value, command: 'resume' }), value => ({ ...value, commandId: 'orphan' }),
  value => ({ ...value, command: 'pause', commandId: ' ', state: 'pausing' }),
]) test('invalid native snapshot fields fail closed without reflecting data', async t => {
  const { life, a, server } = await setup(t); const invalid = mutation(server.state);
  server.fail = async () => response(invalid);
  await assert.rejects(life.preStep(a, 1, a.turnAbort.signal), ERROR); assert.equal(a.cancelled, 1);
});

test('exact superseded pause ACK accepts one newer stop snapshot and settles stronger command', async t => {
  const { life, a, server } = await setup(t); const sent = [];
  const pauseId = server.control('pause'); let stopId;
  server.fail = async options => {
    const body = options.body.length ? JSON.parse(options.body) : undefined;
    if (body?.operation === 'ack') {
      sent.push(body.commandId);
      if (body.commandId === pauseId) { stopId = server.control('stop'); return response(server.state); }
      assert.equal(body.commandId, stopId); server.fail = undefined; return server.transport(options);
    }
    return response(server.state);
  };
  await assert.rejects(life.preStep(a, 1, a.turnAbort.signal), ERROR); await life.flush(a);
  assert.deepEqual(sent, [pauseId, stopId]); assert.equal(server.state.state, 'stopped'); assert.equal(a.cancelled, 1);
});
for (const change of ['same-id', 'same-revision', 'same-command', 'wrong-execution', 'second-upgrade']) test(`superseded ACK rejects ${change} without unbounded retry`, async t => {
  const { life, a, server } = await setup(t); let acks = 0;
  server.control('pause'); const initial = server.state;
  server.fail = async options => {
    const body = options.body.length ? JSON.parse(options.body) : undefined;
    if (body?.operation !== 'ack') return response(initial);
    acks++;
    const next = { ...initial, state: 'stopping', command: 'stop', commandId: randomUUID(), revision: initial.revision + 1 };
    if (change === 'same-id') next.commandId = initial.commandId;
    if (change === 'same-revision') next.revision = initial.revision;
    if (change === 'same-command') { next.state = 'pausing'; next.command = 'pause'; }
    if (change === 'wrong-execution') next.executionId = randomUUID();
    return response(next);
  };
  await assert.rejects(life.preStep(a, 1, a.turnAbort.signal), ERROR); await assert.rejects(life.flush(a), ERROR);
  assert.equal(acks, change === 'second-upgrade' ? 2 : 1);
  await assert.rejects(life.preStep(a, 1, new AbortController().signal), ERROR);
});

test('default transport is authenticatedRequest and missing secret rejects before network', async () => {
  const life = createTaskLifecycle(), a = agent(); start(life, a);
  await assert.rejects(life.preStep(a, 1, a.turnAbort.signal), ERROR);
  assert.equal(a.cancelled, 1); life.dispose(); await assert.rejects(life.flush(), ERROR);
});

test('exact signed CONTROLLER_OFFLINE refusal of begin is actionable, not sticky; next turn begins once controller returns', async t => {
  const server = native(), a = agent(); let offline = true;
  const life = createTaskLifecycle({ secret, offlineWaitMs: 0, transport: options => {
    const body = options.body.length ? JSON.parse(options.body) : undefined;
    if (offline && body?.operation === 'begin') { server.calls.push({ method: options.method, body }); return response({ ok: false, error: { code: 'CONTROLLER_OFFLINE', message: 'Open Cowork controls before starting' } }, 409); }
    return server.transport(options);
  } });
  t.after(async () => { life.dispose(); await life.flush().catch(() => {}); });
  start(life, a);
  await assert.rejects(life.preStep(a, 1, a.turnAbort.signal), error => error.code === 'COWORK_CONTROLLER_OFFLINE' && /控制器无法自动启动/.test(error.message) && !/PRIVATE|secret/.test(error.message));
  assert.equal(server.active, false); assert.equal(a.cancelled, 0);
  end(life, a, 'error'); await life.flush(a);
  assert.deepEqual(server.calls.map(c => c.body?.operation ?? 'GET'), ['GET', 'begin']);
  offline = false; start(life, a, 2);
  const admitted = await life.preStep(a, 2, new AbortController().signal);
  assert.equal(admitted.state, 'running');
  assert.equal(server.calls.filter(c => c.body?.operation === 'begin').length, 2);
  end(life, a, 'completed', 2); await life.flush(a);
});

test('stale controller is woken natively: begin is retried within the wait window and then admitted without sticky fault', async t => {
  const server = native(), a = agent(); let refusals = 2, clock = 0; const sleeps = [];
  const life = createTaskLifecycle({ secret, now: () => clock, sleep: async ms => { sleeps.push(ms); clock += ms; }, transport: options => {
    const body = options.body.length ? JSON.parse(options.body) : undefined;
    if (refusals > 0 && body?.operation === 'begin') { refusals--; return response({ ok: false, error: { code: 'CONTROLLER_OFFLINE', message: 'Controller is starting' } }, 409); }
    return server.transport(options);
  } });
  t.after(async () => { life.dispose(); await life.flush().catch(() => {}); });
  start(life, a);
  const admitted = await life.preStep(a, 1, a.turnAbort.signal);
  assert.equal(admitted.state, 'running'); assert.equal(sleeps.length, 2); assert.equal(refusals, 0);
  end(life, a, 'completed'); await life.flush(a);
});

test('controller that never returns gives the actionable error after the bounded wait', async t => {
  const server = native(), a = agent(); let clock = 0, begins = 0;
  const life = createTaskLifecycle({ secret, offlineWaitMs: 3000, now: () => clock, sleep: async ms => { clock += ms; }, transport: options => {
    const body = options.body.length ? JSON.parse(options.body) : undefined;
    if (body?.operation === 'begin') { begins++; return response({ ok: false, error: { code: 'CONTROLLER_OFFLINE', message: 'Controller is starting' } }, 409); }
    return server.transport(options);
  } });
  t.after(async () => { life.dispose(); await life.flush().catch(() => {}); });
  start(life, a);
  await assert.rejects(life.preStep(a, 1, a.turnAbort.signal), error => error.code === 'COWORK_CONTROLLER_OFFLINE');
  assert.equal(begins, 3); assert.equal(server.active, false);
});
for (const [name, reply] of [
  ['other native code', () => response({ ok: false, error: { code: 'SESSION_BUSY', message: 'x' } }, 409)],
  ['extra fields', () => response({ ok: false, error: { code: 'CONTROLLER_OFFLINE', message: 'x' }, leak: 1 }, 409)],
  ['wrong status', () => response({ ok: false, error: { code: 'CONTROLLER_OFFLINE', message: 'x' } }, 200)],
  ['wrong content type', () => ({ ...response({ ok: false, error: { code: 'CONTROLLER_OFFLINE', message: 'x' } }, 409), headers: { 'content-type': 'text/plain' } })],
]) test(`only the exact offline refusal is non-sticky: ${name} stays sticky`, async t => {
  const server = native(), a = agent();
  const life = createTaskLifecycle({ secret, transport: options => {
    const body = options.body.length ? JSON.parse(options.body) : undefined;
    return body?.operation === 'begin' ? reply() : server.transport(options);
  } });
  t.after(async () => { life.dispose(); await life.flush().catch(() => {}); });
  start(life, a);
  await assert.rejects(life.preStep(a, 1, a.turnAbort.signal), ERROR);
  const replacement = agent(); start(life, replacement);
  await assert.rejects(life.preStep(replacement, 1, replacement.turnAbort.signal), ERROR);
});
