import { tool, object, str, check } from './validation.mjs';

// Which tools the model sees. Every Cowork tool stays registered and guarded as before; what changes is the list sent
// with each request: the everyday tools always, the rest in groups the model loads with cowork_tools when it needs them
// (or that a message calls for, see groupsFor). A loaded group stays for the rest of the session (until DSH restarts):
// the list only grows, so a provider's prompt cache breaks at most once per group, never on every turn.
export const TOOLS_TOOL = 'cowork.tools';
export const TOOL_GROUPS = Object.freeze({
  notes: Object.freeze({ about: 'notes (便条): write, list, change and delete the notes of this session, and see the notes of every session',
    tools: Object.freeze(['schedule_create', 'schedule_list', 'schedule_update', 'schedule_delete', 'cowork_notes']) }),
  memory: Object.freeze({ about: 'memory beyond memory_search and memory_save: read one memory whole, list, update and archive memories',
    tools: Object.freeze(['memory_get', 'memory_list', 'memory_update', 'memory_archive']) }),
  context: Object.freeze({ about: 'the context of this conversation: compress and restore earlier parts, search it, see its size (load it when a context nudge asks you to compress)',
    tools: Object.freeze(['compress', 'decompress', 'search_context', 'acp_status']) }),
  web: Object.freeze({ about: 'web search and reading a public web page', tools: Object.freeze(['web_search', 'web_fetch']) }),
  files: Object.freeze({ about: 'bounded text files in your workspace', tools: Object.freeze(['cowork_files_read', 'cowork_files_write', 'cowork_files_list']) }),
  gesture: Object.freeze({ about: 'free-form finger strokes: drawing, writing, signatures, curved, held or slow drags, two-finger pinch and rotate',
    tools: Object.freeze(['phone_gesture']) }),
  apps: Object.freeze({ about: 'granting an app a runtime permission, uninstalling an app', tools: Object.freeze(['phone_grant_permission', 'phone_uninstall_app']) }),
  jev: Object.freeze({ about: 'reading or setting the Jev focus of an app (which of its notifications wake you)', tools: Object.freeze(['cowork_jev_focus']) }),
});
// Never in the list, still callable: the AI screen always exists (phone_start), and pausing its own task is the owner's.
export const HIDDEN_TOOLS = Object.freeze(['phone_start', 'phone_pause']);
const hidden = new Set(HIDDEN_TOOLS);
const GROUP_OF = new Map(Object.entries(TOOL_GROUPS).flatMap(([group, { tools }]) => tools.map(name => [name, group])));
/** The group a model-visible tool name belongs to, or undefined for an everyday (or hidden) tool. */
export const groupOf = name => GROUP_OF.get(name);
/** Whether a model-visible tool name is in the list of an agent that loaded these groups. */
export function shownWith(loaded, name) {
  if (hidden.has(name)) return false;
  const group = GROUP_OF.get(name);
  return group === undefined || loaded.has(group);
}
/** The source kind of the context nudges of DSH's context plugin (acp), which ask the model to compress. */
export const ACP_NUDGE_KIND = 'plugin:acp-nudge';
const JEV_LINES = ['jev_held_untrusted_json: ', 'jev_focus_json: ', '[JEV HELD]'];
const textOf = message => (Array.isArray(message?.content) ? message.content : []).map(b => (b?.type === 'text' && typeof b.text === 'string' ? b.text : '')).join('\n');
/** The groups the messages of a step call for: a due note or patrol brings the notes, notifications Jev held back
 *  (or the focus of their app) bring the Jev focus, and a context nudge brings the context tools. */
export function groupsFor(messages) {
  const list = Array.isArray(messages) ? messages : [];
  const groups = new Set();
  for (const message of list) {
    if (message?.source?.kind === 'schedule') groups.add('notes');
    if (message?.source?.kind === ACP_NUDGE_KIND) groups.add('context');
    const text = textOf(message);
    if (JEV_LINES.some(line => text.includes(line))) groups.add('jev');
  }
  return [...groups];
}
const OUTPUT = object({ loaded: { type: 'array', items: str }, added: { type: 'array', items: str }, note: str });
/** cowork_tools: load groups of tools into the list (or, without load, see which are loaded). */
export function toolsTool({ loadedOf, load }) {
  const lines = Object.entries(TOOL_GROUPS).map(([group, { about, tools }]) => `${group}: ${about} (${tools.join(', ')})`);
  return tool(TOOLS_TOOL,
    `${TOOLS_TOOL.replace('.', '_')}: some of your tools are kept out of the list until you need them, to keep every request small. Load a group to get its tools from your next step on; it stays loaded for the rest of this session. When a message or your instructions name a tool you do not see, load its group. Omit load to see which groups are loaded. Groups: ${lines.join('; ')}.`,
    object({ load: { type: 'array', items: { type: 'string', enum: Object.keys(TOOL_GROUPS) } } }, []),
    async (args, exec) => {
      const agent = exec.agent;
      const before = new Set(loadedOf(agent));
      for (const group of args.load ?? []) { check(Object.hasOwn(TOOL_GROUPS, group), `Unknown group ${group}`); load(agent, group); }
      const loaded = [...loadedOf(agent)].sort();
      const added = loaded.filter(group => !before.has(group)).flatMap(group => TOOL_GROUPS[group].tools);
      return { loaded, added, note: added.length ? 'These tools are in your list from your next step on.' : 'Nothing new to load.' };
    }, OUTPUT);
}
