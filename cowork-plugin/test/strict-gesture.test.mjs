import test from 'node:test';
import assert from 'node:assert/strict';
import { parsePath, flattenPath, planGesture, GESTURE } from '../src/gesture-path.mjs';
import { BrokerClient } from '../src/broker.mjs';

const secret = 'test-only-secret-not-real'.padEnd(40, 'x'), now = 100000;
const W = 720, H = 1600;
const plan = (strokes, extra = {}) => planGesture({ observationId: 'obs', strokes, ...extra }, W, H);
const points = path => plan([{ path }])[0].fingers[0];
const pairs = flat => flat.reduce((out, v, i) => (i % 2 ? out.at(-1).push(v) : out.push([v]), out), []);
const near = (actual, expected, label) => {
  assert.equal(actual.length, expected.length, `${label}: point count`);
  actual.forEach((p, i) => assert.ok(Math.abs(p[0] - expected[i][0]) < 1e-9 && Math.abs(p[1] - expected[i][1]) < 1e-9, `${label}: point ${i} ${p} != ${expected[i]}`));
};

// ---- SVG path syntax ----
test('lines: absolute, relative, H/V, implicit lineto after a moveto, Z back to the start', () => {
  assert.deepEqual(flattenPath('M10 20 L30 40 h10 v-5', 'p'), [[10, 20], [30, 40], [40, 40], [40, 35]]);
  assert.deepEqual(flattenPath('M0 0 10 10 20 0', 'p'), [[0, 0], [10, 10], [20, 0]]);
  assert.deepEqual(flattenPath('m5 5 l10 0 0 10', 'p'), [[5, 5], [15, 5], [15, 15]], 'a leading m is absolute; repeated l pairs stay relative');
  assert.deepEqual(flattenPath('M10 10 L50 10 L50 50 Z', 'p').at(-1), [10, 10]);
  assert.deepEqual(flattenPath('M10 10 L50 10 z l5 5', 'p').slice(-2), [[10, 10], [15, 15]], 'after Z the current point is the start');
});
test('number syntax: signs as separators, packed decimals, exponents, commas', () => {
  assert.deepEqual(flattenPath('M1.5.5L-2-3', 'p'), [[1.5, 0.5], [-2, -3]]);
  assert.deepEqual(flattenPath('M1e1,2E-1 l+3,.5', 'p'), [[10, 0.2], [13, 0.7]]);
  assert.deepEqual(flattenPath('  M 3 , 4  ', 'p'), [[3, 4]], 'a lone moveto is a dot');
});
test('S and T reflect the previous control point only after a curve of their kind', () => {
  near(flattenPath('M0 0 C0 10 10 10 10 0 S20 -10 20 0', 'p'), flattenPath('M0 0 C0 10 10 10 10 0 C10 -10 20 -10 20 0', 'p'), 'S after C');
  near(flattenPath('M0 0 L10 0 S20 10 30 0', 'p'), flattenPath('M0 0 L10 0 C10 0 20 10 30 0', 'p'), 'S after L');
  near(flattenPath('M0 0 Q5 10 10 0 T20 0', 'p'), flattenPath('M0 0 Q5 10 10 0 Q15 -10 20 0', 'p'), 'T after Q');
  near(flattenPath('M0 0 C0 10 10 10 10 0 T20 0', 'p'), flattenPath('M0 0 C0 10 10 10 10 0 Q10 0 20 0', 'p'), 'T after C');
});
test('cubic curves stay within half a pixel of the curve', () => {
  const [x0, y0, x1, y1, x2, y2, x3, y3] = [0, 0, 0, 300, 300, 300, 300, 0];
  const line = flattenPath(`M${x0} ${y0} C${x1} ${y1} ${x2} ${y2} ${x3} ${y3}`, 'p'), n = line.length - 1;
  const at = t => { const u = 1 - t; return [u ** 3 * x0 + 3 * u * u * t * x1 + 3 * u * t * t * x2 + t ** 3 * x3, u ** 3 * y0 + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t ** 3 * y3]; };
  for (let k = 0; k < n; k++) {
    const [px, py] = at((k + 0.5) / n), [ax, ay] = line[k], [bx, by] = line[k + 1];
    const deviation = Math.abs((bx - ax) * (ay - py) - (ax - px) * (by - ay)) / Math.hypot(bx - ax, by - ay);
    assert.ok(deviation <= 0.5, `segment ${k} deviates ${deviation}`);
  }
  assert.ok(n < 60, `${n} pieces are enough for half a pixel`);
});
test('arcs: a circle from two arcs lies on the circle; radii too small are scaled up; packed flags', () => {
  const circle = flattenPath('M100 300 A100 100 0 0 1 300 300 A100 100 0 0 1 100 300', 'p');
  for (const [x, y] of circle) assert.ok(Math.abs(Math.hypot(x - 200, y - 300) - 100) < 1e-9);
  for (let k = 1; k < circle.length; k++) assert.ok(Math.hypot((circle[k][0] + circle[k - 1][0]) / 2 - 200, (circle[k][1] + circle[k - 1][1]) / 2 - 300) >= 99.5, 'chords within half a pixel');
  assert.ok(circle.length < 100, `${circle.length} points`);
  const half = flattenPath('M0 0a5 5 0 1050 0', 'p'); // flags 1 and 0 packed; rx 5 is too small for a 50 px chord: it becomes 25
  for (const [x, y] of half) assert.ok(Math.abs(Math.hypot(x - 25, y) - 25) < 1e-9);
  assert.ok(Math.max(...half.map(p => p[1])) > 24.9, 'sweep 0 from left to right passes below (y grows downward)');
  assert.deepEqual(flattenPath('M0 0 A0 5 0 0 1 10 10', 'p'), [[0, 0], [10, 10]], 'a zero radius is a line');
  assert.deepEqual(flattenPath('M0 0 A5 5 0 0 1 0 0', 'p'), [[0, 0]], 'an arc to its own start is omitted');
});
test('syntax errors name the stroke and the position', () => {
  for (const [path, message] of [
    ['', /is empty/], ['   ', /is empty/], ['L 10 10', /must start with M/], ['10 10', /must start with M/],
    ['M0 0 L1 1 M5 5', /second M\/m .* own stroke/], ['M0 0 m5 5', /second M/], ['M0 0 X 1', /unknown command 'X'/],
    ['M0 0 L 5', /character 9: expected a number/], ['M0 0 L', /expected a number/], ['M0 0 L C 1 1 2 2 3 3', /expected a number/],
    ['M0 0 Z 5 5', /numbers after Z/], ['M0 0 A 5 5 0 2 0 10 10', /arc flag/], ['M 1e999 0', /out of range/], ['M0 0 L1e 2', /expected a number/],
  ]) assert.throws(() => parsePath(path, 'strokes[3].path'), error => message.test(error.message) && error.message.startsWith('strokes[3].path'), path);
});

