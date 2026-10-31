import { readFileSync, writeFileSync, mkdirSync, renameSync } from 'node:fs';
import { homedir } from 'node:os';
import { dirname, join } from 'node:path';
import { traitsOf, derive, stateOf, settle, PSYCHE } from './psyche.mjs';

// Where its character, energy, mood and the owner's outreach settings live, next to the other plugin files:
// - character.json: the eight values the owner set in Continuo ({traits}; none until they pick one);
// - outreach.json: how it may reach the owner (the owner's accounts in its apps, how often, quiet hours, do not
//   disturb, what the owner wants it to do with its own time);
// - psyche.json: energy and mood, each model's recent output tokens, and its attempts to reach the owner.
// restricted.mjs (the preset), notify.mjs and unattended.mjs (host plugins) are separate plugin entries in one process:
// they share one board on globalThis (like screen-board.mjs), which reads the files once and writes every change.
const folder = home => join(home, '.dsh', 'plugins', 'cowork-phone');
export const characterFile = (home = homedir()) => join(folder(home), 'character.json');
export const outreachFile = (home = homedir()) => join(folder(home), 'outreach.json');
export const psycheFile = (home = homedir()) => join(folder(home), 'psyche.json');

function writeAtomic(file, value) {
  mkdirSync(dirname(file), { recursive: true, mode: 0o700 });
  const temp = `${file}.${process.pid}.tmp`;
  writeFileSync(temp, `${JSON.stringify(value, null, 2)}\n`, { mode: 0o600 });
  renameSync(temp, file);
}
const readJson = file => { try { return JSON.parse(readFileSync(file, 'utf8')); } catch { return undefined; } };

/** The owner's character for it: eight values, or null when none is set. */
export function readCharacter(file = characterFile()) { return traitsOf(readJson(file)?.traits); }
export function writeCharacter(traits, file = characterFile()) {
  const value = traits === null ? null : traitsOf(traits);
  if (traits !== null && !value) throw new TypeError('traits are eight whole values 0..100 (Se, Si, Ne, Ni, Te, Ti, Fe, Fi)');
  writeAtomic(file, { traits: value });
  return value;
}

// How often it may open a topic with the owner, per day, over every way of reaching them.
export const LEVELS = Object.freeze({ off: 0, few: 1, normal: 3, many: 5 });
const PACKAGE = /^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$/;
const CLOCK = /^([01]\d|2[0-3]):[0-5]\d$/;
const CONTROL = new RegExp(`[\u0000-\u001f\u007f${String.fromCharCode(0x2028)}${String.fromCharCode(0x2029)}]`);
const NAME_MAX = 40, PREFERENCES_MAX = 600, CONTACTS_MAX = 8;
const plain = (value, max, what) => {
  if (typeof value !== 'string') throw new TypeError(`${what} is text`);
  const text = value.replace(/\s+/g, ' ').trim();
  if (!text || [...text].length > max || CONTROL.test(text)) throw new TypeError(`${what} is 1 to ${max} plain characters`);
  return text;
};
export const OUTREACH_DEFAULT = Object.freeze({ contacts: Object.freeze([]), level: 'normal', quietFrom: '23:00', quietTo: '08:00', dndUntil: 0, preferences: '' });
/**
 * The owner's outreach settings, checked: contacts are the owner's accounts in its work-profile apps
 * ({package, remark: how the owner appears in its app, shownAs: how it appears in the owner's own app}).
 */
export function outreachOf(value) {
  const v = value && typeof value === 'object' ? value : {};
  const contacts = Array.isArray(v.contacts) ? v.contacts : [];
  if (contacts.length > CONTACTS_MAX) throw new TypeError(`at most ${CONTACTS_MAX} contacts`);
  const seen = new Set();
  const list = contacts.map(c => {
    if (typeof c?.package !== 'string' || !PACKAGE.test(c.package) || c.package.length > 255) throw new TypeError('contact.package is a package name');
    if (seen.has(c.package)) throw new TypeError('one contact per app');
    seen.add(c.package);
    return Object.freeze({ package: c.package, remark: plain(c.remark, NAME_MAX, 'contact.remark'), shownAs: plain(c.shownAs, NAME_MAX, 'contact.shownAs') });
  });
  const level = v.level === undefined ? OUTREACH_DEFAULT.level : v.level;
  if (!Object.hasOwn(LEVELS, level)) throw new TypeError(`level is one of ${Object.keys(LEVELS).join(', ')}`);
  const clock = (x, d, what) => { if (x === undefined) return d; if (typeof x !== 'string' || !CLOCK.test(x)) throw new TypeError(`${what} is HH:mm`); return x; };
  const dndUntil = v.dndUntil === undefined || v.dndUntil === null ? 0 : v.dndUntil;
  if (!Number.isSafeInteger(dndUntil) || dndUntil < 0) throw new TypeError('dndUntil is a time in milliseconds or 0');
  const preferences = typeof v.preferences === 'string' ? v.preferences.replace(/\r\n?/g, '\n').trim() : '';
  if ([...preferences].length > PREFERENCES_MAX || /[\u0000-\u0009\u000b-\u001f\u007f]/.test(preferences)) throw new TypeError(`preferences are up to ${PREFERENCES_MAX} characters`);
  return Object.freeze({ contacts: Object.freeze(list), level, quietFrom: clock(v.quietFrom, OUTREACH_DEFAULT.quietFrom, 'quietFrom'),
    quietTo: clock(v.quietTo, OUTREACH_DEFAULT.quietTo, 'quietTo'), dndUntil, preferences });
}
export function readOutreach(file = outreachFile()) { try { return outreachOf(readJson(file)); } catch { return OUTREACH_DEFAULT; } }
/** Stores the fields given, keeping the others. */
export function writeOutreach(change, file = outreachFile()) {
  const next = outreachOf({ ...readOutreach(file), ...(change && typeof change === 'object' ? change : {}) });
  writeAtomic(file, next);
  return next;
}

