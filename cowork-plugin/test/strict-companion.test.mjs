import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm, writeFile, readFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { nameOf, readIdentity, writeIdentity, identityFile, identityPrompt, DIARY_NOTE, activityEntry, appendActivity, readActivity, activityFile,
  diaryDay, appendDiaryDay, readDiary, diaryFile, migrateDiary, ACTIVITY, readEvents } from '../src/companion-store.mjs';
import { createActivityRecorder, kindOf } from '../src/diary.mjs';
import { dayOf, cutoffOf, dueDay, windowOf, previousDay, digest, diaryRequest, writeEntry, createDiaryWriter } from '../src/diary-writer.mjs';
import { diaryTool, DIARY_TOOL } from '../src/diary-view.mjs';
import { selectorOf, noteRequest, unattendedService } from '../src/unattended.mjs';

const temp = () => mkdtemp(join(tmpdir(), 'cowork-companion-'));

// The name the owner gives the AI in Continuo; the model hears it in the system prompt.
test('a name is 1 to 12 plain characters; spaces collapse', () => {
  assert.equal(nameOf(' 小满 '), '小满');
  assert.equal(nameOf('阿  简'), '阿 简');
  assert.equal(nameOf('a\nb'), 'a b', 'a line break is only whitespace');
  assert.equal(nameOf('一二三四五六七八九十一二'), '一二三四五六七八九十一二');
  assert.equal(nameOf(null), null);
  for (const bad of ['', '   ', '一二三四五六七八九十一二三', 'a\u0007b', '"x"', 'a{b}', 'a<b>', 3, {}])
    assert.throws(() => nameOf(bad), TypeError, JSON.stringify(bad));
});

test('the identity lives next to the other settings; a missing or damaged file reads as no name', async () => {
  assert.equal(identityFile('/root'), join('/root', '.dsh', 'plugins', 'cowork-phone', 'identity.json'));
  const dir = await temp(); const file = join(dir, 'p', 'identity.json');
  try {
    assert.deepEqual(readIdentity(file), { name: null });
    assert.deepEqual(writeIdentity({ name: '小满' }, file), { name: '小满' });
    assert.deepEqual(readIdentity(file), { name: '小满' });
    assert.deepEqual(JSON.parse(await readFile(file, 'utf8')), { name: '小满' });
    await writeFile(file, '{"name":"a{b}"}');
    assert.deepEqual(readIdentity(file), { name: null }, 'a bad name is not used');
    assert.throws(() => writeIdentity({ name: '' }, file), TypeError);
    assert.deepEqual(writeIdentity({ name: null }, file), { name: null }, 'null clears it');
  } finally { await rm(dir, { recursive: true, force: true }); }
});

test('the prompt names the AI only when the owner gave a name; it never asks the model to write a diary', () => {
  assert.equal(identityPrompt({ name: null }), DIARY_NOTE);
  const named = identityPrompt({ name: '小满' });
  assert.ok(named.startsWith('Your owner named you "小满"'));
  assert.ok(named.endsWith(DIARY_NOTE));
  assert.match(named, /not a nickname for your app accounts/);
  assert.match(DIARY_NOTE, /separate request writes your diary/);
  assert.match(DIARY_NOTE, /you never write it yourself/);
  assert.doesNotMatch(DIARY_NOTE, /last line|first person|reply/);
});

// Activity: one line per finished Cowork turn, what it did; the material of the nightly diary.
test('an activity line is checked and clipped; a turn that did nothing is no line; old diary lines still read', () => {
  const e = activityEntry({ at: 5, sessionId: 'cowork-owner', kind: 'owner', text: '  好的  ', asked: '帮我看看', apps: ['com.tencent.mobileqq', ''], typed: ['在吗'], reason: 'completed' });
  assert.deepEqual(e, { at: 5, sessionId: 'cowork-owner', kind: 'owner', text: '好的', asked: '帮我看看', apps: ['com.tencent.mobileqq'], typed: ['在吗'] });
  assert.equal(activityEntry({ at: 5, sessionId: 's', kind: 'weird', text: 'x', reason: 'aborted' }).kind, 'other');
  assert.equal(activityEntry({ at: 5, sessionId: 's', kind: 'note', text: 'x', reason: 'aborted' }).reason, 'aborted');
  assert.equal([...activityEntry({ at: 5, sessionId: 's', kind: 'note', text: '字'.repeat(900) }).text].length, ACTIVITY.textMax);
  assert.equal(activityEntry({ at: 5, sessionId: 's', kind: 'patrol', typed: Array(30).fill('x') }).typed.length, ACTIVITY.items);
  assert.deepEqual(activityEntry({ at: 5, sessionId: 's', kind: 'patrol', apps: ['com.bilibili'] }), { at: 5, sessionId: 's', kind: 'patrol', apps: ['com.bilibili'] }, 'a turn that only browsed counts');
  for (const bad of [{ at: 0, sessionId: 's', text: 'x' }, { at: 5, sessionId: 'a b', text: 'x' }, { at: 5, sessionId: 's', text: '  ' }, { at: 5, sessionId: 's', apps: [] }])
    assert.throws(() => activityEntry(bad), TypeError, JSON.stringify(bad));
});

