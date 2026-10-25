import { appendActivity } from './companion-store.mjs';

// What the diary is written from: when a Cowork turn ends, one activity line says what it did. What started the turn
// (the owner, a due note, a patrol, a notification), what the owner said (their turns only), its last reply, the apps it
// opened, what it typed and what it told the owner. Never the text of a notification, a screen or a tool result: what
// other people wrote stays out of the file (a notification turn records only what the AI itself did).
// Fed by DSH's session events (unattended.mjs listens globally); nothing reaches the model.
export const PRESET = 'cowork-phone';
const NOTIFY_RULE = 'cowork-notify';

const textOf = message => (Array.isArray(message?.content) ? message.content : [])
  .filter(b => b?.type === 'text' && typeof b.text === 'string').map(b => b.text).join('\n').trim();

/** What started the turn, from one of its user-role messages. */
export function kindOf(message) {
  const source = message?.source;
  if (source?.kind === 'user') return 'owner';
  if (source?.kind === 'webhook' && source.ruleId === NOTIFY_RULE) return 'notify';
  if (source?.kind === 'schedule') {
    const text = textOf(message);
    return /\[PATROL\]|\[巡视\]/.test(text) ? 'patrol' : 'note';
  }
  return undefined;
}

/** A tool call's arguments: an object in live events, a JSON string in the session log. */
function argumentsOf(data) {
  const value = data?.arguments ?? data?.input;
  if (typeof value !== 'string') return value && typeof value === 'object' ? value : {};
  try { const parsed = JSON.parse(value); return parsed && typeof parsed === 'object' ? parsed : {}; } catch { return {}; }
}

/** What one of its own tool calls adds to the turn: an app opened, text typed, a notice to the owner. */
function noteCall(turn, name, args) {
  if (name === 'phone_launch_app' && typeof args.packageName === 'string') { if (!turn.apps.includes(args.packageName)) turn.apps.push(args.packageName); }
  else if (name === 'phone_type_text' && typeof args.text === 'string') turn.typed.push(args.text);
  else if (name === 'phone_batch' && Array.isArray(args.steps)) { for (const step of args.steps) if (step?.tool === 'type_text' && typeof step.text === 'string') turn.typed.push(step.text); }
  else if (name === 'phone_notify_owner' && typeof args.text === 'string') turn.notified.push(args.text);
}

/**
 * The recorder: observe(session, event) for every session event. [append] stores one entry (appendActivity by
 * default), [now] is the clock. Only sessions on the Cowork preset count; a turn that did nothing leaves nothing.
 */
export function createActivityRecorder({ append = appendActivity, now = Date.now, warn = () => {} } = {}) {
  const presets = new Map();
  const turns = new Map();
  const presetOf = session => presets.get(session?.id) ?? session?.header?.agentPreset;
  return function observe(session, event) {
    const id = session?.id;
    if (typeof id !== 'string') return;
    switch (event?.type) {
      case 'agent-preset/selected': presets.set(id, event.data?.agentPreset); return;
      case 'turn/start': if (presetOf(session) === PRESET) turns.set(id, { kind: undefined, asked: [], text: '', apps: [], typed: [], notified: [] }); return;
      case 'user/message': {
        const turn = turns.get(id);
        if (!turn) return;
        const message = event.data?.message ?? event.data;
        const kind = kindOf(message);
        if (kind === 'owner') { turn.kind = 'owner'; const said = textOf(message); if (said && turn.asked.length < 3) turn.asked.push(said); }
        else if (kind && !turn.kind) turn.kind = kind;
        return;
      }
      case 'assistant/message': {
        const turn = turns.get(id);
        const text = turn ? textOf(event.data?.message) : '';
        if (text) turn.text = text;
        return;
      }
      case 'tool/call': {
        const turn = turns.get(id);
        if (turn && typeof event.data?.name === 'string') noteCall(turn, event.data.name, argumentsOf(event.data));
        return;
      }
      case 'turn/end': {
        const turn = turns.get(id);
        turns.delete(id);
        if (!turn || (!turn.text && !turn.apps.length && !turn.typed.length && !turn.notified.length)) return;
        try {
          append({ at: now(), sessionId: id, kind: turn.kind ?? 'other', text: turn.text, apps: turn.apps, typed: turn.typed, notified: turn.notified,
            ...(turn.kind === 'owner' && turn.asked.length ? { asked: turn.asked.join('\n') } : {}), reason: event.data?.reason?.kind });
        } catch (error) { warn(`cowork-diary: activity not recorded (${error?.name ?? 'Error'})`); }
        return;
      }
      default:
    }
  };
}