// ---- strokes for the broker ----
test('screen points are rounded integers without repeats, and must stay on the screen', () => {
  assert.deepEqual(points('M0 0 L0.2 0 L1 0 L1.4 0.4'), [0, 0, 1, 0]);
  assert.deepEqual(points('M719 1599'), [719, 1599]);
  assert.throws(() => points('M100 100 L720 100'), /strokes\[0\]\.path leaves the screen at \(720, 100\): x must stay within 0\.\.719 and y within 0\.\.1599/);
  assert.throws(() => points('M100 100 a50 50 0 0 1 0 1600'), /leaves the screen/);
  assert.throws(() => points('M-1 5'), /leaves the screen at \(-1, 5\)/);
});
test('durations: defaults from the length, explicit values and holds pass through', () => {
  const [dot, short, long, huge, own] = plan([{ path: 'M5 5' }, { path: 'M0 0 L100 0' }, { path: 'M0 0 L600 0' }, { path: 'M0 0 L700 0 L700 1500 L0 1500 L0 0 L700 1500' }, { path: 'M0 0 L10 0', durationMs: 20, holdStartMs: 800, holdEndMs: 300 }]);
  assert.deepEqual([dot.durationMs, short.durationMs, long.durationMs, huge.durationMs], [80, 150, 500, 3000]);
  assert.deepEqual(own, { fingers: [[0, 0, 10, 0]], durationMs: 20, holdStartMs: 800, holdEndMs: 300 });
  assert.deepEqual(Object.keys(dot), ['fingers', 'durationMs', 'holdStartMs', 'holdEndMs']);
  assert.throws(() => plan([{ path: 'M0 0', durationMs: 19 }]), /durationMs must be an integer 20\.\.10000/);
  assert.throws(() => plan([{ path: 'M0 0', holdStartMs: 3001 }]), /holdStartMs/);
  assert.throws(() => plan([{ path: 'M0 0', holdEndMs: -1 }]), /holdEndMs/);
});
test('per-call limits: strokes, total time with the lifts, flattened points, path length', () => {
  assert.equal(plan(Array.from({ length: GESTURE.strokes }, () => ({ path: 'M1 1' }))).length, 32);
  assert.throws(() => plan(Array.from({ length: 33 }, () => ({ path: 'M1 1' }))), /1\.\.32 strokes/);
  assert.throws(() => plan([]), /1\.\.32 strokes/);
  assert.equal(plan([{ path: 'M1 1', durationMs: 7470 }, { path: 'M1 1', durationMs: 7470 }]).length, 2, '2 x 7470 + 60 = 15000 ms');
  assert.throws(() => plan([{ path: 'M1 1', durationMs: 7470 }, { path: 'M1 1', durationMs: 7471 }]), /would take 15001 ms .* at most 15000 ms/);
  const zigzag = { path: 'M0 0 ' + Array.from({ length: 900 }, (_, i) => `L${i % 2 ? 9 : 0} ${i + 1}`).join(' '), durationMs: 2000 }; // 901 points
  assert.equal(plan([zigzag, zigzag, zigzag]).length, 3);
  assert.throws(() => plan([zigzag, zigzag, zigzag, zigzag]), /more than 3000 points/);
  assert.throws(() => plan([{ path: 'M0 0 ' + 'l0 0 '.repeat(1600) }]), /1\.\.8000 characters/);
});
test('viewBox and area: uniform scale, centred, edge pixels included', () => {
  assert.deepEqual(plan([{ path: 'M0 0 L10 10' }], { viewBox: [0, 0, 10, 10], area: [100, 200, 201, 301] })[0].fingers[0], [100, 200, 200, 300]);
  assert.deepEqual(plan([{ path: 'M0 0 L10 10' }], { viewBox: [0, 0, 10, 10], area: [0, 0, 201, 101] })[0].fingers[0], [50, 0, 150, 100], 'wider area: centred horizontally');
  assert.deepEqual(plan([{ path: 'M-1 -1' }], { viewBox: [-1, -1, 2, 2], area: [0, 0, 101, 101] })[0].fingers[0], [0, 0], 'viewBox origin');
  const circle = plan([{ path: 'M0 12 A12 12 0 0 1 24 12 A12 12 0 0 1 0 12' }], { viewBox: [0, 0, 24, 24], area: [60, 400, 661, 1001] })[0].fingers[0];
  for (const [x, y] of pairs(circle)) assert.ok(Math.abs(Math.hypot(x - 360, y - 700) - 300) <= 0.71, `${x},${y} on the scaled circle`);
  assert.ok(circle.length / 2 > 50, 'the tolerance applies on the screen, not in viewBox units');
  assert.throws(() => plan([{ path: 'M0 0' }], { viewBox: [0, 0, 10, 10] }), /together/);
  assert.throws(() => plan([{ path: 'M0 0' }], { area: [0, 0, 10, 10] }), /together/);
  assert.throws(() => plan([{ path: 'M0 0' }], { viewBox: [0, 0, 0, 10], area: [0, 0, 10, 10] }), /positive width/);
  assert.throws(() => plan([{ path: 'M0 0' }], { viewBox: [0, 0, 10, 10], area: [0, 0, 721, 10] }), /area must lie on the screen/);
  assert.throws(() => plan([{ path: 'M0 0' }], { viewBox: [0, 0, 10, 10, 3], area: [0, 0, 10, 10] }), /viewBox must be/);
  assert.deepEqual(plan([{ path: 'M0 0 L10 10' }], { viewBox: [0, 0, 10, 10], area: [710, 0, 720, 10] })[0].fingers[0], [710, 0, 719, 9], 'an area at the right edge');
  // 20 viewBox units at 11.9 px each run past the right edge.
  assert.throws(() => plan([{ path: 'M0 0 L20 0' }], { viewBox: [0, 0, 10, 10], area: [600, 0, 720, 120] }), /leaves the screen at \(838, 0\) after mapping viewBox into area/);
});

