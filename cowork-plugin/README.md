# Restricted Cowork phone preset

Local DSH **0.2.0-rc.2** bundle for native-phone Cowork: fixed phone capabilities, bounded document files, and basic public web search/fetch. Enforcement is in the tool registry and executable adapters—not only a prompt.

> **Not deployed.** This directory contains plugin code and offline tests, not an Android backend. No models were launched, packages installed on a device, phone actions performed, or provider credentials changed. Installation must target a **new isolated profile**, never replace the existing Code/desktop profile.

## What is shipped

[The package manifest](<package.json>) exports [the restricted preset plugin](<src/restricted.mjs>). Its [additive bundle patch](<cordis.patch.yml>) registers preset id `cowork-phone`, display name **Cowork**. It does not select a default preset or modify another preset, provider, credentials, or global tool configuration.

| Capability | Allowed surface | Enforcement |
|---|---|---|
| Phone | Sixteen explicit `phone.*` tools | Strict arguments/results, authenticated fixed loopback route, fresh session-bound observations, serialized calls |
| Documents | `cowork.files.read`, `cowork.files.write`, `cowork.files.list` | Fixed `/workspace/cowork`, pinned directory descriptors, no links/traversal, create-only writes |
| Web | `web_search`, `web_fetch` | Bounded search; public DNS IPv4, pinned lookup, standard HTTP(S) ports, bounded text fetch |
| Shell/code/install | None | Empty inherited-tool mask plus monotonic execution guard |
| Subagents/workflows/MCP/configuration | None | Not registered; late/injected or same-name replacement definitions fail ownership checks |

The preset registers definitions in the **bound agent scope**, uses `tools.restrict({allow: []})` there to hide all inherited tools, and uses the real `tools.guard()` to deny unknown or unowned definitions. `tools.presentAs('native')` prevents a code-dispatch presentation layer. Code sessions outside the preset retain their own tools.

A plugin preset is **not an OS sandbox**. A trusted native backend must enforce app allowlists, virtual-display isolation, main-display protection, consent and high-impact-action approval. See [the bridge contract](<PROTOCOL.md>) before implementing or enabling it.

## Prepare an isolated deployment

These are deployment requirements, not actions performed by this change:

1. Use DSH `0.2.0-rc.2`, Node.js 22 or newer, and an isolated Cowork profile. Resolve the exact pinned DSH peer packages from that runtime, not from a different installed version.
2. Provision a private `/workspace/cowork` directory in the Linux/Android runtime, outside configuration, provider credentials and plugin code. File tools intentionally fail closed on Windows. Parent directories must not be writable by an untrusted process.
3. Provision a per-install native **agent secret** outside that workspace. The default trusted config location is `$HOME/.config/dsh/cowork-broker.json`; trusted deployment may select another absolute path with `DSH_COWORK_BROKER_CONFIG`. The file contains exactly one JSON property, `agentSecret`, containing 32–512 printable non-space ASCII characters. Generate at least 32 cryptographically random bytes and encode them safely. On Linux/Android the file must be owned by the runtime user with no group/other permissions; use mode `0600`. No secret is included in this repository.
4. Provide the authenticated native broker at literal `127.0.0.1:3091`; do not bind all interfaces. The plugin has no alternate endpoint, generic RPC method or command field.
5. Register this local bundle using the DSH bundle installation mechanism in **that new profile only**. The installed DSH authoring contract uses `plugin_manager` with `action: install_bundle` and the absolute package directory as `target`. Do not manually overwrite an existing profile's package manifest, composition patch or provider configuration. No install command has been executed here.
6. Verify the resulting composition contains the inserted `preset-cowork-phone` row, select **Cowork** in a new native session, and inspect its offered tool names before enabling phone actions. Keep Code as a separate mode.

The host must provide `tools`, `systemPrompt`, `llm`, `sessions`, `agents`, `attachments`, and `web`. Web search uses the host's existing search service. Auxiliary generation reuses the session's selected provider/model route without copying provider credentials.

## Document tool behavior

[The file adapter](<src/bounded-files.mjs>) accepts a relative POSIX path, or an absolute path inside `/workspace/cowork`. Hidden names, `..`, backslashes, drive/ADS syntax, configuration/package names and common executable extensions are rejected.

- Read: UTF-8 text, at most 65,536 bytes per call; byte offset at most 16 MiB. Non-UTF-8 content or a slice ending inside a UTF-8 sequence fails rather than silently corrupting text.
- Write: new UTF-8 text files only, at most 65,536 bytes, mode `0600`; existing files are never overwritten. No delete, rename, mkdir or executable operation exists. A cancelled/failed write may leave a partial new file; inspect it, do not blindly repeat.
- List: at most 200 visible entries and at most 1,000 scanned entries per call; no recursive traversal.
- Each ancestor is opened through `/proc/self/fd` with `O_DIRECTORY|O_NOFOLLOW`; reads use `O_NOFOLLOW|O_NONBLOCK` and reject hardlinks and nonregular files. Writes use `O_EXCL|O_NOFOLLOW`.

## Public web behavior

[The web adapter](<src/web.mjs>) allows one to four queries of at most 1,024 characters, at most eight results per query, and bounded excerpts. Fetch allows only credential-free public DNS HTTP(S) on standard ports. It rechecks every redirect, pins the checked address into the HTTP connection, bypasses proxy routing, accepts only bounded text/HTML/JSON/XML, removes script/style markup, and caps response bytes at 262,144 with at most 32,000 output characters. IPv6-only or mixed IPv4/IPv6 destinations fail closed. Both tool paths have a 12-second abort deadline.

Fetched pages, phone UI text, files and generated prose remain **untrusted input**, not instructions. These limits reduce transport risks; they do not turn arbitrary web content into trusted facts or prevent every possible data disclosure through a permitted web query.

## Run offline tests

From this directory, using an already-installed Node.js runtime:

```powershell
node --test test/strict-*.test.mjs
```

The tests use deterministic in-process transports and fake model streams. They do not listen on the phone port, contact the device, call a real model, or install anything. They cover:

- actual extracted DSH registry/scope APIs, Code/Cowork isolation, late MCP registration and guard precedence;
- every fixed phone tool, unknown fields, bounds, observation expiry/ownership/consumption, envelope correlation and no retries;
- pinned file handles, create-only flags, hardlink/special-file rejection and Windows fail-closed behavior;
- URL/DNS/redirect restrictions and bounded web search;

Integration tests import the read-only inspected runtime copy under [the research fixture](<research/installed/dsh/node_modules/>). That fixture is not shipped by the package. [The extraction helper](<research/inspect-asar.mjs>) reads selected source text from the installed ASAR into this scoped directory; it never changes the installed checkout. Legacy exploratory source/tests remain outside the manifest's shipped file list and are **not** the production entry point; use the strict suite above.

## Verification limits and next steps

Offline tests establish adapter and inspected-runtime API behavior, not phone deployment readiness. Before production acceptance, independently verify: isolated-profile activation; native port authentication and request replay handling; per-app/display protections; user cancellation and exact-action approval; global screen-generation invalidation; Linux file descriptor behavior on the actual phone runtime; image attachment delivery. These checks require separate authorization and were intentionally not run here.
