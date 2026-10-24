import { readFileSync, writeFileSync, mkdirSync, renameSync } from 'node:fs';
import { homedir } from 'node:os';
import { dirname, join } from 'node:path';

// The focus the Cowork model keeps for each app: one short line it adds to the Jev judge's fixed question about that
// app's notifications (notify.mjs reads it on every Jev call). Written by the cowork_jev_focus tool (jev-focus.mjs),
// cleared by the owner in Continuo (unattended.mjs). Keyed like the app's standing session: `<profile>:<package>`.
// Entries are {text, at, by, revision}; text '' is a cleared focus, kept so the wait between unattended changes still
// counts from the clear (the owner's clear is not undone by the next patrol).
export const FOCUS = Object.freeze({ maxChars: 200, unattendedEveryMs: 6 * 3_600_000 });
export const FOCUS_BY = Object.freeze(['owner', 'patrol', 'notify', 'unattended', 'app']);
export const focusFile = (home = homedir()) => join(home, '.dsh', 'plugins', 'cowork-phone', 'jev-focus.json');

const PACKAGE = /^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+$/;
const BAD = new RegExp('[\\u0000-\\u001f\\u007f\\u2028\\u2029]');

/** Whether `key` names an app the way the standing sessions do (`work:com.tencent.mobileqq`). */
export function isFocusKey(key) {
  if (typeof key !== 'string' || key.length > 220) return false;
  const at = key.indexOf(':');
  return ['work', 'personal'].includes(key.slice(0, at)) && PACKAGE.test(key.slice(at + 1));
}

/** A focus as stored: trimmed, one line, 1..maxChars characters; throws on anything else. */
export function focusTextOf(value) {
  if (typeof value !== 'string') throw new TypeError('A focus is a string');
  const text = value.trim();
  if (text === '' || text.length > FOCUS.maxChars || BAD.test(text)) throw new TypeError(`A focus is one line of 1..${FOCUS.maxChars} characters`);
  return text;
}

function entryOf(value) {
  if (!value || typeof value !== 'object') return undefined;
  const { text, at, by, revision } = value;
  if (typeof text !== 'string') return undefined;
  if (text !== '') { try { if (focusTextOf(text) !== text) return undefined; } catch { return undefined; } }
  if (!Number.isSafeInteger(at) || at <= 0 || !FOCUS_BY.includes(by) || !Number.isSafeInteger(revision) || revision < 1) return undefined;
  return Object.freeze({ text, at, by, revision });
}

/** Every stored entry by app key; a missing or damaged file (or entry) reads as none. */
export function readFocus(file = focusFile()) {
  let raw;
  try { raw = JSON.parse(readFileSync(file, 'utf8')); } catch { return Object.freeze({}); }
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return Object.freeze({});
  const out = {};
  for (const [key, value] of Object.entries(raw)) { const entry = isFocusKey(key) ? entryOf(value) : undefined; if (entry) out[key] = entry; }
  return Object.freeze(out);
}

/** The focus texts in effect by app key (cleared ones left out), as the Jev request uses them. */
export const activeFocus = entries => Object.fromEntries(Object.entries(entries).filter(([, entry]) => entry.text !== '').map(([key, entry]) => [key, entry.text]));

/**
 * Set (or with text null clear) the focus of one app, atomically (owner-only file in an owner-only folder).
 * @returns {{previous, current}} the entries before and after.
 */
export function writeFocus(key, text, { by, at }, file = focusFile()) {
  if (!isFocusKey(key)) throw new TypeError('An app is <work|personal>:<package>');
  if (!FOCUS_BY.includes(by) || !Number.isSafeInteger(at) || at <= 0) throw new TypeError('A focus change needs who and when');
  const value = text === null ? '' : focusTextOf(text);
  const all = { ...readFocus(file) };
  const previous = all[key];
  const current = Object.freeze({ text: value, at, by, revision: (previous?.revision ?? 0) + 1 });
  all[key] = current;
  mkdirSync(dirname(file), { recursive: true, mode: 0o700 });
  const temp = `${file}.${process.pid}.tmp`;
  writeFileSync(temp, `${JSON.stringify(all, null, 2)}\n`, { mode: 0o600 });
  renameSync(temp, file);
  return { previous, current };
}
