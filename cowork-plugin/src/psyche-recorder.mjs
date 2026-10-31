import { stepActivity, stepWeight, remember, spend, ownerSpoke, failure, THANKS } from './psyche.mjs';
import { isOwnerSession } from './companion-store.mjs';
import { latestTopic, answered } from './reach.mjs';

// What moves energy and mood as Cowork turns run, from DSH's session events (unattended.mjs listens globally):
// - turn/start and turn/end: whether a turn is running (rest counts only while none is), and a run that ended in error;
// - assistant/message: one model step: its usage (output tokens, against the model's own recent steps) and its tool
//   calls (what kind of activity the step was), so energy and mood change by the character's effort;
// - user/message of the owner in their conversation (叫它): the owner talked to it, thanked it, or answered its message.
// Notifications (people answering it, the owner's own accounts, floods) are counted where they are delivered
// (notify.mjs), the buttons it pressed where it pressed them (restricted.mjs). Nothing here reads other people's text.
export const PRESET = 'cowork-phone';

const textOf = message => (Array.isArray(message?.content) ? message.content : [])
  .filter(b => b?.type === 'text' && typeof b.text === 'string').map(b => b.text).join('\n').trim();
const argsOf = value => { if (value && typeof value === 'object') return value; try { const v = JSON.parse(value); return v && typeof v === 'object' ? v : {}; } catch { return {}; } };
/** The tool calls of an assistant message: [{name, args}]. */
export const callsOf = message => (Array.isArray(message?.content) ? message.content : [])
  .filter(b => b?.type === 'tool-call' && typeof b.name === 'string').map(b => ({ name: b.name, args: argsOf(b.arguments) }));
/** The output tokens a step reported, or undefined. */
export function outputTokensOf(data) {
  const n = data?.usage?.outputTokens;
  return Number.isSafeInteger(n) && n >= 0 ? n : undefined;
}

/** The recorder: observe(session, event) for every session event. `board` is the psyche board, `now` the clock. */
export function createPsycheRecorder({ board, now = Date.now } = {}) {
  const presets = new Map(), turns = new Map();
  const presetOf = session => presets.get(session?.id) ?? session?.header?.agentPreset;
  return function observe(session, event) {
    const id = session?.id;
    if (typeof id !== 'string') return;
    switch (event?.type) {
      case 'agent-preset/selected': presets.set(id, event.data?.agentPreset); return;
      case 'turn/start':
        if (presetOf(session) !== PRESET) return;
        turns.set(id, { owner: false });
        board.started(id);
        return;
      case 'user/message': {
        const turn = turns.get(id);
        const message = event.data?.message ?? event.data;
        if (!turn || message?.source?.kind !== 'user') return;
        turn.owner = true;
        if (!isOwnerSession(id)) return;
        const at = now(), text = textOf(message);
        board.update((data, derived) => {
          const open = (() => { const topic = latestTopic(data.reaches); return topic.length > 0 && !answered(data, topic); })();
          const state = derived ? ownerSpoke(data.state, derived, { thanks: THANKS.test(text), reply: open, now: at }) : { ...data.state, lastOwnerAt: at };
          return { ...data, state, answeredAt: at };
        });
        return;
      }
      case 'assistant/message': {
        const turn = turns.get(id);
        if (!turn) return;
        const message = event.data?.message;
        const out = outputTokensOf(event.data);
        const route = `${message?.source?.provider ?? ''}/${message?.source?.model ?? ''}`;
        board.update((data, d) => {
          const models = out === undefined || route === '/' ? data.models : { ...data.models, [route]: remember(data.models[route], out) };
          if (!d) return { ...data, models };
          const activity = stepActivity(callsOf(message), d.effort, { owner: turn.owner });
          const weight = stepWeight(out, data.models[route]);
          return { ...data, models, state: spend(data.state, d, { activity, weight, now: now() }) };
        });
        return;
      }
      case 'turn/end': {
        const turn = turns.get(id);
        turns.delete(id);
        if (!turn) return;
        if (event.data?.reason?.kind === 'error') board.update((data, d) => (d ? { ...data, state: failure(data.state, d, { now: now() }) } : data));
        board.ended(id);
        return;
      }
      default:
    }
  };
}
