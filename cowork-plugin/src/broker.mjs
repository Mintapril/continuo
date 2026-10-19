import { authenticatedRequest } from './auth.mjs';
import { randomUUID } from 'node:crypto';
import { readFile, realpath, lstat } from 'node:fs/promises';
import { homedir } from 'node:os';
import path from 'node:path';
import { check, fail, validate, object, str, bool, int, integer, boundedString, CoworkError } from './validation.mjs';
import { planGesture } from './gesture-path.mjs';

export const BROKER_URL = 'http://127.0.0.1:3091/v1/tool';
export const OBSERVATION_TTL_MS = 60_000;
export const MAX_RESPONSE_BYTES = 12 * 1024 * 1024;
// Explicit native denial vocabulary, checked against Java emission sites by strict-native-errors.test.mjs.
// Never reflect arbitrary code strings or native messages, even when they look like error identifiers.
const ERROR_CODES = new Set([
  'APPROVAL_PENDING', 'APPROVAL_STALE', 'APP_CONFLICT', 'APP_DENIED', 'APP_LAUNCH_DENIED',
  'APP_UNAVAILABLE', 'APP_UNKNOWN', 'ARGUMENTS_INVALID', 'CONTROLLER_OFFLINE', 'CONTROL_DENIED', 'LAUNCH_TIMEOUT',
  'CONTROL_INTERRUPTED', 'DISPLAY_BUSY', 'DISPLAY_FAILED', 'EXECUTION_STALE', 'FRAME_UNAVAILABLE',
  'HIGH_IMPACT_SINGLE', 'IME_POLICY_UNAVAILABLE', 'INPUT_REJECTED', 'INVALID_GESTURE', 'KEY_DENIED',
  'LOCKED', 'MAIN_CHANGED', 'MAIN_UNKNOWN', 'NODE_STALE', 'NOT_EDITABLE', 'NO_DISPLAY', 'PAUSED',
  'PLATFORM_ERROR', 'PROTOCOL_INVALID', 'RESTART_REQUIRED', 'REVISION_STALE', 'SCROLL_UNSUPPORTED',
  'SCREEN_SETTLING', 'SECRET_INPUT', 'SECRET_SCREEN', 'SECURE_WINDOW', 'SECURITY_UNAVAILABLE', 'SESSION_BUSY',
  'SESSION_INVALID', 'SESSION_MISMATCH', 'STALE_OBSERVATION', 'TEXT_LIMIT',
  'TEXT_UNSUPPORTED', 'TOOL_DENIED', 'TREE_REQUIRED', 'USER_CONTROL', 'USER_REQUIRED', 'WAIT_LIMIT', 'WRONG_DISPLAY',
  // Retain the previously recognized protocol codes for compatibility.
  'INVALID_REQUEST', 'UNAUTHORIZED', 'UNKNOWN_TOOL', 'PERMISSION_DENIED', 'NOT_READY',
  'OUT_OF_BOUNDS', 'BUSY', 'TIMEOUT', 'INTERNAL_ERROR', 'APP_NOT_APPROVED',
]);
const observed = { observationId: str };
const num = { type: 'number' };
export const PHONE_ARGUMENTS = Object.freeze({
  'phone.status': object(), 'phone.apps': object(),
  'phone.start': object({ packageName: str }, []),
  // Optional zoom: region [left, top, right, bottom] in screen pixels, scale optional; checked and resolved by planZoom.
  'phone.screenshot': object({ region: { type: 'array', items: int }, scale: int }, []), 'phone.ui_tree': object(),
  // Exactly one targeting mode (x and y, or elementId) is enforced in BrokerClient; observationId is the only schema-required key.
  'phone.tap': object({ ...observed, x: int, y: int, elementId: str }, ['observationId']),
  'phone.long_press': object({ ...observed, x: int, y: int, elementId: str, durationMs: int }, ['observationId']),
  'phone.swipe': object({ ...observed, x1: int, y1: int, x2: int, y2: int, durationMs: int }),
  // x/y optional: without them the largest scrollable region of the latest ui_tree is used.
  'phone.scroll': object({ ...observed, deltaY: int, x: int, y: int }, ['observationId', 'deltaY']),
  'phone.read_page': object({ ...observed, maxPages: int }, ['observationId']),
  'phone.type_text': object({ ...observed, text: str, elementId: str }, ['observationId', 'text']),
  'phone.key': object({ ...observed, key: { type: 'string', enum: ['BACK', 'ENTER', 'HOME', 'RECENTS'] } }, ['key']),
  'phone.wait': object({ timeoutMs: int }),
  'phone.launch_app': object({ ...observed, packageName: str }, ['packageName']),
  // 2..5 element inputs planned from one tree observation, a key (ENTER/BACK) only as the last step; checked in BrokerClient.
  'phone.batch': object({ ...observed, steps: { type: 'array', items: object({ tool: { type: 'string', enum: ['tap', 'long_press', 'type_text', 'key'] }, elementId: str, text: str, key: { type: 'string', enum: ['ENTER', 'BACK'] } }, ['tool']) } }),
  // 1..32 strokes, each one touch along SVG path data (path) or two fingers moving together (fingers: two paths);
  // exactly one of the two is checked and flattened into the native wire form by planGesture.
  'phone.gesture': object({ ...observed, strokes: { type: 'array', items: object({ path: str, fingers: { type: 'array', items: str }, durationMs: int, holdStartMs: int, holdEndMs: int }, []) }, viewBox: { type: 'array', items: num }, area: { type: 'array', items: int } }, ['observationId', 'strokes']),
  'phone.pause': object(), 'phone.stop': object(), 'phone.request_intervention': object({ reason: str }, []),
});
// Native-only tools: accepted by BrokerClient but never registered with, or visible to, the model.
export const INTERNAL_ARGUMENTS = Object.freeze({ 'phone.intervention_status': object() });
// Tools natively rejected with USER_CONTROL while the user controls the screen; their wrappers wait for hand-back.
export const USER_CONTROL_TOOLS = Object.freeze(['phone.screenshot', 'phone.ui_tree', 'phone.tap', 'phone.long_press', 'phone.swipe', 'phone.scroll', 'phone.read_page', 'phone.type_text', 'phone.key', 'phone.launch_app', 'phone.wait', 'phone.batch', 'phone.gesture']);
// Read-only observations are re-issued once after hand-back; everything else must be re-planned by the model.
export const REISSUE_AFTER_HANDBACK = Object.freeze(['phone.status', 'phone.apps', 'phone.screenshot', 'phone.ui_tree', 'phone.wait']);
export const HANDBACK_MAX_MS = 15 * 60_000, HANDBACK_POLL_MS = 1000;
const bounds = object({ left: int, top: int, right: int, bottom: int });
const metadata = { observationId: str, capturedAt: int, width: int, height: int };
export const PERFORMED = object({ performed: { type: 'boolean', const: true } });
// Model-visible result of a mutating tool: the native {performed:true}, or a not-performed hand-back notice.
export const MUTATION_OUTPUT = object({ performed: bool, userControlled: bool, note: str }, ['performed']);
// Compact node: empty fields are omitted; bounds are [left, top, right, bottom]; flags is a string of c(lickable) e(ditable) s(crollable) f(ocused) k(checked).
const node = object({ id: str, text: str, desc: str, role: str, rid: str, bounds: { type: 'array', items: int }, flags: str }, ['id', 'bounds']);
const TREE_RESULT = object({ ...metadata, title: str, package: str, nodes: { type: 'array', items: node }, complete: bool, advice: str });
// Inputs that return the settled screen as `observation` (exactly a phone.ui_tree result), so the model continues from
// the reply instead of observing again. changed: differs from the screen last read; settled:false: still changing when
// read; observationError: the input was performed but the screen could not be read (the plugin adds a note).
export const OBSERVED_AFTER = Object.freeze(['phone.tap', 'phone.long_press', 'phone.swipe', 'phone.scroll', 'phone.type_text', 'phone.key', 'phone.wait', 'phone.batch', 'phone.gesture']);
const afterInput = { changed: bool, settled: bool, observationError: str, observation: TREE_RESULT };
// Native result of the inputs above; RECENTS answers with a note and no observation.
const OBSERVED_PERFORMED = object({ performed: { type: 'boolean', const: true }, note: str, ...afterInput }, ['performed']);
// Model-visible result of the inputs above, including the not-performed hand-back notice.
export const OBSERVED_OUTPUT = object({ performed: bool, userControlled: bool, note: str, ...afterInput }, ['performed']);
export const PHONE_RESULTS = Object.freeze({
  'phone.status': object({ state: { type: 'string', enum: ['ready', 'paused', 'stopped', 'unavailable'] }, accessibilityReady: bool, screenCaptureReady: bool, userControl: bool }, ['state', 'accessibilityReady', 'screenCaptureReady']),
  'phone.apps': object({ apps: { type: 'array', items: object({ packageName: str, label: str, approved: bool }) } }),
  // A zoomed screenshot adds region, scale, margin and the image size; width/height stay the screen's.
  'phone.screenshot': object({ ...metadata, mimeType: { type: 'string', enum: ['image/png', 'image/jpeg', 'image/webp'] }, base64: str, region: { type: 'array', items: int }, scale: int, margin: int, imageWidth: int, imageHeight: int }, [...Object.keys(metadata), 'mimeType', 'base64']),
  'phone.ui_tree': TREE_RESULT,
  'phone.scroll': object({ performed: { type: 'boolean', const: true }, moved: bool, note: str, ...afterInput }, ['performed']),
  'phone.read_page': object({ performed: { type: 'boolean', const: true }, text: str, pages: int, reachedEnd: bool, title: str }, ['performed', 'text', 'pages', 'reachedEnd']),
  // completed: steps performed; a stopped batch adds stoppedAt (= completed) and stopReason, uncertain when that step's input may have partly happened.
  'phone.batch': object({ performed: { type: 'boolean', const: true }, completed: int, stoppedAt: int, stopReason: str, uncertain: bool, note: str, ...afterInput }, ['performed', 'completed']),
  // Same shape for strokes: completed strokes performed; uncertain when the stopped stroke was cut off midway.
  'phone.gesture': object({ performed: { type: 'boolean', const: true }, completed: int, stoppedAt: int, stopReason: str, uncertain: bool, note: str, ...afterInput }, ['performed', 'completed']),
  // Native result only; the model-visible result is built by the blocking wrapper in restricted.mjs.
  'phone.request_intervention': object({ performed: { type: 'boolean', const: true }, interventionId: str }),
});
export const INTERNAL_RESULTS = Object.freeze({
  'phone.intervention_status': object({ active: bool, interventionId: str, outcome: { type: 'string', enum: ['none', 'active', 'handed_back', 'expired', 'cancelled'] } }),
});

