import { randomUUID } from 'node:crypto';
import http from 'node:http';
import { assertRecord, integer, text, PHONE_TOOLS } from './policy.mjs';

export const BRIDGE_URL = 'http://127.0.0.1:3091/v1/tool';
export const PROTOCOL_VERSION = 1;
export const MAX_RESPONSE_BYTES = 6 * 1024 * 1024;
const known = new Set(Object.values(PHONE_TOOLS));
const observations = new Set(['phone.screenshot', 'phone.ui_tree']);
const observedActions = new Set(['phone.tap', 'phone.swipe', 'phone.type_text', 'phone.key']);
const mutations = new Set([...observedActions, 'phone.launch_app', 'phone.stop']);
const errorCodes = new Set(['INVALID_REQUEST', 'UNAUTHORIZED', 'UNKNOWN_TOOL', 'PERMISSION_DENIED', 'NOT_READY', 'PAUSED', 'STALE_OBSERVATION', 'OUT_OF_BOUNDS', 'BUSY', 'TIMEOUT', 'INTERNAL_ERROR']);
const string = (description, required = true) => ({ type: 'string', description, required });
const number = description => ({ type: 'integer', description, required: true });
export const PHONE_PARAMETERS = Object.freeze({
  'phone.status': {}, 'phone.screenshot': {}, 'phone.ui_tree': {}, 'phone.stop': {},
  'phone.tap': { observationId: string('ID from the latest screenshot or UI tree'), x: number('Pixel x'), y: number('Pixel y') },
  'phone.swipe': { observationId: string('ID from the latest screenshot or UI tree'), x1: number('Start x'), y1: number('Start y'), x2: number('End x'), y2: number('End y'), durationMs: number('Duration, 100–2000 ms') },
  'phone.type_text': { observationId: string('ID from the latest screenshot or UI tree'), text: string('Literal input text; never a command') },
  'phone.key': { observationId: string('ID from the latest screenshot or UI tree'), key: { type: 'string', enum: ['BACK', 'HOME', 'RECENTS', 'ENTER'], required: true } },
  'phone.launch_app': { packageName: string('Exact installed Android package name; no URI or intent') },
});
export function validateArguments(tool, args) {
  if (!known.has(tool)) throw new Error('Unknown phone capability');
  assertRecord(args, Object.keys(PHONE_PARAMETERS[tool]));
  if (observedActions.has(tool)) text(args.observationId, 'observationId', 128);
  for (const field of ['x', 'y', 'x1', 'y1', 'x2', 'y2']) if (field in args) integer(args[field], field, 0, 16383);
  if (tool === 'phone.swipe') integer(args.durationMs, 'durationMs', 100, 2000);
  if (tool === 'phone.type_text') text(args.text, 'text', 4000);
  if (tool === 'phone.key' && !['BACK', 'HOME', 'RECENTS', 'ENTER'].includes(args.key)) throw new Error('Unsupported phone key');
  if (tool === 'phone.launch_app' && !/^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$/.test(text(args.packageName, 'packageName', 255))) throw new Error('Invalid Android package name');
  return args;
}
function validateResult(tool, value) {
  if (tool === 'phone.status') {
    assertRecord(value, ['state', 'accessibilityReady', 'screenCaptureReady']);
    if (!['ready', 'paused', 'stopped', 'unavailable'].includes(value.state) || typeof value.accessibilityReady !== 'boolean' || typeof value.screenCaptureReady !== 'boolean') throw new Error('Invalid status result');
  } else if (observations.has(tool)) {
    const common = ['observationId', 'capturedAt', 'width', 'height'];
    assertRecord(value, [...common, ...(tool === 'phone.screenshot' ? ['mimeType', 'base64'] : ['nodes'])]);
    text(value.observationId, 'observationId', 128); integer(value.capturedAt, 'capturedAt', 1, Number.MAX_SAFE_INTEGER);
    integer(value.width, 'width', 1, 16384); integer(value.height, 'height', 1, 16384);
    if (tool === 'phone.screenshot') {
      if (!['image/png', 'image/jpeg'].includes(value.mimeType) || !/^[A-Za-z0-9+/]+={0,2}$/.test(text(value.base64, 'image', MAX_RESPONSE_BYTES))) throw new Error('Invalid screenshot encoding');
      const data = Buffer.from(value.base64, 'base64');
      if (data.toString('base64') !== value.base64) throw new Error('Noncanonical screenshot base64');
      if (value.mimeType === 'image/png' ? data.subarray(0, 8).toString('hex') !== '89504e470d0a1a0a' : data[0] !== 255 || data[1] !== 216 || data[2] !== 255) throw new Error('Invalid screenshot signature');
    } else {
      if (!Array.isArray(value.nodes) || value.nodes.length > 500) throw new Error('Invalid UI tree');
      for (const node of value.nodes) {
        assertRecord(node, ['id', 'text', 'description', 'className', 'bounds', 'clickable', 'editable']);
        text(node.id, 'node id', 128); text(node.text, 'node text', 2000, 0); text(node.description, 'description', 2000, 0); text(node.className, 'className', 255, 0);
        assertRecord(node.bounds, ['left', 'top', 'right', 'bottom']);
        for (const [key, n] of Object.entries(node.bounds)) integer(n, key, 0, 16384);
        if (node.bounds.left > node.bounds.right || node.bounds.top > node.bounds.bottom || node.bounds.right > value.width || node.bounds.bottom > value.height) throw new Error('Invalid UI node bounds');
        if (typeof node.clickable !== 'boolean' || typeof node.editable !== 'boolean') throw new Error('Invalid UI node flags');
      }
    }
  } else {
    assertRecord(value, ['performed']);
    if (value.performed !== true) throw new Error('Action was not confirmed performed');
  }
  return value;
}
export function validateResponse(data, request) {
  if (data?.ok === true) assertRecord(data, ['protocolVersion', 'requestId', 'ok', 'result']);
  else if (data?.ok === false) assertRecord(data, ['protocolVersion', 'requestId', 'ok', 'error']);
  else throw new Error('Invalid bridge response');
  if (data.protocolVersion !== PROTOCOL_VERSION || data.requestId !== request.requestId) throw new Error('Bridge response identity/version mismatch');
  if (!data.ok) {
    assertRecord(data.error, ['code', 'message']); text(data.error.code, 'error code', 64); text(data.error.message, 'error message', 1024);
    // Do not reflect backend error text or secrets into model/logs.
    throw new Error(`Phone bridge rejected operation (${errorCodes.has(data.error.code) ? data.error.code : 'BRIDGE_ERROR'})`);
  }
  return validateResult(request.tool, data.result);
}
async function readBounded(response) {
  if (Number(response.headers.get('content-length')) > MAX_RESPONSE_BYTES) throw new Error('Bridge response too large');
  if (!response.body) throw new Error('Empty bridge response');
  const reader = response.body.getReader(), chunks = [];
  let length = 0;
  try {
    for (;;) {
      const { done, value } = await reader.read(); if (done) break;
      length += value.length; if (length > MAX_RESPONSE_BYTES) throw new Error('Bridge response too large'); chunks.push(value);
    }
  } finally { await reader.cancel().catch(() => {}); }
  return JSON.parse(Buffer.concat(chunks).toString('utf8'));
}

