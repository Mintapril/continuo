import { tool, object, str, check } from './validation.mjs';
import { appOfSession } from './jev-focus.mjs';

// cowork_notes: every note the owner's DSH keeps (dsh-schedule's catalog, what the app's 便条 page shows), read-only.
// schedule_list shows only the notes of the session it runs in, so a patrol (its own session) or a note of another
// app's standing session looked like nothing at all. Writing stays with the host tools, in the session of the note.
export const NOTES_TOOL = 'cowork.notes';
export const PATROL_SESSION = 'cowork-app-patrol';
// The timing fields of each stored kind (after, at, every, daily, weekly, cron), besides id, kind, title, prompt and
// scheduledAt (the next time it is due).
const TIMING = Object.freeze(['afterSeconds', 'everySeconds', 'time', 'timeZone', 'weekdays', 'expression']);
const OWN_MARKER = '[便条]', PATROL_MARKER = '[巡视]';
/** Where a note lives, as the model reads it: this session, the patrol session, an app's standing session, or another. */
export function sessionView(sessionId, here) {
  if (sessionId === here) return { session: 'this session' };
  if (sessionId === PATROL_SESSION) return { session: 'the patrol session (巡视 · 自动运营)' };
  const app = appOfSession(sessionId);
  if (app) return { session: 'the standing session of an app', app: { package: app.package, profile: app.profile } };
  return { session: 'another conversation' };
}
/** One active catalog entry as the model sees it. */
export function noteView(entry, here, now) {
  const prompt = typeof entry.prompt === 'string' ? entry.prompt : '';
  const own = prompt.trimStart().startsWith(OWN_MARKER);
  const timing = Object.fromEntries(TIMING.filter(key => entry[key] !== undefined).map(key => [key, entry[key]]));
  return {
    id: entry.id, title: entry.title, prompt, kind: entry.kind, timing,
    nextDueAt: entry.scheduledAt, overdue: now >= Date.parse(entry.scheduledAt),
    whose: own ? 'yours (left on your own)' : "the owner's (written while they talked with you)",
    patrol: !own && prompt.trimStart().startsWith(PATROL_MARKER),
    ...sessionView(entry.sessionId, here), sessionId: entry.sessionId,
  };
}
const NOTE_ITEM = { type: 'object' };
const OUTPUT = object({ notes: { type: 'array', items: NOTE_ITEM }, note: str });
const NOTE = 'Every note you have, in every session, soonest first. Change or delete one only in its own session (schedule_update, schedule_delete there), and only when a message of the owner asks for it.';
export function notesTool({ catalog, now = Date.now }) {
  return tool(NOTES_TOOL,
    `${NOTES_TOOL.replace('.', '_')}: list every note (便条) you have, in every session: the patrol (巡视) in its own patrol session, the notes of each app's standing session and of other conversations, with when each is due next and whose it is. schedule_list shows only the notes of this session: use this before saying you have no note or no patrol. Read-only.`,
    object({}, []),
    async (_args, exec) => {
      const read = catalog();
      check(read !== undefined, 'Notes are unavailable: DSH scheduling is not running', 'UNAVAILABLE');
      const at = now();
      const notes = (await read).filter(entry => entry?.status === 'active').map(entry => noteView(entry, exec.agent.session.id, at));
      return { notes, note: NOTE };
    }, OUTPUT);
}