// Zoomed screenshot limits, the same as ScreenZoom.java: an image side, rulers (margin px a side) included, is at most maxImage.
export const ZOOM = Object.freeze({ maxScale: 8, minSide: 8, maxImage: 1536, margin: 60 });
// The native form of a zoomed screenshot ({region, scale}; undefined for a plain one), explained here in words:
// natively a bad region is only ARGUMENTS_INVALID. Without scale, the largest that fits.
export function planZoom(args, width, height) {
  if (args.region === undefined) { check(args.scale === undefined, 'scale needs a region'); return undefined; }
  check(args.region.length === 4, 'region is [left, top, right, bottom] in screen pixels');
  const [left, top, right, bottom] = args.region;
  integer(left, 0, width - ZOOM.minSide, 'region left'); integer(top, 0, height - ZOOM.minSide, 'region top');
  integer(right, left + ZOOM.minSide, width, 'region right'); integer(bottom, top + ZOOM.minSide, height, 'region bottom');
  const w = right - left, h = bottom - top, side = ZOOM.maxImage - 2 * ZOOM.margin;
  const fit = Math.min(ZOOM.maxScale, Math.floor(side / Math.max(w, h)));
  check(fit >= 1, `region ${w}x${h} is too large to zoom (at most ${side} px a side); use a smaller region or a plain screenshot`);
  if (args.scale !== undefined) { integer(args.scale, 1, ZOOM.maxScale, 'scale'); check(args.scale <= fit, `scale ${args.scale} makes the image too large for a ${w}x${h} region; at most ${fit}`); }
  return { region: [left, top, right, bottom], scale: args.scale ?? fit };
}
// A zoomed reply is exactly the requested zoom, image size included (the model maps ruler readings with it); a plain
// screenshot carries none of it.
function checkZoom(result, zoom) {
  if (zoom === undefined) return check(['region', 'scale', 'margin', 'imageWidth', 'imageHeight'].every(key => result[key] === undefined), 'Unrequested zoom in screenshot result', 'BROKER_PROTOCOL');
  const [left, top, right, bottom] = zoom.region, image = Buffer.from(result.base64, 'base64');
  const width = (right - left) * zoom.scale + 2 * ZOOM.margin, height = (bottom - top) * zoom.scale + 2 * ZOOM.margin;
  check(result.region?.length === 4 && result.region.every((value, i) => value === zoom.region[i]) && result.scale === zoom.scale && result.margin === ZOOM.margin
    && result.imageWidth === width && result.imageHeight === height && result.mimeType === 'image/png' && image.length >= 24
    && image.readUInt32BE(16) === width && image.readUInt32BE(20) === height, 'Zoomed screenshot does not match the request', 'BROKER_PROTOCOL');
}

