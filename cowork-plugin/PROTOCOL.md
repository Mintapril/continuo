# Cowork native broker protocol v1

Fixed typed contract used by [the shipped broker adapter](<src/broker.mjs>) and [the restricted preset](<src/restricted.mjs>) on DSH 0.2.0-rc.2. This replaces the exploratory underscore-tool contract. Model and wire tool names are now the same dotted names.

> The native Android server is **not implemented in this package**. It must independently enforce every authorization and display boundary described below. A valid agent token or model-supplied session ID is not user consent. No real phone request was sent during plugin testing.

## Transport and secret provisioning

- Exactly `POST http://127.0.0.1:3091/v1/tool`, literal IPv4 loopback. No alternate host, port, route, method, proxy, redirect, Unix socket, shell or generic RPC path.
- Headers: `Authorization: Bearer <agent secret>` and `Content-Type: application/json`; the adapter supplies a byte-accurate `Content-Length`.
- The native app provisions a random per-install agent secret. DSH reads it from a trusted config file, not a tool argument: default `$HOME/.config/dsh/cowork-broker.json`, or the absolute path chosen by trusted deployment through `DSH_COWORK_BROKER_CONFIG`. The JSON contains exactly `agentSecret`. Accepted format is 32–512 printable non-space ASCII characters. Use at least 32 random bytes encoded as base64url. The config must be outside `/workspace/cowork`, a small regular file (at most 4,096 bytes), not a symlink, and on Linux/Android owned by the runtime user with mode `0600` or stricter.
- Bind the native listener to `127.0.0.1` only. Authenticate before dispatch, compare secrets in constant time, reject browser Origin requests, and do not enable CORS. Never expose the token in URLs, logs, tools or provider configuration. Rotate it when re-pairing/restarting the native bridge.
- The adapter uses `node:http`, no automatic redirects or retries, with a 12-second request abort deadline. DSH tool timeout is 16 seconds. Calls are serialized within one adapter; native code must serialize across all sessions/processes too.
- Maximum response JSON: **12 MiB**. Recommended native request cap: **32 KiB**. UTF-8 JSON only; reject unknown fields, duplicate JSON keys, wrong types and unsupported protocol versions. No JavaScript/code, command, URI, arbitrary file, intent or method-forwarding field exists.

## Envelopes

Request fields are exactly:

```json
{
  "protocolVersion": 1,
  "requestId": "9baf5148-3454-48cd-854d-f24f2da19158",
  "sessionId": "cowork-session-example",
  "tool": "phone.status",
  "arguments": {}
}
```

The adapter generates a fresh UUID for `requestId`; the model cannot supply one. `sessionId` comes from the bound DSH session, is 1–256 characters without NUL, and namespaces observations only. Native code must not accept it as authorization or an arbitrary filesystem path.

Success is HTTP 200 and exactly:

```json
{
  "protocolVersion": 1,
  "requestId": "9baf5148-3454-48cd-854d-f24f2da19158",
  "ok": true,
  "result": {
    "state": "ready",
    "accessibilityReady": true,
    "screenCaptureReady": true
  }
}
```

An authenticated denial is HTTP 200 and exactly:

```json
{
  "protocolVersion": 1,
  "requestId": "9baf5148-3454-48cd-854d-f24f2da19158",
  "ok": false,
  "error": { "code": "PERMISSION_DENIED", "message": "Native consent is required" }
}
```

Require matching version and request ID. Error `code` is 1–64 characters; `message` is 1–1,024 characters, neither containing NUL. Use fixed documented codes, for example `INVALID_REQUEST`, `UNAUTHORIZED`, `UNKNOWN_TOOL`, `PERMISSION_DENIED`, `NOT_READY`, `PAUSED`, `STALE_OBSERVATION`, `OUT_OF_BOUNDS`, `BUSY`, `TIMEOUT`, `INTERNAL_ERROR`. Do not put UI text, credentials or other dynamic values in codes. The adapter does not reflect native error messages. HTTP/auth/transport failures are generic local failures.

**A timeout or disconnect does not mean an action failed.** Never automatically repeat a mutation. Native code should record request IDs and reject replay without re-executing an action. Failed or malformed responses invalidate cached observations.

## Fixed tool arguments

All fields are required unless explicitly marked optional. `{}` means no properties at all. All numeric fields are finite safe integers, never strings.