test('two fingers: pinch, rotate and a still pivot move together; the longest path sets the default pace', () => {
  const [pinch, rotate, pivot] = plan([
    { fingers: ['M330 770 L180 620', 'M390 830 L540 980'] },
    { fingers: ['M360 650 A150 150 0 0 1 510 800', 'M360 950 A150 150 0 0 1 210 800'], durationMs: 600 },
    { fingers: ['M360 800', 'M560 800 A200 200 0 0 1 360 1000'], holdStartMs: 200 },
  ]);
  assert.deepEqual(pinch, { fingers: [[330, 770, 180, 620], [390, 830, 540, 980]], durationMs: 177, holdStartMs: 0, holdEndMs: 0 });
  assert.equal(rotate.durationMs, 600);
  for (const finger of rotate.fingers) for (const [x, y] of pairs(finger)) assert.ok(Math.abs(Math.hypot(x - 360, y - 800) - 150) <= 0.71, `${x},${y} on the circle`);
  assert.deepEqual(rotate.fingers.map(f => f.slice(-2)), [[510, 800], [210, 800]], 'a quarter turn clockwise');
  assert.deepEqual(pivot.fingers[0], [360, 800]);
  assert.equal(pivot.holdStartMs, 200);
  assert.ok(Math.abs(pivot.durationMs - Math.PI * 100 / 1.2) <= 2, `${pivot.durationMs} ms: the moving finger sets the pace`);
});
test('two-finger mistakes are explained per finger', () => {
  assert.throws(() => plan([{ fingers: ['M1 1'] }]), /strokes\[0\]\.fingers takes exactly 2 paths/);
  assert.throws(() => plan([{ fingers: ['M1 1', 'M2 2', 'M3 3'] }]), /exactly 2 paths/);
  assert.throws(() => plan([{ path: 'M1 1', fingers: ['M1 1', 'M2 2'] }]), /strokes\[0\] takes path \(one finger\) or fingers/);
  assert.throws(() => plan([{ durationMs: 100 }]), /exactly one of them/);
  assert.throws(() => plan([{ fingers: ['M1 1', 'M2 2 L800 2'] }]), /strokes\[0\]\.fingers\[1\] leaves the screen at \(800, 2\)/);
  assert.throws(() => plan([{ fingers: ['M1 1', 'L2 2'] }]), /strokes\[0\]\.fingers\[1\] must start with M/);
  const half = 'M0 0' + 'h1v1h-1v1'.repeat(375); // 1501 points in 3376 characters
  assert.throws(() => plan([{ fingers: [half, half], durationMs: 1000 }]), /more than 3000 points/, 'the point limit counts both fingers');
});
test('the broker receives both fingers of a stroke', async () => {
  const { client, calls } = harness();
  await client.call('s', 'phone.ui_tree', {});
  await client.call('s', 'phone.gesture', { observationId: 'obs', strokes: [{ fingers: ['M330 770 L180 620', 'M390 830 L540 980'], durationMs: 500 }] });
  assert.deepEqual(calls.at(-1).arguments.strokes, [{ fingers: [[330, 770, 180, 620], [390, 830, 540, 980]], durationMs: 500, holdStartMs: 0, holdEndMs: 0 }]);
});

