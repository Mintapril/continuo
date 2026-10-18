// Inert, verified snapshots. Symlinks are data ONLY: no follow, extraction, or restore API.
// Hardlinked bytes are copied to independent numbered objects; identity metadata is retained.
import fs from 'node:fs';
import path from 'node:path';
import {createHash} from 'node:crypto';
const must=(ok,code)=>{if(!ok)throw Error(code);};
const same=(a,b)=>JSON.stringify(a)===JSON.stringify(b);
const flags=fs.constants.O_RDONLY|fs.constants.O_NOFOLLOW|fs.constants.O_NONBLOCK;
const metadata=(s)=>({kind:s.isSymbolicLink()?'symlink':s.isDirectory()?'directory':s.isFile()?'file':'special',size:s.size,mode:s.mode,uid:s.uid,gid:s.gid,mtimeMs:s.mtimeMs,ctimeMs:s.ctimeMs,dev:s.dev,ino:s.ino,nlink:s.nlink});
function bytes(file,expected,sink){
  const fd=fs.openSync(file,flags);try{
    must(same(metadata(fs.fstatSync(fd)),expected),'BACKUP_FILE_CHANGED');
    const hash=createHash('sha256'),buf=Buffer.alloc(1024*1024);let count=0,n;
    while((n=fs.readSync(fd,buf))>0){hash.update(buf.subarray(0,n));count+=n;sink?.(buf.subarray(0,n));}
    must(count===expected.size&&same(metadata(fs.fstatSync(fd)),expected),'BACKUP_FILE_CHANGED');
    return hash.digest('hex');
  }finally{fs.closeSync(fd);}
}
export function inventorySnapshot(source){
  const root=fs.lstatSync(source);must(!root.isSymbolicLink(),'BACKUP_ROOT_LINK');
  const result=[];
  function visit(file,relative,depth){
    must(depth<=128&&result.length<100000,'BACKUP_TREE_LIMIT');
    const s=fs.lstatSync(file),row={relative,...metadata(s)};
    must(s.dev===root.dev&&row.kind!=='special','BACKUP_UNSAFE_TREE');
    if(row.kind==='symlink')row.link=fs.readlinkSync(file,{encoding:'buffer'}).toString('base64');
    if(row.kind==='file')row.sha256=bytes(file,metadata(s));
    result.push(row);
    if(row.kind==='directory')for(const name of fs.readdirSync(file).sort())visit(path.join(file,name),relative?relative+'/'+name:name,depth+1);
  }
  visit(source,'',0);return result;
}
function sync(dir){if(process.platform==='win32')return;const fd=fs.openSync(dir,fs.constants.O_RDONLY);try{fs.fsyncSync(fd);}finally{fs.closeSync(fd);}}
function writeNew(file,value){const fd=fs.openSync(file,fs.constants.O_WRONLY|fs.constants.O_CREAT|fs.constants.O_EXCL|fs.constants.O_NOFOLLOW,0o600);try{fs.writeFileSync(fd,value);fs.fsyncSync(fd);}finally{fs.closeSync(fd);}}
export function backupSnapshot(source,target){
  must(path.isAbsolute(source)&&path.isAbsolute(target)&&source!==target&&!target.startsWith(source+path.sep),'BACKUP_PATH');
  const before=inventorySnapshot(source);
  // Target parent belongs to caller's already validated root-private backup directory.
  fs.mkdirSync(target,{mode:0o700});fs.mkdirSync(path.join(target,'objects'),{mode:0o700});
  const manifest={format:'cowork-inert-snapshot-v1',source,complete:false,entries:before.map(x=>({...x}))};
  for(let i=0;i<manifest.entries.length;i++){
    const entry=manifest.entries[i];if(entry.kind!=='file')continue;
    entry.object=`objects/${String(i).padStart(6,'0')}`;
    const dst=path.join(target,entry.object),src=entry.relative?path.join(source,entry.relative):source;
    const fd=fs.openSync(dst,fs.constants.O_WRONLY|fs.constants.O_CREAT|fs.constants.O_EXCL|fs.constants.O_NOFOLLOW,0o600);
    try{
      const {relative,sha256,object,...expected}=entry;
      const hash=bytes(src,expected,data=>{let pos=0;while(pos<data.length)pos+=fs.writeSync(fd,data,pos,data.length-pos);});
      fs.fsyncSync(fd);must(hash===sha256,'BACKUP_HASH_CHANGED');
    }finally{fs.closeSync(fd);}
    const ds=fs.lstatSync(dst);must(ds.isFile()&&ds.nlink===1&&ds.size===entry.size,'BACKUP_OBJECT_SHAPE');
    must(bytes(dst,metadata(ds))===entry.sha256,'BACKUP_COPY_VERIFY');
  }
  must(same(inventorySnapshot(source),before),'BACKUP_SOURCE_CHANGED');
  manifest.complete=true;writeNew(path.join(target,'snapshot.json'),Buffer.from(JSON.stringify(manifest,null,2)+'\n'));
  sync(path.join(target,'objects'));sync(target);
  return {format:manifest.format,complete:true,entries:before.length,files:before.filter(x=>x.kind==='file').length,symlinks:before.filter(x=>x.kind==='symlink').length};
}
