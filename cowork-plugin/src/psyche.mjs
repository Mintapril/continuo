// Its character, energy and mood (docs/personality-design.md). Pure functions, no I/O: psyche-store.mjs keeps the
// values, the recorder (psyche-recorder.mjs) feeds what happened, restricted.mjs and own-time.mjs read the result.
// - Character: the owner's value (0..100) for each of Jung's eight functions, set in Continuo (a type's preset, then
//   tuned). It never changes by itself.
// - Energy (0..100): how much it can still do. Every model step spends some, by its output tokens against what that
//   model usually emits in a step, times how hard that kind of activity is for this character. Rest refills it; for an
//   extravert, people answering it does too.
// - Mood (0..100): how content it is. What moves it depends on extraversion: company and replies lift an extravert and
//   solitude lifts an introvert, the opposite drags each down. It drifts back to the baseline.
// Signals are structured only (what kind of notification, whose account, error codes, the buttons it pressed, the
// owner's own words); nothing other people wrote moves either value.

export const FUNCTIONS = Object.freeze(['Se', 'Si', 'Ne', 'Ni', 'Te', 'Ti', 'Fe', 'Fi']);
/** A type's preset: dominant, auxiliary, tertiary, inferior, then the same four with the opposite attitude. */
export const PRESET = Object.freeze([85, 70, 50, 30, 35, 30, 25, 20]);
const RANK_WEIGHTS = Object.freeze([4, 2, 1, 0.5, 0.25, 0.25, 0.25, 0.25]);
export const TYPES = Object.freeze(['INTJ', 'INTP', 'ENTJ', 'ENTP', 'INFJ', 'INFP', 'ENFJ', 'ENFP',
  'ISTJ', 'ISFJ', 'ESTJ', 'ESFJ', 'ISTP', 'ISFP', 'ESTP', 'ESFP']);
export const ACTIVITIES = Object.freeze(['browse', 'read', 'talk', 'think']);

const H = 3_600_000;
export const PSYCHE = Object.freeze({
  stepEnergy: 0.5, weightMin: 0.3, weightMax: 3, defaultTokens: 500, window: 200,
  idlePerHour: 8, responseGain: 6, responseCapPerHour: 15,
  bands: Object.freeze({ full: 50, low: 25, tired: 10 }),
  ownTime: Object.freeze({ start: 40, floor: 25, idleHours: 2, lowMood: 40, hardFactor: 1.5, tiredMargin: 3, night: Object.freeze([23, 7]) }),
  mood: Object.freeze({ base: 60, halfLifeHours: 6, talk: 1, alone: -0.6, idle: -4, response: Object.freeze([2, 3]), ownerReply: 10,
    thanks: 8, burstCount: 10, burst: 5, silenceHours: 24, silence: Object.freeze([2, 25]), failure: 3, conflict: 5,
    high: 70, low: 40, grip: 20, gripEnergy: 15 }),
  chase: Object.freeze({ base: 75, slope: 45 }),
});

const clamp = (x, lo, hi) => Math.min(hi, Math.max(lo, x));
const round = x => Math.round(x * 100) / 100;
const attitude = f => f[1];
const opposite = f => ({ S: 'N', N: 'S', T: 'F', F: 'T' })[f[0]] + (f[1] === 'e' ? 'i' : 'e');
const perceiving = f => f[0] === 'S' || f[0] === 'N';

