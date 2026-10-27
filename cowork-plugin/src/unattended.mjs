import { UNATTENDED_KINDS, readUnattended, writeUnattended, selectionOf, unattendedFile } from './unattended-store.mjs';
import { readFocus, writeFocus, isFocusKey, focusFile } from './jev-focus-store.mjs';
import { identityFile, readIdentity, writeIdentity, diaryFile, readDiary, activityFile, appendActivity, migrateDiary, readEvents, ownerSessionFile, readOwnerSession, writeOwnerSession } from './companion-store.mjs';
import { appOfSession } from './jev-focus.mjs';
import { createActivityRecorder } from './diary.mjs';
import { createDiaryWriter } from './diary-writer.mjs';

// Continuo reads and sets the owner's settings for Cowork over DSH's own authenticated RPC (the cookie session the app
// logs in with), namespace `coworkUnattended`:
// - `models()` and `setModel({request: {kind, selection}})`: the model patrols, notification triggers and the owner's
//   conversation (叫它, kind owner) use. A selection must resolve in the live model catalog; null clears it.
//   restricted.mjs applies the stored value per request.
// - `jevFocus()` and `clearJevFocus({request: {app}})`: the Jev focus the model keeps per app (jev-focus.mjs). The
//   owner can only clear one; the clear is kept with its time, so a run nobody watches cannot set it again at once.
// - `identity()` and `setIdentity({request: {name}})`: the name the owner gave the AI (restricted.mjs tells the model).
// - `diary({request: {before?, limit?}})`: the diary, one entry a day ({date, at, text}, newest first), written each
//   night by diary-writer.mjs from the activity diary.mjs records at the end of each Cowork turn.
// - `events({request: {before?, limit?}})`: 事件, the turns it ran on its own (a patrol, a notification, a due note),
//   newest first, from the same activity; each with its app ({profile, package}) when notifications of one app started
//   it (or, before 2026-10-26, when it ran in that app's standing session).
// - `ownerSession()` and `setOwnerSession({request: {sessionId}})`: which session is the owner's conversation now (叫它),
//   the one session notifications go to; Continuo sets it when it connects and when the owner clears the conversation.
// - `createNote({request: {sessionId, title, prompt, change}})`: a note the owner writes in Continuo, stored directly
//   (dsh-schedule's own create, which DSH does not expose remotely), unmarked and in the owner's words, at the exact
//   time they picked: no model turn is spent writing it down. `change` is the shape schedule/update takes.
export const name = 'cowork-unattended';
export const inject = ['llm'];
export const NAMESPACE = 'coworkUnattended';
const SESSION = /^[a-zA-Z0-9_-]{1,100}$/;
const PROMPT_MAX = 4000;

/** dsh-schedule's create selector from an update-shaped `change` ({kind, at|every_seconds|daily|weekly|cron}). */
export function selectorOf(change) {
  switch (change?.kind) {
    case 'at': return { at: change.at };
    case 'every': return { every_seconds: change.every_seconds };
    case 'daily': return { daily: change.daily };
    case 'weekly': return { weekly: change.weekly };
    case 'cron': return { cron: change.cron };
    default: throw new TypeError('change.kind must be at, every, daily, weekly or cron');
  }
}

/** The note the owner wrote, checked before it reaches the schedule service (which checks the timing itself). */
export function noteRequest(request) {
  const { sessionId, title, prompt, change } = request ?? {};
  if (typeof sessionId !== 'string' || !SESSION.test(sessionId)) throw new TypeError('sessionId is a session id');
  if (typeof title !== 'string' || title.trim() === '' || title.length > 120) throw new TypeError('title is 1 to 120 characters');
  if (typeof prompt !== 'string' || prompt.trim() === '' || prompt.length > PROMPT_MAX || prompt.includes('\u0000')) throw new TypeError(`prompt is 1 to ${PROMPT_MAX} characters`);
  return { sessionId, request: { title: title.trim(), prompt: prompt.trim(), ...selectorOf(change) } };
}