export async function loadAgentSecret(configPath = process.env.DSH_COWORK_BROKER_CONFIG ?? path.join(homedir(), '.config', 'dsh', 'cowork-broker.json')) {
  check(path.isAbsolute(configPath), 'Broker config path must be absolute', 'BROKER_CONFIG');
  const actual = await realpath(configPath);
  const workspace = path.resolve('/workspace/cowork');
  check(actual !== workspace && !actual.startsWith(workspace + path.sep), 'Broker config must be outside Cowork workspace', 'BROKER_CONFIG');
  const stat = await lstat(configPath);
  check(stat.isFile() && !stat.isSymbolicLink() && stat.size <= 4096, 'Broker config must be a small regular file, not a link', 'BROKER_CONFIG');
  if (process.platform !== 'win32') {
    check((stat.mode & 0o077) === 0 && stat.uid === process.getuid(), 'Broker config must be owned by runtime user with mode 0600', 'BROKER_CONFIG');
  }
  let data;
  try { data = JSON.parse(await readFile(configPath, 'utf8')); } catch { fail('BROKER_CONFIG', 'Cannot read trusted broker configuration'); }
  validate(object({ agentSecret: str }), data);
  check(/^[\x21-\x7e]{32,512}$/.test(data.agentSecret), 'Invalid agent secret format', 'BROKER_CONFIG');
  return data.agentSecret;
}

