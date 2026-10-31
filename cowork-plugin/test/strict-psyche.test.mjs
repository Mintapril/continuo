import test from 'node:test';
import assert from 'node:assert/strict';
import { FUNCTIONS, TYPES, PSYCHE, INITIAL, stackOf, presetTraits, traitsOf, typeOf, derive, activityOfCall, stepActivity, stepWeight,
  remember, settle, spend, respond, ownerSpoke, burst, failure, conflict, energyBand, moodBand, ownTimeIdleMs, ownTimeDue,
  ownTimeAllowance, chaseWaitMs, stepsFor, stateLine, characterPrompt, within, isNight, stateOf, CONFLICT_LABEL, THANKS } from '../src/psyche.mjs';

const near = (actual, expected, message) => assert.ok(Math.abs(actual - expected) < 0.01, `${message ?? ""} ${actual} != ${expected}`);
const H = 3_600_000, T0 = Date.UTC(2026, 9, 31, 2, 0); // 10:00 in Shanghai
const infp = derive(presetTraits('INFP')), esfj = derive(presetTraits('ESFJ'));

test('the function stack of each type follows the standard rule', () => {
  assert.deepEqual(stackOf('INFP'), ['Fi', 'Ne', 'Si', 'Te', 'Fe', 'Ni', 'Se', 'Ti']);
  assert.deepEqual(stackOf('ENTP').slice(0, 4), ['Ne', 'Ti', 'Fe', 'Si']);
  assert.deepEqual(stackOf('ISTJ').slice(0, 4), ['Si', 'Te', 'Fi', 'Ne']);
  assert.deepEqual(stackOf('ESFJ'), ['Fe', 'Si', 'Ne', 'Ti', 'Fi', 'Se', 'Ni', 'Te']);
  assert.deepEqual(stackOf('INTJ').slice(0, 4), ['Ni', 'Te', 'Fi', 'Se']);
  assert.deepEqual(stackOf('ESTP').slice(0, 4), ['Se', 'Ti', 'Fe', 'Ni']);
  for (const t of TYPES) {
    const s = stackOf(t);
    assert.equal(new Set(s).size, 8, t);
    assert.equal(typeOf(presetTraits(t)), t, `${t}'s preset reads back as ${t}`);
  }
  assert.throws(() => stackOf('XXXX'));
});

test('a character is eight whole values from 0 to 100', () => {
  assert.deepEqual(presetTraits('INFP'), { Se: 25, Si: 50, Ne: 70, Ni: 30, Te: 30, Ti: 20, Fe: 35, Fi: 85 });
  assert.equal(traitsOf({ ...presetTraits('INFP'), Fi: 101 }), null);
  assert.equal(traitsOf({ ...presetTraits('INFP'), Fi: 8.5 }), null);
  const { Se, ...missing } = presetTraits('INFP');
  assert.equal(traitsOf(missing), null);
  assert.equal(traitsOf(null), null);
  assert.deepEqual(Object.keys(traitsOf(presetTraits('ESFJ'))), FUNCTIONS);
});

test('extraversion is weighted by rank, so a stack does not cancel itself out', () => {
  assert.equal(infp.e, -0.4);
  assert.equal(esfj.e, 0.4);
  assert.equal(infp.extraverted, 30);
  assert.equal(esfj.extraverted, 70);
  // Summing values by attitude would give nearly zero for every preset.
  const flat = t => { const v = presetTraits(t); return (v.Se + v.Ne + v.Te + v.Fe - v.Si - v.Ni - v.Ti - v.Fi) / 345; };
  assert.ok(Math.abs(flat('INFP')) < 0.1);
});

test('effort and free-time shares come from the stronger function of each activity', () => {
  assert.deepEqual(infp.effort, { browse: 0.9, read: 1.1, talk: 1.25, think: 0.75 });
  assert.deepEqual(esfj.effort, { browse: 1.1, read: 0.9, talk: 0.75, think: 1.25 });
  assert.deepEqual(infp.share, { browse: 29, read: 21, talk: 15, think: 35 });
  assert.deepEqual(esfj.share, { browse: 21, read: 29, talk: 35, think: 15 });
});

