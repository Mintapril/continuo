import test from 'node:test';
import assert from 'node:assert/strict';
import { BoundedFiles, pathParts, fileTools } from '../src/bounded-files.mjs';
import { pinnedFetch, publicIPv4, publicUrl, webTools } from '../src/web.mjs';
import { EventEmitter } from 'node:events';
const signal = new AbortController().signal;
const flags = { O_RDONLY:0, O_WRONLY:1, O_CREAT:64, O_EXCL:128, O_NOFOLLOW:131072, O_DIRECTORY:65536, O_NONBLOCK:2048 };
function fileFixture({ linked = false, special = false, many = false } = {}) {
  const calls = [], closed = []; let fd = 10;
  const io = {
    async open(path, flag, mode) {
      calls.push({path, flag, mode}); const id = fd++;
      return { fd:id, async close() { closed.push(id); }, async stat() { return { isDirectory:()=>true, isFile:()=>!special, nlink:linked ? 2 : 1, size:5 }; },
        async read(buffer) { buffer.write('hello'); return { bytesRead:5 }; }, async writeFile() {}, async sync() {} };
    },
    async *opendir() { for (const name of many ? Array(201).fill('a.txt') : ['note.txt', '.secret', 'symlink']) yield { name, isSymbolicLink:()=>name==='symlink', isFile:()=>true, isDirectory:()=>false }; },
  };
  return { files:new BoundedFiles({ io, platform:'linux', flags }), calls, closed };
}
test('file lexical boundary denies traversal, config, code, drive, ADS and sibling paths', () => {
  for (const p of ['../secret', '/etc/passwd', '/workspace/cowork-other/a', '.dsh/config', 'a//b', 'a/../b', 'C:\\a', 'a:stream', 'a.py', 'package.json', 'a/.secret']) assert.throws(()=>pathParts(p), p);
  assert.deepEqual(pathParts('/workspace/cowork/notes/你好.txt'), ['notes','你好.txt']);
});
test('read pins every ancestor and opens final file nofollow/nonblocking', async () => {
  const { files,calls,closed } = fileFixture();
  assert.equal((await files.read({path:'notes/a.txt'},signal)).text,'hello');
  assert.deepEqual(calls.map(c=>c.path), ['/', '/proc/self/fd/10/workspace', '/proc/self/fd/11/cowork', '/proc/self/fd/12/notes', '/proc/self/fd/13/a.txt']);
  assert.ok(calls.every(c=>c.flag & flags.O_NOFOLLOW)); assert.equal(closed.length,5);
});
test('write create-only flags and size cap; no overwrite primitive', async () => {
  const { files,calls } = fileFixture();
  assert.deepEqual(await files.write({path:'a.txt',text:'hello'},signal), {created:true,bytesWritten:5});
  assert.ok(calls.at(-1).flag & flags.O_EXCL); assert.equal(calls.at(-1).mode,0o600);
  await assert.rejects(files.write({path:'b.txt',text:'x'.repeat(65537)},signal));
  await assert.rejects(fileTools(files)[1].execute({path:'a.txt',text:'a',overwrite:true},{signal}));
});
test('list caps count and filters symlinks/hidden paths', async () => {
  assert.deepEqual(await fileFixture().files.list({},signal), {entries:[{name:'note.txt',kind:'file'}],truncated:false});
  const list = await fileFixture({many:true}).files.list({},signal); assert.equal(list.entries.length,200); assert.equal(list.truncated,true);
});
test('hardlinks and special files denied, Windows fails closed', async () => {
  await assert.rejects(fileFixture({linked:true}).files.read({path:'a.txt'},signal));
  await assert.rejects(fileFixture({special:true}).files.read({path:'a.txt'},signal));
  await assert.rejects(new BoundedFiles({platform:'win32'}).read({path:'a.txt'},signal), /require Linux/);
});
test('web target checks reject private/local/numeric/auth/port variants', () => {
  for (const u of ['http://127.0.0.1:3091/v1/tool','http://2130706433','http://localhost/','http://foo.local','http://user:pass@example.com','file:///etc/passwd','https://example.com:3091','http://[::1]']) assert.throws(()=>publicUrl(u),u);
  for (const a of ['127.0.0.1','10.0.0.1','169.254.169.254','100.64.0.1','172.16.0.1','192.168.0.1','::1','203.0.113.4']) assert.equal(publicIPv4(a),false,a);
  assert.equal(publicIPv4('93.184.216.34'),true);
});
test('DNS private/mixed results rejected before HTTP dispatch', async () => {
  let sent=0;
  await assert.rejects(pinnedFetch('https://example.com',signal,{lookup:async()=>[{address:'93.184.216.34'},{address:'127.0.0.1'}],request:()=>sent++})); assert.equal(sent,0);
});
function responseRequest({redirect}={}) {
  return (url,options,callback) => {
    const req=new EventEmitter();
    req.end=()=>queueMicrotask(()=> {
      options.lookup(url.hostname,{},(err,address,family)=>{ assert.equal(address,'93.184.216.34'); assert.equal(family,4); });
      const res=new EventEmitter(); res.resume=()=>{}; res.destroy=()=>{};
      res.statusCode=redirect ? 302 : 200; res.headers=redirect ? {location:redirect} : {'content-type':'text/html'}; callback(res);
      if (!redirect) {res.emit('data',Buffer.from('<p>Hello</p><script>untrusted()</script>'));res.emit('end');}
    }); return req;
  };
}
test('web fetch pins DNS, strips scripts and rejects private redirect', async () => {
  const deps={lookup:async()=>[{address:'93.184.216.34'}],request:responseRequest()};
  const value=await pinnedFetch('https://example.com',signal,deps); assert.match(value.text,/Hello/); assert.doesNotMatch(value.text,/untrusted\(\)/);
  await assert.rejects(pinnedFetch('https://example.com',signal,{...deps,request:responseRequest({redirect:'http://127.0.0.1'})}));
});
test('web search bounded independently; no tool dispatcher', async () => {
  const calls=[]; const [search]=webTools({search:async(q)=>{calls.push(q);return {content:'result',sources:[]};}});
  const result=await search.execute({queries:['one','two']},{signal}); assert.equal(result.results.length,2);assert.equal(calls[0].maxResults,8);
  for(const queries of [[],Array(5).fill('q'),['x'.repeat(1025)],[' ']]) await assert.rejects(search.execute({queries},{signal}));
});
