import fs from 'node:fs/promises';
import { constants } from 'node:fs';
import { check, object, str, int, bool, tool, integer } from './validation.mjs';
export const WORKSPACE_ROOT = '/workspace/cowork';
export const MAX_FILE_BYTES = 64 * 1024;
const forbidden = /^(?:node_modules|__proto__|prototype|constructor|cordis\.patch\.ya?ml|package(?:-lock)?\.json|pnpm-lock\.yaml|credentials?(?:\..*)?|.*\.(?:mjs|cjs|js|ts|py|sh|bat|cmd|ps1|exe|dll|so|apk))$/i;
export function pathParts(input, allowRoot = false) {
  check(typeof input === 'string' && input.length <= 1024 && !/[\\\x00-\x1f:]/.test(input), 'Use a bounded POSIX path inside /workspace/cowork');
  let relative = input;
  if (relative.startsWith('/')) {
    check(relative === WORKSPACE_ROOT || relative.startsWith(WORKSPACE_ROOT + '/'), 'Path is outside /workspace/cowork', 'PATH_DENIED');
    relative = relative.slice(WORKSPACE_ROOT.length).replace(/^\//, '');
  }
  if (relative === '' || relative === '.') { check(allowRoot, 'A file path is required'); return []; }
  const parts = relative.split('/');
  check(parts.length <= 16 && parts.every(p => p && p !== '.' && p !== '..' && !p.startsWith('.') && !/[. ]$/.test(p) && !forbidden.test(p) && /^[\p{L}\p{N}_ ()+.,@-]{1,120}$/u.test(p)), 'Traversal, hidden/config/executable paths and special filenames are denied', 'PATH_DENIED');
  return parts;
}
// Linux/Android only: directory fds pin every ancestor. No check-then-open path race.
export class BoundedFiles {
  constructor({ io = fs, platform = process.platform, flags = constants } = {}) { this.io = io; this.platform = platform; this.flags = flags; }
  async withParent(parts, action) {
    check(['linux', 'android'].includes(this.platform), 'Bounded file tools require Linux /proc/self/fd support', 'FILES_UNSUPPORTED_RUNTIME');
    const c = this.flags;
    check(typeof c.O_NOFOLLOW === 'number' && typeof c.O_DIRECTORY === 'number', 'Missing no-follow support', 'FILES_UNSUPPORTED_RUNTIME');
    const handles = [];
    try {
      let current = await this.io.open('/', c.O_RDONLY | c.O_DIRECTORY | c.O_NOFOLLOW); handles.push(current);
      for (const part of ['workspace', 'cowork', ...parts]) {
        current = await this.io.open(`/proc/self/fd/${current.fd}/${part}`, c.O_RDONLY | c.O_DIRECTORY | c.O_NOFOLLOW); handles.push(current);
        check((await current.stat()).isDirectory(), 'Not a directory', 'PATH_DENIED');
      }
      return await action(`/proc/self/fd/${current.fd}`);
    } finally { await Promise.all(handles.reverse().map(h => h.close())); }
  }
  async read(args, signal) {
    const parts = pathParts(args.path), name = parts.pop(), c = this.flags;
    const offset = args.offset ?? 0, maxBytes = args.maxBytes ?? MAX_FILE_BYTES;
    integer(offset, 0, 16 * 1024 * 1024, 'offset'); integer(maxBytes, 1, MAX_FILE_BYTES, 'maxBytes');
    return this.withParent(parts, async parent => {
      const handle = await this.io.open(`${parent}/${name}`, c.O_RDONLY | c.O_NOFOLLOW | c.O_NONBLOCK);
      try {
        const stat = await handle.stat();
        check(stat.isFile() && stat.nlink === 1, 'Only single-link regular files may be read', 'PATH_DENIED');
        signal?.throwIfAborted();
        const buffer = Buffer.alloc(maxBytes), { bytesRead } = await handle.read(buffer, 0, maxBytes, offset);
        const text = new TextDecoder('utf-8', { fatal: true }).decode(buffer.subarray(0, bytesRead));
        check(!text.includes('\0'), 'Binary files are not supported');
        return { text, offset, bytesRead, truncated: offset + bytesRead < stat.size };
      } finally { await handle.close(); }
    });
  }
  async write(args, signal) {
    const parts = pathParts(args.path), name = parts.pop(), c = this.flags;
    check(typeof args.text === 'string' && !args.text.includes('\0') && Buffer.byteLength(args.text) <= MAX_FILE_BYTES, 'text must be UTF-8 text of at most 65536 bytes');
    return this.withParent(parts, async parent => {
      signal?.throwIfAborted();
      const handle = await this.io.open(`${parent}/${name}`, c.O_WRONLY | c.O_CREAT | c.O_EXCL | c.O_NOFOLLOW, 0o600);
      try { await handle.writeFile(args.text, { encoding: 'utf8', signal }); await handle.sync(); }
      finally { await handle.close(); }
      return { created: true, bytesWritten: Buffer.byteLength(args.text) };
    });
  }
  async list(args, signal) {
    const parts = pathParts(args.path ?? '', true);
    return this.withParent(parts, async parent => {
      const entries = []; let truncated = false, scanned = 0;
      const dir = await this.io.opendir(parent);
      for await (const entry of dir) {
        signal?.throwIfAborted();
        if (++scanned > 1000 || entries.length === 200) { truncated = true; break; }
        try { pathParts(entry.name); } catch { continue; }
        if (entry.isSymbolicLink()) continue;
        if (entry.isFile() || entry.isDirectory()) entries.push({ name: entry.name, kind: entry.isDirectory() ? 'directory' : 'file' });
      }
      return { entries, truncated };
    });
  }
}
export function fileTools(files = new BoundedFiles()) {
  return [
    tool('cowork.files.read', 'Read bounded UTF-8 text inside /workspace/cowork. No links, hidden files or traversal.', object({ path: str, offset: int, maxBytes: int }, ['path']), (a, e) => files.read(a, e.signal), object({ text: str, offset: int, bytesRead: int, truncated: bool })),
    tool('cowork.files.write', 'Create a NEW UTF-8 text file inside /workspace/cowork, at most 65536 bytes. Never overwrite existing files.', object({ path: str, text: str }), (a, e) => files.write(a, e.signal), object({ created: { type: 'boolean', const: true }, bytesWritten: int })),
    tool('cowork.files.list', 'List at most 200 visible files/directories inside /workspace/cowork. Does not recurse.', object({ path: str }, []), (a, e) => files.list(a, e.signal), object({ entries: { type: 'array', items: object({ name: str, kind: { type: 'string', enum: ['file', 'directory'] } }) }, truncated: bool })),
  ];
}
