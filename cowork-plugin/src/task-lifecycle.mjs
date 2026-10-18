import { authenticatedRequest } from './auth.mjs';

const MAX_BYTES = 16 * 1024, TIMEOUT_MS = 6000, RETRY_MS = 300, POLL_MS = 500, OFFLINE_WAIT_MS = 25000, OFFLINE_POLL_MS = 1500;
function delay(ms, signal) {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) return reject(unavailable());
    const timer = setTimeout(() => { signal?.removeEventListener('abort', abort); resolve(); }, ms);
    const abort = () => { clearTimeout(timer); reject(unavailable()); };
    signal?.addEventListener('abort', abort, { once: true });
  });
}
const KEYS = ['instanceId', 'executionId', 'taskId', 'revision', 'state', 'command', 'commandId'];
const STATES = new Set(['idle', 'running', 'pausing', 'stopping', 'paused', 'stopped', 'completed', 'error', 'waiting_user']);
const TERMINAL = new Set(['idle', 'paused', 'stopped', 'completed', 'error', 'waiting_user']);
const unavailable = () => new Error('Native task lifecycle unavailable');
// A signed, exact CONTROLLER_OFFLINE refusal of begin is definitive: native checks it before any task
// state changes, so nothing started and nothing is uncertain. It is the only refusal that is not sticky.
const offline = () => Object.assign(new Error('Cowork 控制器无法自动启动：请检查 Continuo 的 Root 授权与通知权限，在应用中打开 设置 → Cowork 查看状态，然后重新发布任务。'
  + ' (Cowork controller could not be started automatically: open Continuo Settings > Cowork, then send the task again.)'), { code: 'COWORK_CONTROLLER_OFFLINE' });
// A verified GET snapshot is native ground truth, not transport uncertainty. A known non-terminal
// state refuses this turn only: it must never set the process-wide sticky transport fault, and the
// state word is surfaced so the user can clear it on the phone instead of guessing.
const busy = state => Object.assign(new Error(`Cowork 原生任务尚未结束（状态 ${state}）：请在 Continuo / DSH Pocket 中查看并结束或交还该 Cowork 任务，然后重新发送。`
  + ` (Cowork native task is still active: state=${state}. Finish or hand back the phone task in the app, then send the turn again.)`), { code: 'COWORK_TASK_BUSY' });
function refusedOffline(bytes) {
  try {
    const value = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes));
    const keys = Object.keys(value ?? {}), inner = value?.error && typeof value.error === 'object' ? Object.keys(value.error) : [];
    return value.ok === false && keys.length === 2 && keys.includes('error') && value.error.code === 'CONTROLLER_OFFLINE'
      && inner.length === 2 && inner.includes('code') && inner.includes('message') && typeof value.error.message === 'string';
  } catch { return false; }
}
const id = value => typeof value === 'string' && value.trim().length > 0 && value.length <= 256
  && value.isWellFormed() && !/[\p{Cc}\p{Cf}\p{Zl}\p{Zp}]/u.test(value);
const uuid = value => typeof value === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(value);
const turnValid = value => Number.isSafeInteger(value) && value > 0;
const outcomeOf = kind => kind === 'completed' ? 'completed' : kind === 'aborted' ? 'stopped' : kind === 'error' ? 'error' : 'waiting_user';