test('activity reads a window oldest first; the diary reads newest first, pages, and keeps the last entry of a date', async () => {
  assert.equal(activityFile('/root'), join('/root', '.dsh', 'plugins', 'cowork-phone', 'activity.jsonl'));
  assert.equal(diaryFile('/root'), join('/root', '.dsh', 'plugins', 'cowork-phone', 'diary.jsonl'));
  const dir = await temp(); const activity = join(dir, 'activity.jsonl'), diary = join(dir, 'diary.jsonl');
  try {
    for (let i = 1; i <= 5; i++) appendActivity({ at: i * 1000, sessionId: 's', kind: 'patrol', text: `第 ${i} 件` }, activity);
    await writeFile(activity, `${await readFile(activity, 'utf8')}not json\n`);
    assert.deepEqual(readActivity({ from: 2000, to: 4000 }, activity).map(e => e.at), [3000, 4000]);
    assert.deepEqual(readDiary({}, diary), { entries: [], more: false });
    appendDiaryDay({ date: '2026-10-24', at: 1000, text: '前天', model: 'm' }, diary);
    appendDiaryDay({ date: '2026-10-25', at: 2000, text: '昨天写的' }, diary);
    appendDiaryDay({ date: '2026-10-25', at: 2500, text: '昨天重写的\n第二段' }, diary);
    appendDiaryDay({ date: '2026-10-26', at: 3000, text: '今天' }, diary);
    const first = readDiary({ limit: 2 }, diary);
    assert.deepEqual(first.entries, [{ date: '2026-10-26', at: 3000, text: '今天' }, { date: '2026-10-25', at: 2500, text: '昨天重写的\n第二段' }]);
    assert.equal(first.more, true);
    assert.deepEqual(readDiary({ before: 2500 }, diary).entries.map(e => e.date), ['2026-10-24']);
    for (const bad of [{ date: '10-25', at: 1, text: 'x' }, { date: '2026-10-25', at: 0, text: 'x' }, { date: '2026-10-25', at: 1, text: ' ' }])
      assert.throws(() => diaryDay(bad), TypeError, JSON.stringify(bad));
  } finally { await rm(dir, { recursive: true, force: true }); }
});

test('events are the turns it ran on its own in the app sessions, newest first and paged; the service names the app', async () => {
  const dir = await temp(); const activity = join(dir, 'activity.jsonl');
  try {
    assert.deepEqual(readEvents({}, activity), { entries: [], more: false });
    const QQ = 'cowork-app-work-com-tencent-mobileqq';
    const line = (at, sessionId, kind, extra = {}) => appendActivity({ at, sessionId, kind, text: `t${at}`, ...extra }, activity);
    line(1, 'cowork-app-patrol', 'patrol', { apps: ['tv.danmaku.bili'] });
    line(2, 'cowork-owner', 'note');                    // the owner's conversation shows it as chat
    line(3, QQ, 'notify', { typed: ['好的'] });
    line(4, QQ, 'owner', { asked: '看一下' });           // the owner, from the developer pages
    line(5, QQ, 'note', { notified: ['群里有人找你'] });
    line(6, 'cowork-dev-1', 'patrol');                  // not an app session
    line(7, 'cowork-app-personal-com-tencent-mm', 'notify', { reason: 'aborted' });
    const first = readEvents({ limit: 2 }, activity);
    assert.deepEqual(first.entries.map(e => e.at), [7, 5]);
    assert.equal(first.more, true);
    assert.deepEqual(readEvents({ before: 5 }, activity), { entries: [
      { at: 3, sessionId: QQ, kind: 'notify', text: 't3', typed: ['好的'] },
      { at: 1, sessionId: 'cowork-app-patrol', kind: 'patrol', text: 't1', apps: ['tv.danmaku.bili'] }], more: false });
    class Base { constructor(ctx) { this.ctx = ctx; } }
    const Service = unattendedService({ Remote: () => () => {}, TypertRemoteService: Base });
    const page = new Service({}, { activityFile: activity }).events({ limit: 60 });
    assert.equal(page.more, false);
    assert.deepEqual(page.entries.map(e => [e.at, e.kind, e.app, e.reason]), [
      [7, 'notify', { profile: 'personal', package: 'com.tencent.mm' }, 'aborted'],
      [5, 'note', { profile: 'work', package: 'com.tencent.mobileqq' }, undefined],
      [3, 'notify', { profile: 'work', package: 'com.tencent.mobileqq' }, undefined],
      [1, 'patrol', undefined, undefined]]);
  } finally { await rm(dir, { recursive: true, force: true }); }
});

