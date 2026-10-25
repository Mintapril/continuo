import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm, writeFile, readFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { selectionOf, readUnattended, writeUnattended, unattendedFile, NO_UNATTENDED } from '../src/unattended-store.mjs';
import { unattendedKind, isOwnerSession } from '../src/restricted.mjs';

// The model of unattended runs (patrols, notification triggers), set by the owner in Continuo; null keeps the session's.
test('a stored selection is a provider and a model, with an optional effort, or null', () => {
  assert.equal(selectionOf(null), null);
  assert.equal(selectionOf(undefined), null);
  assert.deepEqual(selectionOf({ provider: 'codex', model: 'gpt-6-luna' }), { provider: 'codex', model: 'gpt-6-luna' });
  assert.deepEqual(selectionOf({ provider: 'claude', model: 'claude-opus-5-5', reasoningEffort: 'high' }), { provider: 'claude', model: 'claude-opus-5-5', reasoningEffort: 'high' });
  assert.deepEqual(selectionOf({ provider: 'claude', model: 'm', reasoningEffort: null }), { provider: 'claude', model: 'm' });
  for (const bad of [{}, 'codex', [], { provider: 'codex' }, { provider: '', model: 'm' }, { provider: ' codex', model: 'm' }, { provider: 'p', model: 'm\n' }, { provider: 'p', model: 'm', extra: 1 }, { provider: 'p', model: 'm', reasoningEffort: 3 }])
    assert.throws(() => selectionOf(bad), TypeError, JSON.stringify(bad));
});

test('the setting lives in the plugin data folder; a missing or damaged file reads as no setting', async () => {
  assert.equal(unattendedFile('/root'), join('/root', '.dsh', 'plugins', 'cowork-phone', 'unattended-models.json'));
  const dir = await mkdtemp(join(tmpdir(), 'cowork-unattended-'));
  const file = join(dir, 'a', 'b', 'unattended-models.json');
  try {
    assert.equal(readUnattended(file), NO_UNATTENDED);
    const written = writeUnattended({ patrol: { provider: 'claude', model: 'claude-opus-5-5' }, notify: null }, file);
    assert.deepEqual(written, { patrol: { provider: 'claude', model: 'claude-opus-5-5' }, notify: null, owner: null });
    assert.deepEqual(readUnattended(file), written);
    assert.deepEqual(JSON.parse(await readFile(file, 'utf8')), written, 'plain JSON on disk');
    await writeFile(file, '{"patrol":{"provider":"x"},"notify":{"provider":"codex","model":"gpt-6-luna"}}');
    assert.deepEqual(readUnattended(file), { patrol: null, notify: { provider: 'codex', model: 'gpt-6-luna' }, owner: null }, 'a bad entry is dropped, the rest kept');
    writeUnattended({ patrol: null, notify: null, owner: { provider: 'claude', model: 'claude-fable-5-1' } }, file);
    assert.deepEqual(readUnattended(file).owner, { provider: 'claude', model: 'claude-fable-5-1' }, 'the owner conversation has its own setting');
    await writeFile(file, 'not json');
    assert.equal(readUnattended(file), NO_UNATTENDED);
    assert.throws(() => writeUnattended({ patrol: { provider: 'x' }, notify: null }, file), TypeError);
  } finally { await rm(dir, { recursive: true, force: true }); }
});

test('which setting a turn uses comes from what started it', () => {
  const reminder = prompt => ({ role: 'user', source: { kind: 'schedule' }, content: [{ type: 'text', text: ['[SCHEDULE REMINDER]', 'This is a scheduled message from the user',
    'schedule_id_json: "p1"', 'occurrence_at: 2026-10-24T01:00:00.000Z', `reminder_prompt_json: ${JSON.stringify(prompt)}`].join('\n') }] });
  const hook = { role: 'user', source: { kind: 'webhook', ruleId: 'cowork-notify' }, content: [{ type: 'text', text: '[NOTIFICATION TRIGGER]' }] };
  const mine = { role: 'user', source: { kind: 'user' }, content: [{ type: 'text', text: '看看' }] };
  assert.equal(unattendedKind([hook]), 'notify');
  assert.equal(unattendedKind([reminder('[巡视] 每次挑 2–3 个应用逛逛。')]), 'patrol');
  assert.equal(unattendedKind([reminder('[便条] 问问小王')]), undefined, 'a note keeps the session model');
  assert.equal(unattendedKind([reminder('提醒我 [巡视] 的事')]), undefined, 'the marker counts at the start only');
  assert.equal(unattendedKind([hook, mine]), undefined, 'a message of the owner: the session model');
  assert.equal(unattendedKind([{ role: 'user', source: { kind: 'webhook', ruleId: 'other' }, content: [] }]), undefined);
  assert.equal(unattendedKind(undefined), undefined);
  assert.equal(unattendedKind([]), undefined);
});

test('the owner conversation is cowork-owner, or the fresh one Continuo starts when the owner clears it', () => {
  for (const id of ['cowork-owner', 'cowork-owner-20261025-153012']) assert.equal(isOwnerSession(id), true, id);
  for (const id of ['cowork-owner-', 'cowork-owner-x', 'cowork-owner-2026-1', 'cowork-app-patrol', 'xcowork-owner', undefined, 7])
    assert.equal(isOwnerSession(id), false, String(id));
});
