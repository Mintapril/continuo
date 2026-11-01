import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm, readFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { presetTraits, derive, INITIAL, PSYCHE } from '../src/psyche.mjs';
import { createPsycheBoard, outreachOf, readOutreach, writeOutreach, readCharacter, psycheOf, OUTREACH_DEFAULT, LEVELS } from '../src/psyche-store.mjs';
import { reachRefusal, recordReach, reachTool, contactsLine, latestTopic, topicsOn, REACH } from '../src/reach.mjs';
import { ownTimePrompt, silentPrompt, chaseDue, createOwnTime, isOwnTimeSource, OWN } from '../src/own-time.mjs';
import { createPsycheRecorder, callsOf, outputTokensOf } from '../src/psyche-recorder.mjs';
import { fromOwner, fromItself, createOwnerDelivery } from '../src/notify.mjs';
import { kindOf, createActivityRecorder } from '../src/diary.mjs';
import { diaryRequest } from '../src/diary-writer.mjs';
import { unattendedService } from '../src/unattended.mjs';
import { unattendedKind, priorityOf } from '../src/restricted.mjs';
import { PRIORITY } from '../src/task-lifecycle.mjs';

const H = 3_600_000, ZONE = 'Asia/Shanghai';
const at = (h, m = 0) => Date.UTC(2026, 9, 31, h - 8, m); // Shanghai clock on 2026-10-31
const QQ = 'com.tencent.mobileqq', WX = 'com.tencent.mm';
const contacts = [{ package: QQ, remark: '主人', shownAs: '小满' }, { package: WX, remark: '老大', shownAs: '小满' }];
async function folder() {
  const dir = await mkdtemp(join(tmpdir(), 'cowork-personality-'));
  return { dir, files: { character: join(dir, 'character.json'), outreach: join(dir, 'outreach.json'), psyche: join(dir, 'psyche.json') } };
}
const empty = () => psycheOf(undefined);

test('outreach settings are checked field by field and stored as a partial update', async () => {
  assert.deepEqual(outreachOf(undefined), OUTREACH_DEFAULT);
  assert.equal(outreachOf({ level: 'many' }).level, 'many');
  assert.throws(() => outreachOf({ level: 'always' }), /level/);
  assert.throws(() => outreachOf({ quietFrom: '25:00' }), /HH:mm/);
  assert.throws(() => outreachOf({ contacts: [{ package: 'not a package', remark: 'a', shownAs: 'b' }] }), /package/);
  assert.throws(() => outreachOf({ contacts: [contacts[0], contacts[0]] }), /one contact per app/);
  assert.throws(() => outreachOf({ contacts: [{ package: QQ, remark: '', shownAs: 'b' }] }), /remark/);
  assert.throws(() => outreachOf({ dndUntil: -1 }), /dndUntil/);
  assert.equal(outreachOf({ contacts: [{ package: QQ, remark: '  主  人 ', shownAs: '小满' }] }).contacts[0].remark, '主 人');
  assert.deepEqual(LEVELS, { off: 0, few: 1, normal: 3, many: 5 });
  const { dir, files } = await folder();
  try {
    assert.deepEqual(readOutreach(files.outreach), OUTREACH_DEFAULT, 'a missing file reads as the defaults');
    writeOutreach({ contacts }, files.outreach);
    const next = writeOutreach({ level: 'few' }, files.outreach);
    assert.equal(next.level, 'few');
    assert.deepEqual(next.contacts, contacts, 'fields not given are kept');
    assert.deepEqual(JSON.parse(await readFile(files.outreach, 'utf8')).contacts, contacts);
  } finally { await rm(dir, { recursive: true, force: true }); }
});

