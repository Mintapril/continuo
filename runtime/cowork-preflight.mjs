#!/usr/bin/env node
/**
 * RC2, read-only, BEFORE-install Cowork preflight. No CLI arguments.
 * Later, inside the existing chroot, after separately copying this file to
 * /opt/cowork-preflight.mjs (this script does NOT deploy itself):
 *
 * cd /workspace && env -i HOME=/root DSH_HOME=/root/.dsh \
 *   PATH=/opt/node/bin:/usr/bin:/bin DSH_TELEMETRY_DISABLED=1 \
 *   NODE_DISABLE_COMPILE_CACHE=1 /opt/node/bin/node --permission \
 *   --allow-fs-read=/ --no-warnings /opt/cowork-preflight.mjs
 *
 * Exit 0 = static checks passed; 2 = refused/incomplete. One sanitized JSON
 * document only. Never print caught exceptions, warnings, configs or env values.
 * No tests or phone execution were performed when preparing this file.
 *
 * API evidence: actual packed desktop resources/app.asar, runtime 0.2.0-rc.2:
 * dsh/node_modules/@deepseek-ai/dsh-app-boot/package.json:16-26 exports
 *   '.' -> './lib/index.js' and './package.json' -> './package.json'.
 * dsh/node_modules/@deepseek-ai/dsh-app-boot/lib/index.js:
 *   827 readProfileManifest(binName, dir)
 *   901 resolveBundleDir(binName, packageName, installAnchor, profileDir)
 *   920 loadProfileDirectory(binName, dir, installAnchor, options = {})
 *   989 composeEntries(layers, warn = () => {})
 *   1010 resolveTelemetryPatch(disabledEnv, hasRow)
 *   3508 loadOptionalPatches(binName, file); 4137 named exports.
 * loadProfileDirectory returns {name,dir,layers,patchPath,patches,skippedBundles};
 * layers contain {packageName,packageDir,patchPaths,patches}. composeEntries
 * flattens ALL patches once, preserving the Loader's single-pass index semantics.
 * !!js becomes {__jsExpr: string}; no expression is evaluated by these helpers.
 * NEVER call loadProfile / prepareProfile* / boot / --dump-config: loadProfile
 * initializes/normalizes profiles and removes link projections (lines 970-979).
 *
 * Static storage/default evidence (same packed RC2 sources):
 * dsh-session-persistence-jsonl/lib/index.js:2374-2400: required root; resolve(root).
 * dsh-storage-json/lib/index.js:551,558-559,598-599: required root -> backend root.
 * dsh-credentials-local/lib/index.js:56-61: path wins, otherwise
 *   join(resolveDshHome(config.dshHome), '.credentials.yaml').
 * dsh-attachment-local/lib/index.js:971-997: dshHome -> attachments/v1.
 * dsh-home-paths/lib/index.js:73-83: explicit home > DSH_HOME > ~/.dsh;
 *   dshHomePath(...segments) = join(resolveDshHome(), ...segments).
 * dsh-agent-preset/lib/index.js:12-18: config.id and config.plugins.
 * dsh-agent-preset-registry/lib/index.js:471-494: required config.default;
 *   live default = selectedDefault.get() ?? config.default (NOT statically known).
 * dsh-web-app/cordis.patch.yml:562-565: default: standard, NOT 'std'.
 * dsh-web-app/presets/standard.patch.yml:5-8: preset-standard / config.id standard.
 *
 * Only the pinned app-boot module is imported; its trusted library dependencies
 * are imported by Node, but no configured plugin is imported/constructed.
 * Source hashes below deliberately refuse a different build even at the same
 * version. Permission mode is required to deny filesystem writes/child processes/
 * workers/addons/WASI. It is defense in depth, not a root-security sandbox.
 * Filesystem reads can update atime. This is not a transaction or a backup.
 */
import fs from 'node:fs';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';

