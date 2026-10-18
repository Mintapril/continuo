import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { EventEmitter } from 'node:events';
import { createHash, createHmac } from 'node:crypto';
import { authenticatedRequest } from '../src/auth.mjs';
import { brokerTransport, MAX_RESPONSE_BYTES } from '../src/broker.mjs';

const secret = 'test-only-hmac-secret-0123456789abcdef';
const serverNonce = 'b'.repeat(64);
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const mac = text => createHmac('sha256', secret).update(text).digest('hex');
const errorCheck = error => { assert.equal(error.message, 'Native authentication unavailable'); assert.equal(error.cause, undefined); assert.ok(!String(error).includes(secret)); return true; };
const body = Buffer.from('{"text":"你好 😀"}');
const standard = { secret, method: 'POST', path: '/v1/tool', body };

function mockHttp(handle) {
  const calls = [];
  function request(options, callback) {
    const req = new EventEmitter(); req.destroy = () => { req.destroyed = true; };
    req.end = payload => {
      const res = new EventEmitter(); res.destroy = () => { res.destroyed = true; };
      const call = { options, payload, req, res, respond(value, status = 200, headers = {}, rawHeaders) {
        res.statusCode = status; res.headers = { 'content-type': 'application/json', ...headers }; res.rawHeaders = rawHeaders;
        callback(res);
        if (!res.destroyed) {
          res.emit('data', Buffer.isBuffer(value) ? value : Buffer.from(typeof value === 'string' ? value : JSON.stringify(value)));
          res.emit('end'); res.emit('close');
        }
      } };
      calls.push(call); queueMicrotask(() => handle?.(call));
    };
    return req;
  }
  return { request, calls };
}
function fields(challenge) { return `${challenge.role}\n${challenge.clientNonce}\n${serverNonce}\n${challenge.digest}`; }
function acceptChallenge(call, transform = x => x) {
  const challenge = JSON.parse(call.payload);
  assert.deepEqual(Object.keys(challenge), ['role', 'clientNonce', 'digest']);
  assert.equal(call.options.method, 'POST'); assert.equal(call.options.path, '/v1/auth');
  assert.equal(call.options.headers.authorization, undefined);
  assert.match(challenge.clientNonce, /^[0-9a-f]{64}$/); assert.match(challenge.digest, /^[0-9a-f]{64}$/);
  call.respond(transform({ serverNonce, proof: mac(`server\n${fields(challenge)}`) }));
  return challenge;
}
function acceptRequest(call, challenge, raw = Buffer.from('{"ok":true}'), status = 200, overrides = {}) {
  assert.equal(challenge.digest, hash(Buffer.concat([Buffer.from(`${call.options.method}\n${call.options.path}\n`), call.payload ?? Buffer.alloc(0)])));
  assert.equal(call.options.headers.authorization, `Cowork-HMAC ${challenge.role}:${challenge.clientNonce}:${serverNonce}:${mac(`client\n${fields(challenge)}`)}`);
  call.respond(raw, status, { 'x-cowork-proof': mac(`response\n${fields(challenge)}\n${status}\n${hash(raw)}`), ...overrides });
}

for (const role of ['agent', 'controller']) test(`${role}: exact challenge, UTF-8 wire digest, fresh nonce and no secret leakage`, async () => {
  let challenge;
  const mock = mockHttp(call => call.options.path === '/v1/auth' ? challenge = acceptChallenge(call) : acceptRequest(call, challenge));
  for (let i = 0; i < 2; i++) {
    const result = await authenticatedRequest({ ...standard, role, request: mock.request });
    assert.equal(result.body.toString(), '{"ok":true}'); assert.equal(result.status, 200);
  }
  assert.equal(mock.calls.length, 4);
  assert.notEqual(JSON.parse(mock.calls[0].payload).clientNonce, JSON.parse(mock.calls[2].payload).clientNonce);
  for (const call of mock.calls) {
    assert.doesNotMatch(JSON.stringify(call.options) + String(call.payload), /Bearer|test-only-hmac-secret/);
    assert.equal(call.options.hostname, '127.0.0.1'); assert.equal(call.options.port, 3091);
    assert.equal(call.options.agent, false); assert.equal(call.options.auth, undefined); assert.equal(call.options.socketPath, undefined);
  }
});

