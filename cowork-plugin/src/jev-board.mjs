// Notifications the Jev judge held back, or could not judge, kept until the model has seen them: the app's next
// notification turn, or a patrol, shows them, the way a person glancing at the notification shade notices what they
// missed. Memory only, never written to disk: a DSH restart forgets them, and so does a day without a look. What the
// model was shown is part of that turn's message, so it stays in the session history like any notification it got.
// notify.mjs records, restricted.mjs reads for patrols; they are separate plugin entries, so the board lives on
// globalThis under a registered symbol (as screen-board.mjs does); tests create their own.
const KEY = Symbol.for('dsh-cowork-phone.jev-board');
export const HELD = Object.freeze({ keepMs: 24 * 3_600_000, max: 500, show: 20 });

/**
 * entries: {key, app, package, profile, sender, text, at, p, focus} with p the Jev probability, or null and `unjudged`
 * (the judge error) when Jev could not answer. The same notification posted again replaces the older copy.
 */
export function createJevBoard({ now = Date.now, limits = HELD } = {}) {
  let held = [];
  const prune = () => { const t = now(); held = held.filter(x => x.at > t - limits.keepMs).slice(-limits.max); };
  return Object.freeze({
    record(entries) {
      for (const entry of entries) {
        held = held.filter(x => !(x.key === entry.key && x.sender === entry.sender && x.text === entry.text));
        held.push(Object.freeze({ ...entry }));
      }
      held.sort((a, b) => a.at - b.at);
      prune();
    },
    /**
     * What `match` selects, without removing it: at most `show` entries (unjudged ones first, then the likeliest),
     * oldest first, and how many more there are. `commit()` removes all of them once the model has been handed the
     * list; a delivery that fails leaves them for the next look.
     */
    select(match) {
      prune();
      const mine = held.filter(match);
      const rank = x => (x.p === null ? 2 : x.p);
      const items = [...mine].sort((a, b) => rank(b) - rank(a)).slice(0, limits.show).sort((a, b) => a.at - b.at);
      return Object.freeze({ items: Object.freeze(items), omitted: mine.length - items.length,
        commit() { const gone = new Set(mine); held = held.filter(x => !gone.has(x)); } });
    },
    size: () => held.length,
  });
}

export const sharedJevBoard = () => (globalThis[KEY] ??= createJevBoard());

/** The held entries as the model sees them (other people's text, JSON-encoded by the caller). */
export const heldView = items => items.map(x => ({ app: x.app, package: x.package, profile: x.profile, sender: x.sender, text: x.text,
  at: new Date(x.at).toISOString(), ...(x.p === null ? { jev: `not judged (${x.unjudged})` } : { jev_p: Math.round(x.p * 100) / 100 }),
  ...(x.focus ? { with_focus: true } : {}) }));
