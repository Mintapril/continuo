// Executes narrowly selected, unchanged installed DSH implementation seams.
// No package installation, model/provider, network, app, or Android interaction.
import fs from 'node:fs/promises';
import { assertNever, isJsonValue } from '../research/installed/dsh/node_modules/@deepseek-ai/dsh-util-values/lib/index.js';
const root = new URL('../research/installed/dsh/node_modules/@deepseek-ai/', import.meta.url);
const tools = await fs.readFile(new URL('dsh-tools/lib/index.js', root), 'utf8');
function section(source, from, to) {
  const start = source.indexOf(from), end = source.indexOf(to, start + from.length);
  if (start < 0 || end < 0) throw new Error('Installed source seam marker changed');
  return source.slice(start, end);
}
class HarnessError extends Error { constructor(message, code) { super(message); this.code = code; } }
const body = section(tools, '//#region lib/types/json-schema.js', '//#region lib/types/ptc.js');
export const { defineTool, validateJsonSchemaValue } = new Function('HarnessError', 'assertNever', 'isJsonValue', `${body}\nreturn {defineTool, validateJsonSchemaValue};`)(HarnessError, assertNever, isJsonValue);
export const view = new Function(`return function${section(tools, '\tview(scope) {', '\n\t/**\n\t* Look up a tool')};`)();
const llm = await fs.readFile(new URL('dsh-llm/lib/index.js', root), 'utf8');
const assemblerStart = llm.indexOf('var BlockAssembler = class');
const assemblerEnd = llm.indexOf('\n//#endregion', assemblerStart);
if (assemblerStart < 0 || assemblerEnd < 0) throw new Error('Installed assembler seam changed');
export const BlockAssembler = new Function('assertNever', 'brandString', `${llm.slice(assemblerStart, assemblerEnd)}\nreturn BlockAssembler;`)(assertNever, value => value);
export const toolsSource = tools;