const VERSION = '0.2.0-rc.2';
const INSTALL = '/opt/dsh';
const ANCHOR = '/opt/dsh/node_modules/@deepseek-ai/dsh/package.json';
const HOME = '/root/.dsh';
const PROFILE = '/root/.dsh/profiles/web';
const PROFILE_PATCH = `${PROFILE}/cordis.patch.yml`;
const HOME_PATCH = `${HOME}/cordis.patch.yml`;
const TARGET = '@local/dsh-cowork-phone';
const PRESET = '@deepseek-ai/dsh-agent-preset';
const REGISTRY = '@deepseek-ai/dsh-agent-preset-registry';
const SOURCE_PINS = Object.freeze({
  'dsh-app-boot': '234db45e1b3f8c683b5bc1f551948b2a2ec52a6468c7b6937725a23f1c0e0d96',
  'dsh-home-paths': '97c1c10b299a4f2aec5f34bac5370cad3834391cd75c91a76b1e7b8a643584f5',
  'dsh-session-persistence-jsonl': '0845707017acc2b4a8a75eab2244fa3fd88587b8094ab32014321dce7ca1e31b',
  'dsh-storage-json': '0a6b75d5569db3379edd93e863570170e1f4121b6c9fabd856f5b476cffe8df4',
  'dsh-credentials-local': '1688f17801d5809abace4ef6228b771625a0153c043d7d4dba21b398ec4056eb',
  'dsh-attachment-local': 'cd39685581b4573a453c6126f8ce73da0bf1257c378827ebb431d6d4064f7662',
  'dsh-agent-preset': '23cd2dd62ac3b27522a2d377e14b5a93f80c2189e733937dcdd02b73a6d3d1da',
  'dsh-agent-preset-registry': 'ed62d573b8cc6d7ed3e3cb7932dbf5e237f5a603b3cc9d9e586187db00e821cc',
});
const report = {
  format: 'cowork-preflight-v1', status: 'refused',
  versions: { node: null, runtime: null }, packages: [], bundles: [],
  hashes: { config: null, profile: null, home: null, profileManifest: null },
  collisions: [], refusals: [], backupPaths: [], unverifiedModuleSpecifiers: [],
  standardPreset: { declared: false, staticallyEnabled: false, configuredDefault: null,
    configuredDefaultIsStandard: false, liveSelectedDefault: 'not-inspected' },
  limits: [
    'existing-web-profile-only; no CLI patch overlays or alternate config',
    'all bundle/profile/home declarations checked, including dormant and overwritten rows',
    'no expression evaluation; includes and dynamic entry lists refuse completeness',
    'no plugin activation, provider validation, live settings or session inspection',
    'known RC2 session/JSON-storage/credentials/attachments roots only; not arbitrary plugin data',
    'backup paths are a fixed allowlist; unknown roots and symlinked paths refuse',
    'no credential file contents read; env fallback paths are candidates, not inspected',
    'source/content hashes and end recheck are not an atomic or live-process snapshot',
    'no deployment, restart, model invocation, network access or backup performed',
  ],
};
let stage = 'environment';
const snapshots = new Map();
const packageSeen = new Set();
const collisionSeen = new Set();
const reject = (code) => { if (!report.refusals.includes(code)) report.refusals.push(code); };
const must = (condition, code) => { if (!condition) { reject(code); throw new Error('refused'); } };
const sha = (value) => createHash('sha256').update(value).digest('hex');
const object = (v) => v !== null && typeof v === 'object' && !Array.isArray(v);
const expression = (v) => object(v) && typeof v.__jsExpr === 'string';
const safePackage = (v) => typeof v === 'string' && v.length <= 180 &&
  /^(?:@[a-z0-9][a-z0-9._-]*\/)?[a-z0-9][a-z0-9._-]*$/.test(v) ? v : null;
const safeVersion = (v) => typeof v === 'string' && v.length <= 80 &&
  /^v?\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?$/.test(v) ? v : null;