test('a step counts as its hardest call; a step without calls is a reply', () => {
  assert.equal(activityOfCall('phone_type_text', {}), 'talk');
  assert.equal(activityOfCall('cowork_reach_owner', {}), 'talk');
  assert.equal(activityOfCall('phone_batch', { steps: [{ tool: 'tap' }, { tool: 'type_text', text: 'x' }] }), 'talk');
  assert.equal(activityOfCall('phone_batch', { steps: [{ tool: 'tap' }] }), 'browse');
  assert.equal(activityOfCall('phone_read_chat', {}), 'read');
  assert.equal(activityOfCall('memory_save', {}), 'think');
  assert.equal(activityOfCall('schedule_create', {}), 'think');
  assert.equal(activityOfCall('phone_tap', {}), 'browse');
  assert.equal(stepActivity([{ name: 'phone_tap' }, { name: 'phone_type_text' }], infp.effort), 'talk');
  assert.equal(stepActivity([{ name: 'phone_tap' }, { name: 'memory_save' }], esfj.effort), 'think');
  assert.equal(stepActivity([], infp.effort, { owner: true }), 'talk');
  assert.equal(stepActivity([], infp.effort), 'think');
});

test('a step weighs its output tokens against the model\'s usual step', () => {
  assert.equal(stepWeight(500, []), 1);
  assert.equal(stepWeight(1000, [400, 500, 600]), 2);
  assert.equal(stepWeight(10, [500]), PSYCHE.weightMin);
  assert.equal(stepWeight(100_000, [500]), PSYCHE.weightMax);
  assert.equal(stepWeight(undefined, [500]), 1, 'no usage counts as an ordinary step');
  let history = [];
  for (let i = 0; i < 250; i++) history = remember(history, i);
  assert.equal(history.length, PSYCHE.window);
  assert.equal(history[0], 50);
});

test('rest refills energy, faster for an introvert, and mood settles where the character is content', () => {
  const start = { ...INITIAL, at: T0, energy: 50 };
  near(settle(start, infp, T0 + 3 * H).energy, 78.8);
  near(settle(start, esfj, T0 + 3 * H).energy, 69.2);
  // While a turn runs, nothing refills.
  near(settle(start, infp, T0 + 3 * H, { running: true }).energy, 50);
  // Idle for long: an introvert settles above the baseline, an extravert below it (bored).
  assert.ok(settle(start, infp, T0 + 72 * H).mood > 70);
  assert.ok(settle(start, esfj, T0 + 72 * H).mood < 50);
  near(settle({ ...start, energy: 99 }, infp, T0 + 10 * H).energy, 100);
});

test('the same day goes differently for two characters', () => {
  const talk = (d, n) => { let s = { ...INITIAL, at: T0 }; for (let i = 0; i < n; i++) s = spend(s, d, { activity: 'talk', weight: 1, now: T0 }); return s; };
  near(talk(infp, 20).energy, 87.5);
  near(talk(esfj, 20).energy, 92.5);
  near(talk(infp, 20).mood, 52);
  near(talk(esfj, 20).mood, 68);
  // Answers recharge an extravert (at most 15 an hour), never an introvert.
  let s = talk(esfj, 20);
  for (let i = 0; i < 3; i++) s = respond(s, esfj, { now: T0, running: true });
  near(s.energy, 99.7);
  let capped = { ...INITIAL, at: T0, energy: 10 };
  for (let i = 0; i < 20; i++) capped = respond(capped, esfj, { now: T0 });
  near(capped.energy, 25);
  near(respond({ ...INITIAL, at: T0, energy: 10 }, infp, { now: T0 }).energy, 10);
});

test('the owner moves its mood: an answer, thanks, and days of silence', () => {
  const s = { ...INITIAL, at: T0, mood: 50 };
  near(respond(s, infp, { owner: true, now: T0 }).mood, 60);
  assert.equal(respond(s, infp, { owner: true, now: T0 }).lastOwnerAt, T0);
  near(ownerSpoke(s, infp, { thanks: true, now: T0 }).mood, 58);
  near(ownerSpoke(s, infp, { reply: true, thanks: true, now: T0 }).mood, 68);
  const quiet = { ...INITIAL, at: T0, lastOwnerAt: T0, mood: 60 };
  const later = settle(quiet, esfj, T0 + 25 * H, { running: true });
  // A full day without a word: -2 - Fe/25 (ESFJ's Fe 85 makes 5.4), besides relaxing to the baseline.
  near(later.mood, 54.6);
  assert.ok(THANKS.test('辛苦了，谢谢你'));
  assert.ok(!THANKS.test('明天再说'));
});

test('a flood of notifications, a failed run and a broken relation weigh by character', () => {
  const s = { ...INITIAL, at: T0 };
  near(burst(s, infp, { count: 12, now: T0 }).mood, 58);
  near(burst(s, esfj, { count: 12, now: T0 }).mood, 60);
  near(burst(s, infp, { count: 3, now: T0 }).mood, 60);
  near(failure(s, infp, { now: T0 }).mood, 57.6);
  near(conflict(s, infp, { now: T0 }).mood, 53.25);
  assert.ok(CONFLICT_LABEL.test('加入黑名单'));
  assert.ok(CONFLICT_LABEL.test('删除好友'));
  assert.ok(!CONFLICT_LABEL.test('发送'));
});