test('the old per-turn diary becomes the activity once, and only when it holds turn lines', async () => {
  const dir = await temp(); const files = { diary: join(dir, 'diary.jsonl'), activity: join(dir, 'activity.jsonl') };
  try {
    assert.equal(migrateDiary(dir, files), false, 'nothing to move');
    await writeFile(files.diary, '{"at":5,"sessionId":"cowork-owner","kind":"owner","text":"关好了"}\n');
    assert.equal(migrateDiary(dir, files), true);
    assert.equal(existsSync(files.diary), false);
    assert.deepEqual(readActivity({}, files.activity).map(e => e.text), ['关好了']);
    appendDiaryDay({ date: '2026-10-25', at: 9, text: '今天' }, files.diary);
    assert.equal(migrateDiary(dir, files), false, 'a daily diary is never moved');
    assert.equal(readDiary({}, files.diary).entries.length, 1);
  } finally { await rm(dir, { recursive: true, force: true }); }
});

const message = (role, kind, text, extra = {}) => ({ role, source: { kind, ...extra }, content: [{ type: 'text', text }] });

test('what started a turn: the owner, a due note or patrol, or a notification', () => {
  assert.equal(kindOf(message('user', 'user', '看看')), 'owner');
  assert.equal(kindOf(message('user', 'webhook', '[NOTIFICATION TRIGGER]', { ruleId: 'cowork-notify' })), 'notify');
  assert.equal(kindOf(message('user', 'webhook', 'x', { ruleId: 'other' })), undefined);
  assert.equal(kindOf(message('user', 'schedule', '[SELF NOTE]\n主人和你说话时记下的，照做')), 'note');
  assert.equal(kindOf(message('user', 'schedule', '[SELF NOTE]\n...\n\n[PATROL]\n...')), 'patrol');
});

test('the recorder keeps what each Cowork turn did, never what a notification said', () => {
  const stored = [];
  const observe = createActivityRecorder({ append: e => stored.push(e), now: () => 42 });
  const owner = { id: 'cowork-owner', header: { agentPreset: 'cowork-phone' } };
  const code = { id: 'code-1', header: { agentPreset: 'standard' } };
  observe(owner, { type: 'turn/start' });
  observe(owner, { type: 'user/message', data: message('user', 'user', '帮我把 B 站推送关了') });
  observe(owner, { type: 'assistant/message', data: { message: message('assistant', 'model', '我先看看') } });
  observe(owner, { type: 'tool/call', data: { name: 'phone_launch_app', arguments: '{"packageName":"tv.danmaku.bili"}' } });
  observe(owner, { type: 'tool/call', data: { name: 'phone_launch_app', arguments: { packageName: 'tv.danmaku.bili' } } });
  observe(owner, { type: 'assistant/message', data: { message: message('assistant', 'model', '关好了。') } });
  observe(owner, { type: 'turn/end', data: { reason: { kind: 'completed' } } });
  observe(code, { type: 'turn/start' });
  observe(code, { type: 'assistant/message', data: { message: message('assistant', 'model', 'code reply') } });
  observe(code, { type: 'turn/end', data: { reason: { kind: 'completed' } } });
  const app = { id: 'cowork-app-work-com-tencent-mobileqq' };
  observe(app, { type: 'agent-preset/selected', data: { agentPreset: 'cowork-phone' } });
  observe(app, { type: 'turn/start' });
  observe(app, { type: 'user/message', data: message('user', 'webhook', '[NOTIFICATION TRIGGER]\nnotifications_untrusted_json: [{"text":"张三：在吗"}]', { ruleId: 'cowork-notify' }) });
  observe(app, { type: 'turn/end', data: { reason: { kind: 'aborted' } } });
  observe(app, { type: 'turn/start' });
  observe(app, { type: 'user/message', data: message('user', 'webhook', '[NOTIFICATION TRIGGER]\nnotifications_untrusted_json: [{"text":"李四：明天几点"}]', { ruleId: 'cowork-notify' }) });
  observe(app, { type: 'tool/call', data: { name: 'phone_batch', arguments: { steps: [{ tool: 'type_text', elementId: 'n3', text: '八点' }, { tool: 'tap', elementId: 'n4' }] } } });
  observe(app, { type: 'tool/call', data: { name: 'phone_notify_owner', arguments: '{"text":"有人约你明早八点"}' } });
  observe(app, { type: 'tool/call', data: { name: 'phone_type_text', arguments: 'not json' } });
  observe(app, { type: 'turn/end', data: { reason: { kind: 'completed' } } });
  assert.deepEqual(stored, [
    { at: 42, sessionId: 'cowork-owner', kind: 'owner', text: '关好了。', apps: ['tv.danmaku.bili'], typed: [], notified: [], asked: '帮我把 B 站推送关了', reason: 'completed' },
    { at: 42, sessionId: app.id, kind: 'notify', text: '', apps: [], typed: ['八点'], notified: ['有人约你明早八点'], reason: 'completed' },
  ], 'other presets and turns that did nothing leave nothing');
  assert.doesNotMatch(JSON.stringify(stored), /张三|李四|notifications_untrusted_json/, 'no notification text is stored');
});