const safeId = (v) => typeof v === 'string' && /^[a-z][a-z0-9_-]{0,63}$/.test(v) ? v : null;
function collision(kind, layer) {
  const key = `${kind}:${layer}`;
  if (!collisionSeen.has(key)) { collisionSeen.add(key); report.collisions.push({ kind, layer }); }
}
function snapshot(file, optional = false) {
  let bytes;
  try {
    const st = fs.statSync(file);
    must(st.isFile() && st.size <= 32 * 1024 * 1024, 'INPUT_FILE_SHAPE');
    bytes = fs.readFileSync(file);
  } catch (error) {
    if (!optional || error?.code !== 'ENOENT') throw error;
    bytes = null;
  }
  const digest = bytes === null ? null : sha(bytes);
  if (snapshots.has(file)) must(snapshots.get(file) === digest, 'INPUT_CHANGED_DURING_READ');
  else snapshots.set(file, digest);
  return bytes;
}
function jsonFile(file) {
  const value = JSON.parse(snapshot(file).toString('utf8'));
  must(object(value), 'MANIFEST_SHAPE');
  return value;
}
function trustedCode(file) {
  const resolved = fs.realpathSync(file);
  must(resolved.startsWith(`${INSTALL}/`), 'CODE_OUTSIDE_INSTALLATION');
  return resolved;
}
function trustedBundleFile(dir, file) {
  const canonicalDir = fs.realpathSync(dir);
  must(canonicalDir.startsWith(`${INSTALL}/`) || canonicalDir.startsWith(`${PROFILE}/node_modules/`),
    'BUNDLE_OUTSIDE_TRUSTED_ROOTS');
  const target = path.resolve(dir, file);
  must(target.startsWith(`${path.resolve(dir)}/`), 'BUNDLE_FILE_ESCAPES_PACKAGE');
  try {
    must(fs.realpathSync(target).startsWith(`${canonicalDir}/`), 'BUNDLE_FILE_ESCAPES_PACKAGE');
  } catch (error) { if (error?.code !== 'ENOENT') throw error; }
  return target;
}
function recordPackage(manifest, layer) {
  const name = safePackage(manifest.name);
  const version = safeVersion(manifest.version);
  if (manifest.name === TARGET) collision('package', layer);
  const key = `${layer}:${name}:${version}`;
  if (!packageSeen.has(key)) { packageSeen.add(key); report.packages.push({ layer, name, version }); }
  for (const field of ['dependencies', 'devDependencies', 'optionalDependencies', 'peerDependencies']) {
    if (manifest[field] === undefined) continue;
    must(object(manifest[field]), 'DEPENDENCY_MAP_SHAPE');
    for (const [name, spec] of Object.entries(manifest[field])) {
      if (name === TARGET || (typeof spec === 'string' && spec.startsWith(`npm:${TARGET}`))) {
        collision('package-declaration', layer);
      }
    }
  }
}
function probeTarget(anchor, layer) {
  for (const search of createRequire(anchor).resolve.paths(TARGET) ?? []) {
    try {
      // lstat also catches a broken symlink: it still occupies the package slot.
      fs.lstatSync(path.join(search, TARGET));
      collision('installed-package-slot', layer);
    } catch (error) { if (error?.code !== 'ENOENT' && error?.code !== 'ENOTDIR') throw error; }
  }
}
function scan(value, layer, ancestors = new Set(), depth = 0) {
  if (value === null || typeof value !== 'object') return;
  must(depth < 160 && !ancestors.has(value), 'CYCLIC_OR_DEEP_CONFIGURATION');
  ancestors.add(value);
  if (!Array.isArray(value)) {
    if (value.id === 'preset-cowork-phone') collision('loader-id:preset-cowork-phone', layer);
    if (value.id === 'cowork-policy-tools-summary') collision('loader-id:cowork-policy-tools-summary', layer);
    // Conservative for raw patches: config.id can introduce the preset even when
    // this patch omits name, or a later layer disables/replaces that declaration.
    if (object(value.config) && value.config.id === 'cowork-phone') collision('preset-config-id:cowork-phone', layer);
    if (expression(value.id) || expression(value.name)) reject('DYNAMIC_DECLARATION_IDENTITY');
    if (object(value.config) && expression(value.config.id)) reject('DYNAMIC_DECLARATION_IDENTITY');
    if (value.name === TARGET || (typeof value.name === 'string' &&
      (value.name.startsWith(`${TARGET}/`) || value.name.includes(`/${TARGET}/`)))) {
      collision('entry-package', layer);
    }
  }
  for (const child of Object.values(value)) scan(child, layer, ancestors, depth + 1);
  ancestors.delete(value);
}
function rowsFrom(entries, inherited = true, result = [], depth = 0) {
  must(Array.isArray(entries) && depth < 80, 'ENTRY_LIST_SHAPE');
  for (const entry of entries) {
    if (!object(entry)) { reject('DYNAMIC_OR_INVALID_ENTRY'); continue; }
    const enabled = inherited === false || entry.disabled === true ? false
      : inherited === 'unknown' || (entry.disabled !== undefined && entry.disabled !== false) ? 'unknown' : true;
    result.push({ entry, enabled });
    if (typeof entry.name !== 'string' || expression(entry.id)) reject('DYNAMIC_ENTRY_IDENTITY');
    // A file URL or relative plugin can alias an unrecognized include/storage
    // provider; do not load it to discover that fact or claim a complete scan.
    const nativeGroup = entry.name === 'cordis:group';
    // Known RC2 Loader carrier (pinned app-boot:2100/2814) and the exact existing
    // unscoped bundle identity already resolved/read above, not arbitrary imports.
    const verifiedUnscopedBundle = entry.name === 'billion-context-dsh' &&
      report.bundles.some(row => row.name === entry.name && row.version === '0.2.27');
    if (typeof entry.name === 'string' && !nativeGroup && !verifiedUnscopedBundle &&
      !/^@[a-z0-9._-]+\/[a-z0-9._-]+(?:\/[a-z0-9._/-]+)?$/.test(entry.name)) {
      reject('UNVERIFIED_ENTRY_MODULE_SPECIFIER');
      // Diagnostic package identity only; never emit arbitrary paths/config text.
      const label = safePackage(entry.name) ?? `sha256:${sha(entry.name)}`;
      if (!report.unverifiedModuleSpecifiers.includes(label)) report.unverifiedModuleSpecifiers.push(label);
    }
    if (entry.name === '@deepseek-ai/cordis-plugin-include' || entry.name?.startsWith?.('@deepseek-ai/cordis-plugin-include/')) {
      reject('UNEXPANDED_INCLUDE');
    }
    if (entry.group || nativeGroup || entry.name === '@deepseek-ai/cordis-plugin-group') {
      if (Array.isArray(entry.config)) rowsFrom(entry.config, enabled, result, depth + 1);
      else reject('DYNAMIC_GROUP_CHILDREN');
    }
    if (entry.name === PRESET) {
      if (object(entry.config) && Array.isArray(entry.config.plugins)) rowsFrom(entry.config.plugins, enabled, result, depth + 1);
      else reject('DYNAMIC_PRESET_CHILDREN');
      if (!object(entry.config) || typeof entry.config.id !== 'string') reject('DYNAMIC_PRESET_ID');
    }
  }
  return result;
}
// These exact expressions are recognized as DATA, never eval/Function/vm/import.
function staticPath(value) {
  if (typeof value === 'string') return value;
  if (!expression(value) || Object.keys(value).length !== 1) return null;
  const match = /^dshHomePath\(\s*(['"])(sessions|storages)\1\s*\)$/.exec(value.__jsExpr.trim());
  return match ? `${HOME}/${match[2]}` : null;
}
const PATH_ALLOWLIST = new Set([
  HOME, PROFILE, `${PROFILE}/package.json`, PROFILE_PATCH, HOME_PATCH,
  `${PROFILE}/compatibility.json`, `${HOME}/sessions`, `${HOME}/storages`,
  `${HOME}/.credentials.yaml`, `${HOME}/attachments/v1`, `${HOME}/.env`, '/workspace/.env',
]);
function noSymlink(file) {
  // Check all existing ancestors, not just the leaf (missing backup roots are OK).
  let current = '/';
  for (const segment of file.split('/').filter(Boolean)) {
    current = path.join(current, segment);
    try { if (fs.lstatSync(current).isSymbolicLink()) return false; }
    catch (error) { if (error?.code === 'ENOENT') return true; throw error; }
  }
  return true;
}
function backup(role, candidate) {
  if (!PATH_ALLOWLIST.has(candidate) || !noSymlink(candidate)) { reject('UNTRUSTED_OR_DYNAMIC_BACKUP_PATH'); return; }
  if (!report.backupPaths.some((x) => x.role === role && x.path === candidate)) report.backupPaths.push({ role, path: candidate });
}
function storageRoots(rows) {
  const seen = new Set();
  for (const { entry, enabled } of rows) {
    const cfg = entry.config ?? {};
    const name = entry.name;
    if (!['@deepseek-ai/dsh-session-persistence-jsonl', '@deepseek-ai/dsh-storage-json',
      '@deepseek-ai/dsh-credentials-local', '@deepseek-ai/dsh-attachment-local'].includes(name)) continue;
    if (enabled === false) continue;
    if (enabled !== true || !object(cfg) || expression(cfg)) { reject('DYNAMIC_STORAGE_CONFIGURATION'); continue; }
    seen.add(name);
    if (name.endsWith('/dsh-session-persistence-jsonl')) backup('session-persistence-root', staticPath(cfg.root));
    if (name.endsWith('/dsh-storage-json')) backup('json-storage-root', staticPath(cfg.root));
    if (name.endsWith('/dsh-credentials-local')) {
      const home = cfg.dshHome == null ? HOME : staticPath(cfg.dshHome);
      backup('managed-credentials-file', cfg.path == null ? (home === HOME ? `${HOME}/.credentials.yaml` : null) : staticPath(cfg.path));
    }
    if (name.endsWith('/dsh-attachment-local')) {
      const home = cfg.dshHome == null ? HOME : staticPath(cfg.dshHome);
      backup('durable-attachments-root', home === HOME ? `${HOME}/attachments/v1` : null);
    }
  }
  for (const name of ['dsh-session-persistence-jsonl', 'dsh-storage-json', 'dsh-credentials-local', 'dsh-attachment-local']) {
    if (!seen.has(`@deepseek-ai/${name}`)) reject('REQUIRED_STORAGE_BACKEND_NOT_STATICALLY_ENABLED');
  }
}
function standardInfo(rows) {
  const presets = rows.filter(({ entry }) => entry.name === PRESET && entry.config?.id === 'standard');
  report.standardPreset.declared = presets.length > 0;
  report.standardPreset.staticallyEnabled = presets.length === 1 && presets[0].enabled === true;
  const registries = rows.filter(({ entry, enabled }) => entry.name === REGISTRY && enabled !== false);
  if (registries.length === 1 && registries[0].enabled === true) {
    report.standardPreset.configuredDefault = safeId(registries[0].entry.config?.default);
    report.standardPreset.configuredDefaultIsStandard = registries[0].entry.config?.default === 'standard';
  }
  if (!report.standardPreset.staticallyEnabled) reject('STANDARD_PRESET_NOT_UNIQUELY_ENABLED');
  if (!report.standardPreset.configuredDefaultIsStandard) reject('STANDARD_CONFIGURED_DEFAULT_NOT_PROVEN');
}

// Capture the one allowed output channel before suppressing library diagnostics.
const output = process.stdout.write.bind(process.stdout);
const discard = (...args) => { const cb = args.at(-1); if (typeof cb === 'function') cb(); return true; };
process.stdout.write = discard;
process.stderr.write = discard;
for (const name of ['log', 'info', 'warn', 'error', 'debug', 'trace', 'dir']) console[name] = () => {};

async function main() {
  report.versions.node = safeVersion(process.version);
  must(process.platform === 'linux' && process.getuid?.() === 0, 'EXPECTED_ROOT_CHROOT');
  must(process.argv.length === 2 && process.cwd() === '/workspace', 'FIXED_INVOCATION_REQUIRED');
  must(process.env.HOME === '/root' && process.env.DSH_HOME === HOME &&
    process.env.DSH_TELEMETRY_DISABLED === '1' && process.env.NODE_DISABLE_COMPILE_CACHE === '1' &&
    !process.env.NODE_OPTIONS && !process.env.NODE_PATH, 'FIXED_ENVIRONMENT_REQUIRED');
  must(JSON.stringify([...process.execArgv].sort()) ===
    JSON.stringify(['--allow-fs-read=/', '--no-warnings', '--permission']), 'FIXED_NODE_FLAGS_REQUIRED');
  must(process.permission && !process.permission.has('fs.write') &&
    !process.permission.has('child') && !process.permission.has('worker') &&
    !process.permission.has('addons') && !process.permission.has('wasi'), 'READ_ONLY_NODE_PERMISSION_REQUIRED');
  must(noSymlink(PROFILE) && noSymlink(`${PROFILE}/package.json`) &&
    noSymlink(PROFILE_PATCH) && noSymlink(`${PROFILE}/compatibility.json`) &&
    noSymlink(HOME_PATCH), 'PROFILE_OR_HOME_SYMLINK');

  stage = 'runtime-metadata';
  const runtime = jsonFile(trustedCode(ANCHOR));
  recordPackage(runtime, 'runtime');
  report.versions.runtime = safeVersion(runtime.version);
  must(runtime.name === '@deepseek-ai/dsh' && runtime.version === VERSION, 'UNVERIFIED_RUNTIME_VERSION');
  const requireRuntime = createRequire(ANCHOR);
  let bootEntry;
  for (const [shortName, pin] of Object.entries(SOURCE_PINS)) {
    const name = `@deepseek-ai/${shortName}`;
    const metaPath = trustedCode(requireRuntime.resolve(`${name}/package.json`));
    const meta = jsonFile(metaPath);
    recordPackage(meta, 'verified-source');
    must(meta.name === name && meta.version === VERSION, 'UNVERIFIED_HELPER_VERSION');
    const entryPath = trustedCode(requireRuntime.resolve(name));
    must(entryPath === path.join(path.dirname(metaPath), 'lib/index.js'), 'UNVERIFIED_MODULE_ENTRY');
    must(sha(snapshot(entryPath)) === pin, 'UNVERIFIED_SOURCE_HASH');
    if (shortName === 'dsh-app-boot') bootEntry = entryPath;
  }
  // Resolving paths and reading metadata above never imports configured plugins.
  stage = 'helper-import';
  const boot = await import(pathToFileURL(bootEntry).href);
  for (const name of ['readProfileManifest', 'loadProfileDirectory', 'loadOptionalPatches',
    'composeEntries', 'resolveBundleDir', 'resolveTelemetryPatch']) {
    must(typeof boot[name] === 'function', 'HELPER_EXPORT_MISSING');
  }
  must(boot.PROFILE_PATCH_FILENAME === 'cordis.patch.yml' &&
    boot.PROFILE_COMPATIBILITY_FILENAME === 'compatibility.json', 'HELPER_CONSTANT_MISMATCH');

  stage = 'profile-metadata';
  const manifestBytes = snapshot(`${PROFILE}/package.json`);
  const manifest = boot.readProfileManifest('cowork-preflight', PROFILE);
  must(JSON.stringify(manifest) === JSON.stringify(JSON.parse(manifestBytes.toString('utf8'))), 'PROFILE_READ_RACE');
  report.hashes.profileManifest = sha(manifestBytes);
  recordPackage(manifest, 'profile');
  // Inspect direct installed profile dependencies as metadata only, including an
  // alias whose directory name is different from its package.json identity.
  const profileRequire = createRequire(`${PROFILE}/package.json`);
  const dependencyNames = new Set(['dependencies', 'devDependencies', 'optionalDependencies']
    .flatMap((field) => Object.keys(manifest[field] ?? {})));
  for (const name of dependencyNames) {
    must(safePackage(name), 'DEPENDENCY_NAME_SHAPE');
    let found = false;
    for (const search of profileRequire.resolve.paths(name) ?? []) {
      const file = path.join(search, name, 'package.json');
      try {
        fs.lstatSync(file);
        const canonical = fs.realpathSync(file);
        must(canonical.startsWith(`${INSTALL}/`) || canonical.startsWith(`${PROFILE}/node_modules/`),
          'DEPENDENCY_OUTSIDE_TRUSTED_ROOTS');
        recordPackage(jsonFile(file), 'profile-dependency');
        found = true;
        break;
      } catch (error) { if (error?.code !== 'ENOENT' && error?.code !== 'ENOTDIR') throw error; }
    }
    if (!found) reject('PROFILE_DEPENDENCY_METADATA_UNRESOLVED');
  }
  const bundleNames = manifest.dsh?.profile?.bundles;
  must(Array.isArray(bundleNames) && bundleNames.length > 0 && bundleNames.every((x) => safePackage(x)), 'BUNDLE_LIST_SHAPE');
  must(new Set(bundleNames).size === bundleNames.length, 'DUPLICATE_BUNDLE');
  probeTarget(ANCHOR, 'runtime');
  probeTarget(`${PROFILE}/package.json`, 'profile');
  if (bundleNames.includes(TARGET)) collision('bundle', 'profile');
  const profilePatch = snapshot(PROFILE_PATCH, true);
  const homePatch = snapshot(HOME_PATCH, true);
  report.hashes.profile = profilePatch === null ? null : sha(profilePatch);
  report.hashes.home = homePatch === null ? null : sha(homePatch);
  const compatibilityPath = path.join(PROFILE, boot.PROFILE_COMPATIBILITY_FILENAME);
  must(path.dirname(compatibilityPath) === PROFILE, 'COMPATIBILITY_FILENAME_SHAPE');
  snapshot(compatibilityPath, true);
  // Pre-snapshot every declared bundle file so a mid-read change refuses. The
  // actual helper still decides compatibility/skipping and performs composition.
  for (const [index, name] of bundleNames.entries()) {
    const layer = `bundle:${index}`;
    const dir = boot.resolveBundleDir('cowork-preflight', name, ANCHOR, PROFILE);
    const metaPath = trustedBundleFile(dir, 'package.json');
    const meta = jsonFile(metaPath);
    recordPackage(meta, layer);
    probeTarget(metaPath, layer);
    const declared = meta.dsh?.bundle?.patch;
    const files = typeof declared === 'string' ? [declared] : declared;
    const row = { order: index, name: safePackage(name), version: safeVersion(meta.version),
      manifestHash: snapshots.get(metaPath), patchHashes: [] };
    report.bundles.push(row);
    if (!Array.isArray(files) || !files.every((x) => typeof x === 'string')) continue;
    for (const file of files) {
      const patchPath = trustedBundleFile(dir, file);
      const bytes = snapshot(patchPath, true);
      row.patchHashes.push(bytes === null ? null : sha(bytes));
    }
  }

  stage = 'read-and-compose';
  const profile = boot.loadProfileDirectory('cowork-preflight', PROFILE, ANCHOR);
  must(Array.isArray(profile.layers) && Array.isArray(profile.patches) && Array.isArray(profile.skippedBundles), 'PROFILE_RESULT_SHAPE');
  if (profile.skippedBundles.length) {
    reject('SKIPPED_BUNDLES');
    // Never copy .reason: it can contain configuration, expressions or secrets.
    for (const skipped of profile.skippedBundles) collision('skipped-bundle', `bundle:${bundleNames.indexOf(skipped.packageName)}`);
  }
  const layers = profile.layers.map((x) => x.patches);
  layers.push(profile.patches, boot.loadOptionalPatches('cowork-preflight', HOME_PATCH) ?? []);
  for (const [index, patches] of layers.entries()) scan(patches, `layer:${index}`);
  let warningCount = 0;
  const entries = boot.composeEntries(layers, () => { warningCount += 1; });
  if (warningCount) reject('COMPOSITION_SKIPPED_PATCHES');
  // Exact fixed launcher override from start-web.sh, after all persisted layers.
  const telemetry = boot.resolveTelemetryPatch('1', entries.some((entry) => entry.id === 'session-telemetry-otel'));
  const effective = telemetry ? boot.composeEntries([...layers, [telemetry]], () => {}) : entries;
  scan(effective, 'effective');
  report.hashes.config = sha(JSON.stringify(effective));
  const rows = rowsFrom(effective);
  storageRoots(rows);
  standardInfo(rows);
  backup('profile-directory', PROFILE);
  backup('home-patch', HOME_PATCH);
  backup('home-env-credential-fallback-candidate', `${HOME}/.env`);
  backup('project-env-credential-fallback-candidate', '/workspace/.env');
  // Full-home backup is a conservative superset, not a claim to have inspected it.
  backup('conservative-harness-home-superset', HOME);

  stage = 'final-input-recheck';
  for (const [file, digest] of snapshots) {
    const bytes = snapshot(file, digest === null);
    must((bytes === null ? null : sha(bytes)) === digest, 'INPUT_CHANGED_DURING_READ');
  }
  if (report.collisions.length) reject('COLLISIONS_PRESENT');
  report.status = report.refusals.length === 0 ? 'passed-static-only' : 'refused';
}
try { await main(); }
catch { reject(`FAILED_${stage.toUpperCase().replaceAll('-', '_')}`); }
report.status = report.refusals.length || report.collisions.length ? 'refused' : report.status;
process.exitCode = report.status === 'passed-static-only' ? 0 : 2;
output(`${JSON.stringify(report, null, 2)}\n`);