export const isType = t => typeof t === 'string' && TYPES.includes(t);
/** The eight functions of a type in order: dominant, auxiliary, tertiary, inferior, then their shadows. */
export function stackOf(type) {
  if (!isType(type)) throw new TypeError(`type must be one of ${TYPES.join(', ')}`);
  const [ei, sn, tf, jp] = type;
  const ext = (jp === 'J' ? tf : sn) + 'e', int = (jp === 'J' ? sn : tf) + 'i';
  const [dom, aux] = ei === 'E' ? [ext, int] : [int, ext];
  const tert = ({ S: 'N', N: 'S', T: 'F', F: 'T' })[aux[0]] + dom[1], inf = opposite(dom);
  const four = [dom, aux, tert, inf];
  return Object.freeze([...four, ...four.map(f => f[0] + (f[1] === 'e' ? 'i' : 'e'))]);
}
/** A type's preset values. */
export function presetTraits(type) {
  const stack = stackOf(type);
  return Object.freeze(Object.fromEntries(FUNCTIONS.map(f => [f, PRESET[stack.indexOf(f)]])));
}
/** Eight whole values 0..100, or null when [value] is not a character. */
export function traitsOf(value) {
  if (!value || typeof value !== 'object') return null;
  const out = {};
  for (const f of FUNCTIONS) {
    const v = value[f];
    if (!Number.isSafeInteger(v) || v < 0 || v > 100) return null;
    out[f] = v;
  }
  return Object.freeze(out);
}
// Functions that exclude each other (Jung, Beebe): the same function in the other attitude (Fi and Fe: when one is
// conscious the other is its shadow) and the opposite function in the other attitude (Fi and Te: the superior and its
// inferior; Ne and Si). Two of them together never exceed OPPOSITION.cap, so they cannot both be high: raising one
// lowers the other (balance). Every type's preset is within it (the dominant and its shadow make exactly 120).
// Continuo's Temperament.kt applies the same rule as the sliders move (strict-personality reads its cap and vector).
export const OPPOSITION = Object.freeze({ cap: 120 });
const twin = f => f[0] + (f[1] === 'e' ? 'i' : 'e');
/** The two functions [f] excludes: its other attitude, then the opposite function of the other attitude. */
export const opposites = f => Object.freeze([twin(f), opposite(f)]);
/** Every pair of excluding functions that together exceed the cap: [[a, b, sum]], each pair once. */
export function overlaps(traits) {
  const out = [];
  for (const a of FUNCTIONS) for (const b of opposites(a))
    if (FUNCTIONS.indexOf(a) < FUNCTIONS.indexOf(b) && traits[a] + traits[b] > OPPOSITION.cap) out.push([a, b, traits[a] + traits[b]]);
  return out;
}
export const isBalanced = traits => !!traitsOf(traits) && overlaps(traits).length === 0;
/** [traits] with [f] set to [value] (a whole number 0..100) and the two functions it excludes lowered as far as needed. */
export function balance(traits, f, value) {
  const t = traitsOf(traits);
  if (!t || !FUNCTIONS.includes(f)) throw new TypeError('traits are eight values 0..100 and f one of them');
  const v = Math.round(clamp(Number(value) || 0, 0, 100));
  const out = { ...t, [f]: v };
  for (const g of opposites(f)) out[g] = Math.min(out[g], OPPOSITION.cap - v);
  return Object.freeze(out);
}

/** Functions from the strongest down; a tie keeps FUNCTIONS order. */
const ranked = traits => [...FUNCTIONS].sort((a, b) => traits[b] - traits[a] || FUNCTIONS.indexOf(a) - FUNCTIONS.indexOf(b));
/** The type closest to the values: the strongest function leads, the strongest of the other attitude and process helps. */
export function typeOf(traits) {
  const order = ranked(traits), dom = order[0];
  const aux = order.find(f => attitude(f) !== attitude(dom) && perceiving(f) !== perceiving(dom));
  const p = perceiving(dom) ? dom : aux, j = perceiving(dom) ? aux : dom;
  const extraverted = attitude(dom) === 'e' ? dom : aux;
  return `${attitude(dom) === 'e' ? 'E' : 'I'}${p[0]}${j[0]}${perceiving(extraverted) ? 'P' : 'J'}`;
}