// Native http avoids environment proxy routing. No redirects, retry or alternate endpoint.
export async function brokerTransport(envelope, secret, signal) {
  const response = await authenticatedRequest({ secret, role: 'agent', method: 'POST', path: '/v1/tool',
    body: Buffer.from(JSON.stringify(envelope), 'utf8'), signal, timeoutMs: 25_000, maxResponseBytes: MAX_RESPONSE_BYTES });
  if (response.status !== 200 || !/^application\/json(?:;|$)/i.test(response.headers['content-type'] ?? '')) throw new Error('Broker refused request');
  try { return JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(response.body)); }
  catch { throw new Error('Malformed broker response'); }
}

const defaultSleep = (ms, signal) => new Promise((resolve, reject) => {
  signal?.throwIfAborted();
  const timer = setTimeout(() => { signal?.removeEventListener('abort', onAbort); resolve(); }, ms);
  const onAbort = () => { clearTimeout(timer); reject(signal.reason ?? new Error('aborted')); };
  signal?.addEventListener('abort', onAbort, { once: true });
});
// Polls the internal status until the user hands control back or HANDBACK_MAX_MS passes.
// Abort-aware (a pending sleep rejects on the signal); one native call per interval, never a busy loop.
export async function waitForHandback(call, signal, { sleep = defaultSleep, now = Date.now } = {}) {
  const deadline = now() + HANDBACK_MAX_MS;
  for (;;) {
    signal?.throwIfAborted();
    const status = await call('phone.intervention_status', {}, signal);
    if (status.active === false) return status.outcome === 'expired' || status.outcome === 'cancelled' ? status.outcome : 'handed_back';
    if (now() >= deadline) return 'expired';
    await sleep(HANDBACK_POLL_MS, signal);
  }
}