/** Dedicated literal-loopback transport: bypasses DSH/global fetch proxy dispatchers. */
export function directLoopbackFetch(url, options) {
  if (url !== BRIDGE_URL) return Promise.reject(new Error('Only the fixed bridge endpoint is allowed'));
  return new Promise((resolve, reject) => {
    const req = http.request({ hostname: '127.0.0.1', port: 3091, path: '/v1/tool', method: 'POST', agent: false,
      headers: options.headers, signal: options.signal }, res => {
      if (Number(res.headers['content-length']) > MAX_RESPONSE_BYTES) { res.destroy(); reject(new Error('Bridge response too large')); return; }
      const chunks = []; let bytes = 0;
      res.on('data', chunk => {
        bytes += chunk.length;
        if (bytes > MAX_RESPONSE_BYTES) { res.destroy(new Error('Bridge response too large')); return; }
        chunks.push(chunk);
      });
      res.on('error', reject);
      res.on('end', () => {
        try { resolve(new Response([204, 205, 304].includes(res.statusCode) ? null : Buffer.concat(chunks), { status: res.statusCode, headers: { 'content-type': res.headers['content-type'] ?? '' } })); }
        catch { reject(new Error('Bridge response has invalid HTTP framing')); }
      });
    });
    req.on('error', reject); req.end(options.body);
  });
}
export function createPhoneBridge({ token, fetchImpl = directLoopbackFetch, now = Date.now }) {
  if (typeof token !== 'string' || !/^[A-Za-z0-9_-]{32,256}$/.test(token)) throw new Error('COWORK_BRIDGE_TOKEN must be a 32–256 character base64url secret');
  const recent = new Map();
  let busy = false; // One physical phone: serialization is process-wide, not per session.
  return {
    forget(sessionId) { recent.delete(sessionId); },
    async call(tool, args, { sessionId, signal }) {
      validateArguments(tool, args); text(sessionId, 'sessionId', 128);
      if (busy) throw new Error('Phone operation already in progress');
      if (observedActions.has(tool)) {
        const observed = recent.get(sessionId);
        if (!observed || observed.observationId !== args.observationId || now() - observed.receivedAt > 30000) throw new Error('A fresh observation from this session is required');
        for (const field of ['x', 'x1', 'x2']) if (field in args && args[field] >= observed.width) throw new Error('Coordinate outside observed display');
        for (const field of ['y', 'y1', 'y2']) if (field in args && args[field] >= observed.height) throw new Error('Coordinate outside observed display');
      }
      if (mutations.has(tool)) recent.clear(); // Any mutation invalidates every session's physical-screen observation.
      const request = { protocolVersion: PROTOCOL_VERSION, requestId: randomUUID(), sessionId, tool, arguments: structuredClone(args) };
      busy = true;
      const deadline = AbortSignal.timeout(15000);
      try {
        const response = await fetchImpl(BRIDGE_URL, {
          method: 'POST', redirect: 'error', credentials: 'omit', cache: 'no-store',
          headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json', accept: 'application/json' },
          body: JSON.stringify(request), signal: signal ? AbortSignal.any([signal, deadline]) : deadline,
        });
        if (!response.ok) throw new Error(`Phone bridge HTTP ${response.status}; operation outcome may be unknown`);
        if (!response.headers.get('content-type')?.toLowerCase().startsWith('application/json')) throw new Error('Bridge response must be JSON');
        const result = validateResponse(await readBounded(response), request);
        if (observations.has(tool)) recent.set(sessionId, { observationId: result.observationId, width: result.width, height: result.height, receivedAt: now() });
        return result;
      } catch (error) {
        recent.delete(sessionId);
        // Never include network/body errors: hostile responses can contain the transport secret.
        if (error.message?.startsWith('Phone bridge ') || error.message?.startsWith('Bridge response ')) throw error;
        throw new Error('Phone bridge operation failed; do not retry a possible mutation without observing state');
      } finally { busy = false; }
    },
  };
}
