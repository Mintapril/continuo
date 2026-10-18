import http from 'node:http';
import { createHash, createHmac, randomBytes, timingSafeEqual } from 'node:crypto';

const AUTH_MAX_BYTES = 1024;
const HEADER_MAX_BYTES = 16 * 1024;
const unavailable = () => new Error('Native authentication unavailable');
const hex64 = value => typeof value === 'string' && value.length === 64 && /^[0-9a-f]+$/.test(value);
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const mac = (secret, text) => createHmac('sha256', secret).update(text, 'utf8').digest('hex');
const equalProof = (actual, expected) => hex64(actual) && timingSafeEqual(Buffer.from(actual, 'hex'), Buffer.from(expected, 'hex'));
function parseChallenge(bytes) {
  const raw = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(bytes);
  // A tiny exact schema also rejects duplicate JSON keys (JSON.parse alone loses them).
  const match = /^[ \t\r\n]*\{[ \t\r\n]*"(serverNonce|proof)"[ \t\r\n]*:[ \t\r\n]*"([0-9a-f]{64})"[ \t\r\n]*,[ \t\r\n]*"(serverNonce|proof)"[ \t\r\n]*:[ \t\r\n]*"([0-9a-f]{64})"[ \t\r\n]*\}[ \t\r\n]*$/.exec(raw);
  if (!match || match[1] === match[3]) throw unavailable();
  return { [match[1]]: match[2], [match[3]]: match[4] };
}

// One direct HTTP exchange. Never follows redirects, retries, consults proxy settings,
// decodes the body, or reflects socket errors/remote response text to the caller.
function rawRequest({ request, method, path, body, authorization, signal, maxBytes }) {
  return new Promise((resolve, reject) => {
    let req, response, settled = false;
    const cleanup = () => signal.removeEventListener('abort', fail);
    const destroy = () => { try { response?.destroy(); } catch {} try { req?.destroy(); } catch {} };
    function fail() {
      if (settled) return;
      settled = true; cleanup(); destroy(); reject(unavailable());
    }
    try {
      if (signal.aborted) return fail();
      signal.addEventListener('abort', fail, { once: true });
      req = request({
        protocol: 'http:', hostname: '127.0.0.1', port: 3091, method, path,
        agent: false, maxHeaderSize: HEADER_MAX_BYTES,
        headers: { accept: 'application/json',
          ...(authorization === undefined ? {} : { authorization }),
          ...(method === 'GET' ? {} : { 'content-type': 'application/json', 'content-length': body.length }) },
      }, res => {
        response = res;
        res.on('error', fail); res.on('aborted', fail);
        res.on('close', () => { if (!settled) fail(); });
        if (settled) { destroy(); return; }
        if (!Number.isInteger(res.statusCode) || res.statusCode < 200 || res.statusCode >= 600
          || (res.statusCode >= 300 && res.statusCode < 400)) return fail();
        const length = res.headers['content-length'];
        if (length !== undefined && (typeof length !== 'string' || !/^[0-9]+$/.test(length) || Number(length) > maxBytes)) return fail();
        const parts = []; let bytes = 0;
        res.on('data', part => {
          if (settled) return;
          const data = Buffer.isBuffer(part) ? part : Buffer.from(part);
          bytes += data.length;
          if (bytes > maxBytes) fail(); else parts.push(data);
        });
        res.on('end', () => {
          if (settled) return;
          if (signal.aborted || (length !== undefined && Number(length) !== bytes)) return fail();
          settled = true; cleanup();
          resolve({ status: res.statusCode, headers: res.headers, rawHeaders: res.rawHeaders, body: Buffer.concat(parts) });
        });
      });
      req.on('error', fail);
      if (settled) destroy(); else req.end(method === 'GET' ? undefined : body);
    } catch { fail(); }
  });
}

// Body is snapshotted once: the digest and the wire request use the identical bytes.
// The secret never crosses HTTP; only single-request, domain-separated MACs do.
export async function authenticatedRequest({ secret, role = 'agent', method, path, body = Buffer.alloc(0),
  signal, timeoutMs = 12_000, maxResponseBytes = 12 * 1024 * 1024, maxRequestBytes = maxResponseBytes,
  request = http.request } = {}) {
  let timer;
  try {
    if (typeof secret !== 'string' || secret.length < 32 || secret.length > 512 || /[^\x21-\x7e]/.test(secret)
      || !['agent', 'controller'].includes(role) || !['GET', 'POST'].includes(method)
      || typeof path !== 'string' || !path.startsWith('/') || /[^\x21-\x7e]/.test(path) || path.includes('#')
      || !Buffer.isBuffer(body) || body.length > maxRequestBytes || (method === 'GET' && body.length !== 0)
      || !Number.isSafeInteger(timeoutMs) || timeoutMs < 1 || signal?.aborted) throw unavailable();
    const deadline = new AbortController();
    const combined = signal ? AbortSignal.any([signal, deadline.signal]) : deadline.signal;
    timer = setTimeout(() => deadline.abort(), timeoutMs); timer.unref?.();
    const payload = Buffer.from(body);
    const digest = createHash('sha256').update(`${method}\n${path}\n`, 'utf8').update(payload).digest('hex');
    const clientNonce = randomBytes(32).toString('hex');
    const challenge = await rawRequest({ request, method: 'POST', path: '/v1/auth',
      body: Buffer.from(JSON.stringify({ role, clientNonce, digest }), 'utf8'), signal: combined, maxBytes: AUTH_MAX_BYTES });
    if (challenge.status !== 200 || !/^application\/json(?:\s*;|$)/i.test(challenge.headers['content-type'] ?? '')) throw unavailable();
    const data = parseChallenge(challenge.body);
    const fields = `${role}\n${clientNonce}\n${data.serverNonce}\n${digest}`;
    if (!equalProof(data.proof, mac(secret, `server\n${fields}`))) throw unavailable();
    combined.throwIfAborted();
    const response = await rawRequest({ request, method, path, body: payload, signal: combined, maxBytes: maxResponseBytes,
      authorization: `Cowork-HMAC ${role}:${clientNonce}:${data.serverNonce}:${mac(secret, `client\n${fields}`)}` });
    // Reject duplicate proof headers too; never accept an unsigned/modified error body.
    if (response.rawHeaders && response.rawHeaders.filter((entry, index) => index % 2 === 0 && entry.toLowerCase() === 'x-cowork-proof').length !== 1) throw unavailable();
    if (!equalProof(response.headers['x-cowork-proof'], mac(secret, `response\n${fields}\n${response.status}\n${hash(response.body)}`))) throw unavailable();
    combined.throwIfAborted();
    return { status: response.status, headers: response.headers, body: response.body };
  } catch { throw unavailable(); }
  finally { clearTimeout(timer); }
}
