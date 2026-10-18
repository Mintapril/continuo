import net from 'node:net';

export const PRESET_ID = 'cowork-phone';
export const PHONE_TOOLS = Object.freeze({
  phone_status: 'phone.status', phone_screenshot: 'phone.screenshot', phone_ui_tree: 'phone.ui_tree',
  phone_tap: 'phone.tap', phone_swipe: 'phone.swipe', phone_type_text: 'phone.type_text',
  phone_key: 'phone.key', phone_launch_app: 'phone.launch_app', phone_stop: 'phone.stop',
});
export const ALLOWED_TOOLS = Object.freeze(['web_search', 'web_fetch', 'cowork_read', 'cowork_write', 'cowork_list', ...Object.keys(PHONE_TOOLS)]);
const allowed = new Set(ALLOWED_TOOLS);
export function assertRecord(value, keys, required = keys) {
  if (!value || typeof value !== 'object' || Array.isArray(value) || ![Object.prototype, null].includes(Object.getPrototypeOf(value))) throw new Error('Expected a plain object');
  for (const key of Object.keys(value)) if (!keys.includes(key)) throw new Error(`Unexpected field: ${key}`);
  for (const key of required) if (!Object.hasOwn(value, key)) throw new Error(`Missing field: ${key}`);
}
export function text(value, name, max = 4096, min = 1) {
  if (typeof value !== 'string' || value.length < min || value.length > max || value.includes('\0')) throw new Error(`Invalid ${name}`);
  return value;
}
export function integer(value, name, min, max) {
  if (!Number.isSafeInteger(value) || value < min || value > max) throw new Error(`Invalid ${name}`);
  return value;
}
export function assertPublicWebUrl(value) {
  text(value, 'URL', 8192);
  const url = new URL(value);
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password) throw new Error('Only credential-free HTTP(S) URLs are allowed');
  const host = url.hostname.toLowerCase().replace(/^\[|\]$/g, '').replace(/\.$/, '');
  if (host === 'localhost' || host.endsWith('.localhost') || host.endsWith('.local') || !host.includes('.') || net.isIP(host)) {
    // Public DNS names only. The builtin provider additionally checks resolved addresses,
    // pins DNS and validates redirects; this blocks direct bridge/private IP access early.
    throw new Error('Use a public DNS hostname; local and numeric destinations are denied');
  }
  if (url.port && !['80', '443'].includes(url.port)) throw new Error('Only standard public web ports are allowed');
  return url;
}
export function guardTool(exec) {
  if (!allowed.has(exec.name)) return `Cowork denies capability: ${exec.name}`;
  if (!exec.agent) return 'Cowork tools require a bound session';
  try {
    if (exec.name === 'web_fetch') {
      assertRecord(exec.arguments, ['url']); assertPublicWebUrl(exec.arguments.url);
    } else if (exec.name === 'web_search') {
      assertRecord(exec.arguments, ['queries']);
      const qs = exec.arguments.queries;
      if (!Array.isArray(qs) || qs.length < 1 || qs.length > 4) throw new Error('Expected 1–4 queries');
      qs.forEach(q => text(q, 'query', 1024));
    }
  } catch (error) { return `Cowork policy: ${error.message}`; }
  return undefined;
}