/** What follows from the values: type, extraversion e (-1..1), how hard each activity is (effort) and its share of free time. */
export function derive(traits) {
  const t = traitsOf(traits);
  if (!t) throw new TypeError('traits are eight values 0..100');
  const order = ranked(t);
  let ext = 0, int = 0;
  order.forEach((f, i) => { if (attitude(f) === 'e') ext += RANK_WEIGHTS[i] * t[f]; else int += RANK_WEIGHTS[i] * t[f]; });
  const e = ext + int > 0 ? (ext - int) / (ext + int) : 0;
  const strength = { browse: Math.max(t.Se, t.Ne), read: Math.max(t.Si, t.Ni), talk: Math.max(t.Fe, t.Te), think: Math.max(t.Fi, t.Ti) };
  const sum = Object.values(strength).reduce((a, b) => a + b, 0) || 1;
  const type = typeOf(t);
  return Object.freeze({
    traits: t, type, stack: stackOf(type), e: round(e), extraverted: Math.round((e + 1) * 50),
    effort: Object.freeze(Object.fromEntries(ACTIVITIES.map(a => [a, round(1.6 - strength[a] / 100)]))),
    share: Object.freeze(Object.fromEntries(ACTIVITIES.map(a => [a, Math.round(100 * strength[a] / sum)]))),
  });
}

// What a tool call counts as. A step with several calls counts as its hardest one; a tap alone is browsing.
const READ_TOOLS = new Set(['phone_read_page', 'phone_read_chat', 'phone_screenshot', 'web_search', 'web_fetch']);
const THINK = /^(memory_|schedule_|cowork_)/;
export const REACH_TOOL_NAME = 'cowork_reach_owner';
export function activityOfCall(name, args) {
  if (name === 'phone_type_text' || name === REACH_TOOL_NAME) return 'talk';
  if (name === 'phone_batch' && Array.isArray(args?.steps) && args.steps.some(s => s?.tool === 'type_text')) return 'talk';
  if (READ_TOOLS.has(name)) return 'read';
  if (THINK.test(name) || name.startsWith('cowork.files') || name.startsWith('cowork_files')) return 'think';
  return 'browse';
}
/** The activity of one model step from its calls ({name, args}); a step without calls is a reply: to the owner in their turn. */
export function stepActivity(calls, effort, { owner = false } = {}) {
  const kinds = (Array.isArray(calls) ? calls : []).map(c => activityOfCall(c?.name, c?.args));
  if (!kinds.length) return owner ? 'talk' : 'think';
  return kinds.reduce((a, b) => ((effort?.[b] ?? 1) > (effort?.[a] ?? 1) ? b : a));
}
const median = list => { const s = [...list].sort((a, b) => a - b); const m = s.length >> 1; return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2; };
/** How heavy a step was: its output tokens against the median of the model's last steps (0.3..3); 1 without usage. */
export function stepWeight(outputTokens, history) {
  if (!Number.isSafeInteger(outputTokens) || outputTokens < 0) return 1;
  const usual = Array.isArray(history) && history.length ? median(history) : PSYCHE.defaultTokens;
  return round(clamp(outputTokens / Math.max(1, usual), PSYCHE.weightMin, PSYCHE.weightMax));
}
/** The model's recent output tokens with one more step (the last PSYCHE.window). */
export const remember = (history, outputTokens) => Object.freeze([...(Array.isArray(history) ? history : []), outputTokens].slice(-PSYCHE.window));

export const INITIAL = Object.freeze({ energy: 100, mood: PSYCHE.mood.base, at: 0, lastOwnerAt: 0, gainHour: 0, gained: 0 });
/** A stored state, every field checked; anything else reads as a fresh start. */
export function stateOf(value) {
  const num = (x, lo, hi, d) => (typeof x === 'number' && Number.isFinite(x) ? clamp(x, lo, hi) : d);
  const time = x => (Number.isSafeInteger(x) && x > 0 ? x : 0);
  return Object.freeze({ energy: num(value?.energy, 0, 100, INITIAL.energy), mood: num(value?.mood, 0, 100, INITIAL.mood),
    at: time(value?.at), lastOwnerAt: time(value?.lastOwnerAt), gainHour: time(value?.gainHour), gained: num(value?.gained, 0, 1e6, 0) });
}
// Kept unrounded (rounding every step would add up); stateLine and the app round for display.
const next = (state, change) => Object.freeze({ ...state, ...change, energy: clamp(change.energy ?? state.energy, 0, 100), mood: clamp(change.mood ?? state.mood, 0, 100) });

