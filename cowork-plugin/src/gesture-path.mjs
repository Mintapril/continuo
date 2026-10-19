// phone.gesture: SVG path data -> finger strokes. The model describes each stroke with SVG path syntax; this module
// flattens it into the integer polyline the native broker injects (wire form in PROTOCOL.md) and explains every problem
// in words, because a native refusal reaches the model only as a code. Limits mirror StrokePath.java.
import { check, boundedString, integer } from './validation.mjs';

export const GESTURE = Object.freeze({ strokes: 32, fingers: 2, points: 3000, minDurationMs: 20, maxDurationMs: 10000, maxHoldMs: 3000, gapMs: 60, totalMs: 15000, pathChars: 8000 });
const TOLERANCE = 0.5; // px the polyline may deviate from the described curve
const SPEED = 1.2; // px per ms of a stroke without durationMs
const DOT_MS = 80; // press time of a single point without durationMs
const SEGMENT_STEPS = 1000; // most line pieces for one curve command
const ARITY = { M: 2, L: 2, H: 1, V: 1, C: 6, S: 4, Q: 4, T: 2, A: 7, Z: 0 };
const NUMBER = /[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?/y;

// One subpath in absolute coordinates: its start point and segments {type:'L'|'C'|'Q'|'A', ...}.
export function parsePath(d, label) {
  let i = 0, cmd = null, x = 0, y = 0, sx = 0, sy = 0, start = null, control = null;
  const segments = [];
  const where = () => `${label} at character ${i + 1}`;
  const skip = () => { while (i < d.length && /[\s,]/.test(d[i])) i++; };
  const number = () => {
    skip(); NUMBER.lastIndex = i;
    const match = NUMBER.exec(d);
    check(match, `${where()}: expected a number`);
    const value = Number(match[0]);
    check(Number.isFinite(value), `${where()}: number out of range`);
    i = NUMBER.lastIndex; return value;
  };
  const flag = () => { skip(); check(d[i] === '0' || d[i] === '1', `${where()}: expected an arc flag 0 or 1`); return d[i++] === '1'; };
  skip(); check(i < d.length, `${label} is empty`);
  for (skip(); i < d.length; skip()) {
    if (/[A-Za-z]/.test(d[i])) {
      check(Object.hasOwn(ARITY, d[i].toUpperCase()), `${where()}: unknown command '${d[i]}' (use M L H V C S Q T A Z)`);
      cmd = d[i++];
    } else {
      check(cmd !== null, `${label} must start with M or m (the point where the finger goes down)`);
      check(cmd.toUpperCase() !== 'Z', `${where()}: numbers after Z need a command letter`);
      if (cmd === 'M') cmd = 'L'; else if (cmd === 'm') cmd = 'l'; // further pairs after a moveto are lines
    }
    const type = cmd.toUpperCase(), ox = cmd === type ? 0 : x, oy = cmd === type ? 0 : y;
    if (start === null) check(type === 'M', `${label} must start with M or m (the point where the finger goes down)`);
    else check(type !== 'M', `${where()}: a second M/m would lift the finger; each stroke is one touch, so put that part in its own stroke`);
    let next = null;
    switch (type) {
      case 'M': x = number() + ox; y = number() + oy; sx = x; sy = y; start = [x, y]; break;
      case 'L': x = number() + ox; y = number() + oy; segments.push({ type: 'L', x, y }); break;
      case 'H': x = number() + ox; segments.push({ type: 'L', x, y }); break;
      case 'V': y = number() + oy; segments.push({ type: 'L', x, y }); break;
      case 'C': case 'S': {
        let x1, y1;
        if (type === 'C') { x1 = number() + ox; y1 = number() + oy; }
        else [x1, y1] = control?.type === 'C' ? [2 * x - control.x, 2 * y - control.y] : [x, y];
        const x2 = number() + ox, y2 = number() + oy;
        x = number() + ox; y = number() + oy;
        segments.push({ type: 'C', x1, y1, x2, y2, x, y }); next = { type: 'C', x: x2, y: y2 }; break;
      }
      case 'Q': case 'T': {
        let x1, y1;
        if (type === 'Q') { x1 = number() + ox; y1 = number() + oy; }
        else [x1, y1] = control?.type === 'Q' ? [2 * x - control.x, 2 * y - control.y] : [x, y];
        x = number() + ox; y = number() + oy;
        segments.push({ type: 'Q', x1, y1, x, y }); next = { type: 'Q', x: x1, y: y1 }; break;
      }
      case 'A': {
        const rx = number(), ry = number(), rotation = number(), large = flag(), sweep = flag();
        x = number() + ox; y = number() + oy;
        segments.push({ type: 'A', rx, ry, rotation, large, sweep, x, y }); break;
      }
      default: x = sx; y = sy; segments.push({ type: 'L', x, y }); // Z: back to the start, the finger stays down
    }
    control = next; // S and T reflect only the control point of a curve right before them
  }
  return { start, segments };
}

const steps = value => Math.min(SEGMENT_STEPS, Math.max(1, Math.ceil(value)));
function quadratic(out, x0, y0, s, tol) {
  // The chord of a step h is within h^2/4 * |P0 - 2P1 + P2| of the curve.
  const n = steps(Math.sqrt(Math.hypot(x0 - 2 * s.x1 + s.x, y0 - 2 * s.y1 + s.y) / (4 * tol)));
  for (let k = 1; k < n; k++) {
    const t = k / n, u = 1 - t;
    out.push([u * u * x0 + 2 * u * t * s.x1 + t * t * s.x, u * u * y0 + 2 * u * t * s.y1 + t * t * s.y]);
  }
  out.push([s.x, s.y]);
}
function cubic(out, x0, y0, s, tol) {
  // |B''| <= 6 max(|P0 - 2P1 + P2|, |P1 - 2P2 + P3|) and a chord of step h is within h^2/8 * |B''| of the curve.
  const m = Math.max(Math.hypot(x0 - 2 * s.x1 + s.x2, y0 - 2 * s.y1 + s.y2), Math.hypot(s.x1 - 2 * s.x2 + s.x, s.y1 - 2 * s.y2 + s.y));
  const n = steps(Math.sqrt(0.75 * m / tol));
  for (let k = 1; k < n; k++) {
    const t = k / n, u = 1 - t, a = u * u * u, b = 3 * u * u * t, c = 3 * u * t * t, e = t * t * t;
    out.push([a * x0 + b * s.x1 + c * s.x2 + e * s.x, a * y0 + b * s.y1 + c * s.y2 + e * s.y]);
  }
  out.push([s.x, s.y]);
}
function arc(out, x1, y1, s, tol) {
  // Endpoint to centre parameterization, SVG 1.1 implementation notes F.6.5 and F.6.6.
  const { x: x2, y: y2, large, sweep } = s;
  if (x1 === x2 && y1 === y2) return;
  let rx = Math.abs(s.rx), ry = Math.abs(s.ry);
  if (rx === 0 || ry === 0) { out.push([x2, y2]); return; }
  const phi = s.rotation * Math.PI / 180, cos = Math.cos(phi), sin = Math.sin(phi);
  const dx = (x1 - x2) / 2, dy = (y1 - y2) / 2, xp = cos * dx + sin * dy, yp = -sin * dx + cos * dy;
  const lambda = xp * xp / (rx * rx) + yp * yp / (ry * ry);
  if (lambda > 1) { rx *= Math.sqrt(lambda); ry *= Math.sqrt(lambda); }
  const num = rx * rx * ry * ry - rx * rx * yp * yp - ry * ry * xp * xp, den = rx * rx * yp * yp + ry * ry * xp * xp;
  const coef = (large === sweep ? -1 : 1) * Math.sqrt(Math.max(0, num / den));
  const cxp = coef * rx * yp / ry, cyp = -coef * ry * xp / rx;
  const cx = cos * cxp - sin * cyp + (x1 + x2) / 2, cy = sin * cxp + cos * cyp + (y1 + y2) / 2;
  const angle = (ux, uy, vx, vy) => Math.atan2(ux * vy - uy * vx, ux * vx + uy * vy);
  const ux = (xp - cxp) / rx, uy = (yp - cyp) / ry;
  const theta = angle(1, 0, ux, uy);
  let delta = angle(ux, uy, (-xp - cxp) / rx, (-yp - cyp) / ry);
  if (!sweep && delta > 0) delta -= 2 * Math.PI; else if (sweep && delta < 0) delta += 2 * Math.PI;
  const r = Math.max(rx, ry), step = tol >= r ? Math.PI / 2 : Math.min(Math.PI / 2, 2 * Math.acos(1 - tol / r));
  const n = steps(Math.abs(delta) / step);
  for (let k = 1; k < n; k++) {
    const a = theta + delta * k / n, ca = Math.cos(a), sa = Math.sin(a);
    out.push([cx + rx * ca * cos - ry * sa * sin, cy + rx * ca * sin + ry * sa * cos]);
  }
  out.push([x2, y2]);
}
// The subpath as a polyline (start point first) within tol of the described shape.
export function flattenPath(d, label, tol = TOLERANCE, budget = 4 * GESTURE.points) {
  const { start, segments } = parsePath(d, label);
  const out = [start];
  for (const segment of segments) {
    const [x0, y0] = out.at(-1);
    if (segment.type === 'L') out.push([segment.x, segment.y]);
    else if (segment.type === 'Q') quadratic(out, x0, y0, segment, tol);
    else if (segment.type === 'C') cubic(out, x0, y0, segment, tol);
    else arc(out, x0, y0, segment, tol);
    check(out.length <= budget, `${label} is too detailed (more than ${GESTURE.points} points); simplify it or split it over several phone_gesture calls`);
  }
  return out;
}

// The model's phone.gesture arguments as the native wire strokes, for a screen of width x height pixels.
export function planGesture(args, width, height) {
  const { strokes, viewBox, area } = args;
  check(strokes.length >= 1 && strokes.length <= GESTURE.strokes, `strokes takes 1..${GESTURE.strokes} strokes; split a larger drawing over several phone_gesture calls`);
  check((viewBox === undefined) === (area === undefined), 'Provide viewBox and area together, or neither');
  let map = ([x, y]) => [x, y], scale = 1;
  if (viewBox !== undefined) {
    check(viewBox.length === 4 && viewBox[2] > 0 && viewBox[3] > 0, 'viewBox must be [minX, minY, width, height] with a positive width and height');
    check(area.length === 4, 'area must be [left, top, right, bottom], like the bounds of a phone_ui_tree node');
    const [left, top, right, bottom] = area;
    check(left >= 0 && top >= 0 && right <= width && bottom <= height && right - left > 1 && bottom - top > 1, `area must lie on the screen (0..${width} x 0..${height}) with right > left + 1 and bottom > top + 1`);
    // The viewBox is scaled uniformly and centred, edge pixels included (bounds are right/bottom exclusive).
    const w = right - 1 - left, h = bottom - 1 - top, [vx, vy, vw, vh] = viewBox;
    scale = Math.min(w / vw, h / vh);
    const ox = left + (w - vw * scale) / 2 - vx * scale, oy = top + (h - vh * scale) / 2 - vy * scale;
    map = ([x, y]) => [x * scale + ox, y * scale + oy];
  }
  let points = 0, total = GESTURE.gapMs * (strokes.length - 1);
  // One finger's path as integer screen points without repeats, and the length the finger travels.
  const finger = (path, label) => {
    boundedString(path, GESTURE.pathChars, label);
    const flat = [];
    let length = 0, lx, ly;
    for (const point of flattenPath(path, label, TOLERANCE / scale)) {
      const [fx, fy] = map(point), x = Math.round(fx), y = Math.round(fy);
      check(x >= 0 && x < width && y >= 0 && y < height, `${label} leaves the screen at (${x}, ${y})${viewBox ? ' after mapping viewBox into area' : ''}: x must stay within 0..${width - 1} and y within 0..${height - 1}`);
      if (x === lx && y === ly) continue;
      if (lx !== undefined) length += Math.hypot(x - lx, y - ly);
      flat.push(x, y); lx = x; ly = y;
    }
    points += flat.length / 2;
    check(points <= GESTURE.points, `The strokes flatten to more than ${GESTURE.points} points; simplify them or split the drawing over several phone_gesture calls`);
    return { flat, length };
  };
  const wire = strokes.map((stroke, index) => {
    check((stroke.path === undefined) !== (stroke.fingers === undefined), `strokes[${index}] takes path (one finger) or fingers (two fingers moving together), exactly one of them`);
    if (stroke.fingers !== undefined) check(stroke.fingers.length === GESTURE.fingers, `strokes[${index}].fingers takes exactly ${GESTURE.fingers} paths, one per finger; use path for a single finger`);
    // The fingers move together: each covers its own path in durationMs, so the longest one sets the default pace.
    const lines = stroke.fingers === undefined ? [finger(stroke.path, `strokes[${index}].path`)] : stroke.fingers.map((path, f) => finger(path, `strokes[${index}].fingers[${f}]`));
    const length = Math.max(...lines.map(line => line.length));
    const durationMs = stroke.durationMs ?? (length === 0 ? DOT_MS : Math.min(3000, Math.max(150, Math.round(length / SPEED))));
    const holdStartMs = stroke.holdStartMs ?? 0, holdEndMs = stroke.holdEndMs ?? 0;
    integer(durationMs, GESTURE.minDurationMs, GESTURE.maxDurationMs, `strokes[${index}].durationMs`);
    integer(holdStartMs, 0, GESTURE.maxHoldMs, `strokes[${index}].holdStartMs`);
    integer(holdEndMs, 0, GESTURE.maxHoldMs, `strokes[${index}].holdEndMs`);
    total += durationMs + holdStartMs + holdEndMs;
    return { fingers: lines.map(line => line.flat), durationMs, holdStartMs, holdEndMs };
  });
  check(total <= GESTURE.totalMs, `This gesture would take ${total} ms (movement, holds and ${GESTURE.gapMs} ms lifts between strokes); one call may take at most ${GESTURE.totalMs} ms: shorten durations or split it over several phone_gesture calls`);
  return wire;
}
