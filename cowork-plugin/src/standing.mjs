// A standing Cowork session, found live, resumed from persistence (after a DSH restart) or created on first use, the
// same way DSH's own session API and dsh-webhook do it: the Cowork preset mounted, attached to /workspace, the default
// permission preset, the default model, a title. notify.mjs delivers every app's notifications into the owner's
// conversation through it. The plugin entry needs these injected: workspaceRegistry, agents, agentPresets,
// agentDefaultModel, sessionTitle, sessionQuery, permissionPresets.
export const WORKSPACE = '/workspace';
export const PRESET = 'cowork-phone';
export const STANDING_INJECT = Object.freeze(['workspaceRegistry', 'agents', 'agentPresets', 'agentDefaultModel', 'sessionTitle', 'sessionQuery', 'permissionPresets']);

export function createStandingSessions(ctx) {
  const loading = new Map();
  async function stored(id) {
    let lease;
    try { lease = await ctx.sessionQuery.observeSession(id); return true; }
    catch (error) { if (error?.code === 'SESSION_QUERY_SESSION_NOT_FOUND') return false; throw error; }
    finally { lease?.[Symbol.dispose]?.(); }
  }
  async function load(id, title) {
    const preset = await ctx.agentPresets.resolve(PRESET);
    const setup = async agentCtx => { await ctx.agentPresets.mount(agentCtx, preset.id); };
    // An agent without provider and model fails its first step. Like DSH's own sessions, a created or resumed standing
    // session runs on the current default model of DSH.
    const { provider, model, reasoningEffort } = ctx.agentDefaultModel.currentSelection();
    const agentOptions = { provider, model, ...(reasoningEffort === undefined ? {} : { reasoningEffort }) };
    if (await stored(id)) return (await ctx.agents.resume({ resumeSessionId: id, agentOptions, setup })).agent;
    const scope = await ctx.agentPresets.acquireScope(preset.id);
    try {
      const workspace = await ctx.workspaceRegistry.create(WORKSPACE);
      const handle = await ctx.agents.create({ sessionId: id, meta: { cwd: workspace.path, agentPreset: preset.id }, agentOptions, setup });
      let attached = false;
      try {
        await workspace.attachSession(id); attached = true;
        ctx.permissionPresets.set(handle.agent.session, ctx.permissionPresets.defaultPreset);
        ctx.sessionTitle.rename(handle.agent.session, title);
        return handle.agent;
      } catch (error) {
        if (attached) try { await workspace.detachSession(id); } catch {}
        try { await handle.dispose(); } catch {}
        throw error;
      }
    } finally { await scope?.[Symbol.asyncDispose]?.(); }
  }
  return Object.freeze({
    /** The live agent of session `id`, loading (resuming or creating) it once when it is not live. DSH refuses every
     *  step of an archived session, so a standing session the owner archived is unarchived before it is used. */
    async agentOf(id, title) {
      let agent = ctx.agents.get(id);
      if (!agent) {
        if (!loading.has(id)) loading.set(id, load(id, title).finally(() => loading.delete(id)));
        agent = await loading.get(id);
      }
      await ctx.workspaceRegistry.unarchiveSession(agent.session.id);
      return agent;
    },
  });
}
