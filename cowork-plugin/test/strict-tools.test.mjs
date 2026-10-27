import test from 'node:test';
import assert from 'node:assert/strict';
import { TOOL_GROUPS, HIDDEN_TOOLS, groupOf, shownWith, groupsFor, toolsTool } from '../src/tool-groups.mjs';
import { ALLOWED_TOOLS, MEMORY_TOOLS, SCHEDULE_TOOLS, phoneTools } from '../src/restricted.mjs';
import { notesTool, noteView, sessionView, PATROL_SESSION } from '../src/notes-view.mjs';

const known = new Set([...ALLOWED_TOOLS, ...MEMORY_TOOLS, ...SCHEDULE_TOOLS]);
const text = (kind, value) => ({ role: 'user', source: { kind }, content: [{ type: 'text', text: value }] });

test('every grouped or hidden tool is a Cowork tool, in one group only; the everyday list is what every run needs', () => {
  const grouped = Object.values(TOOL_GROUPS).flatMap(g => g.tools);
  assert.equal(new Set(grouped).size, grouped.length, 'no tool in two groups');
  for (const name of [...grouped, ...HIDDEN_TOOLS]) assert.ok(known.has(name), name);
  for (const name of HIDDEN_TOOLS) assert.equal(groupOf(name), undefined);
  assert.deepEqual([...known].filter(name => shownWith(new Set(), name)).sort(), ['cowork_tools', 'memory_save', 'memory_search', 'phone_apps',
    'phone_batch', 'phone_key', 'phone_launch_app', 'phone_long_press', 'phone_notify_owner', 'phone_read_chat', 'phone_read_page', 'phone_request_intervention',
    'phone_screenshot', 'phone_scroll', 'phone_status', 'phone_stop', 'phone_swipe', 'phone_tap', 'phone_type_text', 'phone_ui_tree', 'phone_wait']);
});

/** What a read tool is for, when and how to use it, lives in its own description: the system prompt, patrols and
 *  notification triggers do not repeat it. A chat (newest message at the bottom) is phone_read_chat, everything else that
 *  starts at the top is phone_read_page. */
test('phone_read_page and phone_read_chat say which lists they read, and phone_read_chat when and how', () => {
  const tools = phoneTools({ call: async () => ({}) }, {});
  const page = tools.find(t => t.name === 'phone.read_page').description, chat = tools.find(t => t.name === 'phone.read_chat').description;
  for (const part of ['DOWN', 'articles and answers', 'comment sections', 'conversation list', 'read with phone_read_chat instead']) assert.ok(page.includes(part), part);
  for (const part of ['UP', 'newest item is at the bottom', 'private chat or group in a messaging app', 'private messages inside other apps', 'phone_read_page reads those',
    'live-stream chat', 'more unread messages than one screen holds', 'group conversation you join in the middle', 'refers to something earlier', 'summed up several messages',
    'size maxPages', 'keeps sender names', 'the oldest message it reached first', 'reachedEnd:true', 'before you reply, come back to the newest message'])
    assert.ok(chat.includes(part), part);
  assert.deepEqual(Object.keys(tools.find(t => t.name === 'phone.read_chat').parameters.properties).sort(), ['maxPages', 'observationId']);
});

test('a group shows once loaded; hidden tools never show', () => {
  assert.equal(shownWith(new Set(), 'phone_gesture'), false);
  assert.equal(shownWith(new Set(['gesture']), 'phone_gesture'), true);
  assert.equal(shownWith(new Set(Object.keys(TOOL_GROUPS)), 'phone_start'), false);
  assert.equal(shownWith(new Set(), 'phone_tap'), true);
  assert.equal(shownWith(new Set(), 'some_host_tool'), true, 'only Cowork names are filtered');
});

test('the messages of a step call for their tools: notes for a due note, the Jev focus for held notifications, nothing for a context nudge', () => {
  assert.deepEqual(groupsFor([text('user', '你有定时任务吗')]), []);
  assert.deepEqual(groupsFor([text('schedule', '[SCHEDULE REMINDER]\n...')]), ['notes']);
  assert.deepEqual(groupsFor([text('webhook', '[NOTIFICATION TRIGGER]\njev_held_untrusted_json: []\nnotifications_untrusted_json: []')]), ['jev']);
  assert.deepEqual(groupsFor([text('plugin:acp-nudge', 'This is an efficiency nudge to compress early')]), []);
  assert.deepEqual(groupsFor(undefined), []);
});

test('cowork_tools loads groups once and says what came new', async () => {
  const loaded = new Map(), agent = { session: { id: 's' } };
  const t = toolsTool({ loadedOf: a => loaded.get(a) ?? new Set(), load: (a, g) => loaded.set(a, new Set([...(loaded.get(a) ?? []), g])) });
  assert.match(t.description, /^cowork_tools: /);
  for (const group of Object.keys(TOOL_GROUPS)) assert.match(t.description, new RegExp(`${group}: `), group);
  assert.deepEqual(await t.execute({ load: ['apps', 'web'] }, { agent }), { loaded: ['apps', 'web'], added: ['phone_grant_permission', 'phone_uninstall_app', 'web_search', 'web_fetch'], note: 'These tools are in your list from your next step on.' });
  assert.deepEqual((await t.execute({ load: ['web'] }, { agent })).added, []);
  assert.deepEqual((await t.execute({}, { agent })).loaded, ['apps', 'web']);
  await assert.rejects(t.execute({ load: ['shell'] }, { agent }), /unsupported value/);
});

test('cowork_notes names the session of each note and whose it is', async () => {
  const here = 'cowork-app-work-com-tencent-mobileqq';
  assert.deepEqual(sessionView(here, here), { session: 'this session' });
  assert.deepEqual(sessionView(PATROL_SESSION, here), { session: 'the old patrol session (巡视 · 自动运营)' });
  assert.deepEqual(sessionView('cowork-app-personal-tv-danmaku-bili', here), { session: 'the old standing session of an app', app: { package: 'tv.danmaku.bili', profile: 'personal' } });
  assert.deepEqual(sessionView('session-1', here), { session: 'another conversation' });
  const own = noteView({ id: 'n', kind: 'after', title: '便条：看看', prompt: '[便条] 看看', afterSeconds: 600, scheduledAt: '2026-10-24T12:00:00.000Z', sessionId: here }, here, Date.parse('2026-10-24T11:00:00Z'));
  assert.equal(own.whose, 'yours (left on your own)'); assert.equal(own.patrol, false); assert.equal(own.overdue, false); assert.deepEqual(own.timing, { afterSeconds: 600 });
  const notes = notesTool({ catalog: async () => [
    { id: 'p', kind: 'cron', title: '巡视', prompt: '[巡视] 逛逛', expression: '0 9-21/3 * * *', timeZone: 'Asia/Shanghai', scheduledAt: '2026-10-24T13:00:00.000Z', sessionId: PATROL_SESSION, status: 'active' },
    { id: 'gone', kind: 'at', title: '旧的', prompt: '旧的', scheduledAt: '2026-10-01T00:00:00.000Z', sessionId: 'session-1', status: 'inactive' },
  ], now: () => Date.parse('2026-10-24T11:40:00Z') });
  const out = await notes.execute({}, { agent: { session: { id: here } } });
  assert.deepEqual(out.notes.map(n => [n.id, n.patrol, n.whose, n.session]), [['p', true, "the owner's (written while they talked with you)", 'the old patrol session (巡视 · 自动运营)']]);
  await assert.rejects(notesTool({ catalog: () => undefined }).execute({}, { agent: { session: { id: here } } }), /Notes are unavailable/);
});