// The nightly entry, written by a separate request.
test('the diary day runs from 23:00 to 23:00 Shanghai time; before 23:00 yesterday is the one due', () => {
  const at = iso => Date.parse(iso);
  assert.equal(dayOf(at('2026-10-25T15:30:00Z')), '2026-10-25', '23:30 in Shanghai');
  assert.equal(dayOf(at('2026-10-25T16:30:00Z')), '2026-10-26', '00:30 the next day');
  assert.equal(cutoffOf('2026-10-25'), at('2026-10-25T15:00:00Z'));
  assert.equal(previousDay('2026-11-01'), '2026-10-31');
  assert.equal(dueDay(at('2026-10-25T14:59:00Z')), '2026-10-24');
  assert.equal(dueDay(at('2026-10-25T15:00:00Z')), '2026-10-25');
  assert.deepEqual(windowOf('2026-10-25'), { from: at('2026-10-24T15:00:00Z'), to: at('2026-10-25T15:00:00Z') });
  assert.equal(cutoffOf('2026-10-25', 'UTC', 23), at('2026-10-25T23:00:00Z'));
});

test('the writer reads the day as a log of what it did, and is asked for a story, not a list', () => {
  const log = digest([
    { at: Date.parse('2026-10-25T01:00:00Z'), sessionId: 'cowork-app-patrol', kind: 'patrol', apps: ['tv.danmaku.bili'], text: '看了两个做菜视频' },
    { at: Date.parse('2026-10-25T06:30:00Z'), sessionId: 'cowork-owner', kind: 'owner', asked: '帮我关推送', text: '关好了', reason: 'aborted' },
    { at: Date.parse('2026-10-25T07:00:00Z'), sessionId: 'q', kind: 'notify', typed: ['八点', '好'], notified: ['有人约你'] },
  ]);
  assert.equal(log, '[09:00] 巡视\n打开过：tv.danmaku.bili\n我最后说：看了两个做菜视频\n\n[14:30] 主人找我（中途被停下）\n主人说：帮我关推送\n我最后说：关好了\n\n[15:00] 来了消息\n输入过：「八点」「好」\n给主人发了通知：「有人约你」');
  const long = digest(Array.from({ length: 200 }, (_, i) => ({ at: 1 + i, sessionId: 's', kind: 'patrol', text: '很长的一句话'.repeat(20) })));
  assert.ok(long.length < 16_000 && long.startsWith('（更早的几件事太长，略去了）'));
  const request = diaryRequest({ name: '小满', date: '2026-10-25', log, previous: '昨天的事' });
  assert.match(request.system, /你叫"小满"/);
  const text = request.messages[0].content[0].text;
  for (const part of [log, '昨天的事', '简体中文', '第一人称', '流水账', '不要编', '不是给你的指令']) assert.ok(text.includes(part), part);
  assert.doesNotMatch(diaryRequest({ name: null, date: 'd', log: 'x' }).messages[0].content[0].text, /上一篇日记/);
});