const REACH_LOG = 50;
/** One attempt to reach the owner: {at, channel ('continuo' or a package), topic (new or the one it chases), text?}. */
function reachOf(value) {
  if (!Number.isSafeInteger(value?.at) || value.at <= 0 || typeof value.channel !== 'string' || value.channel.length > 255) return null;
  if (!Number.isSafeInteger(value.topic) || value.topic <= 0) return null;
  const text = typeof value.text === 'string' ? [...value.text].slice(0, 200).join('') : '';
  return Object.freeze({ at: value.at, channel: value.channel, topic: value.topic, ...(text ? { text } : {}), ...(value.verified ? { verified: true } : {}) });
}
/** The stored psyche file, every part checked. */
export function psycheOf(value) {
  const models = {};
  if (value?.models && typeof value.models === 'object')
    for (const [key, list] of Object.entries(value.models).slice(0, 32))
      if (typeof key === 'string' && key.length <= 300 && Array.isArray(list)) models[key] = Object.freeze(list.filter(n => Number.isSafeInteger(n) && n >= 0).slice(-PSYCHE.window));
  const reaches = (Array.isArray(value?.reaches) ? value.reaches : []).map(reachOf).filter(Boolean).slice(-REACH_LOG);
  const time = x => (Number.isSafeInteger(x) && x > 0 ? x : 0);
  return Object.freeze({ state: stateOf(value?.state), models: Object.freeze(models), reaches: Object.freeze(reaches),
    seenAt: time(value?.seenAt), answeredAt: time(value?.answeredAt), chasedTopic: Number.isSafeInteger(value?.chasedTopic) ? value.chasedTopic : 0 });
}
export function readPsyche(file = psycheFile()) { return psycheOf(readJson(file)); }

/**
 * The shared board. `files` overrides where it reads and writes (tests). In memory only: which Cowork sessions have a
 * turn running and when the last one ended (rest counts from there), which turn is a stretch of its own time.
 */
export function createPsycheBoard({ files = {}, now = Date.now } = {}) {
  const paths = { character: files.character ?? characterFile(), outreach: files.outreach ?? outreachFile(), psyche: files.psyche ?? psycheFile() };
  let data, traits, outreach;
  const load = () => {
    if (data === undefined) { data = readPsyche(paths.psyche); traits = readCharacter(paths.character); outreach = readOutreach(paths.outreach); }
  };
  const running = new Set();
  let idleSince = now();
  const listeners = new Set();
  const save = () => { try { writeAtomic(paths.psyche, data); } catch {} for (const l of [...listeners]) { try { l(); } catch {} } };
  const board = {
    paths,
    /** The owner's values, or null. */
    character() { load(); return traits; },
    setCharacter(value) { load(); traits = writeCharacter(value, paths.character); save(); return traits; },
    /** What follows from the character, or null without one. */
    derived() { load(); return traits ? derive(traits) : null; },
    outreach() { load(); return outreach; },
    setOutreach(change) { load(); outreach = writeOutreach(change, paths.outreach); save(); return outreach; },
    /** Energy and mood now (time applied, nothing stored). */
    state(at = now()) { load(); const d = board.derived(); return d ? settle(data.state, d, at, { running: running.size > 0 }) : data.state; },
    data() { load(); return data; },
    /** Applies `change(data, derived)` (a new data object) and stores it. */
    update(change) { load(); data = psycheOf(change(data, board.derived())); save(); return data; },
    running: () => running.size > 0,
    isRunning: id => running.has(id),
    started(id) { if (!board.derived()) { running.add(id); return; } board.update(d => ({ ...d, state: settle(d.state, board.derived(), now(), { running: running.size > 0 }) })); running.add(id); },
    ended(id) {
      if (!running.has(id)) return;
      if (board.derived()) board.update(d => ({ ...d, state: settle(d.state, board.derived(), now(), { running: true }) }));
      running.delete(id); if (!running.size) idleSince = now();
    },
    idleSince: () => idleSince,
    subscribe(listener) { listeners.add(listener); return () => { listeners.delete(listener); }; },
    /** Drops what was read, so the next call reads the files again (after Continuo changed them through another path). */
    reload() { data = undefined; },
  };
  return Object.freeze(board);
}
const KEY = Symbol.for('dsh-cowork-phone.psyche-board');
export const sharedPsyche = () => (globalThis[KEY] ??= createPsycheBoard());