| Tool | Arguments | Result |
|---|---|---|
| `phone.status` | `{}` | Status object below |
| `phone.apps` | `{}` | App list below |
| `phone.start` | `packageName` (optional; the AI screen is a standing display, phone.start is not required) | `{ "performed": true }` |
| `phone.screenshot` | `{}` | Screenshot below |
| `phone.ui_tree` | `{}` | UI tree below |
| `phone.tap` | `observationId`, and either `x`+`y` or `elementId` (a node of the latest `phone.ui_tree`) | `{ "performed": true }` |
| `phone.long_press` | `observationId`, either `x`+`y` or `elementId`, optional `durationMs` (500–2,000) | `{ "performed": true }` |
| `phone.swipe` | `observationId`, `x1`, `y1`, `x2`, `y2`, `durationMs` (100–2,000) | `{ "performed": true }` |
| `phone.scroll` | `observationId`, `deltaY` (−1,000–1,000; nonzero), optional `x`+`y` (omitted: centre of the largest scrollable region) | `{ "performed": true, "moved": bool, "note"?: string }` |
| `phone.read_page` | `observationId`, optional `maxPages` (1–12, default 4) | `{ "performed": true, "text", "pages", "reachedEnd", "title"? }` |
| `phone.type_text` | `observationId`, `text` (0–4,000 characters); optional `elementId` (1–256) | `{ "performed": true }` |
| `phone.key` | `key`: `BACK`, `ENTER`, `HOME` or `RECENTS`; `observationId` required for `BACK`/`ENTER` only | `{ "performed": true }`, `RECENTS` adds `note` (app list) |
| `phone.wait` | `timeoutMs` (0–10,000) | `{ "performed": true }` |
| `phone.launch_app` | `observationId`, `packageName` | `{ "performed": true }` |
| `phone.pause` | `{}` | `{ "performed": true }` |
| `phone.stop` | `{}` | `{ "performed": true }` |
| `phone.request_intervention` | optional `reason` (1–120 chars) | native `{ "performed": true, "interventionId" }`; the plugin blocks (polls internal `phone.intervention_status` ~1 s, max 15 min) and returns `{ performed, outcome, note }` |
| `phone.intervention_status` (internal, not model-visible) | `{}` | `{ active, interventionId, outcome: none/active/handed_back/expired/cancelled }` |

`packageName` is at most 255 characters, matches an Android-style dotted identifier (`[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+`), and must additionally be explicitly approved natively. It is not an intent, URI, executable name or command line. The display has its own virtual navigation keys: `BACK` (display-local key event), `HOME` (opens the profile-owned AI desktop; the running app keeps running in the background) and `RECENTS` (lists the work-profile tasks on the owned display without changing it). `HOME` and `RECENTS` address the owned display, not pixels, so they need no observation; `phone.launch_app` switched back to an app needs none either. `phone.start` and `phone.launch_app` never authorize arbitrary applications.

Native behavior requirements:

- `start`: create/claim a protected virtual-display session for an approved package only; fail rather than change the user's main display.
- `pause`: stop accepting mutations, retaining enough state for the user to inspect the reason.
- `stop`: complete safe session/display teardown before returning `performed: true`; do not report a mere request acknowledgment as completion.
- `request_intervention`: open native user-intervention UI, without giving the model an approval primitive or clearing a pause.
- `wait`: bounded wait only, not an arbitrary polling expression or script.
- `type_text`: use only a supported display-bound native text channel. Do not silently fall back to shared clipboard or main-display IME. If an `elementId` is supplied, it must identify an editable node in the exact current tree. Otherwise native focus validation is still mandatory.

## Result objects

All result objects reject extra fields. `performed` must be exactly `true`; denials or incomplete operations use `ok: false`, not a misleading successful object.

### Status and apps

Status contains exactly `state`, `accessibilityReady` and `screenCaptureReady`. State is `ready`, `paused`, `stopped` or `unavailable`; the readiness fields are booleans. A state other than `ready` invalidates observations. Status is real native state, not generated prose.

Apps contains exactly `apps`, an array of at most 500 objects, each containing `packageName` (1–255 characters), `label` (0–256 characters) and `approved` (boolean). This list does not grant approval; native state is authoritative at each action.

### Shared observation metadata

Screenshot and UI tree results include:

| Field | Type and meaning |
|---|---|
| `observationId` | Opaque 1–256 character string without NUL, unique to one native display generation and session |
| `capturedAt` | Unix epoch milliseconds, safe integer; no more than 60 seconds old or 1 second in the future; screen generation and single-use validation remain mandatory |
| `width`, `height` | Safe integers 1–8,192, dimensions of the controlled virtual display |

