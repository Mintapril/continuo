// Fixed read-only metadata precheck. Never opens private regular-file contents or follows links.
import fs from 'node:fs';
import path from 'node:path';
const root='/root/.dsh',counts={directories:0,regularFiles:0,symlinks:0,hardlinkedFiles:0,special:0,mountCrossings:0,bytes:0};
try {
  if(process.platform!=='linux'||process.arch!=='arm64')throw Error();
  const dev=fs.lstatSync(root).dev;
  function visit(file){
    const s=fs.lstatSync(file);
    if(s.dev!==dev){counts.mountCrossings++;return;}
    if(s.isSymbolicLink()){counts.symlinks++;return;}
    if(s.isDirectory()){counts.directories++;for(const name of fs.readdirSync(file))visit(path.join(file,name));}
    else if(s.isFile()){counts.regularFiles++;counts.bytes+=s.size;if(s.nlink!==1)counts.hardlinkedFiles++;}
    else counts.special++;
  }
  visit(root);console.log(JSON.stringify({kind:'backup-metadata-only',...counts}));
}catch{console.error('Backup metadata unavailable');process.exitCode=1;}
