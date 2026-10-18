#!/usr/bin/env node
/**
 * Offline-only additive installer, prepared on PC; run ONLY through deploy-cowork-plugin.py.
 * Fixed root-private staging: /root/.dsh-cowork-install-v2. No arbitrary path flags.
 * No npm, plugin activation, provider call, service start/stop, deletion or restore.
 *
 * RC2 source evidence (research/preflight-api/.../dsh-app-boot/lib/index.js):
 * 901-905 installation-first bundle lookup; 920-955 bundles load patches;
 * 677-718 runtime dependencies supply installation resolution;
 * 771-774 profile dependencies need a profile-local package;
 * 816-819 deletes bundle identities from profile resolution. Therefore an /opt-only
 * package needs ONE additive runtime dependency, AND ONE web-profile bundle append.
 * Adding only profile.dependencies would not make its own bare import resolvable.
 * Every cordis patch remains byte-exact. Standard/Code/providers stay unchanged.
 *
 * Safety boundary: an operator MUST know sessions are idle and maintain an exclusive
 * no-restart maintenance window. native-service.sh has no shared transaction lock.
 * Repeated /proc + port checks reduce, but cannot eliminate, concurrent-root races.
 * Backups record nested symlinks as inert metadata, never follow or extract them.
 * Hardlinked bytes get independent verified snapshot objects plus link identity.
 * GNU PAX archives additionally preserve ACL/xattrs; no restore/extract API exists.
 * Special files and mount crossings refuse before any installation changes.
 * Any failure retains staging, lock, partial backup and journal for manual inspection.
 *
 * Safe inverse (MANUAL, stopped/quiescent, freshly backed up current state): remove
 * only the exact added bundle and exact runtime dependency if still equal to journal;
 * preserve all other fields/current user data. Never restore historic .dsh wholesale.
 * Leave package/config/workspace/backups in place by default. Remove newly-created
 * artifacts only after explicit ownership+hash+non-use review; never delete a now-used
 * workspace. No rollback command is supplied. No automatic cleanup is performed.
 */