Coordinates are **virtual-display physical pixels** in this observation, not main-display/screen-global coordinates: `0 <= x < width`, `0 <= y < height`. No explicit display ID is accepted from the model. The backend binds it internally.

### Screenshot

Screenshot adds exactly `mimeType` (`image/png`, `image/jpeg` or `image/webp`) and `base64` (nonempty canonical base64, at most 11,184,812 characters, approximately 8 MiB decoded). Native code must provide the actual image format and matching dimensions. The adapter admits the image through DSH `attachments.saveImage` and returns a canonical image attachment plus metadata to the model—not a raw base64 text dump. Failed image admission invalidates the observation.

### UI tree

UI tree adds exactly `title`, `package`, `nodes`, at most 2,000 objects. Each node contains:

- `id`: unique 1–256 character identifier;
- `text`, `desc`, `role`, `rid`, `flags`: optional strings of at most 4,000 characters each (empty fields are omitted; `flags` letters: c clickable, e editable, s scrollable, f focused, k checked);
- `bounds`: exactly four integers `[left, top, right, bottom]`, with `0 <= left <= right <= width` and `0 <= top <= bottom <= height`.

All text is untrusted observed data. Omit password/secret content and unrelated main-display data. A node ID is not a permission token.

## Observation and action lifecycle

1. Request `status` and verify native readiness/consent.
2. Request a new screenshot or UI tree; inspect that observation, including the image when using coordinates.
3. Send one action carrying that exact `observationId`. The adapter checks ownership, expiry and coordinate bounds and consumes the observation **before** dispatch, so a coordinate action cannot be replayed after it reached the broker. An action that is refused by argument or element validation (the native event was never injected) leaves the observation usable, and an `elementId` action verifies the tree observation instead of consuming the pixel one, so `phone_ui_tree` → `phone_screenshot` → `phone_tap(elementId)` is a valid reading flow.
4. Observe again before another action. Never retain an old screenshot coordinate across app switches, user activity or other mutations.
5. On denial, pause, timeout, mismatch or uncertain outcome, stop automatic actions and inspect real state. Do not bypass protections, clear pause flags or retry mutations blindly.

The **native server must own one physical-display generation globally**, invalidate all affected observations across sessions/processes on every mutation/user change, and reject observations from any earlier generation. Client-side serialization/cache checks alone cannot detect external screen changes or serve as the ultimate stale-action boundary.

## Native authorization requirements

These are required backend behavior, not guarantees that a DSH JavaScript plugin can supply:

1. Keep exact controlled-display binding and main-display protection. Pause when main-screen ownership changes or safety cannot be established. No default-display input, global force-stop, root escalation, or automatic pause override.
2. Obtain explicit native user permission for session start and the allowed app set. Disallow terminal/code runners, installers, developer/configuration/credential screens and other routes to shell, arbitrary code, package installation or policy changes.
3. Payment, message sending, deletion, account changes, authorization grants and similarly high-impact actions require exact-action native user confirmation. Do not accept a model boolean such as `approved` or a generic earlier consent as authorization.
4. Revalidate the foreground package, observation generation, permissions and confirmation immediately before execution. Enforce authentication, replay protection, bounded input and global serialization on the server too.
5. Expose only the sixteen enumerated capabilities. There is no shell endpoint, generic intent, filesystem path, arbitrary method dispatch, MCP relay or developer backdoor.

## Task-island activity

`/v1/state` now carries an `activity` object `{seq, app, text, events:[{id,text}]}` produced natively by the broker from key actions for the task island. No model call is involved and no plugin-side publication endpoint exists.

## Offline contract checks

[The strict broker tests](<test/strict-broker.test.mjs>) independently exercise every capability, unknown arguments, correlation, observation bounds/expiry and failure behavior with fake transport. [The integration tests](<test/strict-integration.test.mjs>) check the DSH guard and canonical image delivery. These are not end-to-end Android tests.

Changes to [the argument/result schemas](<src/broker.mjs>) require coordinated native implementation updates and a protocol-version compatibility review. Unknown fields fail closed; they must never be forwarded as a convenient extension mechanism.


UI tree: title, package, nodes (compact: id, text?, desc?, role?, rid?, bounds [l,t,r,b], flags? letters c/e/s/f/k), complete and advice. Native error USER_CONTROL (user holds the screen) is not an error for the model: the plugin waits for hand-back, re-issues observations once, and reports mutations as {performed:false,userControlled:true,note}.