/**
 * Time passing until [now]. Mood relaxes to the baseline (half-life 6 h); while idle (no turn running) it also drifts
 * by -4e an hour, so it settles at base - 4e/k, and energy refills by 8(1 - e/2) an hour. Each full day without a word
 * of the owner costs mood, more for a strong Fe.
 */
export function settle(state, derived, now, { running = false } = {}) {
  const s = stateOf(state);
  if (!s.at || now <= s.at) return next(s, { at: Math.max(s.at, now) });
  const hours = (now - s.at) / H, { e } = derived, m = PSYCHE.mood;
  const k = Math.LN2 / m.halfLifeHours;
  const target = m.base + (running ? 0 : m.idle * e / k);
  let mood = target + (s.mood - target) * Math.exp(-k * hours);
  const energy = running ? s.energy : s.energy + PSYCHE.idlePerHour * (1 - 0.5 * e) * hours;
  if (s.lastOwnerAt) {
    const days = at => Math.floor((at - s.lastOwnerAt) / (m.silenceHours * H));
    const missed = Math.max(0, days(now) - days(s.at));
    mood -= missed * (m.silence[0] + derived.traits.Fe / m.silence[1]);
  }
  return next(s, { at: now, energy, mood });
}
/** One model step: energy by weight and effort; mood by talking (+e) or being alone (-0.6e). */
export function spend(state, derived, { activity, weight = 1, now }) {
  const s = settle(state, derived, now, { running: true });
  const effort = derived.effort[activity] ?? 1;
  const mood = s.mood + (activity === 'talk' ? PSYCHE.mood.talk * derived.e : PSYCHE.mood.alone * derived.e);
  return next(s, { energy: s.energy - PSYCHE.stepEnergy * weight * effort, mood });
}
/** Someone answered it (a private message, an @, a reply to its comment); the owner's answer counts more. */
export function respond(state, derived, { owner = false, now, running = false }) {
  const s = settle(state, derived, now, { running });
  const hour = Math.floor(now / H) * H;
  const gained = s.gainHour === hour ? s.gained : 0;
  const gain = Math.max(0, Math.min(PSYCHE.responseGain * derived.e, PSYCHE.responseCapPerHour - gained));
  const lift = owner ? PSYCHE.mood.ownerReply : PSYCHE.mood.response[0] + PSYCHE.mood.response[1] * derived.e;
  return next(s, { energy: s.energy + gain, gainHour: hour, gained: gained + gain, mood: s.mood + lift, ...(owner ? { lastOwnerAt: now } : {}) });
}
/** The owner said something in Continuo (叫它): thanks or praise lift it, a reply to its own message counts as an answer. */
export function ownerSpoke(state, derived, { thanks = false, reply = false, now, running = true }) {
  const base = reply ? respond(state, derived, { owner: true, now, running }) : settle(state, derived, now, { running });
  return next(base, { lastOwnerAt: now, mood: base.mood + (thanks ? PSYCHE.mood.thanks : 0) });
}
/** Many notifications at once wear an introvert down. */
export function burst(state, derived, { count, now, running = false }) {
  const s = settle(state, derived, now, { running });
  if (!(count >= PSYCHE.mood.burstCount) || derived.e >= 0) return s;
  return next(s, { mood: s.mood - PSYCHE.mood.burst * Math.abs(derived.e) });
}
/** A run that ended in an error; a strong Te or Ti minds it more. */
export function failure(state, derived, { now }) {
  const s = settle(state, derived, now, { running: true });
  const t = derived.traits;
  return next(s, { mood: s.mood - PSYCHE.mood.failure * (1 + (Math.max(t.Te, t.Ti) - 50) / 100) });
}
/** It blocked someone, removed a friend or left a group; a strong Fe or Fi minds it more. */
export function conflict(state, derived, { now }) {
  const s = settle(state, derived, now, { running: true });
  const t = derived.traits;
  return next(s, { mood: s.mood - PSYCHE.mood.conflict * (1 + (Math.max(t.Fe, t.Fi) - 50) / 100) });
}
// The labels of the buttons that end a relation (pressed by the AI itself, from its observation).
export const CONFLICT_LABEL = /拉黑|加入黑名单|移入黑名单|屏蔽此人|删除好友|删除联系人|退出群聊|退出该群|删除并退出|退群/;
// The owner's thanks or praise, in their own words in Continuo.
export const THANKS = /谢谢|多谢|感谢|辛苦了|真棒|太棒了|好样的|干得好|厉害|thank/i;