/** The Remote service class, built over the host's Typert protocol module (dsh-schedule uses the same base). */
export function unattendedService({ Remote, TypertRemoteService }) {
  return class CoworkUnattended extends TypertRemoteService {
    static inject = ['llm'];
    constructor(ctx, config = {}) {
      super(ctx, NAMESPACE);
      this.file = config.file ?? unattendedFile();
      this.focusFile = config.focusFile ?? focusFile();
      this.identityFile = config.identityFile ?? identityFile();
      this.diaryFile = config.diaryFile ?? diaryFile();
      this.activityFile = config.activityFile ?? activityFile();
      this.ownerSessionFile = config.ownerSessionFile ?? ownerSessionFile();
      this.schedule = config.schedule ?? (() => undefined);
      this.now = config.now ?? Date.now;
      // Plain JavaScript has no decorator syntax: mark the Remote methods exactly as @Remote would.
      for (const method of ['models', 'setModel', 'jevFocus', 'clearJevFocus', 'identity', 'setIdentity', 'diary', 'events', 'createNote', 'ownerSession', 'setOwnerSession'])
        Remote(method)(this[method], { kind: 'method', name: method, static: false, private: false, addInitializer: init => init.call(this) });
    }
    models() { return readUnattended(this.file); }
    async setModel(request) {
      const kind = request?.kind;
      if (!UNATTENDED_KINDS.includes(kind)) throw new Error(`kind must be one of ${UNATTENDED_KINDS.join(', ')}`);
      let selection = selectionOf(request.selection);
      if (selection !== null) {
        // Store what the catalog resolves to, so a request later never names a route that does not exist.
        const resolved = await this.ctx.llm.resolveCallConfig(selection);
        // An effort is kept only when one was chosen: without one the adapter's own default applies on each request.
        selection = selectionOf({ provider: resolved.provider, model: resolved.model,
          reasoningEffort: selection.reasoningEffort === undefined ? undefined : (resolved.reasoningEffort ?? selection.reasoningEffort) });
      }
      return writeUnattended({ ...readUnattended(this.file), [kind]: selection }, this.file);
    }
    /** Every app's focus entry ({text, at, by, revision}; text '' is a cleared one), by `<profile>:<package>`. */
    jevFocus() { return readFocus(this.focusFile); }
    clearJevFocus(request) {
      const app = request?.app;
      if (!isFocusKey(app)) throw new Error('app must be <work|personal>:<package>');
      writeFocus(app, null, { by: 'app', at: this.now() }, this.focusFile);
      return readFocus(this.focusFile);
    }
    identity() { return readIdentity(this.identityFile); }
    setIdentity(request) { return writeIdentity({ name: request?.name ?? null }, this.identityFile); }
    diary(request) { return readDiary({ before: request?.before, limit: request?.limit }, this.diaryFile); }
    events(request) {
      const page = readEvents({ before: request?.before, limit: request?.limit }, this.activityFile);
      const appOf = entry => { const own = appOfSession(entry.sessionId); if (own) return own; const at = entry.app?.indexOf(':') ?? -1; return at > 0 ? { profile: entry.app.slice(0, at), package: entry.app.slice(at + 1) } : undefined; };
      const withApp = entry => { const app = appOf(entry); return app ? { ...entry, app: { profile: app.profile, package: app.package } } : entry; };
      return { entries: page.entries.map(withApp), more: page.more };
    }
    ownerSession() { return { sessionId: readOwnerSession(this.ownerSessionFile) }; }
    setOwnerSession(request) { return { sessionId: writeOwnerSession(request?.sessionId, this.ownerSessionFile) }; }
    async createNote(request) {
      const schedule = this.schedule();
      if (typeof schedule?.create !== 'function') throw new Error('DSH scheduling is not running');
      const { sessionId, request: note } = noteRequest(request);
      const record = await schedule.create(sessionId, note);
      return { ...record, sessionId };
    }
  };
}

export async function apply(ctx, config = {}) {
  const protocol = await import('@deepseek-ai/dsh-typert-protocol');
  // The host's dsh-schedule service, when it runs: taken when it appears, never required.
  let schedule;
  if (typeof ctx.inject === 'function')
    ctx.inject(['schedule'], scheduleCtx => { const service = scheduleCtx.schedule; schedule = service; scheduleCtx.on('dispose', () => { if (schedule === service) schedule = undefined; }); });
  const warn = message => ctx.logger?.warn?.(message);
  // The old per-turn diary lines are activity now (once); the daily diary takes over diary.jsonl.
  if (!config.diaryFile) try { if (migrateDiary()) ctx.logger?.info?.('cowork-diary: the per-turn diary became activity.jsonl'); } catch (error) { warn(`cowork-diary: diary.jsonl not moved (${error?.code ?? error?.name})`); }
  const observe = createActivityRecorder({ warn, ...(config.activityFile ? { append: entry => appendActivity(entry, config.activityFile) } : {}) });
  ctx.on('session/event', (session, event) => { observe(session, event); }, { global: true });
  // The nightly entry: written with the owner's model (叫它), else the patrol's, else DSH's default.
  const writer = createDiaryWriter({
    llm: () => ctx.llm,
    target: async () => {
      const stored = readUnattended(config.file ?? unattendedFile());
      const wanted = stored.owner ?? stored.patrol ?? ctx.get?.('agentDefaultModel')?.currentSelection?.();
      if (!wanted?.provider || !wanted?.model) return null;
      const routed = await ctx.llm.resolveCallConfig({ provider: wanted.provider, model: wanted.model, ...(wanted.reasoningEffort ? { reasoningEffort: wanted.reasoningEffort } : {}) });
      return { provider: routed.provider, model: routed.model, ...(routed.reasoningEffort ? { reasoningEffort: routed.reasoningEffort } : {}) };
    },
    name: () => readIdentity(config.identityFile ?? identityFile()).name,
    files: { activity: config.activityFile ?? activityFile(), diary: config.diaryFile ?? diaryFile() },
    log: { info: message => ctx.logger?.info?.(message), warn },
  });
  ctx.effect(() => writer.start(), 'cowork-diary: nightly entry');
  ctx.plugin(unattendedService(protocol), { schedule: () => schedule, ...config });
}