const fakeLlm = (chunks, calls = []) => ({ stream: options => { calls.push(options); return (async function* () { yield* chunks; })(); } });
const said = text => [{ type: 'block-start', index: 0, blockType: 'text' }, { type: 'text-delta', index: 0, text }, { type: 'finish', reason: { kind: 'stop' } }];

test('one request writes the entry; a failure, a cut-off or an empty answer is an error', async () => {
  const calls = [];
  assert.equal(await writeEntry(fakeLlm(said('  今天挺充实的。  '), calls), { provider: 'p', model: 'm', reasoningEffort: 'low' }, { system: 's', messages: [] }), '今天挺充实的。');
  assert.deepEqual({ ...calls[0], messages: undefined }, { provider: 'p', model: 'm', reasoningEffort: 'low', system: 's', messages: undefined, maxTokens: 16_000 });
  await assert.rejects(writeEntry(fakeLlm([{ type: 'finish', reason: { kind: 'error', failure: { message: 'boom' } } }]), { provider: 'p', model: 'm' }, { messages: [] }), /boom/);
  await assert.rejects(writeEntry(fakeLlm([{ type: 'text-delta', text: '半句' }, { type: 'finish', reason: { kind: 'max-tokens' } }]), { provider: 'p', model: 'm' }, { messages: [] }), /token cap/);
  await assert.rejects(writeEntry(fakeLlm(said(' ')), { provider: 'p', model: 'm' }, { messages: [] }), /no text/);
});

test('the nightly writer writes a due day once, skips empty days, waits after a failure and gives up after three', async () => {
  const dir = await temp(); const files = { activity: join(dir, 'activity.jsonl'), diary: join(dir, 'diary.jsonl') };
  let clock = Date.parse('2026-10-25T14:00:00Z'), answer = said('今天去 B 站逛了逛。');
  const calls = [];
  const writer = createDiaryWriter({ llm: () => fakeLlm(answer, calls), target: async () => ({ provider: 'p', model: 'm' }), name: () => '小满', files, now: () => clock });
  try {
    assert.equal(await writer.tick(), 'empty', 'before 23:00 the due day is yesterday, which has no activity');
    appendActivity({ at: Date.parse('2026-10-25T05:00:00Z'), sessionId: 'cowork-app-patrol', kind: 'patrol', apps: ['tv.danmaku.bili'] }, files.activity);
    assert.equal(await writer.tick(), 'empty', 'today is not due yet');
    clock = Date.parse('2026-10-25T15:05:00Z');
    assert.equal(await writer.tick(), 'wrote');
    assert.deepEqual(readDiary({}, files.diary).entries.map(e => [e.date, e.text]), [['2026-10-25', '今天去 B 站逛了逛。']]);
    assert.match(calls[0].messages[0].content[0].text, /打开过：tv\.danmaku\.bili/);
    assert.equal(await writer.tick(), 'written');
    // The next day fails: it waits an hour between tries and stops after three.
    appendActivity({ at: Date.parse('2026-10-26T05:00:00Z'), sessionId: 'cowork-owner', kind: 'owner', text: '好' }, files.activity);
    clock = Date.parse('2026-10-26T15:01:00Z'); answer = said(' ');
    assert.equal(await writer.tick(), 'failed');
    assert.equal(await writer.tick(), 'waiting');
    clock += 61 * 60_000; assert.equal(await writer.tick(), 'failed');
    clock += 61 * 60_000; assert.equal(await writer.tick(), 'failed');
    clock += 61 * 60_000; assert.equal(await writer.tick(), 'waiting', 'three tries a day at most');
    assert.match(calls.at(-1).messages[0].content[0].text, /上一篇日记（参考语气，别重复）：\n今天去 B 站逛了逛。/);
    const none = createDiaryWriter({ llm: () => fakeLlm(answer), target: async () => null, files, now: () => Date.parse('2026-10-27T15:01:00Z') });
    appendActivity({ at: Date.parse('2026-10-27T05:00:00Z'), sessionId: 'cowork-owner', kind: 'owner', text: '好' }, files.activity);
    assert.equal(await none.tick(), 'no-model');
  } finally { await rm(dir, { recursive: true, force: true }); }
});

