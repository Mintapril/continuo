// Cowork's tool-result pruner (the `toolResultPruner` service of the preset's compaction group): DSH's compaction
// engine calls pruneSession once the history crosses its threshold, before it considers a summary. Old screens become
// one line each (context-plan.mjs), with no model call; other tools' oversized results keep DSH's head/tail trim. A
// phone result is never trimmed: a cut screen would hand the model element ids that are not there.
// Pruning only at the threshold lands the replacements in batches, so a provider's prompt cache breaks once per batch.
import { ToolResultPruner } from '@deepseek-ai/dsh-compaction-tool-result-pruner';
import { freezeMessage } from '@deepseek-ai/dsh-llm';
import { phoneResult, prunePlan } from './context-plan.mjs';

export default class CoworkPruner extends ToolResultPruner {
  pruneSession(session) {
    const results = [];
    for (const seq of [...session.surface.nodes]) {
      const event = session.eventAt(seq);
      if (event?.type === 'tool/result') results.push({ seq, event, original: session.deriveEventMessage(event) });
    }
    const plan = new Map(prunePlan(results.map(r => ({ seq: r.seq, turn: r.event.data.turn, content: r.original.content }))).map(p => [p.seq, p.content]));
    const pruned = [];
    let charsRemoved = 0;
    for (const { seq, event, original } of results) {
      const content = plan.get(seq) ?? (phoneResult(original.content) === undefined ? this.pruneContent(original.content) : null);
      if (content === null) continue;
      const charsBefore = this.measureContent(original.content);
      const charsAfter = this.measureContent(content);
      // The same durable replacement as DSH's pruner: the shadow price first, then the result in its place.
      session.append('compaction/prune', { shadowedRange: { start: seq, end: seq }, shadowedSeqs: [seq],
        shadowedTokenCount: this.ctx.tokenMeter.estimateMessage(original) });
      const replacement = session.append('tool/result', { ...event.data, message: freezeMessage({ ...original, content }) },
        { surfaceOp: { op: 'replace', startSeq: seq, endSeq: seq }, sourceEventSeqs: [seq] });
      pruned.push({ originalSeq: seq, replacementSeq: replacement.seq, callId: event.data.message.source.callId, charsBefore, charsAfter });
      charsRemoved += charsBefore - charsAfter;
    }
    return { pruned, charsRemoved };
  }
}