test('GET digest binds the exact encoded path and an empty body', async () => {
  let challenge;
  const path = '/v1/state?taskId=%E4%B8%AD%2F%3F';
  const mock = mockHttp(call => call.options.path === '/v1/auth' ? challenge = acceptChallenge(call) : acceptRequest(call, challenge));
  await authenticatedRequest({ secret, method: 'GET', path, request: mock.request });
  assert.equal(challenge.digest, hash(Buffer.from(`GET\n${path}\n`)));
  assert.equal(mock.calls[1].payload, undefined);
});

test('malicious prebound listener cannot elicit authenticated request or reusable bearer', async () => {
  for (const transform of [value => ({ ...value, proof: '0'.repeat(64) }), value => ({ ...value, proof: value.proof.toUpperCase() }),
    value => ({ ...value, serverNonce: 'a'.repeat(63) }), value => ({ ...value, proof: value.proof + '\n' }),
    value => ({ ...value, extra: true }), value => ({ serverNonce: value.serverNonce }), () => null, () => []]) {
    const mock = mockHttp(call => acceptChallenge(call, transform));
    await assert.rejects(authenticatedRequest({ ...standard, request: mock.request }), errorCheck);
    assert.equal(mock.calls.length, 1); assert.equal(mock.calls[0].options.path, '/v1/auth');
    assert.doesNotMatch(JSON.stringify(mock.calls[0].options) + mock.calls[0].payload, /Bearer|test-only-hmac-secret|Cowork-HMAC/);
  }
});

test('duplicate challenge keys are rejected even with an otherwise valid proof', async () => {
  const mock = mockHttp(call => {
    const proof = mac(`server\n${fields(JSON.parse(call.payload))}`);
    call.respond(`{"serverNonce":"${serverNonce}","proof":"${proof}","proof":"${proof}"}`);
  });
  await assert.rejects(authenticatedRequest({ ...standard, request: mock.request }), errorCheck);
  assert.equal(mock.calls.length, 1);
});

test('challenge proof is bound to role, both nonces and exact method/path/body digest', async () => {
  for (const alter of [c => ({ ...c, role: 'controller' }), c => ({ ...c, clientNonce: 'f'.repeat(64) }),
    c => ({ ...c, digest: hash(Buffer.from('POST\n/v1/control\n' + body)) }),
    c => ({ ...c, digest: hash(Buffer.from('POST\n/v1/tool\n{}')) }),
    c => ({ ...c, digest: hash(Buffer.from('GET\n/v1/tool\n' + body)) })]) {
    const mock = mockHttp(call => {
      const changed = alter(JSON.parse(call.payload));
      call.respond({ serverNonce, proof: mac(`server\n${fields(changed)}`) });
    });
    await assert.rejects(authenticatedRequest({ ...standard, request: mock.request }), errorCheck); assert.equal(mock.calls.length, 1);
  }
});

test('client snapshots raw body before auth; later caller mutation cannot change signed request', async () => {
  const mutable = Buffer.from(body); let challenge;
  const mock = mockHttp(call => {
    if (call.options.path === '/v1/auth') { mutable.fill(0); challenge = acceptChallenge(call); }
    else { assert.deepEqual(call.payload, body); acceptRequest(call, challenge); }
  });
  await authenticatedRequest({ ...standard, body: mutable, request: mock.request });
});

test('response body/status/proof tamper, unsigned errors and duplicate proofs are rejected before return', async () => {
  for (const mode of ['body', 'status', 'missing', 'wrong', 'uppercase', 'duplicate', 'unsigned-error']) {
    let challenge;
    const mock = mockHttp(call => {
      if (call.options.path === '/v1/auth') { challenge = acceptChallenge(call); return; }
      const raw = Buffer.from('{"ok":true}');
      const proof = mac(`response\n${fields(challenge)}\n200\n${hash(raw)}`);
      const headers = mode === 'missing' || mode === 'unsigned-error' ? {} : { 'x-cowork-proof': mode === 'wrong' ? '0'.repeat(64) : mode === 'uppercase' ? proof.toUpperCase() : proof };
      call.respond(mode === 'body' ? '{"ok":false}' : raw, mode === 'status' || mode === 'unsigned-error' ? 401 : 200, headers,
        mode === 'duplicate' ? ['X-Cowork-Proof', proof, 'x-cowork-proof', proof] : undefined);
    });
    await assert.rejects(authenticatedRequest({ ...standard, request: mock.request }), errorCheck); assert.equal(mock.calls.length, 2);
  }
});