// A refusal is not a dead end: tell the model what to do next instead of only naming a code.
export const REFUSAL_HINT = Object.freeze({
  SCREEN_SETTLING: 'The screen is changing (app switch, dialog or animation) and the session is still running: call phone_wait (it returns the settled screen).',
  STALE_OBSERVATION: 'The screen changed since that observation: call phone_ui_tree again.',
  NODE_STALE: 'That element moved or disappeared: read phone_ui_tree again, or use coordinates with the same observationId.',
  INPUT_REJECTED: 'This window refuses injected input (usually a system permission or chooser dialog). The session is still running: ask the user to take over with phone_request_intervention.',
  APP_DENIED: 'The target is outside the owned work-profile display or refused: observe again, or ask the user. The session is still running.',
  APP_UNKNOWN: 'The AI screen shows no window of the app yet: call phone_wait (it returns the screen); if the screen stays empty call phone_launch_app with the package.',
  LAUNCH_TIMEOUT: 'The app is still starting: call phone_wait (up to 10000 ms; it returns the screen); do not call phone_start again.',
  SESSION_MISMATCH: 'The AI screen belongs to another session or turn: call phone_status; the screen is claimed automatically by your next phone_launch_app / phone_ui_tree.',
  SESSION_BUSY: 'Another session currently uses the AI screen: wait, then call phone_status.',
  CONTROL_INTERRUPTED: 'The user paused or stopped the workspace: call phone_status, then phone_launch_app if it is stopped.',
  PAUSED: 'The workspace is paused: the user must resume it, or start a new session.',
  LOCKED: 'The phone is locked: ask the user to unlock it.',
  CONTROLLER_OFFLINE: 'The Cowork controls are not connected: the user must open the app.',
  TREE_REQUIRED: 'Call phone_ui_tree for the current screen before a screenshot.',
  HIGH_IMPACT_SINGLE: 'The first step must run on its own: it sends, pays, deletes, confirms, grants, buys or publishes, or its target has no label to judge it by. Nothing was performed.',
  USER_CONTROL: 'The user took over the screen: your next phone call waits until they hand it back; observe again then.',
  RUNNING: 'The action was skipped because the task is no longer running: call phone_status and continue with phone_launch_app / phone_ui_tree.'
});
// Why a batch stopped before a step, besides the refusal codes above. Never reflected when unknown.
const BATCH_STOPS = new Set(['WINDOW_CHANGED', 'SCREEN_CHANGED_ELSEWHERE', 'BATCH_TIME_LIMIT']);
const BATCH_STOP_HINT = Object.freeze({
  WINDOW_CHANGED: 'The previous step opened or closed a window (dialog, menu, suggestions, new page).',
  SCREEN_CHANGED_ELSEWHERE: 'The previous step changed the screen outside its own element (a message, a new section, a list update).',
  NODE_STALE: 'The next target no longer showed what was observed (moved, gone, or other content).',
  HIGH_IMPACT_SINGLE: 'That step must run on its own: it sends, pays, deletes, confirms, grants, buys or publishes, its target has no label to judge it by, or it is ENTER outside a search field.',
  BATCH_TIME_LIMIT: 'The batch took too long; the screen reacts slowly.',
});
const GESTURE_STOP_HINT = Object.freeze({
  WINDOW_CHANGED: 'The previous stroke opened or closed a window (dialog, menu, new page).',
  USER_CONTROL: 'The user took over the screen.',
  GESTURE_TIME_LIMIT: 'The screen reacted slowly, so the call ran out of time before this stroke.',
});
const BATCH_FIELDS = Object.freeze({ tap: ['tool', 'elementId'], long_press: ['tool', 'elementId'], type_text: ['tool', 'elementId', 'text'], key: ['tool', 'key'] });
// Refusals raised by argument or element validation, before a single event reached the display.
export const KEEPS_OBSERVATION = Object.freeze(new Set([
  'ARGUMENTS_INVALID', 'HIGH_IMPACT_SINGLE', 'KEY_DENIED', 'INVALID_GESTURE', 'NODE_STALE', 'NOT_EDITABLE', 'SECRET_INPUT',
  'TEXT_UNSUPPORTED', 'TEXT_LIMIT', 'WAIT_LIMIT', 'SCROLL_UNSUPPORTED', 'TREE_REQUIRED', 'TOOL_DENIED',
]));
export const TREE_REQUIRED_MESSAGE = 'Screenshot refused: call phone_ui_tree first and read the UI elements of the current screen. The tree is the primary, cheaper and more precise observation; screenshots are only for screens whose tree is incomplete (complete:false), custom-drawn content (WebView, game, canvas) or information the tree lacks. The requirement renews after every action, wait, or hand-back.';
export class BrokerClient {
  #observations = new Map(); #tree = new Map(); #tail = Promise.resolve(); #treeRead = new Set(); #incomplete = new Set();
  constructor({ secret, transport = brokerTransport, now = Date.now, uuid = randomUUID } = {}) {
    check(typeof secret === 'string' && /^[\x21-\x7e]{32,512}$/.test(secret), 'Missing trusted agent secret', 'BROKER_CONFIG');
    this.secret = secret; this.transport = transport; this.now = now; this.uuid = uuid;
  }
  call(sessionId, name, args, signal) {
    // Shared device dispatch ordering, also covering concurrent direct invocations.
    const task = this.#tail.then(() => this.#call(sessionId, name, args, signal));
    this.#tail = task.catch(() => {}); return task;
  }
  forget(sessionId) { this.#observations.delete(sessionId); this.#tree.delete(sessionId); this.#treeRead.delete(sessionId); this.#incomplete.delete(sessionId); }
  // Checks a schema-valid screenshot or tree, then makes it the session's current observation (a tree also binds element ids).
  #admit(sessionId, name, result) {
    boundedString(result.observationId, 256, 'observationId');
    integer(result.width, 1, 8192, 'width'); integer(result.height, 1, 8192, 'height');
    check(this.now() - result.capturedAt <= OBSERVATION_TTL_MS && result.capturedAt <= this.now() + 1000, 'Broker observation already stale', 'STALE_OBSERVATION');
    if (name === 'phone.screenshot') {
      check(result.base64.length <= 11_184_812 && result.base64.length > 0 && Buffer.from(result.base64, 'base64').toString('base64') === result.base64, 'Invalid or oversized canonical image base64', 'BROKER_PROTOCOL');
      const image = Buffer.from(result.base64, 'base64');
      check(image.length <= 8 * 1024 * 1024, 'Decoded image exceeds 8 MiB', 'BROKER_PROTOCOL');
      const signature = result.mimeType === 'image/png' ? image.subarray(0, 8).toString('hex') === '89504e470d0a1a0a' : result.mimeType === 'image/jpeg' ? image[0] === 255 && image[1] === 216 && image[2] === 255 : image.subarray(0, 4).toString() === 'RIFF' && image.subarray(8, 12).toString() === 'WEBP';
      check(signature, 'Image signature does not match MIME type', 'BROKER_PROTOCOL');
    } else {
      check(result.nodes.length <= 2000, 'UI tree exceeds node limit', 'BROKER_PROTOCOL');
      boundedString(result.advice, 1000, 'advice', 0);
      const ids = new Set();
      for (const node of result.nodes) {
        boundedString(node.id, 256, 'node.id'); check(!ids.has(node.id), 'Duplicate UI node id', 'BROKER_PROTOCOL'); ids.add(node.id);
        for (const field of ['text', 'desc', 'role', 'rid', 'flags']) if (node[field] !== undefined) boundedString(node[field], 4000, `node.${field}`, 0);
        check(node.bounds.length === 4 && node.bounds.every(Number.isSafeInteger), 'UI bounds malformed', 'BROKER_PROTOCOL');
        const [left, top, right, bottom] = node.bounds;
        check(left >= 0 && top >= 0 && right >= left && bottom >= top && right <= result.width && bottom <= result.height, 'UI bounds outside observation', 'BROKER_PROTOCOL');
      }
    }
    this.#observations.set(sessionId, result);
    if (name === 'phone.ui_tree') {
      this.#tree.set(sessionId, result); this.#treeRead.add(sessionId);
      if (result.complete === false) this.#incomplete.add(sessionId); else this.#incomplete.delete(sessionId);
    }
  }
  async #call(sessionId, name, args, signal) {
    check(Object.hasOwn(PHONE_ARGUMENTS, name) || Object.hasOwn(INTERNAL_ARGUMENTS, name), 'Phone tool is not allowed', 'TOOL_DENIED');
    boundedString(sessionId, 256, 'sessionId'); validate(PHONE_ARGUMENTS[name] ?? INTERNAL_ARGUMENTS[name], args);
    signal?.throwIfAborted();
    // Code-level rule (also enforced natively): no screenshot before the UI structure of the current screen was read.
    // A tree that could not describe the screen (complete:false) already tells the model to screenshot: allow it.
    if (name === 'phone.screenshot') check(this.#treeRead.has(sessionId) || this.#incomplete.has(sessionId), TREE_REQUIRED_MESSAGE, 'TREE_REQUIRED');
    else if (!['phone.status', 'phone.apps', 'phone.ui_tree', 'phone.intervention_status'].includes(name)) { this.#treeRead.clear(); this.#incomplete.clear(); } // one physical device: any change renews the requirement for every session
    if (args.packageName !== undefined) check(/^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$/.test(args.packageName) && args.packageName.length <= 255, 'Invalid Android packageName');
    if (name === 'phone.request_intervention' && args.reason !== undefined) boundedString(args.reason, 120, 'reason');
    if (name === 'phone.tap' || name === 'phone.long_press') {
      // Exactly one targeting mode: both coordinates, or one element of the latest ui_tree observation.
      const point = args.x !== undefined || args.y !== undefined;
      check(!(point && args.elementId !== undefined), 'Use either x and y, or elementId, not both');
      check(point ? args.x !== undefined && args.y !== undefined : args.elementId !== undefined, 'Provide both x and y, or an elementId');
      if (args.elementId !== undefined) boundedString(args.elementId, 256, 'elementId');
    }
    if (name === 'phone.long_press' && args.durationMs !== undefined) integer(args.durationMs, 500, 2000, 'durationMs');
    if (name === 'phone.swipe') integer(args.durationMs, 100, 2000, 'durationMs');
    if (name === 'phone.read_page' && args.maxPages !== undefined) integer(args.maxPages, 1, 12, 'maxPages');
    if (name === 'phone.scroll') check((args.x === undefined) === (args.y === undefined), 'Provide both x and y, or neither');
    if (name === 'phone.wait') integer(args.timeoutMs, 0, 10000, 'timeoutMs');
    if (name === 'phone.scroll') { integer(args.deltaY, -1000, 1000, 'deltaY'); check(args.deltaY !== 0, 'deltaY must be nonzero'); }
    if (name === 'phone.type_text') { boundedString(args.text, 4000, 'text', 0); if (args.elementId !== undefined) boundedString(args.elementId, 256, 'elementId'); }
    if (name === 'phone.batch') {
      check(args.steps.length >= 2 && args.steps.length <= 5, 'phone_batch takes 2..5 steps; use the single tool for one input');
      let text = 0;
      args.steps.forEach((step, index) => {
        const fields = BATCH_FIELDS[step.tool];
        check(Object.keys(step).length === fields.length && fields.every(key => Object.hasOwn(step, key)), `steps[${index}] (${step.tool}) takes exactly ${fields.join(', ')}`);
        if (step.tool === 'key') check(index === args.steps.length - 1, 'A key (ENTER or BACK) may only be the last step of a batch');
        else boundedString(step.elementId, 256, 'elementId');
        if (step.text !== undefined) { boundedString(step.text, 4000, 'text', 0); text += step.text.length; }
      });
      const targets = args.steps.filter(step => step.tool !== 'key').map(step => step.elementId);
      check(new Set(targets).size === targets.length, 'Each element may be the target of only one step in a batch');
      check(text <= 8000, 'phone_batch text is limited to 8000 characters in total');
    }
    let wire; // what the native broker receives when it differs from the model's arguments
    if (args.observationId !== undefined) {
      boundedString(args.observationId, 256, 'observationId');
      // Element ids belong to the retained phone_ui_tree observation: a screenshot taken afterwards does not take
      // them away, and a refused element action does not force a new observation. A coordinate action may use the
      // newest observation or, when a screenshot came in between, the tree the model is still targeting.
      // [elementId, must be editable] of every element this call targets (a batch: all of its steps).
      const elements = name === 'phone.batch' ? args.steps.filter(step => step.tool !== 'key').map(step => [step.elementId, step.tool === 'type_text'])
        : args.elementId !== undefined ? [[args.elementId, name === 'phone.type_text']] : [];
      const treeObs = elements.length ? this.#tree.get(sessionId) : undefined;
      const offered = elements.length ? [treeObs] : [this.#observations.get(sessionId), this.#tree.get(sessionId)];
      const obs = offered.find(candidate => candidate && candidate.observationId === args.observationId) ?? offered[0];
      check(obs && obs.observationId === args.observationId && this.now() - obs.capturedAt >= -1000 && this.now() - obs.capturedAt <= OBSERVATION_TTL_MS, 'Observation missing, stale, consumed, or from another session; observe again', 'STALE_OBSERVATION');
      for (const key of ['x', 'x1', 'x2']) if (args[key] !== undefined) integer(args[key], 0, obs.width - 1, key);
      for (const key of ['y', 'y1', 'y2']) if (args[key] !== undefined) integer(args[key], 0, obs.height - 1, key);
      // Path problems are explained here, before the observation is consumed: natively they are only a code.
      if (name === 'phone.gesture') wire = { observationId: args.observationId, strokes: planGesture(args, obs.width, obs.height) };
      if (elements.length) {
        check(Array.isArray((treeObs ?? obs).nodes), 'elementId requires a phone_ui_tree observation; screenshot observations have no elements');
        for (const [id, editable] of elements) check((treeObs ?? obs).nodes.some(n => n.id === id && (!editable || (n.flags ?? '').includes('e'))),
          editable ? 'elementId must identify an editable node in the current tree' : 'elementId must identify a node in the current tree');
      }
      if (name === 'phone.batch') check(treeObs.complete !== false, 'phone_batch needs a complete tree; this one has complete:false, so act step by step');
      // Coordinates consume the observation they were bound to; an id that is also the retained tree is single-use
      // through both slots (element ids are verified natively against the tree instead; a batch is replaced by the
      // screen it returns).
      if (!elements.length) {
        // One physical screen: another session's tree is as stale as this session's pixel observation.
        for (const other of [...this.#tree.keys()]) if (other !== sessionId) this.#tree.delete(other);
        if (this.#tree.get(sessionId)?.observationId === obs.observationId) this.#tree.delete(sessionId);
        this.#observations.clear();
      }
    } else if (!['phone.status', 'phone.apps', 'phone.screenshot', 'phone.ui_tree', 'phone.intervention_status'].includes(name) && !(name === 'phone.key' && args.key === 'RECENTS')) { this.#observations.clear(); this.#tree.clear(); }
    // A zoom is checked against the screen last observed (a screenshot always follows a tree); natively against the display.
    if (name === 'phone.screenshot') { const screen = this.#observations.get(sessionId) ?? this.#tree.get(sessionId); wire = planZoom(args, screen?.width ?? 8192, screen?.height ?? 8192); }
    const requestId = this.uuid();
    const envelope = { protocolVersion: 1, requestId, sessionId, tool: name, arguments: wire ?? args };
    // Native start/launch may wait up to 8 s for a slow app plus display setup; the request must outlive it.
    const timeout = AbortSignal.timeout(25_000);
    const combined = signal ? AbortSignal.any([signal, timeout]) : timeout;
    let response;
    try { response = await this.transport(envelope, this.secret, combined); }
    catch { this.forget(sessionId); fail('BROKER_UNAVAILABLE', 'Native broker failed or timed out; no automatic retry. Observe status before continuing.'); }
    combined.throwIfAborted();
    try {
      validate(response?.ok === true ? object({ protocolVersion: int, requestId: str, ok: bool, result: { type: 'object', properties: {}, required: [], additionalProperties: false } }) : object({ protocolVersion: int, requestId: str, ok: bool, error: object({ code: str, message: str }) }), response?.ok === true ? { ...response, result: {} } : response);
      check(response.protocolVersion === 1 && response.requestId === requestId, 'Broker correlation mismatch', 'BROKER_PROTOCOL');
      if (response.ok !== true) {
        boundedString(response.error.code, 64, 'error.code'); boundedString(response.error.message, 1024, 'error.message');
        // A refusal raised before any event was injected leaves the screen exactly as observed, so the model may
        // retry the same observation (a coordinate fallback after a stale element). Everything else - an uncertain
        // injection, a changed screen, a lost session - drops it: an observation is never replayed after that.
        if (!KEEPS_OBSERVATION.has(response.error.code)) this.forget(sessionId);
        // The only error that is a normal flow: the user holds the screen. Distinguishable, never BROKER_REJECTED.
        if (response.error.code === 'TREE_REQUIRED') fail('TREE_REQUIRED', TREE_REQUIRED_MESSAGE);
        if (response.error.code === 'USER_CONTROL') fail('USER_CONTROL', 'The user is controlling the screen; the action was not performed.');
        // Native error text is not reflected: it may contain secrets or untrusted UI text.
        const code = ERROR_CODES.has(response.error.code) ? response.error.code : 'BROKER_ERROR';
        fail('BROKER_REJECTED', `Native broker rejected the action (${code}). ${REFUSAL_HINT[code] ?? 'The workspace is unchanged; adjust the request and retry.'}`);
      }
      let reply = response.result, observation;
      // The settled screen after an input is checked on its own: a bad observation must never turn a performed input
      // into an error, or the model would repeat an input that already happened.
      if (OBSERVED_AFTER.includes(name) && Object.hasOwn(reply, 'observation')) ({ observation, ...reply } = reply);
      const result = validate(PHONE_RESULTS[name] ?? INTERNAL_RESULTS[name] ?? (OBSERVED_AFTER.includes(name) ? OBSERVED_PERFORMED : PERFORMED), reply);
      if (name === 'phone.screenshot') checkZoom(result, wire);
      if (name === 'phone.screenshot' || name === 'phone.ui_tree') this.#admit(sessionId, name, result);
      if (observation !== undefined) {
        try { this.#admit(sessionId, 'phone.ui_tree', validate(TREE_RESULT, observation, 'observation')); result.observation = observation; }
        catch { result.observationError = 'BROKER_ERROR'; }
      }
      if (name === 'phone.batch') {
        const total = args.steps.length;
        integer(result.completed, 1, total, 'completed');
        if (result.stopReason === undefined) check(result.stoppedAt === undefined && result.uncertain === undefined && result.completed === total, 'Inconsistent batch result', 'BROKER_PROTOCOL');
        else {
          check(result.stoppedAt === result.completed && result.completed < total, 'Inconsistent batch result', 'BROKER_PROTOCOL');
          const code = ERROR_CODES.has(result.stopReason) || BATCH_STOPS.has(result.stopReason) ? result.stopReason : 'BROKER_ERROR';
          const next = result.stoppedAt + 1;
          result.stopReason = code;
          result.note = [`Batch stopped before step ${next} of ${total} (${code}): ${result.completed === 1 ? 'step 1 was' : `steps 1-${result.completed} were`} performed, the rest were not.`,
            result.uncertain ? `Step ${next} may have partly happened: check the screen before repeating it.` : '',
            BATCH_STOP_HINT[code] ?? REFUSAL_HINT[code] ?? '', 'Continue from the returned screen with single steps.'].filter(Boolean).join(' ');
        }
      }
      if (name === 'phone.gesture') {
        const total = args.strokes.length;
        integer(result.completed, 1, total, 'completed');
        if (result.stopReason === undefined) check(result.stoppedAt === undefined && result.uncertain === undefined && result.completed === total, 'Inconsistent gesture result', 'BROKER_PROTOCOL');
        else {
          check(result.stoppedAt === result.completed && result.completed < total, 'Inconsistent gesture result', 'BROKER_PROTOCOL');
          const code = ERROR_CODES.has(result.stopReason) || Object.hasOwn(GESTURE_STOP_HINT, result.stopReason) ? result.stopReason : 'BROKER_ERROR';
          const next = result.stoppedAt + 1;
          result.stopReason = code;
          result.note = [`Gesture stopped at stroke ${next} of ${total} (${code}): ${result.completed === 1 ? 'stroke 1 was' : `strokes 1-${result.completed} were`} performed, stroke ${next}${next < total ? ' and later ones were' : ' was'} not.`,
            result.uncertain ? `Stroke ${next} was cut off midway and may be partly drawn: look at the screen before drawing it again.` : '',
            GESTURE_STOP_HINT[code] ?? REFUSAL_HINT[code] ?? '', 'Continue from the returned screen and send only the strokes that are still missing.'].filter(Boolean).join(' ');
        }
      }
      if (result.observationError !== undefined) {
        // The input changed the screen and nothing current describes it: drop what this session still holds.
        this.forget(sessionId);
        const code = ERROR_CODES.has(result.observationError) ? result.observationError : 'BROKER_ERROR';
        result.observationError = code;
        result.note = `${result.note ? `${result.note} ` : ''}The action was performed, but the screen could not be read afterwards (${code}). ${REFUSAL_HINT[code] ?? 'Call phone_ui_tree before the next action.'}`;
      }
      if (name === 'phone.status' && (result.state !== 'ready' || result.userControl === true)) this.forget(sessionId);
      if (name === 'phone.intervention_status') check(result.active ? result.outcome === 'active' : result.outcome !== 'active', 'Inconsistent intervention status', 'BROKER_PROTOCOL');
      if (name === 'phone.apps') {
        check(result.apps.length <= 500, 'Too many apps', 'BROKER_PROTOCOL');
        for (const app of result.apps) { boundedString(app.packageName, 255, 'packageName'); boundedString(app.label, 256, 'label', 0); }
      }
      return result;
    } catch (error) {
      // A native refusal already decided above what it keeps; only a protocol failure still drops the observations.
      if (!(error instanceof CoworkError && error.code === 'BROKER_REJECTED')) this.forget(sessionId);
      throw error;
    }
  }
}

