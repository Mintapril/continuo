import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { EventEmitter } from 'node:events';
import { createPhoneBridge, directLoopbackFetch, validateArguments, validateResponse, BRIDGE_URL, MAX_RESPONSE_BYTES } from '../src/bridge.mjs';

const token = 'test-only-secret-123456789012345678901234567890';
const sessionId = 'test-session';
const image = { observationId: 'observation-1', capturedAt: 1000, width: 1080, height: 2400, mimeType: 'image/png', base64: 'iVBORw0KGgo=' };
const tree = { observationId: 'observation-1', capturedAt: 1000, width: 1080, height: 2400, nodes: [] };
function harness(responder, now = () => 1000) {
  const calls = [];
  const bridge = createPhoneBridge({ token, now, fetchImpl: async (url, options) => {
    calls.push({ url, options, request: JSON.parse(options.body) });
    const request = calls.at(-1).request;
    const data = await responder(request, calls.length);
    return data instanceof Response ? data : Response.json({ protocolVersion: 1, requestId: request.requestId, ok: true, result: data });
  } });
  return { bridge, calls, call: (tool, args = {}, session = sessionId) => bridge.call(tool, args, { sessionId: session }) };
}

test('fixed authenticated loopback JSON route, strict identities, no arbitrary endpoint', async () => {
  const h = harness(() => ({ state: 'ready', accessibilityReady: true, screenCaptureReady: false }));
  assert.equal((await h.call('phone.status')).state, 'ready');
  const { url, options, request } = h.calls[0];
  assert.equal(url, BRIDGE_URL); assert.equal(url, 'http://127.0.0.1:3091/v1/tool');
  assert.equal(options.redirect, 'error'); assert.equal(options.method, 'POST');
  assert.equal(options.headers.authorization, `Bearer ${token}`);
  assert.deepEqual(Object.keys(request).sort(), ['protocolVersion', 'requestId', 'sessionId', 'tool', 'arguments'].sort());
  assert.equal(request.protocolVersion, 1); assert.equal(request.sessionId, sessionId);
  assert.match(request.requestId, /^[a-f\d-]{36}$/); assert.ok(options.signal instanceof AbortSignal);
  assert.ok(!JSON.stringify(request).includes(token));
  for (const bad of ['', 'short', 'bad\nheader'.repeat(10), undefined]) assert.throws(() => createPhoneBridge({ token: bad }));
});
test('wire arguments reject missing/extra fields, code, paths, intents and malformed numbers', () => {
  for (const [tool, args] of [
    ['phone.shell', { command: 'id' }], ['phone.status', { endpoint: 'https://evil.invalid' }],
    ['phone.tap', { observationId: 'x', x: 1 }], ['phone.tap', { observationId: 'x', x: -1, y: 0 }],
    ['phone.tap', { observationId: 'x', x: 1.2, y: 0 }], ['phone.key', { observationId: 'x', key: 'POWER' }],
    ['phone.type_text', { observationId: 'x', text: 'x'.repeat(4001) }],
    ['phone.launch_app', { packageName: 'intent://action' }], ['phone.launch_app', { packageName: 'org.app', uri: 'file:///etc' }],
    ['phone.swipe', { observationId: 'x', x1: 0, y1: 0, x2: 1, y2: 1, durationMs: 10 }],
  ]) assert.throws(() => validateArguments(tool, args));
  assert.deepEqual(validateArguments('phone.type_text', { observationId: 'x', text: '你好; $(not code)' }), { observationId: 'x', text: '你好; $(not code)' });
});
test('fresh session-owned observation, bounds, consumed mutation, expiration and latest-only semantics', async () => {
  let clock = 1000, sequence = 0;
  const h = harness(req => req.tool === 'phone.ui_tree' ? { ...tree, observationId: `observation-${++sequence}` } : { performed: true }, () => clock);
  await assert.rejects(h.call('phone.tap', { observationId: 'observation-1', x: 1, y: 1 }), /fresh observation/);
  await h.call('phone.ui_tree');
  await assert.rejects(h.call('phone.tap', { observationId: 'observation-1', x: 1, y: 1 }, 'other-session'), /fresh observation/);
  await assert.rejects(h.call('phone.tap', { observationId: 'observation-1', x: 1080, y: 1 }), /Coordinate/);
  await h.call('phone.tap', { observationId: 'observation-1', x: 1, y: 1 });
  await assert.rejects(h.call('phone.tap', { observationId: 'observation-1', x: 1, y: 1 }), /fresh observation/);
  await h.call('phone.ui_tree'); await h.call('phone.ui_tree');
  await assert.rejects(h.call('phone.key', { observationId: 'observation-2', key: 'BACK' }), /fresh observation/);
  clock += 30001;
  await assert.rejects(h.call('phone.key', { observationId: 'observation-3', key: 'BACK' }), /fresh observation/);
});
test('failed/timeout mutation is never automatically retried and drops observation', async () => {
  const h = harness(req => req.tool === 'phone.ui_tree' ? tree : new Response('sensitive server body', { status: 500 }));
  await h.call('phone.ui_tree');
  await assert.rejects(h.call('phone.tap', { observationId: 'observation-1', x: 1, y: 1 }), /HTTP 500/);
  assert.equal(h.calls.length, 2);
  await assert.rejects(h.call('phone.tap', { observationId: 'observation-1', x: 1, y: 1 }), /fresh observation/);
  assert.equal(h.calls.length, 2);
});
test('response parsing rejects mismatched IDs, unknown shapes, invalid image and oversized data', async () => {
  const req = { requestId: 'request-1', tool: 'phone.screenshot' };
  assert.deepEqual(validateResponse({ protocolVersion: 1, requestId: req.requestId, ok: true, result: image }, req), image);
  for (const data of [
    { protocolVersion: 2, requestId: req.requestId, ok: true, result: image },
    { protocolVersion: 1, requestId: 'other', ok: true, result: image },
    { protocolVersion: 1, requestId: req.requestId, ok: true, result: { ...image, command: 'x' } },
    { protocolVersion: 1, requestId: req.requestId, ok: true, result: { ...image, base64: 'notAnImage==' } },
    { protocolVersion: 1, requestId: req.requestId, ok: true, result: { ...image, width: 0 } },
  ]) assert.throws(() => validateResponse(data, req));
  const h = harness(() => new Response('{}', { headers: { 'content-type': 'application/json', 'content-length': String(MAX_RESPONSE_BYTES + 1) } }));
  await assert.rejects(h.call('phone.status'), /too large/);
});
test('untrusted errors do not reflect secret data and repeated calls are serialized', async () => {
  const h = harness(req => Response.json({ protocolVersion: 1, requestId: req.requestId, ok: false, error: { code: 'PERMISSION_DENIED', message: token } }));
  await assert.rejects(h.call('phone.status'), error => error.message.includes('PERMISSION_DENIED') && !error.message.includes(token));
  let resolve;
  const pending = harness(() => new Promise(r => { resolve = r; }));
  const first = pending.call('phone.status');
  await assert.rejects(pending.call('phone.status'), /already in progress/);
  resolve({ state: 'paused', accessibilityReady: false, screenCaptureReady: false });
  await first;
});
test('direct transport pins literal loopback, ignores global fetch and never follows redirects (offline HTTP mock)', async t => {
  const calls = [];
  t.mock.method(globalThis, 'fetch', () => { throw new Error('Global/proxied fetch must never run'); });
  t.mock.method(http, 'request', (options, callback) => {
    calls.push(options);
    const req = new EventEmitter();
    req.end = body => {
      assert.ok(body.includes('protocolVersion'));
      queueMicrotask(() => {
        const res = new EventEmitter(); res.statusCode = 302; res.headers = { 'content-type': 'application/json', location: 'https://evil.invalid' };
        callback(res); res.emit('data', Buffer.from('{}')); res.emit('end');
      });
    };
    return req;
  });
  await assert.rejects(createPhoneBridge({ token }).call('phone.status', {}, { sessionId }), /HTTP 302/);
  assert.equal(calls.length, 1);
  assert.deepEqual([calls[0].hostname, calls[0].port, calls[0].path, calls[0].method, calls[0].agent], ['127.0.0.1', 3091, '/v1/tool', 'POST', false]);
  await assert.rejects(directLoopbackFetch('http://localhost:3091/v1/tool', {}), /fixed bridge/);
  assert.equal(calls.length, 1);
});
test('one physical phone serializes sessions and every mutation invalidates all observations', async () => {
  const h = harness(req => req.tool === 'phone.ui_tree' ? { ...tree, observationId: req.sessionId } : { performed: true });
  await h.call('phone.ui_tree', {}, 'session-one'); await h.call('phone.ui_tree', {}, 'session-two');
  await h.call('phone.key', { observationId: 'session-one', key: 'BACK' }, 'session-one');
  await assert.rejects(h.call('phone.key', { observationId: 'session-two', key: 'BACK' }, 'session-two'), /fresh observation/);
  const req = { requestId: 'r', tool: 'phone.status' };
  assert.throws(() => validateResponse({ protocolVersion: 1, requestId: 'r', ok: false, error: { code: 'SECRET_TOKEN_CONTENT', message: 'private' } }, req), error => error.message.endsWith('(BRIDGE_ERROR)'));
});
