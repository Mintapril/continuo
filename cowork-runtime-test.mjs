// Operator-only acceptance. Fixed loopback, stdin login, sanitized metadata only.
// Does not read provider configuration, session contents or make model requests.
import fs from 'node:fs';
import { randomUUID } from 'node:crypto';
const origin = 'http://127.0.0.1:3080';
function requireValue(value, message) { if (!value) throw Error(message); }
async function main() {
  const token = fs.readFileSync(0, 'utf8').trim();
  requireValue(/^[A-Za-z0-9_-]{16,256}$/.test(token), 'Missing private login');
  const auth = await fetch(`${origin}/?token=${token}`, { redirect:'manual', signal:AbortSignal.timeout(10000) });
  const cookie = auth.headers.getSetCookie().map(v => v.split(';', 1)[0]).join('; ');
  requireValue(cookie, 'Runtime authentication failed');
  async function rpc(method, args = {}) {
    const rpcId = randomUUID();
    const res = await fetch(`${origin}/api/${method}`, { method:'POST', redirect:'error', headers:{ cookie, origin, 'content-type':'application/json' }, body:JSON.stringify({type:'client-request', rpcId, method, payload:{args}}), signal:AbortSignal.timeout(15000) });
    requireValue(res.ok, `RPC unavailable: ${method}`);
    const data = await res.json();
    requireValue(data.rpcId === rpcId && data.result?.ok === true, `RPC refused: ${method}`);
    return data.result.value;
  }
  requireValue(process.argv[2] === 'inspect', 'Unknown test mode');
  const roster = await rpc('agentPresets/list');
  const catalog = await rpc('session/modelCatalog');
  const sessions = await rpc('session/list', {_request:{}});
  requireValue(Array.isArray(sessions.items) && Array.isArray(roster.presets), 'Runtime catalog shape changed');
  const running = sessions.items.filter(row => row.running === true);
  const unknown = sessions.items.filter(row => typeof row.running !== 'boolean');
  const report = {
    kind:'read-only-live-runtime', modelCalled:false,
    presets:roster.presets.map(p=>({id:p.id,name:p.name})),
    presetDefault:roster.default ?? roster.defaultPreset ?? null,
    modelDefault:catalog.default ? {provider:catalog.default.provider,model:catalog.default.model} : null,
    sessionCount:sessions.items.length, runningCount:running.length, unknownRunningCount:unknown.length,
    quiescent:running.length === 0 && unknown.length === 0,
  };
  fs.writeFileSync(new URL('./test-evidence/cowork/runtime-inspection.json',import.meta.url), JSON.stringify(report,null,2)+'\n');
  console.log(JSON.stringify(report));
}
main().catch(error=>{console.error(error.message?.startsWith('RPC ') || /^(Missing private|Runtime |Unknown test)/.test(error.message??'') ? error.message : 'Runtime inspection failed; no private diagnostics emitted');process.exitCode=1;});