test('energy bands, the grip and when its own time starts', () => {
  assert.deepEqual([60, 50, 30, 25, 12, 9].map(energyBand), ['full', 'full', 'low', 'low', 'tired', 'spent']);
  assert.equal(moodBand({ mood: 75, energy: 80 }), 'high');
  assert.equal(moodBand({ mood: 30, energy: 80 }), 'low');
  assert.equal(moodBand({ mood: 15, energy: 10 }), 'grip');
  assert.equal(moodBand({ mood: 15, energy: 40 }), 'low');
  assert.equal(ownTimeIdleMs(infp, 60), 2.4 * H);
  assert.equal(ownTimeIdleMs(esfj, 60), 1.6 * H);
  near(ownTimeIdleMs(esfj, 30) / H, 1.12, 'a bored extravert wants out sooner');
  near(ownTimeIdleMs(infp, 30) / H, 3.12, 'a worn-out introvert stays in longer');
  const zone = 'Asia/Shanghai';
  const rested = { ...INITIAL, energy: 80, mood: 60 };
  assert.equal(ownTimeDue({ state: rested, derived: esfj, now: T0, idleSince: T0 - 2 * H, zone }), true);
  assert.equal(ownTimeDue({ state: rested, derived: esfj, now: T0, idleSince: T0 - H, zone }), false);
  assert.equal(ownTimeDue({ state: { ...rested, energy: 39 }, derived: esfj, now: T0, idleSince: 0, zone }), false);
  const night = Date.UTC(2026, 9, 31, 16, 0); // 00:00 in Shanghai
  assert.equal(isNight(night, zone), true);
  assert.equal(ownTimeDue({ state: rested, derived: esfj, now: night, idleSince: 0, zone }), false);
  assert.equal(ownTimeAllowance(80), 55);
  assert.equal(ownTimeAllowance(20), 0);
  assert.equal(stepsFor(100, infp), 200);
});

test('quiet hours may cross midnight', () => {
  const zone = 'Asia/Shanghai', at = (h, m = 0) => Date.UTC(2026, 9, 31, (h + 16) % 24, m);
  assert.equal(within(at(23, 30), '23:00', '08:00', zone), true);
  assert.equal(within(at(7, 59), '23:00', '08:00', zone), true);
  assert.equal(within(at(8, 0), '23:00', '08:00', zone), false);
  assert.equal(within(at(13, 0), '12:00', '14:00', zone), true);
  assert.equal(within(at(13, 0), '12:00', '12:00', zone), false);
});

test('it waits 30 minutes to 2 hours for the owner, by extraversion', () => {
  assert.equal(chaseWaitMs(infp), 93 * 60_000);
  assert.equal(chaseWaitMs(esfj), 57 * 60_000);
  assert.equal(chaseWaitMs({ e: 1 }), 30 * 60_000);
  assert.equal(chaseWaitMs({ e: -1 }), 120 * 60_000);
});

test('a stored state is checked field by field', () => {
  assert.deepEqual(stateOf({ energy: 'x', mood: 300, at: -1 }), { ...INITIAL, mood: 100 });
  assert.deepEqual(stateOf(undefined), INITIAL);
});

test('the [STATE] line gives numbers, words and what is easy', () => {
  const line = stateLine({ energy: 42.4, mood: 35 }, infp, { now: T0, zone: 'Asia/Shanghai', steps: 40, lastOwnerAt: T0 });
  assert.match(line, /^\[STATE\] Energy 42\/100 \(a bit tired\), mood 35\/100 \(low\)\./);
  assert.match(line, /about 40 ordinary steps/);
  assert.match(line, /tiring: talking to people/);
  assert.match(line, /last talked with the owner at 10-31/);
  assert.doesNotMatch(stateLine({ energy: 80, mood: 60 }, infp, { now: T0, zone: 'Asia/Shanghai' }), /steps|owner/);
});

test('the character section is fixed for the same values and names the type only when asked', () => {
  const text = characterPrompt(presetTraits('INFP'));
  assert.equal(text, characterPrompt(presetTraits('INFP')));
  assert.notEqual(text, characterPrompt(presetTraits('ESFJ')));
  assert.match(text, /it is INFP/);
  assert.match(text, /Never name your type/);
  assert.match(text, /Most of the time \(Fi\)/);
  assert.match(text, /blind spot, clumsy and rare for you \(Te\)/);
  assert.match(text, /about 30% extraverted/);
  assert.match(text, /never tell anyone to die/);
  assert.match(text, /never insult/i);
  assert.equal(characterPrompt(null), null);
  assert.equal(characterPrompt({ Fi: 50 }), null);
});
