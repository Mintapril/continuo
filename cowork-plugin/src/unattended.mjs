import { UNATTENDED_KINDS, readUnattended, writeUnattended, selectionOf, unattendedFile } from './unattended-store.mjs';

// Continuo reads and sets the model of unattended runs over DSH's own authenticated RPC (the cookie session the app
// logs in with), namespace `coworkUnattended`: `models()` and `setModel({request: {kind, selection}})`. A selection
// must resolve in the live model catalog; null clears it. restricted.mjs applies the stored value per request.
export const name = 'cowork-unattended';
export const inject = ['llm'];
export const NAMESPACE = 'coworkUnattended';

/** The Remote service class, built over the host's Typert protocol module (dsh-schedule uses the same base). */
export function unattendedService({ Remote, TypertRemoteService }) {
  return class CoworkUnattended extends TypertRemoteService {
    static inject = ['llm'];
    constructor(ctx, config = {}) {
      super(ctx, NAMESPACE);
      this.file = config.file ?? unattendedFile();
      // Plain JavaScript has no decorator syntax: mark the Remote methods exactly as @Remote would.
      for (const method of ['models', 'setModel'])
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
  };
}

export async function apply(ctx, config = {}) {
  const protocol = await import('@deepseek-ai/dsh-typert-protocol');
  ctx.plugin(unattendedService(protocol), config);
}