test('neither auth nor actual response redirects are followed, and failures never retry', async () => {
  for (const phase of ['auth', 'actual']) for (const status of [301, 302, 303, 307, 308]) {
    let challenge;
    const mock = mockHttp(call => {
      if (call.options.path === '/v1/auth' && phase !== 'auth') challenge = acceptChallenge(call);
      else call.respond({}, status, { location: 'http://127.0.0.1:3091/steal' });
    });
    await assert.rejects(authenticatedRequest({ ...standard, request: mock.request }), errorCheck);
    assert.equal(mock.calls.length, phase === 'auth' ? 1 : 2);
  }
});

test('one total deadline spans slow auth and actual legs, destroying actual request at expiry', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const mock = mockHttp();
  const pending = authenticatedRequest({ ...standard, request: mock.request, timeoutMs: 3000 });
  const checked = assert.rejects(pending, errorCheck);
  t.mock.timers.tick(2500); acceptChallenge(mock.calls[0]);
  await Promise.resolve(); await Promise.resolve();
  assert.equal(mock.calls.length, 2);
  t.mock.timers.tick(499); assert.notEqual(mock.calls[1].req.destroyed, true);
  t.mock.timers.tick(1); await checked; assert.equal(mock.calls[1].req.destroyed, true);
});

test('auth leg has a small response bound and rejects invalid UTF-8/JSON/content type', async () => {
  for (const [value, headers] of [[Buffer.alloc(1025), {}], [Buffer.from([0xff]), {}], ['{', {}], [{}, { 'content-type': 'text/html' }], [{}, { 'content-length': '1025' }]]) {
    const mock = mockHttp(call => call.respond(value, 200, headers));
    await assert.rejects(authenticatedRequest({ ...standard, request: mock.request }), errorCheck); assert.equal(mock.calls.length, 1);
  }
});

test('real loopback HTTP exchanges authenticate raw response headers/body without proxy routing', async t => {
  const calls = []; let challenge;
  const server = http.createServer((req, res) => {
    const parts = [];
    req.on('data', part => parts.push(part));
    req.on('end', () => {
      const raw = Buffer.concat(parts); calls.push({ headers: req.headers, raw });
      res.setHeader('content-type', 'application/json');
      if (req.url === '/v1/auth') {
        challenge = JSON.parse(raw);
        res.end(JSON.stringify({ serverNonce, proof: mac(`server\n${fields(challenge)}`) }));
      } else {
        assert.equal(challenge.digest, hash(Buffer.concat([Buffer.from(`${req.method}\n${req.url}\n`), raw])));
        assert.equal(req.headers.authorization, `Cowork-HMAC agent:${challenge.clientNonce}:${serverNonce}:${mac(`client\n${fields(challenge)}`)}`);
        const result = Buffer.from('{"ok":true}');
        res.setHeader('X-Cowork-Proof', mac(`response\n${fields(challenge)}\n200\n${hash(result)}`));
        res.end(result);
      }
    });
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise(resolve => { server.close(resolve); server.closeAllConnections(); }));
  const response = await authenticatedRequest({ ...standard, request: (options, callback) => http.request({ ...options, port: server.address().port }, callback) });
  assert.equal(response.body.toString(), '{"ok":true}'); assert.equal(calls.length, 2);
  for (const call of calls) assert.doesNotMatch(JSON.stringify(call.headers) + call.raw, /Bearer|test-only-hmac-secret/);
});

test('broker public transport uses HMAC helper and preserves 12MiB response limit', async t => {
  const envelope = { protocolVersion: 1, requestId: 'request', sessionId: 's', tool: 'phone.status', arguments: {} };
  const response = { protocolVersion: 1, requestId: 'request', ok: true, result: { state: 'ready', accessibilityReady: true, screenCaptureReady: true } };
  let challenge;
  const mock = mockHttp(call => call.options.path === '/v1/auth' ? challenge = acceptChallenge(call) : acceptRequest(call, challenge, Buffer.from(JSON.stringify(response))));
  t.mock.method(http, 'request', mock.request);
  assert.deepEqual(await brokerTransport(envelope, secret), response); assert.equal(mock.calls.length, 2);
  assert.equal(MAX_RESPONSE_BYTES, 12 * 1024 * 1024);
  const tooLarge = mockHttp(call => {
    if (call.options.path === '/v1/auth') challenge = acceptChallenge(call);
    else call.respond(Buffer.alloc(MAX_RESPONSE_BYTES + 1));
  });
  t.mock.method(http, 'request', tooLarge.request);
  await assert.rejects(brokerTransport(envelope, secret), errorCheck);
  assert.equal(tooLarge.calls.length, 2); assert.equal(tooLarge.calls[1].res.destroyed, true);
});
