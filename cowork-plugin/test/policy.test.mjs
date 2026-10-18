import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { ALLOWED_TOOLS, guardTool, assertPublicWebUrl } from '../src/policy.mjs';
import { createScopedFiles, relativeParts } from '../src/files.mjs';

const agent = { id: 'test-session' };
test('guard denies all non-allowlisted capabilities including late registrations and escapes', () => {
  for (const name of ['pwsh', 'bash', 'shell', 'run_code', 'eval', 'subagent', 'subagent_fork', 'workflow', 'skill', 'load_workspace_dependencies', 'read', 'write', 'edit', 'glob', 'grep', 'present', 'web_search_ex', 'mcp__android__shell', 'read_mcp_resource', 'phone_shell', 'phone_intent']) {
    assert.match(guardTool({ name, agent, arguments: {} }), /denies capability/);
  }
  for (const name of ALLOWED_TOOLS.filter(n => !n.startsWith('web_'))) assert.equal(guardTool({ name, agent, arguments: {} }), undefined);
  assert.match(guardTool({ name: 'phone_status', arguments: {} }), /bound session/);
});
test('web guards require bounded search and public credential-free DNS URLs', () => {
  assert.equal(guardTool({ name: 'web_search', agent, arguments: { queries: ['weather'] } }), undefined);
  for (const args of [{ queries: [] }, { queries: ['x', 'x', 'x', 'x', 'x'] }, { queries: ['x'], command: 'whoami' }, { queries: [null] }]) assert.match(guardTool({ name: 'web_search', agent, arguments: args }), /policy/);
  assert.equal(assertPublicWebUrl('https://example.com/docs').hostname, 'example.com');
  for (const url of ['http://127.0.0.1:3091/v1/tool', 'http://2130706433/', 'http://0x7f000001/', 'http://[::1]/', 'http://10.0.0.1/', 'http://localhost./', 'http://foo.local/', 'http://internal/', 'file:///etc/passwd', 'https://user:pass@example.com/', 'https://example.com:3091/']) assert.throws(() => assertPublicWebUrl(url));
  assert.match(guardTool({ name: 'web_fetch', agent, arguments: { url: 'https://example.com', headers: { Authorization: 'x' } } }), /Unexpected/);
});
test('relative path boundary rejects traversal, Windows forms, hidden/config/code paths', () => {
  assert.deepEqual(relativeParts('notes/今天.md'), ['notes', '今天.md']);
  for (const input of ['../x', '/tmp/x', 'C:/x', 'a\\b', 'a//b', '.dsh/token', 'foo/.env', 'a/..', 'notes/x.ps1', 'node_modules/x', 'package.json', 'cordis.patch.yml', 'foo.', 'con.txt', 'x\0y']) assert.throws(() => relativeParts(input), input);
});

test('scoped file service enforces caps, no default overwrite, UTF-8, links and special roots', async t => {
  // Every test artifact stays inside the delegated plugin directory.
  const testRoot = fileURLToPath(new URL('./.tmp/', import.meta.url));
  await fs.mkdir(testRoot, { recursive: true });
  const dir = await fs.mkdtemp(path.join(testRoot, 'files-'));
  t.after(async () => {
    assert.ok(path.resolve(dir).startsWith(path.resolve(testRoot) + path.sep));
    await fs.rm(dir, { recursive: true, force: true });
  });
  const files = await createScopedFiles(dir);
  await files.write({ path: 'notes.md', content: 'first\n第二行' });
  assert.deepEqual(await files.read({ path: 'notes.md', offset: 2 }), { path: 'notes.md', offset: 2, totalLines: 2, content: '第二行' });
  await assert.rejects(files.write({ path: 'notes.md', content: 'replacement' }), /EEXIST/);
  await files.write({ path: 'notes.md', content: 'replacement', overwrite: true });
  assert.equal((await files.read({ path: 'notes.md' })).content, 'replacement');
  for (const path of ['../secret', '.env', 'cordis.patch.yml', 'script.js']) await assert.rejects(files.write({ path, content: 'blocked' }));
  await assert.rejects(files.write({ path: 'large.md', content: '界'.repeat(100000) }), /limit/);
  await assert.rejects(files.write({ path: 'extra.md', content: 'x', shell: true }), /Unexpected/);
  await assert.rejects(files.read({ path: 'notes.md', limit: 9999 }), /Invalid/);
  await fs.writeFile(path.join(dir, 'bad.txt'), Buffer.from([255, 254, 253]));
  await assert.rejects(files.read({ path: 'bad.txt' }), /encoded data/);
  await fs.link(path.join(dir, 'notes.md'), path.join(dir, 'alias.md'));
  await assert.rejects(files.read({ path: 'alias.md' }), /Links/);
  await assert.rejects(files.write({ path: 'notes.md', content: 'no', overwrite: true }), /Links/);
  await fs.mkdir(path.join(dir, 'real'));
  await fs.symlink(path.join(dir, 'real'), path.join(dir, 'linked'), process.platform === 'win32' ? 'junction' : 'dir');
  await assert.rejects(files.write({ path: 'linked/escape.md', content: 'no' }), /Links/);
  const listing = await files.list({});
  assert.ok(!listing.entries.some(e => e.name === 'linked'));
  await assert.rejects(createScopedFiles(path.parse(dir).root), /Unsafe/);
});
