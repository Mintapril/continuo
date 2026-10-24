// Which Cowork sessions have a turn queued for the AI screen (task-lifecycle's admission queue). The notification
// plugin reads it so a queued turn, which has started but holds nothing, does not count as the screen being in use.
// restricted.mjs and notify.mjs are separate plugin entries and need not share module instances, so the board lives
// on globalThis under a registered symbol; tests create their own. Listeners run synchronously inside the lifecycle's
// grant/release path (notify's coalescer pokes and may start a delivery there): keep them short and never call back
// into the lifecycle from them.
const KEY = Symbol.for('dsh-cowork-phone.screen-board');

export function createScreenBoard() {
  const waiting = new Map(), listeners = new Set();
  const changed = () => { for (const listener of [...listeners]) { try { listener(); } catch {} } };
  return Object.freeze({
    enter(sessionId) { waiting.set(sessionId, (waiting.get(sessionId) ?? 0) + 1); changed(); },
    leave(sessionId) {
      const left = (waiting.get(sessionId) ?? 0) - 1;
      if (left > 0) waiting.set(sessionId, left); else waiting.delete(sessionId);
      changed();
    },
    has: sessionId => waiting.has(sessionId),
    subscribe(listener) { listeners.add(listener); return () => { listeners.delete(listener); }; },
  });
}

export const sharedScreenBoard = () => (globalThis[KEY] ??= createScreenBoard());
