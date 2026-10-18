import fs from 'node:fs/promises';
import { constants } from 'node:fs';
import path from 'node:path';
import { assertRecord, text, integer } from './policy.mjs';

const MAX_FILE_BYTES = 256 * 1024;
const forbidden = /^(?:node_modules|__proto__|prototype|constructor|cordis\.patch\.ya?ml|package(?:-lock)?\.json|pnpm-lock\.yaml|credentials?(?:\..*)?|.*\.(?:mjs|cjs|js|ts|py|sh|bat|cmd|ps1|exe|dll|so|apk))$/i;
export function relativeParts(value, allowRoot = false) {
  text(value, 'relative path', 512);
  if (allowRoot && value === '.') return [];
  if (path.isAbsolute(value) || /[\\:\x00-\x1f]/.test(value)) throw new Error('Path must be relative and use forward slashes');
  const parts = value.split('/');
  if (parts.some(p => !p || p.startsWith('.') || /[. ]$/.test(p) || forbidden.test(p) || /^(?:con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)/i.test(p))) throw new Error('Hidden, traversal, executable and configuration paths are denied');
  return parts;
}

/** A private, dedicated document directory, never the profile or credential directory. */
export async function createScopedFiles(root) {
  if (!root || !path.isAbsolute(root)) throw new Error('COWORK_FILES_ROOT must name an existing absolute private document directory');
  const resolved = path.resolve(root);
  const stat = await fs.lstat(resolved);
  if (!stat.isDirectory() || stat.isSymbolicLink()) throw new Error('Document root must be a real directory');
  const canonical = await fs.realpath(resolved);
  if (canonical !== resolved) throw new Error('Document root cannot contain symlink ancestors');
  if (canonical === path.parse(canonical).root || /(?:^|[\\/])(?:\.dsh|profiles|node_modules|proc|sys|dev)(?:[\\/]|$)/i.test(canonical) || path.basename(canonical) === 'cowork-plugin') throw new Error('Unsafe document root');
  async function locate(relative, mayCreate = false) {
    const parts = relativeParts(relative);
    let target = canonical;
    for (let i = 0; i < parts.length; i++) {
      target = path.join(target, parts[i]);
      let s;
      try { s = await fs.lstat(target); }
      catch (error) { if (error.code === 'ENOENT' && mayCreate && i === parts.length - 1) return target; throw error; }
      if (s.isSymbolicLink() || (!s.isDirectory() && (!s.isFile() || s.nlink !== 1))) throw new Error('Links and special files are denied');
      if (i < parts.length - 1 && !s.isDirectory()) throw new Error('Not a directory');
    }
    return target;
  }
  async function openFile(target, flags) {
    const handle = await fs.open(target, flags | (constants.O_NOFOLLOW ?? 0), 0o600);
    try {
      const s = await handle.stat();
      if (!s.isFile() || s.nlink !== 1 || s.size > MAX_FILE_BYTES) throw new Error('Expected a single-link bounded regular file');
      return handle;
    } catch (error) { await handle.close(); throw error; }
  }
  return {
    async read(args) {
      assertRecord(args, ['path', 'offset', 'limit'], ['path']);
      const offset = args.offset ?? 1, limit = args.limit ?? 200;
      integer(offset, 'offset', 1, 100000); integer(limit, 'limit', 1, 2000);
      const target = await locate(args.path);
      const handle = await openFile(target, constants.O_RDONLY);
      try {
        // Read at most cap + 1 even if another writer grows the file after stat.
        const buffer = Buffer.alloc(MAX_FILE_BYTES + 1);
        const { bytesRead } = await handle.read(buffer, 0, buffer.length, 0);
        if (bytesRead > MAX_FILE_BYTES) throw new Error('File exceeds size limit');
        const content = new TextDecoder('utf-8', { fatal: true }).decode(buffer.subarray(0, bytesRead));
        const lines = content.split(/\r?\n/);
        return { path: args.path, offset, totalLines: lines.length, content: lines.slice(offset - 1, offset - 1 + limit).join('\n') };
      } finally { await handle.close(); }
    },
    async write(args) {
      assertRecord(args, ['path', 'content', 'overwrite'], ['path', 'content']);
      text(args.content, 'content', MAX_FILE_BYTES, 0);
      if (Buffer.byteLength(args.content) > MAX_FILE_BYTES) throw new Error('Content exceeds size limit');
      if (args.overwrite !== undefined && typeof args.overwrite !== 'boolean') throw new Error('overwrite must be boolean');
      const target = await locate(args.path, true);
      // No truncate-before-validation: open existing files, verify inode/link count, then truncate.
      const flags = constants.O_WRONLY | constants.O_CREAT | (args.overwrite ? 0 : constants.O_EXCL);
      const handle = await openFile(target, flags);
      try { await handle.truncate(0); await handle.writeFile(args.content, 'utf8'); }
      finally { await handle.close(); }
      return { path: args.path, bytes: Buffer.byteLength(args.content) };
    },
    async list(args) {
      assertRecord(args, ['path'], []);
      const relative = args.path ?? '.';
      const target = relativeParts(relative, true).length ? await locate(relative) : canonical;
      const items = await fs.readdir(target, { withFileTypes: true });
      return { path: relative, entries: items.filter(i => {
        try { relativeParts(i.name); return !i.isSymbolicLink() && (i.isFile() || i.isDirectory()); } catch { return false; }
      }).slice(0, 200).map(i => ({ name: i.name, kind: i.isDirectory() ? 'directory' : 'file' })), truncated: items.length > 200 };
    },
  };
}
