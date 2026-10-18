import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import {backupSnapshot,inventorySnapshot} from './cowork-private-backup.mjs';
function fixture(run){const base=fs.mkdtempSync(path.join(os.tmpdir(),'cowork-inert-backup-test-'));try{run(base);}finally{const absolute=path.resolve(base);assert.equal(path.dirname(absolute),path.resolve(os.tmpdir()));assert.ok(path.basename(absolute).startsWith('cowork-inert-backup-test-'));fs.rmSync(absolute,{recursive:true});}}
test('inert snapshot copies Unicode bytes and metadata with verified hashes',()=>fixture(base=>{
  const src=path.join(base,'source'),dst=path.join(base,'snapshot');fs.mkdirSync(src);fs.mkdirSync(path.join(src,'nested'));fs.writeFileSync(path.join(src,'nested','中文.txt'),'Fixture 中文\n');
  const result=backupSnapshot(src,dst);assert.equal(result.complete,true);assert.equal(result.files,1);
  const manifest=JSON.parse(fs.readFileSync(path.join(dst,'snapshot.json'))),file=manifest.entries.find(x=>x.kind==='file');
  assert.equal(file.relative,'nested/中文.txt');assert.equal(fs.readFileSync(path.join(dst,file.object),'utf8'),'Fixture 中文\n');assert.ok(file.ino);assert.ok(file.mode);assert.equal(fs.existsSync(path.join(dst,'nested')),false);
}));
test('hardlinks copied independently with relationship metadata, no source mutation',()=>fixture(base=>{
  const src=path.join(base,'source'),dst=path.join(base,'snapshot');fs.mkdirSync(src);fs.writeFileSync(path.join(src,'a'),'linked bytes');fs.linkSync(path.join(src,'a'),path.join(src,'b'));
  const before=inventorySnapshot(src);backupSnapshot(src,dst);assert.deepEqual(inventorySnapshot(src),before);
  const files=JSON.parse(fs.readFileSync(path.join(dst,'snapshot.json'))).entries.filter(x=>x.kind==='file');assert.equal(files.length,2);assert.equal(files[0].ino,files[1].ino);assert.equal(files[0].nlink,2);assert.notEqual(fs.statSync(path.join(dst,files[0].object)).ino,fs.statSync(path.join(dst,files[1].object)).ino);
}));
test('directory symlinks remain inert metadata and outside bytes never copied',()=>fixture(base=>{
  const src=path.join(base,'source'),dst=path.join(base,'snapshot'),outside=path.join(base,'outside');fs.mkdirSync(src);fs.mkdirSync(outside);fs.writeFileSync(path.join(outside,'private'),'DO NOT COPY');fs.symlinkSync(outside,path.join(src,'link'),process.platform==='win32'?'junction':'dir');
  const result=backupSnapshot(src,dst);assert.equal(result.files,0);assert.equal(result.symlinks,1);assert.deepEqual(fs.readdirSync(path.join(dst,'objects')),[]);
  const link=JSON.parse(fs.readFileSync(path.join(dst,'snapshot.json'))).entries.find(x=>x.kind==='symlink');assert.ok(Buffer.from(link.link,'base64').length);assert.equal(fs.existsSync(path.join(dst,'link')),false);
}));
test('source root symlink and target inside source rejected',()=>fixture(base=>{
  const src=path.join(base,'source');fs.mkdirSync(src);fs.symlinkSync(src,path.join(base,'alias'),process.platform==='win32'?'junction':'dir');
  assert.throws(()=>backupSnapshot(path.join(base,'alias'),path.join(base,'snapshot')),/BACKUP_ROOT_LINK/);assert.throws(()=>backupSnapshot(src,path.join(src,'snapshot')),/BACKUP_PATH/);
}));
test('existing target never overwritten',()=>fixture(base=>{
  const src=path.join(base,'source'),dst=path.join(base,'snapshot');fs.mkdirSync(src);fs.mkdirSync(dst);fs.writeFileSync(path.join(dst,'keep'),'KEEP');assert.throws(()=>backupSnapshot(src,dst));assert.equal(fs.readFileSync(path.join(dst,'keep'),'utf8'),'KEEP');
}));