// ---- the broker client ----
function harness(reply = r => ({ performed: true, completed: r.arguments.strokes.length })) {
  const calls = [];
  const client = new BrokerClient({ secret, now: () => now, transport: async request => {
    calls.push(request);
    const result = request.tool === 'phone.ui_tree'
      ? { observationId: 'obs', capturedAt: now, width: 720, height: 1600, title: 'Paint', package: 'dev.fixture.app', nodes: [{ id: 'canvas', role: 'View', bounds: [0, 200, 720, 1400] }], complete: true, advice: '' }
      : reply(request);
    return { protocolVersion: 1, requestId: request.requestId, ok: true, result };
  } });
  return { client, calls };
}
test('the broker receives integer strokes, never the SVG text', async () => {
  const { client, calls } = harness();
  await client.call('s', 'phone.ui_tree', {});
  const result = await client.call('s', 'phone.gesture', { observationId: 'obs', strokes: [{ path: 'M100 300 C100 500 300 500 300 300', holdStartMs: 600 }, { path: 'm400 300 h100' }] });
  assert.equal(result.completed, 2);
  const sent = calls.at(-1);
  assert.equal(sent.tool, 'phone.gesture');
  assert.deepEqual(Object.keys(sent.arguments), ['observationId', 'strokes']);
  assert.equal(sent.arguments.observationId, 'obs');
  const [curve, line] = sent.arguments.strokes;
  assert.deepEqual(line, { fingers: [[400, 300, 500, 300]], durationMs: 150, holdStartMs: 0, holdEndMs: 0 });
  assert.equal(curve.holdStartMs, 600);
  const [drawn] = curve.fingers;
  assert.equal(curve.fingers.length, 1);
  assert.deepEqual(drawn.slice(0, 2), [100, 300]); assert.deepEqual(drawn.slice(-2), [300, 300]);
  assert.ok(drawn.every(Number.isInteger) && drawn.length > 20);
});
test('the largest gesture still fits the 64 KiB native request body', async () => {
  const { client, calls } = harness();
  await client.call('s', 'phone.ui_tree', {});
  // 32 strokes of about 93 points each: x and y with four digits wherever possible.
  const path = i => 'M1000 1000 ' + Array.from({ length: 92 }, (_, k) => `L${k % 2 ? 1999 - i : 1000 + i} ${1000 + 5 * k + i}`).join(' ');
  const big = planGesture({ observationId: 'obs', strokes: Array.from({ length: 32 }, (_, i) => ({ path: path(i), durationMs: 400 })) }, 2000, 2000);
  assert.ok(big.reduce((n, s) => n + s.fingers[0].length / 2, 0) <= GESTURE.points);
  const body = JSON.stringify({ protocolVersion: 1, requestId: '00000000-0000-0000-0000-000000000000', sessionId: 'x'.repeat(256), tool: 'phone.gesture', arguments: { observationId: 'x'.repeat(256), strokes: big } });
  assert.ok(Buffer.byteLength(body) < 65536, `${Buffer.byteLength(body)} bytes`);
  assert.equal(calls.length, 1);
});
test('a path problem is refused before the broker and keeps the observation', async () => {
  const { client, calls } = harness();
  await client.call('s', 'phone.ui_tree', {});
  await assert.rejects(client.call('s', 'phone.gesture', { observationId: 'obs', strokes: [{ path: 'M0 0 L800 0' }] }), /leaves the screen/);
  await assert.rejects(client.call('s', 'phone.gesture', { observationId: 'obs', strokes: [{ path: 'M0 0' }], viewBox: [0, 0, 1, 1] }), /together/);
  await assert.rejects(client.call('s', 'phone.gesture', { observationId: 'obs', strokes: [{ path: 'M0 0', speed: 2 }] }), /not allowed/);
  assert.equal(calls.length, 1);
  await client.call('s', 'phone.gesture', { observationId: 'obs', strokes: [{ path: 'M0 0 L10 10' }] });
  await assert.rejects(client.call('s', 'phone.gesture', { observationId: 'obs', strokes: [{ path: 'M0 0 L10 10' }] }), /stale/, 'performed: the observation is consumed');
});
test('a stopped gesture says which strokes happened', async () => {
  const strokes = [{ path: 'M1 1' }, { path: 'M2 2' }, { path: 'M3 3' }, { path: 'M4 4' }];
  const run = async native => { const { client } = harness(() => ({ performed: true, ...native })); await client.call('s', 'phone.ui_tree', {}); return client.call('s', 'phone.gesture', { observationId: 'obs', strokes }); };
  const stopped = { completed: 2, stoppedAt: 2, stopReason: 'WINDOW_CHANGED' };
  let result = await run(stopped);
  assert.equal(result.stopReason, 'WINDOW_CHANGED');
  assert.match(result.note, /stopped at stroke 3 of 4 \(WINDOW_CHANGED\): strokes 1-2 were performed, stroke 3 and later ones were not\. The previous stroke opened or closed a window/);
  assert.match(result.note, /only the strokes that are still missing/);
  result = await run({ ...stopped, stopReason: 'CONTROL_INTERRUPTED', uncertain: true });
  assert.match(result.note, /Stroke 3 was cut off midway and may be partly drawn/);
  assert.match((await run({ completed: 3, stoppedAt: 3, stopReason: 'USER_CONTROL' })).note, /stroke 4 was not\. The user took over the screen/);
  assert.match((await run({ ...stopped, stopReason: 'GESTURE_TIME_LIMIT' })).note, /\(GESTURE_TIME_LIMIT\): strokes 1-2 were performed.* ran out of time before this stroke/);
  assert.equal((await run({ ...stopped, stopReason: 'native secret text' })).stopReason, 'BROKER_ERROR', 'unknown codes are never reflected');
  assert.equal((await run({ ...stopped, stopReason: 'hasOwnProperty' })).stopReason, 'BROKER_ERROR');
  assert.equal((await run({ completed: 4 })).note, undefined, 'a complete gesture has no note');
  for (const bad of [{ ...stopped, completed: 4 }, { ...stopped, completed: 0, stoppedAt: 0 }, { completed: 5 }, { completed: 2, stoppedAt: 1, stopReason: 'WINDOW_CHANGED' }, { completed: 3, uncertain: true }, { completed: 4, stoppedAt: 4 }]) {
    await assert.rejects(run(bad), /Inconsistent gesture result|completed must be/, JSON.stringify(bad));
  }
});