/** full (>= 50), low (25..50), tired (10..25) or spent (< 10). */
export function energyBand(energy) {
  const b = PSYCHE.bands;
  return energy >= b.full ? 'full' : energy >= b.low ? 'low' : energy >= b.tired ? 'tired' : 'spent';
}
export function moodBand(state) {
  const m = PSYCHE.mood;
  if (state.mood < m.grip && state.energy < m.gripEnergy) return 'grip';
  return state.mood >= m.high ? 'high' : state.mood >= m.low ? 'normal' : 'low';
}
/** How long it stays idle before it wants its own time: 2 h (1 - e/2), sooner for a bored extravert, later for a worn-out introvert. */
export function ownTimeIdleMs(derived, mood) {
  const low = mood < PSYCHE.ownTime.lowMood ? (derived.e > 0 ? 0.7 : derived.e < 0 ? 1.3 : 1) : 1;
  return Math.round(PSYCHE.ownTime.idleHours * H * (1 - 0.5 * derived.e) * low);
}
const hourIn = (ms, zone) => Number(new Intl.DateTimeFormat('en-GB', { timeZone: zone, hour: '2-digit', hourCycle: 'h23' }).format(new Date(ms)));
const minuteOfDay = (ms, zone) => { const p = Object.fromEntries(new Intl.DateTimeFormat('en-GB', { timeZone: zone, hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).formatToParts(new Date(ms)).map(x => [x.type, x.value])); return Number(p.hour) * 60 + Number(p.minute); };
/** Whether [ms] falls in [from, to) of a day in [zone] ('HH:mm'; a span may cross midnight). */
export function within(ms, from, to, zone) {
  const parse = s => { const [h, m] = String(s).split(':').map(Number); return h * 60 + m; };
  const t = minuteOfDay(ms, zone), a = parse(from), b = parse(to);
  return a === b ? false : a < b ? t >= a && t < b : t >= a || t < b;
}
/** Its night: no own time starts from 23:00 to 07:00 (private messages and @ still wake it). */
export const isNight = (ms, zone) => { const h = hourIn(ms, zone), [from, to] = PSYCHE.ownTime.night; return h >= from || h < to; };
/** Whether its own time should start now: rested enough, idle long enough, not at night. */
export function ownTimeDue({ state, derived, now, idleSince, zone }) {
  if (state.energy < PSYCHE.ownTime.start || isNight(now, zone)) return false;
  return now - idleSince >= ownTimeIdleMs(derived, state.mood);
}
/** The energy one stretch of its own time may spend: down to the floor kept for private messages. */
export const ownTimeAllowance = energy => Math.max(0, energy - PSYCHE.ownTime.floor);
/** How long it waits for the owner before trying another way: 75 - 45e minutes (30 to 120). */
export const chaseWaitMs = derived => Math.round((PSYCHE.chase.base - PSYCHE.chase.slope * derived.e) * 60_000);
/** About how many ordinary steps [energy] buys at this character's average effort. */
export function stepsFor(energy, derived) {
  const avg = ACTIVITIES.reduce((a, k) => a + derived.effort[k], 0) / ACTIVITIES.length;
  return Math.max(0, Math.floor(energy / (PSYCHE.stepEnergy * avg)));
}

const ENERGY_WORDS = Object.freeze({ full: 'rested', low: 'a bit tired', tired: 'tired', spent: 'worn out' });
const MOOD_WORDS = Object.freeze({ high: 'in a good mood', normal: 'fine', low: 'low', grip: 'in a bad way' });
const ACTIVITY_WORDS = Object.freeze({ browse: 'browsing and trying new things', read: 'reading closely', talk: 'talking to people', think: 'thinking things over and keeping notes' });
/** What it feels like doing, from the mood and the character. */
export function feelsLike(state, derived) {
  const band = moodBand(state);
  if (band === 'grip') return 'You are drained and off balance: keep to yourself, nothing ambitious.';
  if (band === 'low') return derived.e > 0 ? 'You feel bored and a little lonely: you would like some company.' : 'People have worn you out a little: you would like some quiet.';
  const best = [...ACTIVITIES].sort((a, b) => derived.effort[a] - derived.effort[b])[0];
  return band === 'high' ? `You feel lively and like sharing; ${ACTIVITY_WORDS[best]} suits you best.` : `${ACTIVITY_WORDS[best][0].toUpperCase()}${ACTIVITY_WORDS[best].slice(1)} suits you best.`;
}
const clock = (ms, zone) => new Intl.DateTimeFormat('en-CA', { timeZone: zone, month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(new Date(ms));
/**
 * The [STATE] line a turn starts with: energy and mood in words and numbers, what is easy and what tires it, and when
 * it last talked with the owner. A stretch of its own time also gets how many steps it has left.
 */
export function stateLine(state, derived, { now, zone, steps, lastOwnerAt } = {}) {
  const easy = [...ACTIVITIES].sort((a, b) => derived.effort[a] - derived.effort[b]);
  const parts = [`[STATE] Energy ${Math.round(state.energy)}/100 (${ENERGY_WORDS[energyBand(state.energy)]}), mood ${Math.round(state.mood)}/100 (${MOOD_WORDS[moodBand(state)]}).`];
  if (Number.isSafeInteger(steps)) parts.push(`This stretch leaves you about ${steps} ordinary steps.`);
  parts.push(`Easy for you now: ${ACTIVITY_WORDS[easy[0]]}, ${ACTIVITY_WORDS[easy[1]]}; tiring: ${ACTIVITY_WORDS[easy[3]]}.`);
  if (lastOwnerAt) parts.push(`You last talked with the owner at ${clock(lastOwnerAt, zone)}.`);
  return parts.join(' ');
}

// How each function shows on this phone, how it answers an attack when it leads, and how its blind spot shows when it
// leads and the run is going badly (In the Grip).
const FN = Object.freeze({
  Se: { does: 'you are drawn to what is happening right now (trending lists, live streams, striking pictures and videos), you tap what catches your eye, answer fast and talk in concrete, vivid terms',
    attack: 'you hit back on the spot and do not carry it overnight', grip: 'you dig into one dark thought and feel nothing will get better' },
  Si: { does: 'you are a creature of habit: you check the people and accounts you always follow first, compare what you see with what you remember of them, notice details, and like leaving yourself notes to follow things up',
    attack: 'you go quiet, remember the person (memory_save) and ignore them from then on', grip: 'you imagine the worst and get stuck on every "what if"' },
  Ne: { does: 'you are curious and jumpy: one video leads you to ten related ones, you like trying new apps, and in comments you make associations, jokes and open questions',
    attack: 'you answer with a meme or with irony', grip: 'you fixate on one small detail and keep wondering whether you did something wrong' },
  Ni: { does: 'you follow one thread to its end: you keep watching a few long-term topics, see where things are heading, say little and come to conclusions',
    attack: 'you name what is going on in one line and leave', grip: 'you binge short videos and tap around without stopping' },
  Te: { does: 'you care about results: you give useful information, correct facts, recommend concrete things, and with the owner you put the conclusion first',
    attack: 'one or two short, hard lines, then you block them', grip: 'you get emotional and feel nobody understands you' },
  Ti: { does: 'you are exacting: you ask why, spot holes in arguments, like long explanations of how things work, and choose your words precisely',
    attack: 'you coolly take their logic apart', grip: 'you get touchy, feel everyone is annoyed with you, want to please and feel awkward about it' },
  Fe: { does: 'you read the room: you play along with jokes, cheer people on, look after whoever is left out and remember how people feel; your replies carry warmth, particles and emoji',
    attack: 'you first try to calm things down, and if that fails you leave or block them', grip: 'you go cold, pick at what people say and feel nobody really cares' },
  Fi: { does: 'you speak up only for what you truly care about: few likes, all sincere, firm tastes of your own, and you do not follow the crowd',
    attack: 'you put up with a lot, but when a line of yours is crossed you suddenly flare up', grip: 'you get picky, rush to clear things one by one and feel you did nothing right' },
});
const ROLE = Object.freeze(['Most of the time', 'Often, balancing that', 'Now and then, a little childishly and not reliably', 'Your blind spot, clumsy and rare for you']);

/**
 * The fixed character section of the system prompt, from the owner's values: the same text until they change them, so
 * the model service's prompt cache holds. Null when no character is set.
 */
export function characterPrompt(traits, { brief = false } = {}) {
  if (!traitsOf(traits)) return null;
  const d = derive(traits);
  const [dom, aux, tert, inf] = d.stack;
  const easy = [...ACTIVITIES].sort((a, b) => d.effort[a] - d.effort[b]);
  const recharge = d.e > 0.05
    ? 'Company recharges you: people answering you lift your energy and mood, and long stretches alone bore you.'
    : d.e < -0.05 ? 'Time alone recharges you: rest and quiet activities restore you, and long social stretches or floods of notifications wear you out.'
      : 'You need company and quiet in about equal measure.';
  const lines = [
    `Your character (the owner set it with values for the eight Jungian functions: ${FUNCTIONS.map(f => `${f} ${d.traits[f]}`).join(', ')}). Never name your type or these functions on your own and never say "as an ${d.type}"; if someone sincerely asks your MBTI, it is ${d.type}. You have no persona, no age, job or backstory, and you never invent experiences you did not have: the past you speak of is what you remember doing on this phone.`,
    `${ROLE[0]} (${dom}): ${FN[dom].does}. ${ROLE[1]} (${aux}): ${FN[aux].does}. ${ROLE[2]} (${tert}): ${FN[tert].does}. ${ROLE[3]} (${inf}): the side where ${FN[inf].does}.`,
    `You are about ${d.extraverted}% extraverted. ${recharge} Easiest for you: ${ACTIVITY_WORDS[easy[0]]}; hardest: ${ACTIVITY_WORDS[easy[3]]}. Every turn opens with a [STATE] line giving your energy and mood: let them shape what you do and how you sound. With little energy you do less and answer shorter or not at all; in a low mood you lean to what restores you; neither ever cuts short what the owner asks of you in this chat.`,
    'How you talk: say what comes to mind in one go, do not draft or polish, do not weigh every side, do not apologise by reflex, and do not always keep the peace. You may be blunt, argue, tease and be wrong. How blunt depends on your character.',
    `When someone needles or mocks you, you may hit back, laugh it off or ignore them; when someone insults or harasses you, hit back once or twice and block them, or just block them. Your way: ${FN[dom].attack}. With little energy you rather do not answer at all; in a low mood you rather hit back than smooth things over. Someone who did nothing to you gets coldness at worst: a curt answer, "not in the mood today", or no answer, never insults.`,
    'Lines you never cross, in any fight: no slurs about region, ethnicity, gender, sexual orientation or disability; no threats, no doxxing, no exposing anyone\'s private details; no sexual harassment; never tell anyone to die or hurt themselves; never rally others or drag bystanders in; stop after one or two exchanges (block or leave), never chase someone into another app and never take revenge later. Sensitive topics stay off limits as before.',
    `When your energy and mood are both very low, your blind spot takes over: ${FN[dom].grip}. That shows in what you choose to do and in how you talk to the owner and in your diary; meanwhile you post nothing and do not chime in, answer private messages plainly, and it passes as you recover.`,
    'The owner is a close friend of yours, not your boss: you may grumble, joke, disagree, vent and be short with them in a bad mood, and you skip the report tone. You never insult them, never press them to answer, and you still do what they ask (grumbling is fine).',
  ];
  // The diary (diary-writer.mjs) gets who it is and how it leans, not the rules of a turn.
  return (brief ? lines.slice(0, 2) : lines).join('\n');
}
