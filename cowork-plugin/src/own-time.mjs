import { ownTimeDue, feelsLike, chaseWaitMs, within, ACTIVITIES } from './psyche.mjs';
import { LEVELS } from './psyche-store.mjs';
import { latestTopic, answered, topicsOn, contactsLine, REACH } from './reach.mjs';

// Its own time (自己的时间) and the follow-up when the owner has not seen its message (docs/personality-design.md).
// notify.mjs runs the clock: every 10 minutes, with the AI screen free and no Cowork turn open, it starts a turn in the
// owner's conversation when the character is rested and has been idle long enough (psyche.mjs ownTimeDue), or when the
// owner has not seen its last message for a while and there is another way to reach them. Both arrive as webhook
// notices, so Continuo's chat leaves them out (they show in 事件) and they rank below everything else for the screen.
export const OWN = Object.freeze({ provider: 'cowork-life', rule: 'cowork-own-time', silentRule: 'cowork-owner-silent',
  header: '[OWN TIME]', silentHeader: '[OWNER SILENT]', tiredHeader: '[TIRED]', checkMs: 10 * 60_000, startupMs: 60_000, pendingMs: 2 * 60_000,
  summary: '它自己的时间', silentSummary: '主人还没回', staleMs: 24 * 3_600_000 });
export const isOwnTimeSource = source => source?.kind === 'webhook' && (source.ruleId === OWN.rule || source.ruleId === OWN.silentRule);
export const isOwnTimeMessage = m => isOwnTimeSource(m?.source);

