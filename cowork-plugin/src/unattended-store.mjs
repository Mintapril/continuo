import { readFileSync, writeFileSync, mkdirSync, renameSync } from 'node:fs';
import { homedir } from 'node:os';
import { dirname, join } from 'node:path';

// The models the owner picks in Continuo for Cowork runs: one selection for patrols, one for runs a notification
// trigger started and one for the owner's own conversation (叫它), each or null (the session keeps its own model).
// Written by unattended.mjs, read by restricted.mjs on every request of such a run.
export const UNATTENDED_KINDS = Object.freeze(['patrol', 'notify', 'owner']);
export const NO_UNATTENDED = Object.freeze({ patrol: null, notify: null, owner: null });
export const unattendedFile = (home = homedir()) => join(home, '.dsh', 'plugins', 'cowork-phone', 'unattended-models.json');

const CONTROL = new RegExp(`[${String.fromCharCode(0)}-${String.fromCharCode(31)}${String.fromCharCode(127)}]`);
const plain = (value, max) => typeof value === 'string' && value.trim() === value && value !== '' && value.length <= max && !CONTROL.test(value);
const SELECTION_KEYS = new Set(['provider', 'model', 'reasoningEffort']);

/** A stored selection ({provider, model, reasoningEffort?}) or null; throws on anything else. */
export function selectionOf(value) {
  if (value === null || value === undefined) return null;
  if (typeof value !== 'object' || Array.isArray(value) || Object.keys(value).some(key => !SELECTION_KEYS.has(key)))
    throw new TypeError('A model selection is {provider, model, reasoningEffort?} or null');
  const { provider, model, reasoningEffort } = value;
  if (!plain(provider, 200) || !plain(model, 300)) throw new TypeError('A model selection needs a provider and a model');
  if (reasoningEffort !== undefined && reasoningEffort !== null && !plain(reasoningEffort, 40)) throw new TypeError('A reasoning effort is a short name');
  return Object.freeze({ provider, model, ...(reasoningEffort ? { reasoningEffort } : {}) });
}

const lenient = value => { try { return selectionOf(value); } catch { return null; } };

/** The stored setting; a missing or damaged file reads as no setting at all. */
export function readUnattended(file = unattendedFile()) {
  let raw;
  try { raw = JSON.parse(readFileSync(file, 'utf8')); } catch { return NO_UNATTENDED; }
  if (!raw || typeof raw !== 'object') return NO_UNATTENDED;
  return Object.freeze({ patrol: lenient(raw.patrol), notify: lenient(raw.notify), owner: lenient(raw.owner) });
}

/** Replace the stored setting atomically (owner-only file in an owner-only folder). */
export function writeUnattended(value, file = unattendedFile()) {
  const next = Object.freeze({ patrol: selectionOf(value.patrol), notify: selectionOf(value.notify), owner: selectionOf(value.owner) });
  mkdirSync(dirname(file), { recursive: true, mode: 0o700 });
  const temp = `${file}.${process.pid}.tmp`;
  writeFileSync(temp, `${JSON.stringify(next, null, 2)}\n`, { mode: 0o600 });
  renameSync(temp, file);
  return next;
}
