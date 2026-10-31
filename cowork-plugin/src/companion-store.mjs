import { readFileSync, writeFileSync, mkdirSync, renameSync, appendFileSync, statSync, existsSync } from 'node:fs';
import { homedir } from 'node:os';
import { dirname, join } from 'node:path';
import { isFocusKey } from './jev-focus-store.mjs';

// What Continuo shows of the AI as a companion, kept in the plugin data folder next to unattended-models.json:
// - identity.json: the name the owner gave it (restricted.mjs puts it in the system prompt);
// - activity.jsonl: one line per finished Cowork turn, what it did (diary.mjs records it), the material of the diary;
// - diary.jsonl: one entry a day, written at night by a separate request from that day's activity (diary-writer.mjs);
// - owner-session.json: which session is the owner's conversation now (叫它), the one session everything goes to.
// All stay on this phone; the app reads them over DSH's authenticated RPC (unattended.mjs), the model reads the diary
// with cowork_diary (diary-view.mjs).
const folder = home => join(home, '.dsh', 'plugins', 'cowork-phone');
export const identityFile = (home = homedir()) => join(folder(home), 'identity.json');
export const activityFile = (home = homedir()) => join(folder(home), 'activity.jsonl');
export const diaryFile = (home = homedir()) => join(folder(home), 'diary.jsonl');
export const ownerSessionFile = (home = homedir()) => join(folder(home), 'owner-session.json');

// The owner's conversation with the AI (Continuo's 叫它): cowork-owner, then a fresh cowork-owner-<yyyyMMdd-HHmmss> each
// time the owner clears it (the app's Companion.isOwnerSession and nextOwnerSession make the same ids). Since
// 2026-10-26 it is the only session the AI lives in: the owner's messages, patrols, notes and every app's notifications.
export const OWNER_SESSION = 'cowork-owner';
const OWNER_ID = /^cowork-owner(?:-(\d{8}-\d{6}))?$/;
export const isOwnerSession = id => typeof id === 'string' && OWNER_ID.test(id);
/** Whether owner session `a` was made after `b` (the ids carry the local time they were made at). */
export const newerOwnerSession = (a, b) => isOwnerSession(a) && (!isOwnerSession(b) || (OWNER_ID.exec(a)[1] ?? '') > (OWNER_ID.exec(b)[1] ?? ''));

/** The owner's conversation now: what Continuo last said (setOwnerSession), or the first one when it never did. */
export function readOwnerSession(file = ownerSessionFile()) {
  try {
    const id = JSON.parse(readFileSync(file, 'utf8'))?.sessionId;
    return isOwnerSession(id) ? id : OWNER_SESSION;
  } catch { return OWNER_SESSION; }
}

export function writeOwnerSession(id, file = ownerSessionFile()) {
  if (!isOwnerSession(id)) throw new TypeError('sessionId is the owner\'s conversation: cowork-owner or cowork-owner-<yyyyMMdd-HHmmss>');
  writeAtomic(file, `${JSON.stringify({ sessionId: id }, null, 2)}\n`);
  return id;
}

/** A message of the owner seen in `id`: it becomes the owner's conversation when it is newer than the stored one, so
 *  a plugin that has not heard from the app yet still finds the conversation the owner talks in. Returns the current id. */
export function adoptOwnerSession(id, file = ownerSessionFile()) {
  const current = readOwnerSession(file);
  return newerOwnerSession(id, current) ? writeOwnerSession(id, file) : current;
}

export const NAME_MAX = 12;
export const ACTIVITY = Object.freeze({ textMax: 600, askedMax: 300, itemMax: 200, items: 10, keep: 3000,
  kinds: Object.freeze(['owner', 'note', 'patrol', 'notify', 'own', 'other']) });
export const DIARY = Object.freeze({ textMax: 4000, keep: 400, pageMax: 60 });
const CONTROL = new RegExp(`[${String.fromCharCode(0)}-${String.fromCharCode(31)}${String.fromCharCode(127)}]`);
const CONTROLS = new RegExp(CONTROL.source, 'g');
const SESSION = /^[a-zA-Z0-9_-]{1,100}$/;
const DATE = /^\d{4}-\d{2}-\d{2}$/;