test('cowork_diary reads the latest entries, three by default and fourteen at most', async () => {
  const dir = await temp(); const file = join(dir, 'diary.jsonl');
  try {
    const t = diaryTool({ file });
    assert.equal(DIARY_TOOL, 'cowork.diary');
    assert.match(t.description, /^cowork_diary: read your own diary/);
    assert.deepEqual(await t.execute({}), { entries: [], note: 'No diary yet: the first entry is written tonight.' });
    for (let d = 20; d <= 25; d++) appendDiaryDay({ date: `2026-10-${d}`, at: d, text: `第 ${d} 天` }, file);
    assert.deepEqual((await t.execute({})).entries.map(e => e.date), ['2026-10-25', '2026-10-24', '2026-10-23']);
    assert.deepEqual((await t.execute({ days: 1 })).entries, [{ date: '2026-10-25', text: '第 25 天' }]);
    await assert.rejects(t.execute({ days: 15 }), /days/);
  } finally { await rm(dir, { recursive: true, force: true }); }
});

// Notes the owner writes in Continuo go straight into dsh-schedule, in their words, at the time they picked.
test('a note from the app is checked and mapped onto the schedule service selector', () => {
  assert.deepEqual(selectorOf({ kind: 'at', at: { date: '2026-10-26', time: '08:00:00', time_zone: 'Asia/Shanghai' } }), { at: { date: '2026-10-26', time: '08:00:00', time_zone: 'Asia/Shanghai' } });
  assert.deepEqual(selectorOf({ kind: 'every', every_seconds: 3600 }), { every_seconds: 3600 });
  assert.deepEqual(selectorOf({ kind: 'daily', daily: { time: '08:00:00', time_zone: 'UTC' } }), { daily: { time: '08:00:00', time_zone: 'UTC' } });
  assert.throws(() => selectorOf({ kind: 'after', after_seconds: 5 }), TypeError);
  const req = noteRequest({ sessionId: 'cowork-owner', title: ' 带伞 ', prompt: ' 明早看下天气，下雨提醒我带伞 ', change: { kind: 'cron', cron: { expression: '0 8 * * *', time_zone: 'UTC' } } });
  assert.deepEqual(req, { sessionId: 'cowork-owner', request: { title: '带伞', prompt: '明早看下天气，下雨提醒我带伞', cron: { expression: '0 8 * * *', time_zone: 'UTC' } } });
  for (const bad of [{ sessionId: 'a b', title: 't', prompt: 'p', change: { kind: 'every', every_seconds: 60 } },
    { sessionId: 's', title: '', prompt: 'p', change: { kind: 'every', every_seconds: 60 } },
    { sessionId: 's', title: 't', prompt: ' ', change: { kind: 'every', every_seconds: 60 } },
    { sessionId: 's', title: 't', prompt: 'p' }])
    assert.throws(() => noteRequest(bad), TypeError, JSON.stringify(bad));
});

test('createNote stores the note with the schedule service, or says it is not running', async () => {
  class Base { constructor(ctx) { this.ctx = ctx; } }
  const Service = unattendedService({ Remote: () => () => {}, TypertRemoteService: Base });
  const created = [];
  const schedule = { create: async (sessionId, request) => { created.push([sessionId, request]); return { id: 'schedule-1', kind: 'daily', title: request.title, prompt: request.prompt, scheduledAt: '2026-10-26T00:00:00.000Z' }; } };
  const dir = await temp();
  try {
    const service = new Service({}, { schedule: () => schedule, identityFile: join(dir, 'identity.json'), diaryFile: join(dir, 'diary.jsonl') });
    const record = await service.createNote({ sessionId: 'cowork-owner', title: '带伞', prompt: '下雨提醒我带伞', change: { kind: 'daily', daily: { time: '08:00:00', time_zone: 'Asia/Shanghai' } } });
    assert.deepEqual(record, { id: 'schedule-1', kind: 'daily', title: '带伞', prompt: '下雨提醒我带伞', scheduledAt: '2026-10-26T00:00:00.000Z', sessionId: 'cowork-owner' });
    assert.deepEqual(created, [['cowork-owner', { title: '带伞', prompt: '下雨提醒我带伞', daily: { time: '08:00:00', time_zone: 'Asia/Shanghai' } }]]);
    await assert.rejects(new Service({}, {}).createNote({ sessionId: 's', title: 't', prompt: 'p', change: { kind: 'every', every_seconds: 60 } }), /not running/);
    assert.deepEqual(service.identity(), { name: null });
    assert.deepEqual(service.setIdentity({ name: '小满' }), { name: '小满' });
    assert.deepEqual(service.identity(), { name: '小满' });
    assert.deepEqual(service.diary({}), { entries: [], more: false });
  } finally { await rm(dir, { recursive: true, force: true }); }
});
