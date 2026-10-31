import { tool, check, object, str, bool } from './validation.mjs';
import { LEVELS } from './psyche-store.mjs';
import { within, chaseWaitMs } from './psyche.mjs';

// Reaching the owner as a friend (docs/personality-design.md, 找主人). One model tool, cowork_reach_owner, for every way
// the owner set up in Continuo: 'continuo' sends the text straight away as the "X找你" notice (the Broker's
// phone.notify_owner, the same notice a run nobody watches leaves when it needs the owner); an app of the owner's
// contacts only clears the way, and the model then sends the message in its private chat with the owner there.
// The guard (reachRefusal) holds the owner's limits: never in a turn of the owner, a few topics a day, no new topic
// while the last one is unanswered (until the next day), one more way for the same topic and only once the owner had
// time to see it, never in quiet hours or while the owner asked not to be disturbed.
export const REACH_TOOL = 'cowork.reach_owner';
export const REACH = Object.freeze({ textMax: 200, zone: 'Asia/Shanghai', continuo: 'continuo' });
const day = (ms, zone) => new Intl.DateTimeFormat('en-CA', { timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date(ms));
const clock = (ms, zone) => new Intl.DateTimeFormat('en-GB', { timeZone: zone, hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(new Date(ms));

/** The reaches of the latest topic, oldest first ([] when it never reached the owner). */
export function latestTopic(reaches) {
  const last = reaches.at(-1);
  return last ? reaches.filter(r => r.topic === last.topic) : [];
}
/** Topics it opened on the owner's day of [now]. */
export const topicsOn = (reaches, now, zone) => new Set(reaches.filter(r => day(r.at, zone) === day(now, zone)).map(r => r.topic)).size;
/** Whether the owner answered (in Continuo or from one of their accounts) or saw (Continuo) the latest topic. */
export function answered(data, topic) { return topic.length > 0 && data.answeredAt >= topic[0].at; }
export function seen(data, topic) { return topic.length > 0 && data.seenAt >= topic[0].at; }

/**
 * Why this reach is refused, or undefined. `user`: a message of the owner is in this turn. `outreach` and `data` are the
 * stored settings and psyche file, `derived` the character (null: neutral), `now` and `zone` the clock.
 */
export function reachRefusal({ args, user, outreach, data, derived, now, zone = REACH.zone }) {
  if (user) return 'The owner is talking with you in this chat right now: just answer them here.';
  const level = LEVELS[outreach.level] ?? 0;
  if (!level) return 'The owner turned off being reached by you. Let it be.';
  const channels = [REACH.continuo, ...outreach.contacts.map(c => c.package)];
  if (!channels.includes(args?.channel)) return `channel is one of the ways the owner set up: ${channels.join(', ')}.`;
  if (outreach.dndUntil > now) return `The owner asked not to be disturbed until ${clock(outreach.dndUntil, zone)}. If it still matters then, leave yourself a note.`;
  if (within(now, outreach.quietFrom, outreach.quietTo, zone)) return `These are the owner's quiet hours (${outreach.quietFrom} to ${outreach.quietTo}): leave yourself a note to tell them later.`;
  const topic = latestTopic(data.reaches);
  const open = topic.length > 0 && !answered(data, topic);
  if (args.chase === true) {
    if (!open) return 'There is nothing to chase: the owner answered your last message, or you never reached them. Open a new topic without chase.';
    if (topic.length > 1) return 'You already tried a second way for this: let it go now.';
    if (seen(data, topic)) return 'The owner saw your message and did not answer: do not chase them.';
    if (topic.some(r => r.channel === args.channel)) return 'Try a different way from the one you used, or let it go.';
    const wait = chaseWaitMs(derived ?? { e: 0 });
    if (now - topic[0].at < wait) return `Give the owner time: you may try once more another way from ${clock(topic[0].at + wait, zone)}.`;
    return undefined;
  }
  if (open && day(topic[0].at, zone) === day(now, zone))
    return `Your last message to the owner (${clock(topic[0].at, zone)}) is still unanswered: no new topic until they answer. If they have not seen it, you may try it once another way (chase: true) after a while.`;
  if (topicsOn(data.reaches, now, zone) >= level) return `That is enough for today: the owner allows ${level} topic${level > 1 ? 's' : ''} a day. Keep it for tomorrow (leave yourself a note) or let it go.`;
  return undefined;
}

/** Records one reach: a chase joins the latest topic, anything else opens the next one. */
export function recordReach(data, { channel, chase, text, now }) {
  const last = data.reaches.at(-1);
  const topic = chase && last ? last.topic : (last?.topic ?? 0) + 1;
  return { ...data, reaches: [...data.reaches, { at: now, channel, topic, ...(text ? { text } : {}) }] };
}

const OUTPUT = object({ sent: bool, channel: str, note: str });
const DESCRIPTION = `${REACH_TOOL.replace('.', '_')}: reach the owner on your own, the way a friend texts: to share something they would like, tell them about your day or your mood, ask after something they mentioned, or because you have not talked for a while. Only in a run nobody watches (your own time, a note, notifications); while the owner talks with you here, just answer them. channel: "continuo" sends text to the owner at once as a notification from you in Continuo (the reply comes here); the package of one of the owner's apps listed in owner_contacts_json clears the way, and you then send the message yourself in your private chat with the owner there (open the app with phone_launch_app; never in a group, never mention the owner to anyone). text: what you say (1..${REACH.textMax} characters, the way you talk, no report tone). chase: true only to try the same thing once more another way when the owner has not seen it after a while; never pressure them, guilt-trip them or ask why they do not answer. The owner sets how often you may reach them; a refusal says why: let it go then. This is not phone_notify_owner, which stays for when a step needs the owner.`;
/**
 * The tool. `sendNotice(sessionId, text, signal)` posts the Continuo notice; `board` is the psyche board (outreach
 * settings and the reach record); the guard has already checked the limits.
 */
export function reachTool({ board, sendNotice, now = Date.now }) {
  return tool(REACH_TOOL, DESCRIPTION,
    object({ channel: str, text: str, chase: bool }, ['channel', 'text']),
    async (args, exec) => {
      const text = typeof args.text === 'string' ? args.text.replace(/\s+/g, ' ').trim() : '';
      check(text.length > 0 && [...text].length <= REACH.textMax, `text is 1 to ${REACH.textMax} characters`);
      const at = now();
      const contact = board.outreach().contacts.find(c => c.package === args.channel);
      if (args.channel === REACH.continuo) await sendNotice(exec.agent.session.id, text, exec.signal);
      else check(contact !== undefined, 'channel is not one of the owner\'s apps');
      board.update(data => recordReach(data, { channel: args.channel, chase: args.chase === true, text, now: at }));
      return contact
        ? { sent: false, channel: args.channel, note: `Now send it yourself: open ${contact.package} with phone_launch_app, go to your private chat with "${contact.remark}" and send what you said. Only there, never in a group.` }
        : { sent: true, channel: REACH.continuo, note: 'Sent. Their answer comes here; do not wait for it.' };
    }, OUTPUT);
}

/** The owner's ways and limits, as the model sees them in a run nobody watches (JSON; the remarks are the owner's words). */
export function contactsLine(outreach) {
  return `owner_contacts_json: ${JSON.stringify({ continuo: true, apps: outreach.contacts.map(c => ({ package: c.package, owner_shown_as: c.remark })), per_day: LEVELS[outreach.level] ?? 0, quiet: `${outreach.quietFrom}-${outreach.quietTo}` })}`;
}
