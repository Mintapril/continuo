// Cowork's compaction engine (`ctx.compaction` in the preset's compaction group), in place of the host-wide
// billion-context-dsh: DSH's own engine (thresholds, the durable compaction transaction, overflow recovery), with the
// summary written for a phone session instead of a coding one. It works between steps with no tool and no nudge, so
// managing the context never costs the model a step. When the history crosses the threshold the engine first lets the
// pruner (context-pruner.mjs) drop old screens; only if that is not enough does it ask the session's own model (warm
// prompt cache) for a summary of the oldest part, with SUMMARY_INSTRUCTION.
// When it starts is Cowork's own (compactIfNeeded): at half of a window under 500K tokens, a quarter of a bigger one
// (compactAt), and at most once a turn, so a long patrol or chat keeps one prompt cache and is summarized once. Only a
// request the provider refuses as too long (the engine's overflow recovery) compacts again within the turn.
import { BasicCompactionEngine } from '@deepseek-ai/dsh-compaction-basic';
import { BlockAssembler, LlmError, contentHasImage } from '@deepseek-ai/dsh-llm';
import { SUMMARY_INSTRUCTION, compactAt } from './context-plan.mjs';

const INSTRUCTION = Object.freeze({ role: 'user', content: Object.freeze([Object.freeze({ type: 'text', text: SUMMARY_INSTRUCTION })]) });

export default class CoworkCompaction extends BasicCompactionEngine {
  constructor(ctx, config) {
    super(ctx, config);
    // The turn each session is in, and the turn it last compacted in (an attempt counts, done or failed).
    this.turnOf = new Map();
    this.compactedIn = new Map();
    ctx.on('session/event', (session, event) => {
      if (event?.type === 'turn/start' && Number.isSafeInteger(event.data?.turn)) this.turnOf.set(session.id, event.data.turn);
    });
  }

  /** Step pressure: past compactAt of the window, once a turn; then DSH's engine as before. Overflow recovery as DSH's. */
  async compactIfNeeded(agent, trigger, signal) {
    if (trigger !== 'pressure') return super.compactIfNeeded(agent, trigger, signal);
    const id = agent.session.id;
    const turn = this.turnOf.get(id) ?? (agent.phase?.kind === 'running' ? agent.phase.turn : undefined);
    if (turn !== undefined && this.compactedIn.get(id) === turn) return null;
    const target = agent.session.requestHeader()?.config;
    if (!target?.provider || !target?.model) return super.compactIfNeeded(agent, trigger, signal);
    const window = (await this.ctx.llm.resolveModelInfo(target.provider, target.model, signal))?.context?.contextWindow;
    // No window known: the engine reports it (once per route) as it always did.
    if (!Number.isSafeInteger(window) || window <= 0) return super.compactIfNeeded(agent, trigger, signal);
    if (this.ctx.tokenMeter.measure(agent.session).totalTokens < compactAt(window)) return null;
    if (turn !== undefined) this.compactedIn.set(id, turn);
    return super.compactIfNeeded(agent, trigger, signal);
  }

  /** DSH's summary call (summarizeWithLlm) with Cowork's instruction: the replayed conversation, then the request. */
  async summarize(input, agent, signal) {
    const { summarizationProvider: provider, summarizationModel: model, maxTokens } = this.config;
    const latest = agent.session.requestHeader()?.config;
    const own = agent.options?.provider && agent.options?.model ? { provider: agent.options.provider, model: agent.options.model } : undefined;
    const target = provider ? { provider, model } : latest ?? own;
    if (target === undefined) throw new Error('cowork compaction: no provider/model to write the summary; route one request first');
    const options = { provider: target.provider, model: target.model, messages: [...input.messages, INSTRUCTION],
      toolHistory: agent.session.toolHistory(), ...(input.tools === undefined ? {} : { tools: [...input.tools] }),
      maxTokens, sessionId: agent.session.id, purpose: 'compaction', ...(signal === undefined ? {} : { signal }) };
    const assembler = new BlockAssembler();
    for await (const chunk of this.ctx.llm.stream(options)) assembler.push(chunk);
    const finish = assembler.finish;
    if (finish.kind === 'error' || finish.kind === 'aborted') throw new LlmError(finish.failure.message, finish.failure.code, finish.failure);
    if (finish.kind === 'max-tokens') throw Object.assign(new Error('summarization truncated at the token cap (incomplete checkpoint)'), { code: 'MAX_TOKENS' });
    const rawOutput = assembler.blocks();
    if (contentHasImage(rawOutput)) throw new LlmError('compaction summary cannot contain image output', 'UNSUPPORTED_CONTENT');
    const summary = rawOutput.filter(block => block.type === 'text');
    if (!summary.some(block => block.text.trim().length > 0)) throw new Error('summarization produced no text summary content');
    return { summary, rawOutput, llmStreamCall: true, provider: options.provider, model: options.model, maxTokens,
      ...(assembler.usage === undefined ? {} : { usage: assembler.usage }) };
  }
}
