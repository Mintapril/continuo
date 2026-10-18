import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { spawn } from 'node:child_process';
import { createHash, createHmac } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { homedir } from 'node:os';
import path from 'node:path';

const python = process.env.COWORK_TEST_PYTHON ?? (process.platform === 'win32'
  ? path.join(homedir(), '.dsh/dsh-runtimes/dsh-primary-runtime/dependencies/python/python.exe') : 'python3');
const helperDir = fileURLToPath(new URL('../../', import.meta.url));
const secret = 'test-only-python-secret-0123456789abcd';
const serverNonce = 'c'.repeat(64);
const hash = raw => createHash('sha256').update(raw).digest('hex');
const mac = raw => createHmac('sha256', secret).update(raw).digest('hex');
const script = `import json,sys
sys.path.insert(0,sys.argv[1])
from cowork_auth import request
options=json.loads(sys.argv[3])
try:
    result=request('/v1/tool?taskId=%E4%B8%AD',${JSON.stringify(secret)},options.pop('value',None),port=int(sys.argv[2]),**options)
    print(json.dumps({'result':result}))
except Exception as error:
    print(json.dumps({'error':str(error)}))
`;

async function fixture(t, mode = 'ok') {
  const calls = [], challenges = new Map();
  const server = http.createServer((req, res) => {
    const chunks = [];
    req.on('data', part => chunks.push(part));
    req.on('end', () => {
      const body = Buffer.concat(chunks);
      calls.push({ method: req.method, path: req.url, headers: req.headers, body });
      if (req.url === '/v1/auth') {
        const challenge = JSON.parse(body), { role, clientNonce, digest } = challenge;
        assert.deepEqual(Object.keys(challenge), ['role', 'clientNonce', 'digest']);
        assert.equal(req.headers.authorization, undefined); assert.match(clientNonce, /^[0-9a-f]{64}$/);
        const fields = `${role}\n${clientNonce}\n${serverNonce}\n${digest}`;
        challenges.set(clientNonce, { challenge, fields });
        if (mode === 'auth-timeout') return;
        if (mode === 'auth-redirect') { res.writeHead(307, { location: '/steal' }); res.end(); return; }
        res.writeHead(200, { 'content-type': 'application/json' });
        let proof = mac(`server\n${fields}`);
        if (mode === 'bad-server-proof') proof = '0'.repeat(64);
        if (mode === 'auth-digest-tamper') proof = mac(`server\n${role}\n${clientNonce}\n${serverNonce}\n${hash(Buffer.from('tampered'))}`);
        if (mode === 'duplicate-challenge') res.end(`{"serverNonce":"${serverNonce}","proof":"${proof}","proof":"${proof}"}`);
        else res.end(JSON.stringify({ serverNonce, proof }));
        return;
      }
      const match = /^Cowork-HMAC (agent|controller):([0-9a-f]{64}):([0-9a-f]{64}):([0-9a-f]{64})$/.exec(req.headers.authorization ?? '');
      assert.ok(match); assert.equal(match[3], serverNonce);
      const { challenge, fields } = challenges.get(match[2]);
      challenges.delete(match[2]);
      assert.equal(challenge.role, match[1]);
      assert.equal(challenge.digest, hash(Buffer.concat([Buffer.from(`${req.method}\n${req.url}\n`), body])));
      assert.equal(match[4], mac(`client\n${fields}`));
      const raw = Buffer.from(JSON.stringify({ ok: true, text: '中文 😀' }));
      let status = mode === 'signed-error' ? 409 : 200;
      const proof = mac(`response\n${fields}\n${status}\n${hash(raw)}`);
      if (mode === 'status-tamper') status = 403;
      if (mode === 'redirect') { res.writeHead(302, { location: '/steal' }); res.end(); return; }
      const headers = { 'content-type': 'application/json' };
      if (mode !== 'missing-proof') headers['x-cowork-proof'] = mode === 'duplicate-proof' ? [proof, proof] : proof;
      if (mode === 'byte-limit') headers['content-length'] = '1000';
      res.writeHead(status, headers);
      if (mode === 'body-timeout') { res.write('{'); return; }
      if (mode === 'drip-timeout') {
        const timer = setInterval(() => res.write(' '), 20);
        res.on('close', () => clearInterval(timer));
        return;
      }
      res.end(mode === 'body-tamper' ? Buffer.from('{"ok":false}') : raw);
    });
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise(resolve => { server.close(resolve); server.closeAllConnections(); }));
  return { calls, port: server.address().port };
}
async function invoke(port, options = {}) {
  const child = spawn(python, ['-B', '-c', script, helperDir, String(port), JSON.stringify(options)], {
    env: { ...process.env, HTTP_PROXY: 'http://127.0.0.1:1', HTTPS_PROXY: 'http://127.0.0.1:1', ALL_PROXY: 'http://127.0.0.1:1', NO_PROXY: '' },
    windowsHide: true,
  });
  const out = [], err = [];
  child.stdout.on('data', data => out.push(data)); child.stderr.on('data', data => err.push(data));
  const code = await new Promise((resolve, reject) => { child.on('error', reject); child.on('close', resolve); });
  assert.equal(code, 0, Buffer.concat(err).toString()); assert.equal(Buffer.concat(err).length, 0);
  return JSON.parse(Buffer.concat(out).toString());
}
for (const role of ['agent', 'controller']) test(`Python ${role} interoperates with independent fixture; no proxy or bearer leakage`, async t => {
  const f = await fixture(t);
  for (const value of [undefined, { text: 'Unicode 中文 😀' }]) {
    assert.deepEqual(await invoke(f.port, { role, value }), { result: { ok: true, text: '中文 😀' } });
  }
  assert.equal(f.calls.length, 4);
  assert.equal(f.calls[1].method, 'GET'); assert.equal(f.calls[1].body.length, 0);
  assert.equal(f.calls[3].method, 'POST'); assert.equal(JSON.parse(f.calls[3].body).text, 'Unicode 中文 😀');
  for (const call of f.calls) assert.doesNotMatch(JSON.stringify(call.headers) + call.body, /Bearer|test-only-python-secret/);
});
for (const mode of ['bad-server-proof', 'auth-digest-tamper', 'duplicate-challenge', 'auth-redirect', 'body-tamper', 'status-tamper', 'missing-proof', 'duplicate-proof', 'redirect', 'byte-limit']) {
  test(`Python fails closed: ${mode}, without retries or secret-bearing errors`, async t => {
    const f = await fixture(t, mode);
    assert.deepEqual(await invoke(f.port, { max_response_bytes: 128 }), { error: 'Native authentication unavailable' });
    assert.equal(f.calls.length, ['bad-server-proof', 'auth-digest-tamper', 'duplicate-challenge', 'auth-redirect'].includes(mode) ? 1 : 2);
  });
}
for (const mode of ['auth-timeout', 'body-timeout', 'drip-timeout']) test(`Python total deadline interrupts ${mode}`, { timeout: 4000 }, async t => {
  const f = await fixture(t, mode), start = performance.now();
  assert.deepEqual(await invoke(f.port, { timeout: 0.2 }), { error: 'Native authentication unavailable' });
  assert.ok(performance.now() - start < 2000);
  assert.equal(f.calls.length, mode === 'auth-timeout' ? 1 : 2);
});
test('Python preserves caller HTTP-error rejection even for correctly authenticated errors', async t => {
  const f = await fixture(t, 'signed-error');
  assert.deepEqual(await invoke(f.port), { error: 'Native authentication unavailable' });
});