import fs from 'node:fs';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { createRequire } from 'node:module';
import { pathToFileURL, fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import {backupSnapshot, inventorySnapshot} from './cowork-private-backup.mjs';

export const PACKAGE_FILES = Object.freeze([
  'package.json', 'src/auth.mjs', 'src/task-lifecycle.mjs', 'src/restricted.mjs',
  'src/validation.mjs', 'src/broker.mjs', 'src/bounded-files.mjs', 'src/web.mjs',
  'src/aux-summary.mjs', 'src/summary-transport.mjs', 'cordis.patch.yml', 'README.md', 'PROTOCOL.md',
]);
export const TARGET_NAME = '@local/dsh-cowork-phone';
export const VERSION = '0.2.0-rc.2';
const STAGE = '/root/.dsh-cowork-install-v2';
const BACKUP = '/root/.dsh-cowork-backup-v1';
const HOME = '/root/.dsh';
const PROFILE = HOME + '/profiles/web';
const MANIFEST = PROFILE + '/package.json';
const RUNTIME = '/opt/dsh/node_modules/@deepseek-ai/dsh/package.json';
const TARGET = '/opt/dsh/node_modules/' + TARGET_NAME;
const CONFIG = '/root/.config/dsh/cowork-broker.json';
const WORKSPACE = '/workspace/cowork';
const PREFLIGHT_PIN = '37160f891828cf099b3ecf8a318a1ae3482f5e787d004b75ddce3e521a6e91cb';
const BASELINE_PIN = 'bbaa423a8806992968cc58b140d2b9a124e2e408591daba54b3a7122c25c65be';
// Verified against device bytes AND public npm release package/package.json.
const RUNTIME_PIN = '0a85203e3e907dd2e3bec34324a2494758ca6cdee234b07090a7c8df0b11acac';
const BOOT_PIN = '234db45e1b3f8c683b5bc1f551948b2a2ec52a6468c7b6937725a23f1c0e0d96';
const ENV = Object.freeze({ HOME: '/root', DSH_HOME: HOME, PATH: '/opt/node/bin:/usr/bin:/bin',
  DSH_TELEMETRY_DISABLED: '1', NODE_DISABLE_COMPILE_CACHE: '1' });
const object = v => v !== null && typeof v === 'object' && !Array.isArray(v);
export const digest = b => createHash('sha256').update(b).digest('hex');
const must = (v, code) => { if (!v) throw new Refusal(code); };
class Refusal extends Error { constructor(code) { super(code); this.code = code; } }
const equal = (a, b) => JSON.stringify(a) === JSON.stringify(b);

export function additiveManifests(profile, runtime, pkg) {
  must(object(profile) && object(runtime) && object(pkg), 'MANIFEST_SHAPE');
  must(pkg.name === TARGET_NAME && pkg.version === '0.1.0' && pkg.private === true, 'PACKAGE_ID');
  must(runtime.name === '@deepseek-ai/dsh' && runtime.version === VERSION && object(runtime.dependencies), 'RUNTIME_ID');
  must(Array.isArray(profile.dsh?.profile?.bundles) && profile.dsh.profile.bundles.length > 0, 'BUNDLES_SHAPE');
  for (const manifest of [profile, runtime]) {
    must(!manifest.dsh?.profile?.bundles?.includes(TARGET_NAME), 'BUNDLE_COLLISION');
    for (const field of ['dependencies', 'devDependencies', 'optionalDependencies', 'peerDependencies']) {
      must(manifest[field] === undefined || object(manifest[field]), 'DEPENDENCY_SHAPE');
      must(!Object.hasOwn(manifest[field] ?? {}, TARGET_NAME), 'DEPENDENCY_COLLISION');
    }
  }
  const nextProfile = structuredClone(profile), nextRuntime = structuredClone(runtime);
  nextProfile.dsh.profile.bundles.push(TARGET_NAME);
  // This exact local file spec is metadata only: no package manager is run.
  nextRuntime.dependencies[TARGET_NAME] = 'file:/opt/dsh/node_modules/@local/dsh-cowork-phone';
  return { profile: nextProfile, runtime: nextRuntime };
}

export function verifyBaseline(fresh, expected) {
  must(fresh?.status === 'passed-static-only' && expected?.status === 'passed-static-only', 'PREFLIGHT_REFUSED');
  must(fresh.collisions?.length === 0 && fresh.refusals?.length === 0, 'PREFLIGHT_REFUSED');
  for (const key of ['format', 'versions', 'packages', 'bundles', 'hashes', 'backupPaths', 'standardPreset', 'unverifiedModuleSpecifiers'])
    must(equal(fresh[key], expected[key]), 'PREFLIGHT_DRIFT');
  must(fresh.standardPreset?.configuredDefaultIsStandard === true, 'STANDARD_DEFAULT');
}

function stat(file, optional = false) {
  try { return fs.lstatSync(file); }
  catch (e) { if (optional && e.code === 'ENOENT') return null; throw e; }
}
function safeAncestors(file) {
  let current = '/';
  for (const part of file.split('/').filter(Boolean)) {
    current = path.join(current, part);
    const s = stat(current, true);
    if (!s) break;
    must(!s.isSymbolicLink() && s.uid === 0, 'UNSAFE_PATH');
    // Existing user-file permissions are preserved. Parents of writes must be protected.
    if (s.isDirectory()) must((s.mode & 0o022) === 0, 'WRITABLE_PARENT');
  }
}
function read(file, optional = false, immutable = false) {
  safeAncestors(file);
  const s = stat(file, optional);
  if (!s) return null;
  must(s.isFile() && (immutable || s.nlink === 1) && s.size <= 8 * 1024 * 1024, 'NON_REGULAR_FILE');
  const fd = fs.openSync(file, fs.constants.O_RDONLY | fs.constants.O_NOFOLLOW | fs.constants.O_NONBLOCK);
  const identity = x => [x.ino,x.dev,x.mode,x.uid,x.gid,x.nlink,x.size,x.mtimeMs,x.ctimeMs];
  try {
    must(equal(identity(fs.fstatSync(fd)),identity(s)), 'READ_RACE');
    const bytes = fs.readFileSync(fd);
    must(equal(identity(fs.fstatSync(fd)),identity(s)) && equal(identity(stat(file)),identity(s)), 'READ_RACE');
    return bytes;
  } finally { fs.closeSync(fd); }
}
function immutablePatch(file) {
  const resolved = fs.realpathSync(file);
  must(resolved.startsWith('/opt/dsh/') || resolved.startsWith(PROFILE + '/'), 'PATCH_OUTSIDE_VERIFIED_ROOTS');
  return read(resolved, false, true);
}
function absent(file) { safeAncestors(file); must(stat(file, true) === null, 'TARGET_COLLISION'); }
function mkdirNew(file) { absent(file); fs.mkdirSync(file, { mode: 0o700 }); }
function ensurePrivateDir(file) {
  safeAncestors(file);
  const s = stat(file, true);
  if (!s) return mkdirNew(file);
  must(s.isDirectory() && s.uid === 0 && (s.mode & 0o777) === 0o700, 'PRIVATE_DIRECTORY_REQUIRED');
}
function writeNew(file, bytes, mode = 0o600) {
  absent(file);
  const fd = fs.openSync(file, fs.constants.O_CREAT | fs.constants.O_EXCL | fs.constants.O_WRONLY | fs.constants.O_NOFOLLOW, mode);
  try { fs.writeFileSync(fd, bytes); fs.fsyncSync(fd); } finally { fs.closeSync(fd); }
}
function syncDir(dir) {
  const fd = fs.openSync(dir, fs.constants.O_RDONLY | fs.constants.O_DIRECTORY | fs.constants.O_NOFOLLOW);
  try { fs.fsyncSync(fd); } finally { fs.closeSync(fd); }
}
function json(file) { return JSON.parse(read(file).toString('utf8')); }
function serialize(value) { return Buffer.from(JSON.stringify(value, null, 2) + '\n'); }

// Scan the ENTIRE process table; the fixed PIDfile alone may be stale or absent.
// Process root identity is checked by dev+ino, not host/chroot-specific readlink text.
function quiescent() {
  const root = fs.statSync('/');
  const pids = fs.readdirSync('/proc').filter(v => /^\d+$/.test(v));
  must(pids.includes(String(process.pid)), 'PROC_UNAVAILABLE');
  for (const pid of pids) {
    if (+pid === process.pid) continue;
    try {
      const s = fs.statSync(`/proc/${pid}/root`);
      must(!(s.dev === root.dev && s.ino === root.ino), 'CHROOT_PROCESS_PRESENT');
      const cmd = fs.readFileSync(`/proc/${pid}/cmdline`).toString('utf8');
      must(!cmd.includes('/opt/dsh/') && !cmd.includes('/opt/start-web.sh'), 'DSH_PROCESS_PRESENT');
    } catch (e) { if (e.code !== 'ENOENT' && e.code !== 'ESRCH') throw e; }
  }
  for (const table of ['/proc/net/tcp', '/proc/net/tcp6']) {
    for (const row of fs.readFileSync(table, 'utf8').trim().split('\n').slice(1)) {
      const cols = row.trim().split(/\s+/);
      must(!(cols[1]?.endsWith(':0C08') && cols[3] === '0A'), 'WEB_PORT_LISTENING');
    }
  }
}
function runPreflight(expected) {
  const result = spawnSync('/opt/node/bin/node', ['--permission', '--allow-fs-read=/', '--no-warnings', STAGE + '/cowork-preflight.mjs'],
    { cwd: '/workspace', env: ENV, encoding: 'utf8', timeout: 120000, maxBuffer: 1024 * 1024, stdio: ['ignore', 'pipe', 'pipe'] });
  must(!result.error && result.status === 0, 'FRESH_PREFLIGHT_FAILED');
  let report; try { report = JSON.parse(result.stdout); } catch { throw new Refusal('FRESH_PREFLIGHT_FORMAT'); }
  verifyBaseline(report, expected);
  return report;
}

// Complete, verified, private GNU tar archive, never a selective sessions backup.
// Reject unsafe tree shapes BEFORE archiving; inventory+metadata stay ONLY on phone.
function inventory(file, relative = '', rootDev, result = []) {
  const s = stat(file);
  rootDev ??= s.dev;
  must(s.dev === rootDev && !s.isSymbolicLink() && (s.isDirectory() || (s.isFile() && s.nlink === 1)), 'BACKUP_UNSAFE_TREE');
  const row = { relative, directory: s.isDirectory(), size: s.size, mode: s.mode & 0o7777,
    uid: s.uid, gid: s.gid, mtimeMs: s.mtimeMs, ino: s.ino, dev: s.dev };
  if (s.isFile()) {
    const fd = fs.openSync(file, fs.constants.O_RDONLY | fs.constants.O_NOFOLLOW | fs.constants.O_NONBLOCK);
    try {
      const actual = fs.fstatSync(fd); must(actual.ino === s.ino && actual.dev === s.dev && actual.size === s.size, 'BACKUP_RACE');
      const hash = createHash('sha256'), buf = Buffer.alloc(1024 * 1024); let n;
      while ((n = fs.readSync(fd, buf)) > 0) hash.update(buf.subarray(0, n));
      row.sha256 = hash.digest('hex');
    } finally { fs.closeSync(fd); }
  }
  result.push(row);
  if (s.isDirectory()) for (const name of fs.readdirSync(file).sort()) inventory(path.join(file, name), relative ? `${relative}/${name}` : name, rootDev, result);
  return result;
}
function backupTree(source, target) {
  safeAncestors(source); absent(target);
  const before = inventorySnapshot(source);
  // Independent inert content snapshot verifies every regular byte without links.
  backupSnapshot(source, target + '.snapshot');
  // Ubuntu GNU tar preserves full original modes/owners/times plus ACL/xattrs;
  // no archive or file list is ever sent to PC. O_EXCL fd is inherited as fd 3.
  const fd = fs.openSync(target, fs.constants.O_RDWR | fs.constants.O_CREAT | fs.constants.O_EXCL | fs.constants.O_NOFOLLOW, 0o600);
  const metadata = ['--acls', '--xattrs', '--xattrs-include=*', '--numeric-owner'];
  try {
    const created = spawnSync('/usr/bin/tar', ['--format=pax', ...metadata, '--create', '--file=/proc/self/fd/3', '--directory=/', '--', source.slice(1)],
      { env: ENV, timeout: 600000, stdio: ['ignore', 'ignore', 'ignore', fd] });
    must(!created.error && created.status === 0, 'BACKUP_ARCHIVE_FAILED');
    fs.fsyncSync(fd);
    const compared = spawnSync('/usr/bin/tar', [...metadata, '--compare', '--file=' + target, '--directory=/'],
      { env: ENV, timeout: 600000, stdio: ['ignore', 'ignore', 'ignore'] });
    must(!compared.error && compared.status === 0, 'BACKUP_ARCHIVE_VERIFY_FAILED');
    must(equal(inventorySnapshot(source), before), 'BACKUP_SOURCE_CHANGED');
    must(fs.fstatSync(fd).size > 0, 'BACKUP_EMPTY');
  } finally { fs.closeSync(fd); }
  return before;
}

function assertPackage(stage, plan) {
  must(equal(Object.keys(plan.package).sort(), [...PACKAGE_FILES].sort()), 'PACKAGE_ALLOWLIST');
  const tree = inventory(stage);
  must(equal(tree.filter(x => !x.directory).map(x => x.relative).sort(), [...PACKAGE_FILES].sort()), 'PACKAGE_EXTRA_FILES');
  for (const file of PACKAGE_FILES) must(digest(read(stage + '/' + file)) === plan.package[file], 'PACKAGE_CHANGED');
  const pkg = json(stage + '/package.json');
  must(equal(pkg.files, PACKAGE_FILES.filter(x => x !== 'package.json')), 'PACKAGE_FILES_DECLARATION');
  must(pkg.exports?.['.'] === './src/restricted.mjs' && pkg.dsh?.bundle?.patch === './cordis.patch.yml', 'PACKAGE_EXPORTS');
  must(!pkg.dependencies && !pkg.optionalDependencies && !pkg.devDependencies, 'OFFLINE_PACKAGE_DEPENDENCIES');
  must(Object.keys(pkg.peerDependencies ?? {}).length === 5 && Object.values(pkg.peerDependencies).every(v => v === VERSION), 'PEER_VERSIONS');
  return pkg;
}
function assertPeers(pkg) {
  const req = createRequire(TARGET + '/package.json');
  for (const [name, version] of Object.entries(pkg.peerDependencies)) {
    const file = fs.realpathSync(req.resolve(name + '/package.json'));
    must(file.startsWith('/opt/dsh/'), 'PEER_OUTSIDE_RUNTIME');
    const p = JSON.parse(fs.readFileSync(file));
    must(p.name === name && p.version === version, 'PEER_MISMATCH');
  }
}

async function main() {
  must(process.platform === 'linux' && process.arch === 'arm64' && process.getuid?.() === 0, 'EXPECTED_PHONE_CHROOT');
  must(process.version === 'v24.21.0' && process.execPath === '/opt/node/bin/node', 'EXPECTED_NODE');
  must(process.argv.length === 3 && process.argv[2] === '--install' && process.execArgv.length === 0, 'FIXED_INVOCATION');
  must(fileURLToPath(import.meta.url) === STAGE + '/install-cowork-plugin.mjs' && process.cwd() === '/workspace', 'FIXED_LOCATION');
  must(equal(Object.keys(process.env).sort(), Object.keys(ENV).sort()) && Object.entries(ENV).every(([k,v]) => process.env[k] === v), 'FIXED_ENVIRONMENT');
  process.umask(0o077);
  safeAncestors(STAGE); must((stat(STAGE).mode & 0o777) === 0o700, 'PRIVATE_STAGE');
  const plan = json(STAGE + '/plan.json');
  must(plan.format === 'cowork-offline-install-v1' && plan.runtimeManifestHash === RUNTIME_PIN, 'PLAN_FORMAT');
  must(digest(read(STAGE + '/install-cowork-plugin.mjs')) === plan.installerHash, 'INSTALLER_CHANGED');
  must(digest(read(STAGE + '/cowork-private-backup.mjs')) === plan.backupModuleHash, 'BACKUP_MODULE_CHANGED');
  must(digest(read(STAGE + '/cowork-preflight.mjs')) === PREFLIGHT_PIN, 'PREFLIGHT_CHANGED');
  must(digest(read(STAGE + '/preflight-v3.json')) === BASELINE_PIN, 'BASELINE_CHANGED');
  const baseline = json(STAGE + '/preflight-v3.json'), pkg = assertPackage(STAGE + '/package', plan);
  for (const file of [TARGET, CONFIG, WORKSPACE, BACKUP, MANIFEST + '.cowork-new', RUNTIME + '.cowork-new']) absent(file);
  // Refuse hidden ancestor/local package candidates as well as the fixed target.
  // A nearer existing package could otherwise shadow the installation mapping.
  const candidateAnchors = [RUNTIME, MANIFEST];
  // Search-path candidates are READ-ONLY absence checks, not write destinations.
  // Official Node tar ancestors retain uid 1001; never chmod/reown them to install.
  for (const anchor of candidateAnchors) for (const search of createRequire(anchor).resolve.paths(TARGET_NAME) ?? [])
    must(stat(path.join(search, TARGET_NAME), true) === null, 'TARGET_COLLISION');
  ensurePrivateDir('/root');
  const tokenParts = []; let tokenSize = 0;
  for await (const chunk of process.stdin) { tokenSize += chunk.length; must(tokenSize <= 513, 'TOKEN_SHAPE'); tokenParts.push(chunk); }
  const agentSecret = Buffer.concat(tokenParts).toString('utf8').trim(); tokenParts.forEach(x => x.fill(0));
  must(/^[\x21-\x7e]{32,512}$/.test(agentSecret), 'TOKEN_SHAPE');
  quiescent(); runPreflight(baseline);
  const runtimeBytes = read(RUNTIME), profileBytes = read(MANIFEST);
  must(digest(runtimeBytes) === RUNTIME_PIN && digest(profileBytes) === baseline.hashes.profileManifest, 'MANIFEST_DRIFT');
  const patches = new Map([PROFILE + '/cordis.yml', PROFILE + '/cordis.patch.yml', PROFILE + '/compatibility.json', HOME + '/cordis.patch.yml'].map(f => [f, read(f, true)]));
  const envs = new Map([HOME + '/.env', '/workspace/.env'].map(f => [f, read(f, true)]));
  for (const bytes of envs.values()) must(!bytes || !/\bDSH_COWORK_BROKER_CONFIG\b/.test(bytes.toString('utf8')), 'BROKER_ENV_OVERRIDE');
  const added = additiveManifests(JSON.parse(profileBytes), JSON.parse(runtimeBytes), pkg);
  const req = createRequire(RUNTIME), bootPath = fs.realpathSync(req.resolve('@deepseek-ai/dsh-app-boot'));
  must(bootPath.startsWith('/opt/dsh/') && digest(fs.readFileSync(bootPath)) === BOOT_PIN, 'BOOT_PIN');
  const boot = await import(pathToFileURL(bootPath).href);
  const before = boot.loadProfileDirectory('cowork-install', PROFILE, RUNTIME);
  must(before.skippedBundles.length === 0, 'BUNDLE_SKIPPED');
  const immutablePatches = new Map();
  for (const layer of before.layers) for (const file of layer.patchPaths) immutablePatches.set(file, immutablePatch(file));
  // Complete all private backups before installing code, creating config, or editing manifests.
  mkdirNew(BACKUP);
  const index = { format: 'cowork-private-backup-v1', paths: {}, absent: [], complete: false };
  for (const [src, label] of [[HOME, 'dsh-home'], ['/workspace/.env', 'workspace.env'], [CONFIG, 'cowork-broker.json'], [RUNTIME, 'runtime-package.json'], ['/opt/start-web.sh', 'start-web.sh']]) {
    if (!stat(src, true)) { index.absent.push(src); continue; }
    index.paths[src] = { archive: label + '.tar', inventory: backupTree(src, BACKUP + '/' + label + '.tar') };
  }
  index.complete = true; writeNew(BACKUP + '/backup-index.json', serialize(index)); syncDir(BACKUP); syncDir('/root');
  quiescent(); runPreflight(baseline);
  const journal = { format: 'cowork-install-journal-v1', stage: 'backed-up', target: TARGET, config: CONFIG,
    workspace: WORKSPACE, profileBefore: digest(profileBytes), runtimeBefore: digest(runtimeBytes),
    profileAfter: digest(serialize(added.profile)), runtimeAfter: digest(serialize(added.runtime)), package: plan.package,
    addedBundle: TARGET_NAME, addedRuntimeDependency: added.runtime.dependencies[TARGET_NAME] };
  writeNew(BACKUP + '/journal-prepared.json', serialize(journal));
  const scope = '/opt/dsh/node_modules/@local';
  if (!stat(scope, true)) mkdirNew(scope);
  safeAncestors(scope); must(stat(scope).isDirectory(), 'SCOPE_NOT_DIRECTORY');
  // O_EXCL directory + fixed regular-file allowlist; no extraction or scripts here.
  mkdirNew(TARGET); mkdirNew(TARGET + '/src');
  for (const file of PACKAGE_FILES) writeNew(TARGET + '/' + file, read(STAGE + '/package/' + file));
  assertPackage(TARGET, plan); assertPeers(pkg); syncDir(TARGET + '/src'); syncDir(TARGET); syncDir(scope); syncDir('/opt/dsh/node_modules');
  for (const dir of ['/root/.config', '/root/.config/dsh']) {
    if (!stat(dir, true)) mkdirNew(dir);
    safeAncestors(dir); must(stat(dir).isDirectory(), 'CONFIG_PARENT');
  }
  writeNew(CONFIG, serialize({ agentSecret })); syncDir(path.dirname(CONFIG)); syncDir('/root/.config'); syncDir('/root'); mkdirNew(WORKSPACE); syncDir('/workspace');
  const addition = boot.loadOverlayPatches('cowork-install', TARGET + '/cordis.patch.yml');
  must(Array.isArray(addition) && addition.length === 1 && Object.keys(addition[0]).length === 1 && Array.isArray(addition[0].insert) && addition[0].insert.length === 1, 'PATCH_NOT_ADDITIVE');
  const newRow = addition[0].insert[0];
  must(newRow.id === 'preset-cowork-phone' && newRow.name === '@deepseek-ai/dsh-agent-preset' && newRow.config?.id === 'cowork-phone' && newRow.config?.plugins?.length === 1 && newRow.config.plugins[0].id === 'cowork-policy-tools-summary' && newRow.config.plugins[0].name === TARGET_NAME, 'PATCH_ID');
  must(equal(Object.keys(newRow).sort(), ['config', 'id', 'name']) && equal(Object.keys(newRow.config).sort(), ['description', 'id', 'name', 'order', 'plugins']) && equal(Object.keys(newRow.config.plugins[0]).sort(), ['id', 'name']), 'PATCH_EXTRA_FIELDS');
  must(newRow.config.name === 'Cowork' && typeof newRow.config.description === 'string' && newRow.config.order === 20, 'PATCH_METADATA');
  const tail = [before.patches, boot.loadOptionalPatches('cowork-install', HOME + '/cordis.patch.yml') ?? []];
  let warnings = 0;
  const original = boot.composeEntries([...before.layers.map(x => x.patches), ...tail], () => warnings++);
  const candidate = boot.composeEntries([...before.layers.map(x => x.patches), addition, ...tail], () => warnings++);
  must(warnings === 0 && candidate.length === original.length + 1 && equal(candidate.filter(x => x.id !== newRow.id), original) && candidate.filter(x => x.id === newRow.id).length === 1 && equal(candidate.find(x => x.id === newRow.id), newRow), 'EXISTING_COMPOSITION_CHANGED');
  // Compare-and-replace only the two backed-up manifests. User patches never opened for write.
  function unchanged() {
    quiescent();
    for (const [file, bytes] of [...patches, ...envs]) must(equal(read(file, true), bytes), 'PROTECTED_FILE_CHANGED');
    for (const [file, bytes] of immutablePatches) must(equal(immutablePatch(file), bytes), 'BUNDLE_PATCH_CHANGED');
    assertPackage(TARGET, plan);
    must(equal(read(CONFIG), serialize({ agentSecret })), 'BROKER_CONFIG_CHANGED');
  }
  function commit(file, oldBytes, next, receipt) {
    unchanged(); must(equal(read(file), oldBytes), 'MANIFEST_CHANGED_BEFORE_COMMIT');
    const oldStat = stat(file);
    must(oldStat.uid === 0 && oldStat.gid === 0 && (oldStat.mode & 0o022) === 0 && (oldStat.mode & 0o7000) === 0, 'MANIFEST_PERMISSIONS');
    writeNew(file + '.cowork-new', serialize(next));
    fs.chmodSync(file + '.cowork-new', oldStat.mode & 0o777);
    const stagedFd = fs.openSync(file + '.cowork-new', fs.constants.O_RDONLY | fs.constants.O_NOFOLLOW);
    try { fs.fsyncSync(stagedFd); } finally { fs.closeSync(stagedFd); }
    unchanged(); must(equal(read(file), oldBytes), 'MANIFEST_CHANGED_BEFORE_RENAME');
    must(equal(read(file + '.cowork-new'), serialize(next)), 'STAGED_MANIFEST_CHANGED');
    fs.renameSync(file + '.cowork-new', file); syncDir(path.dirname(file));
    writeNew(BACKUP + '/' + receipt, serialize({ committed: true, hash: digest(serialize(next)) })); syncDir(BACKUP);
  }
  commit(RUNTIME, runtimeBytes, added.runtime, 'runtime-committed.json');
  // Prove the new package is now supplied by real RC2 runtime resolution before
  // appending the profile bundle (the last activation-affecting write).
  const resolution = await boot.createRuntimeResolution({ installAnchor: RUNTIME, profile: before, home: HOME });
  must(resolution.entries.some(e => e.name === TARGET_NAME && e.scope === 'installation' && e.packageDir === TARGET), 'PLUGIN_RESOLUTION_MISSING');
  commit(MANIFEST, profileBytes, added.profile, 'profile-committed.json');
  unchanged();
  const after = boot.loadProfileDirectory('cowork-install', PROFILE, RUNTIME);
  must(after.skippedBundles.length === 0 && after.layers.at(-1)?.packageDir === TARGET, 'POSTINSTALL_STATIC_FAILED');
  const finalEntries = boot.composeEntries([...after.layers.map(x => x.patches), ...tail], () => warnings++);
  must(warnings === 0 && equal(finalEntries, candidate), 'POSTINSTALL_COMPOSITION_FAILED');
  writeNew(BACKUP + '/complete.json', serialize({ complete: true, webStarted: false, standardDefaultChanged: false })); syncDir(BACKUP);
}

// Imports expose pure helpers for PC fixtures; never run an installer on import.
const direct = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (direct) {
  const output = process.stdout.write.bind(process.stdout);
  const discard = (...args) => { const callback = args.at(-1); if (typeof callback === 'function') callback(); return true; };
  process.stdout.write = discard; process.stderr.write = discard;
  for (const name of ['log','info','warn','error','debug','trace','dir']) console[name] = () => {};
  try { await main(); output('{"status":"installed-stopped","backup":"/root/.dsh-cowork-backup-v1","restartRequired":true}\n'); }
  catch (error) {
    // Never reflect OS/parser/library exceptions, config, paths from user data, or stdin.
    const code = error instanceof Refusal ? error.code : 'INSTALL_FAILED_PRIVATE_STATE_RETAINED';
    output(JSON.stringify({ status: 'refused', code, automaticRestore: false }) + '\n'); process.exitCode = 2;
  }
}