test('the board keeps the character, counts rest from the last turn and stores every change', async () => {
  const { dir, files } = await folder();
  let clock = at(10);
  try {
    const board = createPsycheBoard({ files, now: () => clock });
    assert.equal(board.character(), null);
    assert.equal(board.derived(), null);
    board.setCharacter(presetTraits('ESFJ'));
    assert.equal(board.derived().type, 'ESFJ');
    assert.deepEqual(readCharacter(files.character), presetTraits('ESFJ'));
    assert.throws(() => board.setCharacter({ Fi: 1 }), /eight whole values/);
    board.update(d => ({ ...d, state: { ...d.state, at: clock, energy: 50 } }));
    board.started('cowork-owner');
    clock += 2 * H;
    assert.equal(board.state().energy, 50, 'nothing refills while a turn runs');
    board.ended('cowork-owner');
    const endedAt = clock;
    assert.equal(board.idleSince(), endedAt);
    clock += H;
    assert.ok(Math.abs(board.state().energy - (50 + 8 * 0.8)) < 0.01, 'an hour of rest refills 8(1 - e/2)');
    const reread = createPsycheBoard({ files, now: () => clock });
    assert.equal(reread.data().state.energy, 50, 'the stored value, rest is applied when read');
    assert.equal(reread.derived().type, 'ESFJ');
    assert.equal(reread.idleSince(), endedAt, 'a restart of DSH does not start the rest over');
    // Two turns at once: rest starts when the last one ends.
    board.started('a'); board.started('b');
    clock += H;
    board.ended('a');
    assert.equal(createPsycheBoard({ files, now: () => clock }).idleSince(), endedAt, 'b still runs');
    board.ended('b');
    assert.equal(createPsycheBoard({ files, now: () => clock }).idleSince(), clock);
    // A stored time after now (the clock was set back) counts as unknown: rest counts from the board.
    assert.equal(createPsycheBoard({ files, now: () => clock - 2 * H }).idleSince(), clock - 2 * H);
    board.setCharacter(null);
    assert.equal(createPsycheBoard({ files }).character(), null);
    // Without a character the end of a turn is still kept, for when the owner sets one.
    board.started('cowork-owner');
    clock += H;
    board.ended('cowork-owner');
    assert.equal(createPsycheBoard({ files, now: () => clock + H }).idleSince(), clock);
  } finally { await rm(dir, { recursive: true, force: true }); }
});

test('reaching the owner: never in their turn, a few topics a day, one more way only once they had time to see it', () => {
  const outreach = outreachOf({ contacts, level: 'normal' });
  const esfj = derive(presetTraits('ESFJ'));
  const base = { args: { channel: 'continuo', text: 'hi' }, user: false, outreach, data: empty(), derived: esfj, now: at(10), zone: ZONE };
  assert.equal(reachRefusal(base), undefined);
  assert.match(reachRefusal({ ...base, user: true }), /talking with you in this chat/);
  assert.match(reachRefusal({ ...base, outreach: outreachOf({ level: 'off' }) }), /turned off/);
  assert.match(reachRefusal({ ...base, args: { channel: 'com.example.app', text: 'x' } }), /continuo, com.tencent.mobileqq, com.tencent.mm/);
  assert.match(reachRefusal({ ...base, outreach: outreachOf({ contacts, dndUntil: at(11) }) }), /not to be disturbed until 11:00/);
  assert.match(reachRefusal({ ...base, now: at(23, 30) }), /quiet hours/);
  // One topic, unanswered: no new topic today; a chase only after waiting, on another way, once.
  let data = psycheOf(recordReach(empty(), { channel: 'continuo', text: '看到一个好笑的视频', now: at(10) }));
  assert.match(reachRefusal({ ...base, data, now: at(10, 5) }), /still unanswered/);
  assert.match(reachRefusal({ ...base, data, args: { channel: QQ, text: 'x', chase: true }, now: at(10, 30) }), /from 10:57/);
  assert.equal(reachRefusal({ ...base, data, args: { channel: QQ, text: 'x', chase: true }, now: at(11) }), undefined);
  assert.match(reachRefusal({ ...base, data, args: { channel: 'continuo', text: 'x', chase: true }, now: at(11) }), /different way/);
  assert.match(reachRefusal({ ...base, data: { ...data, seenAt: at(10, 10) }, args: { channel: QQ, text: 'x', chase: true }, now: at(11) }), /saw your message/);
  const chased = psycheOf(recordReach(data, { channel: QQ, chase: true, now: at(11) }));
  assert.equal(latestTopic(chased.reaches).length, 2);
  assert.match(reachRefusal({ ...base, data: chased, args: { channel: WX, text: 'x', chase: true }, now: at(12) }), /already tried a second way/);
  // The owner answered: a new topic is fine, until the day's allowance is used.
  data = { ...chased, answeredAt: at(12) };
  assert.equal(reachRefusal({ ...base, data, now: at(13) }), undefined);
  assert.match(reachRefusal({ ...base, data, args: { channel: 'continuo', text: 'x', chase: true }, now: at(13) }), /nothing to chase/);
  let full = data;
  for (const h of [13, 14]) full = { ...psycheOf(recordReach(full, { channel: 'continuo', now: at(h) })), answeredAt: at(h, 1) };
  assert.equal(topicsOn(full.reaches, at(15), ZONE), 3);
  assert.match(reachRefusal({ ...base, data: full, now: at(15) }), /enough for today: the owner allows 3 topics a day/);
  assert.equal(reachRefusal({ ...base, data: full, now: at(15) + 24 * H }), undefined, 'the next day');
  // Unanswered since yesterday: a new day may open a new topic.
  const yesterday = psycheOf(recordReach(empty(), { channel: 'continuo', now: at(20) - 24 * H }));
  assert.equal(reachRefusal({ ...base, data: yesterday }), undefined);
});