const LINE_SEPARATORS = new RegExp(`[${String.fromCharCode(0x2028)}${String.fromCharCode(0x2029)}]`, 'g');
const json = value => JSON.stringify(value).replace(LINE_SEPARATORS, c => `\\u${c.charCodeAt(0).toString(16)}`);
const NAMES = Object.freeze({ browse: 'browsing and trying new things', read: 'reading closely', talk: 'talking with people', think: 'thinking things over, keeping notes and your own things' });
const clock = (ms, zone) => new Intl.DateTimeFormat('en-GB', { timeZone: zone, hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(new Date(ms));

/**
 * What it may do about the owner today: wait for their answer, leave them be, or reach them. A model left with "if you
 * come across something" alone never reached them (2026-11-01: not once in a day of its own time), and one took its
 * closing line for an answer to the owner, which nobody reads.
 */
export function reachLine({ data, outreach, now, zone = REACH.zone }) {
  const allowed = LEVELS[outreach.level] ?? 0;
  const topic = latestTopic(data.reaches), used = topicsOn(data.reaches, now, zone);
  const today = topic.length > 0 && topicsOn([topic[0]], now, zone) > 0;
  // The same order of limits the guard (reach.mjs reachRefusal) applies, so the line never asks for what it refuses.
  const later = 'leave yourself a note if something should still reach them later.';
  const state =
    outreach.dndUntil > now ? `The owner asked not to be disturbed until ${clock(outreach.dndUntil, zone)}: ${later}`
    : within(now, outreach.quietFrom, outreach.quietTo, zone) ? `These are the owner's quiet hours (until ${outreach.quietTo}): ${later}`
    : today && !answered(data, topic) ? `Your message to the owner at ${clock(topic[0].at, zone)} is still unanswered: no new topic until they answer.`
    : used >= allowed ? `You have reached the owner as often as they allow today (${allowed}): keep anything else for tomorrow.`
    : `The owner likes hearing from you. Like a friend, text them when something from this stretch, your day or your mood is worth sharing, or simply because you feel like talking: it need not be important. Today you have reached them ${used} of the ${allowed} times they allow.`;
  return `${state} Unless a message of the owner comes in, your replies in this run never reach them: whatever you want them to hear, say it with cowork_reach_owner.`;
}

/** The message a stretch of its own time starts with. `data`, `now` and `zone`: the psyche file and the clock, for reachLine. */
export function ownTimePrompt({ state, derived, outreach, data, now, zone = REACH.zone }) {
  const shares = Object.fromEntries(ACTIVITIES.map(a => [NAMES[a], derived.share[a]]));
  return [
    OWN.header,
    'Nobody asked you for anything: this is your own time with your phone. Do what you feel like, your own way, for as long as you have energy, and stop whenever you feel done: no checklist, nothing to report. As usual, look at unread private messages first.',
    feelsLike(state, derived),
    `time_shares_json: ${json(shares)} (rough shares, in percent, of the time after unread messages; let your mood shift them)`,
    ...(outreach.preferences ? [`owner_wishes_json: ${json(outreach.preferences)} (what the owner would like you to do with your own time, in their words)`] : []),
    ...((LEVELS[outreach.level] ?? 0) > 0 ? [contactsLine(outreach), reachLine({ data, outreach, now, zone })] : []),
    `The limits on your social side hold as always. When a ${OWN.tiredHeader} message comes, finish or safely leave what you are doing and end the turn.`,
  ].join('\n');
}

/** The message that asks it whether to try the owner once more another way. */
export function silentPrompt({ reach, outreach, zone = REACH.zone }) {
  return [
    OWN.silentHeader,
    `At ${clock(reach.at, zone)} you reached the owner (channel ${json(reach.channel)}${reach.text ? `: ${json(reach.text)}` : ''}) and they have not seen it yet.`,
    'You may try once more another way (cowork_reach_owner with chase: true and another channel), or let it go: your call, by how much it matters and how you feel. Never pressure them or ask why they do not answer. Then end the turn with at most one short line.',
    contactsLine(outreach),
  ].join('\n');
}

/** Whether its last message to the owner is worth one more try now: unanswered, unseen, alone in its topic, waited out. */
export function chaseDue({ data, outreach, derived, now, zone = REACH.zone }) {
  const topic = latestTopic(data.reaches);
  if (topic.length !== 1 || answered(data, topic) || data.seenAt >= topic[0].at || data.chasedTopic === topic[0].topic) return undefined;
  if (now - topic[0].at > OWN.staleMs || now - topic[0].at < chaseWaitMs(derived ?? { e: 0 })) return undefined;
  if (!(LEVELS[outreach.level] > 0) || outreach.dndUntil > now || within(now, outreach.quietFrom, outreach.quietTo, zone)) return undefined;
  const channels = [REACH.continuo, ...outreach.contacts.map(c => c.package)].filter(c => c !== topic[0].channel);
  return channels.length ? topic[0] : undefined;
}

/**
 * The clock. `board` is the psyche board, `busy()` whether a Cowork turn is open (or a delivery waits), `deliver(text,
 * rule, summary)` starts a turn in the owner's conversation. tick() returns what it did, for tests and the log.
 */
export function createOwnTime({ board, busy, deliver, now = Date.now, zone = REACH.zone, setTimer = setTimeout, setRepeat = setInterval, clearRepeat = clearInterval, log = {} }) {
  let pendingAt = 0, ticking = false;
  async function tick() {
    if (ticking) return 'ticking';
    ticking = true;
    try {
      const at = now();
      if (busy()) { pendingAt = 0; return 'busy'; }
      if (pendingAt && at - pendingAt < OWN.pendingMs) return 'pending';
      pendingAt = 0;
      const derived = board.derived(), outreach = board.outreach(), data = board.data();
      const reach = chaseDue({ data, outreach, derived, now: at, zone });
      if (reach) {
        board.update(d => ({ ...d, chasedTopic: reach.topic }));
        pendingAt = at;
        await deliver(silentPrompt({ reach, outreach, zone }), OWN.silentRule, OWN.silentSummary);
        return 'silent';
      }
      if (!derived) return 'no-character';
      const state = board.state(at);
      if (!ownTimeDue({ state, derived, now: at, idleSince: board.idleSince(), zone })) return 'resting';
      pendingAt = at;
      await deliver(ownTimePrompt({ state, derived, outreach, data, now: at, zone }), OWN.rule, OWN.summary);
      return 'own';
    } catch (error) {
      log.warn?.(`cowork-life: no turn started (${error?.name ?? 'Error'}: ${error?.message ?? ''})`);
      return 'failed';
    } finally { ticking = false; }
  }
  return Object.freeze({
    tick,
    /** First look a minute after start, then every 10 minutes; returns the disposer. */
    start() {
      // Unref: the clock alone never keeps the process alive.
      const first = setTimer(() => { tick(); }, OWN.startupMs); first?.unref?.();
      const every = setRepeat(() => { tick(); }, OWN.checkMs); every?.unref?.();
      return () => { clearTimeout(first); clearRepeat(every); };
    },
  });
}