function snapshot(bytes) {
  const text = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
  const value = JSON.parse(text);
  // The schema is flat; count encoded keys too, so duplicate JSON keys cannot disappear in JSON.parse.
  const keys = [...text.matchAll(/("(?:[^"\\]|\\.)*")\s*:/g)].map(match => JSON.parse(match[1]));
  if (!value || typeof value !== 'object' || Array.isArray(value) || keys.length !== KEYS.length
    || new Set(keys).size !== KEYS.length || !KEYS.every(key => Object.hasOwn(value, key))
    || !id(value.instanceId) || !Number.isSafeInteger(value.revision) || value.revision < 0 || Object.is(value.revision, -0)
    || !STATES.has(value.state) || !['', 'pause', 'stop'].includes(value.command)
    || (value.taskId === '' ? value.executionId !== '' || value.state !== 'idle' : !id(value.taskId) || !uuid(value.executionId))
    || (value.command === '' ? value.commandId !== '' : !id(value.commandId) || value.state !== (value.command === 'pause' ? 'pausing' : 'stopping'))
    || (['pausing', 'stopping'].includes(value.state) && value.command === '')) throw unavailable();
  return Object.freeze(Object.fromEntries(KEYS.map(key => [key, value[key]])));
}

/** Private host coordinator, never a tool or model-editable configuration.
 * transport is an offline-test seam with authenticatedRequest's signature/result.
 * Forward all session events: RC.2 ToolExecution has no turn; tool/call binds callId to its exact turn.
 * preStep is the admission barrier. runTool calls body with a copied exec and combined signal.
 * Transport uncertainty is sticky: recreate only after native/user reconciliation, never retry begin.
 * Active waiting_user drains the captured turn; exact approval never resumes or replays its tools.
 * A later waiting_user GET refuses admission until native stop, without poisoning the transport.
 */
export function createTaskLifecycle({ secret, transport = authenticatedRequest, offlineWaitMs = OFFLINE_WAIT_MS, now = Date.now, sleep = delay } = {}) {
  const pause = (ms, signal) => ms <= 0 ? Promise.resolve() : sleep(ms, signal);
  const records = new Map(), pending = new Map();
  let lease, fault = false, disposed = false, ioTail = Promise.resolve();
  function track(task, promise) {
    pending.set(promise, task?.agent);
    promise.then(() => pending.delete(promise), () => pending.delete(promise));
    return promise;
  }
  function stopTimer(task) { clearTimeout(task.timer); task.timer = undefined; }
  function cancel(task) {
    stopTimer(task);
    // An old response must not cancel a later turn on the same object, or its replacement object.
    if (!task.closed && !task.cancelled && records.get(task.agent)?.current === task) {
      task.cancelled = true;
      try { task.agent.cancel({ kind: 'user' }); } catch { fault = true; }
    }
    task.control.abort();
  }
  function fail(task) {
    fault = true;
    // A failed task is finished locally at once: it never polls, ends or acks again (an old
    // execution's late 409 must not re-poison a recovered host). Its lease is kept until a
    // verified native snapshot proves the execution is gone or retires it (see recover).
    if (task) { cancel(task); task.failed = true; task.closed = true; stopTimer(task); task.calls.clear(); }
    return unavailable();
  }
  // Every operation except begin is idempotent on native (poll/GET read, end/ack of a settled
  // execution are no-ops), so a lost response is retried once. begin is never replayed.
  async function exchange(method, bytes) {
    const deadline = new AbortController(); let timer;
    try {
      const timeout = new Promise((_, reject) => {
        timer = setTimeout(() => { deadline.abort(); reject(unavailable()); }, TIMEOUT_MS); timer.unref?.();
      });
      return await Promise.race([transport({ secret, role: 'agent', method,
        path: method === 'GET' ? '/v1/task-state' : '/v1/task', body: bytes, signal: deadline.signal,
        timeoutMs: TIMEOUT_MS, maxResponseBytes: MAX_BYTES, maxRequestBytes: MAX_BYTES }), timeout]);
    } finally { clearTimeout(timer); }
  }
  function wire(task, method, body, refusal = false, verify = false) {
    const operation = ioTail.then(async () => {
      // verify is the single fault-recovery probe: it may read and settle native ground truth while
      // the sticky fault is set. Every other operation stays blocked by the fault.
      if (fault && !verify) throw unavailable();
      try {
        const bytes = body === undefined ? Buffer.alloc(0) : Buffer.from(JSON.stringify(body));
        if (bytes.length > MAX_BYTES) throw unavailable();
        let response;
        for (let attempt = 0; ; attempt++) {
          try { response = await exchange(method, bytes); break; } catch (error) {
            if (attempt >= 1 || disposed || body?.operation === 'begin') throw error;
            await new Promise(resolve => setTimeout(resolve, RETRY_MS).unref?.());
          }
        }
        if (refusal && response?.status === 409 && /^application\/json(?:\s*;|$)/i.test(response.headers?.['content-type'] ?? '')
          && Buffer.isBuffer(response.body) && response.body.length <= MAX_BYTES && refusedOffline(response.body)) return Object.freeze({ offline: true });
        if (response?.status !== 200 || !/^application\/json(?:\s*;|$)/i.test(response.headers?.['content-type'] ?? '')
          || !Buffer.isBuffer(response.body) || response.body.length > MAX_BYTES) throw unavailable();
        return snapshot(response.body); // ok:false and 409 are never success or retried.
      } catch {
        // A verification probe only reports whether native ground truth was readable: it never
        // cancels the caller's turn and never re-poisons the already sticky fault.
        if (verify) throw unavailable();
        throw fail(task);
      }
    });
    ioTail = operation.catch(() => {});
    return track(task, operation);
  }
  const ids = task => ({ taskId: task.taskId, instanceId: task.native.instanceId, executionId: task.native.executionId });
  function matching(task, value) {
    const old = task.native;
    if (!old || value.instanceId !== old.instanceId || value.executionId !== old.executionId
      || value.taskId !== task.taskId || value.revision < old.revision) throw fail(task);
    task.native = value;
    return value;
  }
  function release(task) { stopTimer(task); task.closed = true; task.calls.clear(); if (lease === task) lease = undefined; }
  async function acknowledge(task, value) {
    task.command = value;
    let result = matching(task, await wire(task, 'POST', { ...ids(task), operation: 'ack', commandId: value.commandId }));
    if (result.command) {
      // Native recognizes ONLY this same-execution superseded pause ACK. HTTP 200
      // advertises the stronger stop without settling it. One verified upgrade;
      // generic 409 or uncertainty remains sticky, with no mutation retry.
      if (value.command !== 'pause' || result.command !== 'stop' || result.state !== 'stopping'
        || result.commandId === value.commandId || result.revision <= value.revision) throw fail(task);
      value = result; task.command = value;
      result = matching(task, await wire(task, 'POST', { ...ids(task), operation: 'ack', commandId: value.commandId }));
    }
    if (result.command !== '' || result.commandId !== '' || result.state !== (value.command === 'pause' ? 'paused' : 'stopped')) throw fail(task);
    release(task);
    return result;
  }
  /** Settle a pending native control command (pause/stop) that no live task here owns: this host
   * just started, or the owning turn is already gone. Exactly one ack with the verified snapshot
   * ids, native stays authoritative, and any mismatch is a local refusal, never a sticky fault.
   * A state without a command is owned elsewhere and cannot be settled from here.
   */
  async function reconcile(task, value, verify = false) {
    if (value.command === '') throw busy(value.state);
    const settled = await wire(task, 'POST', { taskId: value.taskId, instanceId: value.instanceId,
      executionId: value.executionId, operation: 'ack', commandId: value.commandId }, false, verify);
    if (settled.taskId !== value.taskId || settled.instanceId !== value.instanceId
      || settled.executionId !== value.executionId || settled.command !== '' || settled.commandId !== ''
      || settled.revision <= value.revision
      || settled.state !== (value.command === 'pause' ? 'paused' : 'stopped')) throw busy(value.state);
    return settled;
  }
  /** Transport uncertainty stays sticky, but native is authoritative: one verified GET may prove
   * that no task is live and no command is pending, and only then is the fault cleared. A live
   * task, an unsettleable command or any transport failure keeps it. Begin is never replayed from
   * memory: a recovered admission re-reads native and begins from that fresh snapshot alone.
   */
  // A running execution is ours to retire only when the task that failed here owned it: same
  // session, and either its begin was uncertain (no snapshot) or the execution ids match.
  function abandoned(value) {
    const owner = lease;
    return value.state === 'running' && value.command === '' && !!owner?.failed && owner.taskId === value.taskId
      && (!owner.native || owner.native.executionId === value.executionId);
  }
  async function retire(task, value) {
    const result = await wire(task, 'POST', { taskId: value.taskId, instanceId: value.instanceId,
      executionId: value.executionId, operation: 'end', outcome: 'stopped' }, false, true);
    if (result.instanceId !== value.instanceId || result.executionId !== value.executionId || result.taskId !== value.taskId) throw unavailable();
    if (result.command) return reconcile(task, result, true);
    if (!TERMINAL.has(result.state)) throw unavailable();
    return result;
  }
  async function recover(task) {
    let current;
    try { current = await wire(task, 'GET', undefined, false, true); } catch { return false; }
    // A live execution owned by someone else is a known state: say so instead of a generic error.
    if (current.command === '' && !TERMINAL.has(current.state) && !abandoned(current)) throw busy(current.state);
    try {
      if (current.command !== '') await reconcile(task, current, true);
      else if (!TERMINAL.has(current.state)) await retire(task, current);
    } catch { return false; }
    // Native now verifiably runs nothing of ours, so a lease still held by a failed task, or by an
    // already ended task whose finish has settled, can no longer overlap a replacement. An in-flight
    // finish may still post an end, and that end must not race the replacement begin.
    if (lease && lease !== task && (lease.failed || (lease.ended && lease.finishDone))) release(lease);
    fault = false;
    return true;
  }
  function settle(task, value) {
    if ((!value.command && value.state !== 'waiting_user') || task.settlement || task.failed) return;
    if (value.command) task.command = value;
    else task.waitingUser = true;
    cancel(task); // Always reached asynchronously, never from Session.append's observer stack.
    task.settlement = track(task, Promise.resolve().then(async () => {
      try {
        await task.agent.whenIdle();
        await Promise.allSettled([...task.tools]);
        if (fault) throw unavailable();
        // Cancellation can take arbitrarily long. A pause may have become a stop,
        // so never ACK the command captured before whenIdle/wrapper settlement.
        const current = matching(task, await wire(task, 'POST', { ...ids(task), operation: 'poll' }));
        if (current.command) return await acknowledge(task, current);
        if (!task.waitingUser || !['running', 'waiting_user'].includes(current.state)) throw fail(task);
        // An exact approval may have run while settling. It does not revive this
        // turn or replay its tool; end still reports waiting_user, not completed.
        const result = matching(task, await wire(task, 'POST', { ...ids(task), operation: 'end', outcome: 'waiting_user' }));
        if (result.command) return await acknowledge(task, result);
        if (result.state !== 'waiting_user') throw fail(task);
        release(task);
        return result;
      } catch { throw fail(task); }
    }));
  }
  function arm(task) {
    if (disposed || fault || task.closed || task.ended || task.settlement || task.timer) return;
    task.timer = setTimeout(() => {
      task.timer = undefined;
      poll(task).catch(() => {});
    }, POLL_MS);
    task.timer.unref?.();
  }
  function poll(task) {
    if (task.polling) return task.polling;
    if (fault || task.closed || task.settlement) return Promise.reject(unavailable());
    stopTimer(task);
    const promise = (async () => {
      const value = matching(task, await wire(task, 'POST', { ...ids(task), operation: 'poll' }));
      if (value.command || value.state === 'waiting_user') settle(task, value);
      else if (value.state !== 'running') throw fail(task);
      return value;
    })();
    task.polling = track(task, promise);
    promise.then(() => { task.polling = undefined; arm(task); }, () => { task.polling = undefined; });
    return promise;
  }
  function eligible(task, signal) {
    if (disposed || fault || task.record.removed || task.record.current !== task || task.ended
      || task.closed || task.settlement || task.control.signal.aborted || signal?.aborted) throw unavailable();
  }
  // Do not let a next-turn pre-step waiting on old ack deadlock whenIdle(). Its
  // barrier rejects on old cancellation; the driver can then become idle without cancelling a replacement turn.
  function afterPrevious(task, previous, signal) {
    return new Promise((resolve, reject) => {
      const barrier = AbortSignal.any([previous.control.signal, task.control.signal, signal]);
      const abort = () => reject(unavailable());
      if (barrier.aborted) return abort();
      barrier.addEventListener('abort', abort, { once: true });
      previous.finish.then(resolve, reject).finally(() => barrier.removeEventListener('abort', abort)).catch(() => {});
    });
  }
  function acquire(task, signal) {
    if (task.admission) return task.admission;
    task.admission = track(task, Promise.resolve().then(async () => {
      eligible(task, signal);
      if (lease && lease !== task) {
        const previous = lease;
        if (!previous.ended || !previous.finish) throw unavailable();
        await afterPrevious(task, previous, signal);
        eligible(task, signal);
      }
      if (lease && lease !== task) throw unavailable();
      lease = task; // Reserve before GET; a failed/uncertain begin must not permit replacement overlap.
      let current, value;
      // Native wakes the app-side controller on demand when begin finds a stale heartbeat; wait for it briefly.
      for (const deadline = now() + offlineWaitMs; ; ) {
        current = await wire(task, 'GET');
        // A verified snapshot is a known native state, never transport uncertainty: settle a
        // pending command once, otherwise refuse this turn only. Never poison the whole process.
        if (!TERMINAL.has(current.state) || current.command !== '') current = await reconcile(task, current);
        if (current.state === 'waiting_user') break;
        eligible(task, signal);
        value = await wire(task, 'POST', { operation: 'begin', taskId: task.taskId, instanceId: current.instanceId, revision: current.revision }, true);
        if (!value.offline || now() >= deadline) break;
        await pause(Math.min(OFFLINE_POLL_MS, Math.max(0, deadline - now())), signal);
        eligible(task, signal);
      }
      if (current.state === 'waiting_user') {
        // The metadata-only snapshot cannot prove that a retained approval/display
        // is gone. Refuse this turn locally until explicit native stop; no begin,
        // no mutation retry, and no sticky fault for a known non-running state.
        release(task);
        throw busy('waiting_user');
      }
      if (value.offline) { release(task); throw offline(); } // Nothing began; no sticky fault, a later turn may retry.
      if (value.taskId !== task.taskId || value.instanceId !== current.instanceId || value.revision <= current.revision
        || !uuid(value.executionId) || (value.state !== 'running' && !value.command)) throw fail(task);
      task.native = value;
      if (value.command) settle(task, value);
      else arm(task);
      eligible(task, signal);
      return value;
    }));
    return task.admission;
  }
  function finish(task) {
    if (task.finish) return task.finish;
    stopTimer(task);
    task.finish = track(task, Promise.resolve().then(async () => {
      if (task.admission) await task.admission.catch(() => {});
      if (!task.native) { if (lease === task && !fault) release(task); return; }
      if (task.closed) return task.native;
      if (task.settlement) return task.settlement;
      await Promise.allSettled([...task.tools]);
      if (fault) throw unavailable();
      if (task.settlement) return task.settlement;
      await poll(task);
      if (task.settlement) return task.settlement;
      const value = matching(task, await wire(task, 'POST', { ...ids(task), operation: 'end', outcome: task.outcome }));
      if (value.command) { settle(task, value); return task.settlement; }
      // Native paused workspace/approval state is authoritative over turn/end.
      // This is the response to our exact end, not an active waiting_user poll.
      if (value.state !== task.outcome && value.state !== 'waiting_user') throw fail(task);
      release(task);
      return value;
    }));
    // A stale lease is reclaimable only after this finish has settled, success or failure.
    task.finish.then(() => { task.finishDone = true; }, () => { task.finishDone = true; });
    return task.finish;
  }
  function observe(agent, event) {
    if (disposed) return;
    if (event?.type === 'turn/start') {
      if (!turnValid(event.data?.turn) || !id(agent?.session?.id)) return;
      let record = records.get(agent);
      if (record?.removed) return;
      if (!record) { record = { current: undefined, removed: false }; records.set(agent, record); }
      if (record.current && !record.current.ended) return;
      record.current = { agent, record, taskId: agent.session.id, turn: event.data.turn,
        control: new AbortController(), calls: new Set(), tools: new Set(), outcome: 'error' };
      return; // Metadata only; first pre-step, not this notification, acquires the native lease.
    }
    const task = records.get(agent)?.current;
    if (!task || event?.data?.turn !== task.turn) return;
    if (event.type === 'tool/call' && task.admitted && !task.ended && id(event.data.callId)) task.calls.add(event.data.callId);
    if (event.type === 'tool/result') task.calls.delete(event.data.message?.callId);
    if (event.type === 'turn/end' && !task.ended) {
      task.ended = true; task.outcome = outcomeOf(event.data.reason?.kind); task.calls.clear();
      finish(task); // Starts only in a microtask, after synchronous event emission returns.
    }
  }
  async function preStep(agent, turn, signal) {
    if (!turnValid(turn) || !id(agent?.session?.id) || !signal
      || typeof agent.cancel !== 'function' || typeof agent.whenIdle !== 'function') throw unavailable();
    let record = records.get(agent);
    if (record?.removed) throw unavailable();
    if (!record) { record = { current: undefined, removed: false }; records.set(agent, record); }
    let task = record.current;
    if (task && task.turn !== turn) {
      // The turn/start notification for this turn was never seen: the host loaded mid-turn, the
      // plugin reloaded, or a durable session resumed without a fresh start event. Retire the stale
      // task exactly like a missed turn/end so its finish can still settle native, then admit the
      // current turn with its own lease instead of failing this session for the rest of its life.
      const stale = task;
      if (!stale.ended) { stale.ended = true; stale.outcome = 'stopped'; }
      stale.calls.clear(); stopTimer(stale);
      track(stale, Promise.resolve().then(() => finish(stale)));
      task = undefined;
    }
    if (!task) {
      task = { agent, record, taskId: agent.session.id, turn,
        control: new AbortController(), calls: new Set(), tools: new Set(), outcome: 'error' };
      record.current = task;
    }
    // A sticky fault is recoverable exactly once per turn, from a verified native snapshot only.
    if (!disposed && fault && !await recover(task)) throw unavailable();
    eligible(task, signal);
    if (!task.native) await acquire(task, signal);
    else await poll(task);
    eligible(task, signal);
    task.admitted = true;
    return task.native;
  }
  function runTool(agent, exec, body) {
    const task = records.get(agent)?.current;
    if (!task || exec?.agent !== agent || !task.admitted || !task.native || !task.calls.delete(exec.callId)
      || (exec.turn !== undefined && exec.turn !== task.turn) || !exec.signal || typeof body !== 'function') return Promise.reject(unavailable());
    const promise = Promise.resolve().then(async () => {
      eligible(task, exec.signal);
      await poll(task); // File/web-only tools obey the same native admission/control boundary.
      eligible(task, exec.signal);
      const signal = AbortSignal.any([exec.signal, task.control.signal]);
      const result = await body({ ...exec, signal });
      if (signal.aborted) throw unavailable();
      return result;
    });
    task.tools.add(promise);
    promise.then(() => task.tools.delete(promise), () => task.tools.delete(promise));
    return track(task, promise);
  }
  function remove(agent) {
    const record = records.get(agent);
    if (!record || record.removed) return;
    record.removed = true;
    const task = record.current;
    if (task) {
      if (!task.ended) { task.ended = true; task.outcome = 'stopped'; }
      task.calls.clear(); stopTimer(task);
      track(task, Promise.resolve().then(() => { cancel(task); return finish(task); }));
    }
  }
  function dispose() { if (!disposed) { for (const agent of records.keys()) remove(agent); disposed = true; } }
  async function flush(agent) {
    // Drain deferred work, not the future polling timer. Useful for deterministic offline tests/shutdown.
    for (;;) {
      const work = [...pending].filter(([, owner]) => agent === undefined || owner === agent).map(([promise]) => promise);
      if (!work.length) break;
      await Promise.allSettled(work);
    }
    if (fault) throw unavailable();
    return records.get(agent)?.current?.native;
  }
  return Object.freeze({ observe, preStep, runTool, remove, dispose, flush });
}