test('the reach tool sends in Continuo, or clears the way and says where to send', async () => {
  const sent = [];
  let data = empty();
  const board = { outreach: () => outreachOf({ contacts }), update: change => { data = psycheOf(change(data)); return data; } };
  const definition = reachTool({ board, sendNotice: async (sid, text) => { sent.push([sid, text]); }, now: () => at(10) });
  assert.equal(definition.name, 'cowork.reach_owner');
  const exec = { agent: { session: { id: 'cowork-owner' } }, signal: new AbortController().signal };
  assert.deepEqual(await definition.execute({ channel: 'continuo', text: '  今天被人怼了，   气死  ' }, exec), { sent: true, channel: 'continuo', note: 'Sent. Their answer comes here; do not wait for it.' });
  assert.deepEqual(sent, [['cowork-owner', '今天被人怼了， 气死']]);
  const r = await definition.execute({ channel: QQ, text: '在吗', chase: true }, exec);
  assert.equal(r.sent, false);
  assert.match(r.note, /open com.tencent.mobileqq with phone_launch_app, go to your private chat with "主人"/);
  assert.deepEqual(data.reaches.map(x => [x.channel, x.topic, x.text]), [['continuo', 1, '今天被人怼了， 气死'], [QQ, 1, '在吗']]);
  await assert.rejects(definition.execute({ channel: 'continuo', text: 'x'.repeat(201) }, exec));
  const line = contactsLine(outreachOf({ contacts }));
  assert.ok(line.startsWith('owner_contacts_json: '));
  assert.deepEqual(JSON.parse(line.slice('owner_contacts_json: '.length)), { continuo: true, apps: [{ package: QQ, owner_shown_as: '主人' }, { package: WX, owner_shown_as: '老大' }], per_day: 3, quiet: '23:00-08:00' });
});