/** The owner-given name, trimmed, or throws: 1 to 12 characters, no control characters or prompt-like symbols. */
export function nameOf(value) {
  if (value === null || value === undefined) return null;
  if (typeof value !== 'string') throw new TypeError('A name is a string');
  const name = value.replace(/\s+/g, ' ').trim();
  const length = [...name].length;
  if (length === 0 || length > NAME_MAX || CONTROL.test(name) || /["\\<>{}[\]`]/.test(name)) throw new TypeError(`A name is 1 to ${NAME_MAX} plain characters`);
  return name;
}

export function readIdentity(file = identityFile()) {
  try {
    const raw = JSON.parse(readFileSync(file, 'utf8'));
    return Object.freeze({ name: nameOf(raw?.name) });
  } catch { return Object.freeze({ name: null }); }
}

function writeAtomic(file, text) {
  mkdirSync(dirname(file), { recursive: true, mode: 0o700 });
  const temp = `${file}.${process.pid}.tmp`;
  writeFileSync(temp, text, { mode: 0o600 });
  renameSync(temp, file);
}

export function writeIdentity(value, file = identityFile()) {
  const next = Object.freeze({ name: nameOf(value?.name) });
  writeAtomic(file, `${JSON.stringify(next, null, 2)}\n`);
  return next;
}

// The model talks naturally: the diary is written apart from its turns, and it only reads it.
export const DIARY_NOTE = 'Every night a separate request writes your diary from what you did that day; you never write it yourself. To recall earlier days, read it with cowork_diary.';

/** The system prompt lines about the name (none while the owner has not given one) and the diary. */
export function identityPrompt(identity) {
  const name = identity?.name;
  const named = name ? `Your owner named you ${JSON.stringify(name)}: that is your name when you talk with the owner in this chat, and the name they see on your notifications. It is not a nickname for your app accounts: keep the names those accounts already have unless a message of the owner asks you to change one. ` : '';
  return named + DIARY_NOTE;
}

const clip = (text, max) => {
  const plain = String(text ?? '').replace(CONTROLS, ' ').trim();
  return [...plain].length > max ? `${[...plain].slice(0, max - 1).join('')}…` : plain;
};
const list = (value, max) => (Array.isArray(value) ? value : []).map(item => clip(item, max)).filter(Boolean).slice(0, ACTIVITY.items);

/** One finished turn, validated: {at, sessionId, kind, text?, asked?, apps?, typed?, notified?, app?, reason?}; app is
 *  the app whose notifications started it (`<profile>:<package>`). The lines of the old per-turn diary (at, sessionId,
 *  kind, text, asked, reason) are activity entries too. */
export function activityEntry(value) {
  const at = value?.at;
  if (!Number.isSafeInteger(at) || at <= 0) throw new TypeError('at is a time in milliseconds');
  if (typeof value.sessionId !== 'string' || !SESSION.test(value.sessionId)) throw new TypeError('sessionId is a session id');
  const kind = ACTIVITY.kinds.includes(value.kind) ? value.kind : 'other';
  const text = clip(value.text, ACTIVITY.textMax);
  const asked = clip(value.asked, ACTIVITY.askedMax);
  const apps = list(value.apps, 255), typed = list(value.typed, ACTIVITY.itemMax), notified = list(value.notified, ACTIVITY.itemMax);
  if (!text && !apps.length && !typed.length && !notified.length) throw new TypeError('a turn that did nothing is not recorded');
  const reason = typeof value.reason === 'string' && /^[a-z_-]{1,30}$/.test(value.reason) ? value.reason : undefined;
  const app = isFocusKey(value.app) ? value.app : undefined;
  return Object.freeze({ at, sessionId: value.sessionId, kind, ...(text ? { text } : {}), ...(asked ? { asked } : {}),
    ...(apps.length ? { apps } : {}), ...(typed.length ? { typed } : {}), ...(notified.length ? { notified } : {}),
    ...(app ? { app } : {}), ...(reason && reason !== 'completed' ? { reason } : {}) });
}

/** One day's diary entry: {date (YYYY-MM-DD, the owner's day), at, text, provider?, model?}. */
export function diaryDay(value) {
  if (typeof value?.date !== 'string' || !DATE.test(value.date)) throw new TypeError('date is YYYY-MM-DD');
  if (!Number.isSafeInteger(value.at) || value.at <= 0) throw new TypeError('at is a time in milliseconds');
  const text = String(value.text ?? '').replace(/\r\n?/g, '\n').replace(/[\u0000-\u0009\u000b-\u001f\u007f]/g, ' ').trim();
  if (!text) throw new TypeError('text is required');
  const body = [...text].length > DIARY.textMax ? `${[...text].slice(0, DIARY.textMax - 1).join('')}…` : text;
  const route = s => (typeof s === 'string' && s.length > 0 && s.length <= 200 ? s : undefined);
  return Object.freeze({ date: value.date, at: value.at, text: body,
    ...(route(value.provider) ? { provider: value.provider } : {}), ...(route(value.model) ? { model: value.model } : {}) });
}

function readLines(file, parse) {
  let raw;
  try { raw = readFileSync(file, 'utf8'); } catch { return []; }
  const out = [];
  for (const line of raw.split('\n')) {
    if (!line.trim()) continue;
    try { out.push(parse(JSON.parse(line))); } catch { /* a damaged line is skipped */ }
  }
  return out;
}

function appendLine(file, entry, keep, parse, lineBytes) {
  mkdirSync(dirname(file), { recursive: true, mode: 0o700 });
  appendFileSync(file, `${JSON.stringify(entry)}\n`, { mode: 0o600 });
  let size = 0;
  try { size = statSync(file).size; } catch { /* just written */ }
  if (size > lineBytes * (keep + 100)) {
    const lines = readLines(file, parse);
    if (lines.length > keep) writeAtomic(file, lines.slice(-keep).map(e => JSON.stringify(e)).join('\n') + '\n');
  }
  return entry;
}

/** Appends one turn; past [ACTIVITY.keep] lines the oldest are dropped. */
export const appendActivity = (value, file = activityFile()) => appendLine(file, activityEntry(value), ACTIVITY.keep, activityEntry, 600);

/** The turns that ended in (from, to], oldest first. */
export function readActivity({ from = 0, to = Infinity } = {}, file = activityFile()) {
  return readLines(file, activityEntry).filter(e => e.at > from && e.at <= to).sort((a, b) => a.at - b.at);
}

/** Appends one day's entry; a later entry for the same date replaces the earlier one when read. */
export const appendDiaryDay = (value, file = diaryFile()) => appendLine(file, diaryDay(value), DIARY.keep, diaryDay, 3000);

/** Newest first: up to [limit] days written before [before] (ms), with whether more remain. */
export function readDiary({ before, limit } = {}, file = diaryFile()) {
  const max = Number.isSafeInteger(limit) && limit > 0 ? Math.min(limit, DIARY.pageMax) : 20;
  const byDate = new Map();
  for (const entry of readLines(file, diaryDay)) byDate.set(entry.date, entry);
  const all = [...byDate.values()].filter(e => !(Number.isSafeInteger(before) && e.at >= before)).sort((a, b) => b.at - a.at)
    .map(({ date, at, text }) => Object.freeze({ date, at, text }));
  return Object.freeze({ entries: all.slice(0, max), more: all.length > max });
}

/** Continuo's 事件: the turns it ran on its own, started by a patrol, a notification or a due note: in the owner's
 *  conversation since everything lives there (2026-10-26), before that in the standing app sessions (cowork-app-*).
 *  The turns the owner's messages started are the chat, never an event. */
export const EVENTS = Object.freeze({ kinds: Object.freeze(['patrol', 'notify', 'note', 'own']), page: 30, pageMax: 60 });

/** Newest first: up to [limit] events that ended before [before] (ms), with whether more remain. */
export function readEvents({ before, limit } = {}, file = activityFile()) {
  const max = Number.isSafeInteger(limit) && limit > 0 ? Math.min(limit, EVENTS.pageMax) : EVENTS.page;
  const all = readLines(file, activityEntry)
    .filter(e => EVENTS.kinds.includes(e.kind) && !(Number.isSafeInteger(before) && e.at >= before))
    .sort((a, b) => b.at - a.at);
  return Object.freeze({ entries: all.slice(0, max), more: all.length > max });
}

/** Once: the old diary.jsonl held one line per turn, which is activity now. It becomes activity.jsonl, so the first
 *  night's entry covers that day, and the file name is free for the daily diary. Returns whether it moved. */
export function migrateDiary(home = homedir(), files = { diary: diaryFile(home), activity: activityFile(home) }) {
  if (existsSync(files.activity) || !existsSync(files.diary)) return false;
  let raw;
  try { raw = readFileSync(files.diary, 'utf8'); } catch { return false; }
  const first = raw.split('\n').find(line => line.trim());
  try { if (!first || typeof JSON.parse(first)?.sessionId !== 'string') return false; } catch { return false; }
  renameSync(files.diary, files.activity);
  return true;
}