test('its own time: the message, when it starts, and the follow-up when the owner has not seen its message', async () => {
  const infp = derive(presetTraits('INFP'));
  const outreach = outreachOf({ contacts, preferences: '多去 B站 看看画画的视频' });
  const prompt = (data, now = at(10), more = {}) => ownTimePrompt({ state: { ...INITIAL, mood: 60 }, derived: infp, outreach, data, now, zone: ZONE, ...more });
  const text = prompt(empty());
  assert.ok(text.startsWith('[OWN TIME]\nNobody asked you for anything'));
  assert.match(text, /time_shares_json: \{"browsing and trying new things":29,"reading closely":21,"talking with people":15,"thinking things over, keeping notes and your own things":35\}/);
  assert.match(text, /owner_wishes_json: "多去 B站 看看画画的视频"/);
  assert.match(text, /owner_contacts_json: /);
  assert.doesNotMatch(prompt(empty(), at(10), { outreach: outreachOf({ level: 'off' }) }), /owner_contacts_json|owner_wishes_json|cowork_reach_owner/);
  // Reaching the owner is spelled out: how many times are left today, and that its replies here reach nobody.
  assert.match(text, /The owner likes hearing from you\. Like a friend, text them .* Today you have reached them 0 of the 3 times they allow\./);
  assert.match(text, /Unless a message of the owner comes in, your replies in this run never reach them: whatever you want them to hear, say it with cowork_reach_owner\./);
  let reached = psycheOf(recordReach(empty(), { channel: 'continuo', text: '在干嘛', now: at(9) }));
  assert.match(prompt(reached), /Your message to the owner at 09:00 is still unanswered: no new topic until they answer\./);
  assert.doesNotMatch(prompt(reached), /likes hearing from you/);
  assert.match(prompt({ ...reached, answeredAt: at(9, 30) }), /Today you have reached them 1 of the 3 times/);
  assert.match(prompt(reached, at(10) + 24 * H), /Today you have reached them 0 of the 3 times/, 'unanswered since yesterday: a new day');
  for (const h of [11, 12]) reached = { ...psycheOf(recordReach(reached, { channel: 'continuo', now: at(h) })), answeredAt: at(h, 1) };
  assert.match(prompt(reached, at(13)), /You have reached the owner as often as they allow today \(3\): keep anything else for tomorrow\./);
  // Its own time may start from 07:00, the owner's quiet hours last until 08:00: the line asks for nothing the guard refuses.
  assert.match(prompt(empty(), at(7, 10)), /These are the owner's quiet hours \(until 08:00\): leave yourself a note/);
  assert.doesNotMatch(prompt(empty(), at(7, 10)), /likes hearing from you/);
  assert.notEqual(reachRefusal({ args: { channel: 'continuo', text: 'x' }, user: false, outreach, data: empty(), derived: infp, now: at(7, 10), zone: ZONE }), undefined);
  assert.match(prompt(empty(), at(10), { outreach: outreachOf({ contacts, dndUntil: at(11) }) }), /not to be disturbed until 11:00: leave yourself a note/);
  assert.ok(isOwnTimeSource({ kind: 'webhook', ruleId: OWN.rule }));
  assert.ok(!isOwnTimeSource({ kind: 'webhook', ruleId: 'cowork-notify' }));
  // The follow-up: unanswered, unseen, alone in its topic, waited out (INFP: 93 minutes), another way exists.
  const data = psycheOf(recordReach(empty(), { channel: 'continuo', text: '在干嘛', now: at(10) }));
  assert.equal(chaseDue({ data, outreach, derived: infp, now: at(11), zone: ZONE }), undefined);
  assert.equal(chaseDue({ data, outreach, derived: infp, now: at(11, 40), zone: ZONE })?.topic, 1);
  assert.equal(chaseDue({ data: { ...data, seenAt: at(10, 5) }, outreach, derived: infp, now: at(12), zone: ZONE }), undefined);
  assert.equal(chaseDue({ data, outreach: outreachOf({}), derived: infp, now: at(12), zone: ZONE }), undefined, 'no other way to try');
  assert.equal(chaseDue({ data: { ...data, chasedTopic: 1 }, outreach, derived: infp, now: at(12), zone: ZONE }), undefined);
  assert.match(silentPrompt({ reach: data.reaches[0], outreach, zone: ZONE }), /^\[OWNER SILENT\]\nAt 10:00 you reached the owner \(channel "continuo": "在干嘛"\)/);
  // The clock.
  const { dir, files } = await folder();
  let clock = at(10), busy = false;
  const delivered = [];
  try {
    const board = createPsycheBoard({ files, now: () => clock });
    const own = createOwnTime({ board, busy: () => busy, deliver: async (body, rule) => { delivered.push(rule); }, now: () => clock, zone: ZONE });
    assert.equal(await own.tick(), 'no-character');
    board.setCharacter(presetTraits('ESFJ'));
    board.update(d => ({ ...d, state: { ...d.state, at: clock, energy: 80 } }));
    assert.equal(await own.tick(), 'resting', 'idle only since the board was made');
    clock += 2 * H;
    busy = true;
    assert.equal(await own.tick(), 'busy');
    busy = false;
    assert.equal(await own.tick(), 'own');
    assert.equal(await own.tick(), 'pending', 'one start at a time');
    clock = at(23, 30) + 24 * H;
    assert.equal(await own.tick(), 'resting', 'not at night');
    board.setOutreach({ contacts });
    board.update(d => recordReach(d, { channel: 'continuo', text: 'x', now: at(9) + 24 * H }));
    clock = at(10, 30) + 24 * H;
    assert.equal(await own.tick(), 'silent');
    assert.equal(board.data().chasedTopic, 1);
    clock += 3 * 60_000;
    busy = true;
    assert.equal(await own.tick(), 'busy', 'the follow-up turn holds the screen');
    busy = false;
    assert.equal(await own.tick(), 'own', 'asked once per topic: its own time comes next');
    assert.deepEqual(delivered, [OWN.rule, OWN.silentRule, OWN.rule]);
  } finally { await rm(dir, { recursive: true, force: true }); }
});

test('the recorder spends energy per model step by output tokens and effort, and hears the owner', async () => {
  const { dir, files } = await folder();
  let clock = at(10);
  try {
    const board = createPsycheBoard({ files, now: () => clock });
    board.setCharacter(presetTraits('INFP'));
    board.update(d => ({ ...d, state: { ...d.state, at: clock } }));
    const observe = createPsycheRecorder({ board, now: () => clock });
    const session = { id: 'cowork-owner', header: { agentPreset: 'cowork-phone' } };
    const step = (out, calls = []) => observe(session, { type: 'assistant/message', data: { message: { content: calls.map(([name, args]) => ({ type: 'tool-call', id: name, name, arguments: JSON.stringify(args) })), source: { provider: 'p', model: 'm' } }, ...(out === undefined ? {} : { usage: { inputTokens: 90000, outputTokens: out } }) } });
    observe({ id: 'other', header: { agentPreset: 'standard' } }, { type: 'turn/start', data: { turn: 1 } });
    assert.equal(board.running(), false, 'only Cowork turns count');
    observe(session, { type: 'turn/start', data: { turn: 1 } });
    assert.equal(board.running(), true);
    step(500, [['phone_type_text', { text: '哈哈' }]]);
    assert.ok(Math.abs(board.data().state.energy - (100 - 0.5 * 1.25)) < 1e-9, 'a talking step for an INFP');
    step(1000, [['memory_save', {}]]);
    assert.deepEqual(board.data().models['p/m'], [500, 1000]);
    assert.ok(Math.abs(board.data().state.energy - (100 - 0.625 - 0.5 * 2 * 0.75)) < 1e-9, 'twice its usual output, an easy activity');
    step(undefined);
    assert.deepEqual(board.data().models['p/m'], [500, 1000], 'no usage: an ordinary step, nothing remembered');
    // The owner thanks it in their conversation and answers its message.
    board.update(d => recordReach(d, { channel: 'continuo', now: clock - 60_000 }));
    const mood = board.data().state.mood;
    observe(session, { type: 'user/message', data: { role: 'user', content: [{ type: 'text', text: '谢谢你' }], source: { kind: 'user' } } });
    assert.ok(Math.abs(board.data().state.mood - (mood + PSYCHE.mood.ownerReply + PSYCHE.mood.thanks)) < 0.01);
    assert.equal(board.data().answeredAt, clock);
    assert.equal(board.data().state.lastOwnerAt, clock);
    const before = board.data().state.mood;
    observe(session, { type: 'turn/end', data: { reason: { kind: 'error' } } });
    assert.ok(board.data().state.mood < before, 'a run that ended in error');
    assert.equal(board.running(), false);
    assert.deepEqual(callsOf({ content: [{ type: 'tool-call', name: 'phone_tap', arguments: '{"elementId":"e1"}' }, { type: 'text', text: 'x' }] }), [{ name: 'phone_tap', args: { elementId: 'e1' } }]);
    assert.equal(outputTokensOf({ usage: { outputTokens: -1 } }), undefined);
  } finally { await rm(dir, { recursive: true, force: true }); }
});

test('notifications: the owner\'s own account, its own message in the owner\'s app, and what moves its energy', async () => {
  const outreach = outreachOf({ contacts });
  const item = over => ({ package: QQ, app: 'QQ', profile: 'work', sender: '主人', text: '在吗', at: 1, ...over });
  assert.equal(fromOwner(item(), outreach), true);
  assert.equal(fromOwner(item({ sender: '群聊' }), outreach), false);
  assert.equal(fromOwner(item({ origin: 'screen' }), outreach), false);
  assert.equal(fromOwner(item({ profile: 'personal' }), outreach), false);
  assert.equal(fromItself(item({ profile: 'personal', sender: '小满' }), outreach), true);
  assert.equal(fromItself(item({ sender: '小满' }), outreach), false);
  const { dir, files } = await folder();
  let clock = at(10);
  try {
    const board = createPsycheBoard({ files, now: () => clock });
    board.setCharacter(presetTraits('ESFJ'));
    board.setOutreach({ contacts });
    board.update(d => ({ ...d, state: { ...d.state, at: clock, energy: 50 } }));
    const pushed = [];
    const agent = { followup: m => pushed.push(m), steer: m => pushed.push(m) };
    const delivery = createOwnerDelivery({}, { createUserMessage: m => ({ role: 'user', ...m }), boundContextSummary: s => s },
      { standing: { agentOf: async () => agent }, psyche: board, now: () => clock, jevBoard: { select: () => ({ items: [], commit() {} }) } });
    await delivery.deliver('work:com.tencent.mobileqq', { id: 'b1', count: 1, firstAt: 1, lastAt: 1, items: [item()] }, 'followup');
    assert.equal(board.data().answeredAt, clock, 'the owner answered from QQ');
    assert.ok(board.data().state.mood > 60);
    await delivery.deliver('work:com.tencent.mobileqq', { id: 'b2', count: 5, firstAt: 1, lastAt: 1, items: [1, 2, 3, 4, 5].map(n => item({ sender: `人${n}` })) }, 'followup');
    assert.ok(Math.abs(board.data().state.energy - (50 + 6 * 0.4 * 4)) < 0.01, 'one answer of the owner, three of five others count');
    await delivery.start('[OWN TIME]\nx', OWN.rule, OWN.summary);
    assert.deepEqual({ kind: pushed.at(-1).source.kind, ruleId: pushed.at(-1).source.ruleId, provider: pushed.at(-1).source.provider }, { kind: 'webhook', ruleId: 'cowork-own-time', provider: 'cowork-life' });
  } finally { await rm(dir, { recursive: true, force: true }); }
});

test('own time ranks lowest, uses the patrol\'s model and counts as its own kind in the diary', () => {
  const own = { role: 'user', content: [{ type: 'text', text: '[OWN TIME]\nx' }], source: { kind: 'webhook', ruleId: OWN.rule } };
  const silent = { ...own, source: { kind: 'webhook', ruleId: OWN.silentRule } };
  assert.equal(priorityOf([own]), PRIORITY.scheduled);
  assert.equal(unattendedKind([own]), 'patrol');
  assert.equal(unattendedKind([silent]), 'patrol');
  assert.equal(unattendedKind([own, { role: 'user', source: { kind: 'user' } }]), undefined);
  assert.equal(kindOf(own), 'own');
  assert.equal(kindOf(silent), 'own');
  const entries = [];
  const observe = createActivityRecorder({ append: e => entries.push(e), now: () => at(10) });
  const session = { id: 'cowork-owner', header: { agentPreset: 'cowork-phone' } };
  observe(session, { type: 'turn/start', data: { turn: 1 } });
  observe(session, { type: 'user/message', data: own });
  observe(session, { type: 'tool/call', data: { name: 'cowork_reach_owner', arguments: { channel: 'continuo', text: '刷到个好东西' } } });
  observe(session, { type: 'turn/end', data: { reason: { kind: 'completed' } } });
  assert.equal(entries[0].kind, 'own');
  assert.deepEqual(entries[0].notified, ['刷到个好东西']);
  const request = diaryRequest({ name: '小满', date: '2026-10-31', log: 'x', character: 'Your character…' });
  assert.match(request.system, /自己的日记|写今天的日记/);
  assert.match(request.system, /\n\nYour character…$/);
  assert.doesNotMatch(diaryRequest({ name: '小满', date: '2026-10-31', log: 'x' }).system, /Your character/);
});

test('Continuo sets the character, reads energy and mood, sets how it may reach the owner and says what the owner saw', async () => {
  class Base { constructor(ctx) { this.ctx = ctx; } }
  const Service = unattendedService({ Remote: () => () => {}, TypertRemoteService: Base });
  const { dir, files } = await folder();
  let clock = at(10);
  try {
    const board = createPsycheBoard({ files, now: () => clock });
    const service = new Service({}, { psyche: board, now: () => clock });
    assert.deepEqual(service.character(), { traits: null, derived: null });
    assert.equal(service.psyche().active, false);
    assert.equal(service.setCharacter({ type: 'ENTP' }).derived.type, 'ENTP');
    assert.throws(() => service.setCharacter({ type: 'ABCD' }), /16 MBTI types/);
    const tuned = { ...presetTraits('ENTP'), Fe: 40 };
    assert.deepEqual(service.setCharacter({ traits: tuned }).traits, tuned);
    assert.throws(() => service.setCharacter({ traits: { ...presetTraits('ENTP'), Fe: 80 } }), /Ti and Fe exclude each other: together at most 120, not 150/);
    assert.deepEqual(service.character().traits, tuned, 'a refused character changes nothing');
    assert.deepEqual(Object.keys(service.psyche()).sort(), ['active', 'energy', 'energyBand', 'lastOwnerAt', 'mood', 'moodBand', 'running']);
    assert.equal(service.psyche().active, true);
    assert.equal(service.setOutreach({ contacts, level: 'many', ignored: 1 }).level, 'many');
    assert.equal(service.outreach().contacts.length, 2);
    assert.equal(service.setDnd({ until: clock + H }).dndUntil, clock + H);
    assert.equal(service.setDnd({ until: 0 }).dndUntil, 0);
    assert.equal(service.setDnd({ until: clock - 1 }).dndUntil, 0, 'a time already past clears it');
    assert.deepEqual(service.ownerSeen({ at: clock - 5 }), { seenAt: clock - 5 });
    assert.deepEqual(service.ownerSeen({ at: clock + H }), { seenAt: clock }, 'never ahead of the clock');
    assert.throws(() => service.ownerSeen({}), /at is a time/);
    assert.deepEqual(service.setCharacter({ traits: null }), { traits: null, derived: null });
  } finally { await rm(dir, { recursive: true, force: true }); }
});

test('Continuo shows what it said to the owner by the tool name and the arguments the plugin uses', async () => {
  const { REACH_TOOL, REACH } = await import('../src/reach.mjs');
  const { modelName } = await import('../src/validation.mjs');
  const { activityOfCall } = await import('../src/psyche.mjs');
  const kotlin = await readFile(new URL('../../app/src/main/java/dev/dsh/pocket/data/ChatLog.kt', import.meta.url), 'utf8');
  const constant = name => kotlin.match(new RegExp(`const val ${name} = "([^"]+)"`))?.[1];
  assert.equal(constant('REACH_TOOL'), modelName(REACH_TOOL));
  assert.equal(constant('CONTINUO'), REACH.continuo);
  assert.match(kotlin, /args\.text\("text"\)/);
  assert.match(kotlin, /args\.text\("channel"\)/);
  assert.equal(activityOfCall(modelName(REACH_TOOL), {}), 'talk', 'reaching the owner is talking to someone');
});

test('Continuo balances the sliders by the same rule: the same cap, the same drags give the same values', async () => {
  const { OPPOSITION, balance, isBalanced } = await import('../src/psyche.mjs');
  const kotlin = await readFile(new URL('../../app/src/main/java/dev/dsh/pocket/data/Temperament.kt', import.meta.url), 'utf8');
  assert.equal(Number(kotlin.match(/const val OPPOSITE_CAP = (\d+)/)?.[1]), OPPOSITION.cap);
  const test = await readFile(new URL('../../app/src/test/java/dev/dsh/pocket/data/TemperamentTest.kt', import.meta.url), 'utf8');
  const vector = JSON.parse(test.match(/private val balanceVector = """(.*)"""/)[1]);
  assert.deepEqual(vector.from, presetTraits('INFP'));
  let traits = vector.from;
  for (const [f, v] of vector.drags) traits = balance(traits, f, v);
  assert.deepEqual(traits, vector.to);
  assert.ok(isBalanced(traits));
});
