# Cowork native broker protocol v1

Fixed typed contract used by [the shipped broker adapter](<src/broker.mjs>) and [the restricted preset](<src/restricted.mjs>) on DSH 0.2.1-rc.2. This replaces the exploratory underscore-tool contract. Model and wire tool names are now the same dotted names.

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
| `phone.screenshot` | `{}`, or a zoom: `region` `[left, top, right, bottom]` (integer screen pixels, right/bottom exclusive, at least 8 a side) and `scale` (1–8). The model may omit `scale`; the adapter sends the largest that fits | Screenshot below |
| `phone.ui_tree` | `{}` | UI tree below |
| `phone.tap` | `observationId`, and either `x`+`y` or `elementId` (a node of the latest `phone.ui_tree`) | `{ "performed": true }` + settled screen (below) |
| `phone.long_press` | `observationId`, either `x`+`y` or `elementId`, optional `durationMs` (500–2,000) | `{ "performed": true }` + settled screen |
| `phone.swipe` | `observationId`, `x1`, `y1`, `x2`, `y2`, `durationMs` (100–2,000) | `{ "performed": true }` + settled screen |
| `phone.scroll` | `observationId`, `deltaY` (−1,000–1,000; nonzero), optional `x`+`y` (omitted: centre of the largest scrollable region) | `{ "performed": true, "moved": bool, "note"?: string }` + settled screen |
| `phone.read_page` | `observationId`, optional `maxPages` (1–12, default 4) | `{ "performed": true, "text", "pages", "reachedEnd", "title"? }` |
| `phone.type_text` | `observationId`, `text` (0–4,000 characters); optional `elementId` (1–256) | `{ "performed": true }` + settled screen |
| `phone.key` | `key`: `BACK`, `ENTER`, `HOME` or `RECENTS`; `observationId` required for `BACK`/`ENTER` only | `{ "performed": true }` + settled screen; `RECENTS` returns `note` (app list) and no screen |
| `phone.wait` | `timeoutMs` (0–10,000) | `{ "performed": true }` + settled screen |
| `phone.batch` | `observationId` (a `phone.ui_tree` observation with `complete` not `false`), `steps`: 2–5 of `{tool:"tap"\|"long_press", elementId}`, `{tool:"type_text", elementId, text}` and, only last, `{tool:"key", key:"ENTER"\|"BACK"}`; each element at most once, at most 8,000 text characters in total | `{ "performed": true, "completed": n, "stoppedAt"?, "stopReason"?, "uncertain"?, "note"? }` + settled screen |
| `phone.gesture` | `observationId`, `strokes`: 1–32 of `{ fingers: [[x0, y0, x1, y1, …], …], durationMs, holdStartMs, holdEndMs }`: 1–2 fingers moving together, each integer points on the screen (at least one), `durationMs` 20–10,000 (movement time; fingers that do not move are pressed that long), holds 0–3,000; at most 3,000 points (all fingers) and 15,000 ms per call, counting a 60 ms lift between strokes. The model writes SVG path data instead: its arguments are `strokes` of `{ path | fingers: [path, path], durationMs?, holdStartMs?, holdEndMs? }` (one subpath per finger) and optional `viewBox` + `area`, which the plugin flattens to within 0.5 px (`src/gesture-path.mjs`) | `{ "performed": true, "completed": n, "stoppedAt"?, "stopReason"?, "uncertain"?, "note"? }` + settled screen |
| `phone.launch_app` | `observationId`, `packageName` | `{ "performed": true }` |
| `phone.pause` | `{}` | `{ "performed": true }` |
| `phone.stop` | `{}` | `{ "performed": true }` |
| `phone.request_intervention` | optional `reason` (1–120 chars) | native `{ "performed": true, "interventionId" }`; the plugin blocks (polls internal `phone.intervention_status` ~1 s, max 15 min) and returns `{ performed, outcome, note }` |
| `phone.intervention_status` (internal, not model-visible) | `{}` | `{ active, interventionId, outcome: none/active/handed_back/expired/cancelled }` |
| `phone.notify_owner` | `text` (1–200 chars, not blank) | `{ "performed": true }`; the plugin returns `{ performed, note }` telling the model to end the task. The plugin admits it only in a turn without a user message (a reminder or a notification started it), once per turn, and refuses `phone.request_intervention` in such a turn |
| `phone.grant_permission` | `packageName`, `permission`: `location`, `camera`, `microphone`, `storage`, `contacts` or `notifications`, each a fixed list of runtime permissions (`PERMISSION_GROUPS` in `src/broker.mjs`, the same as `ProfileAppPolicy.GROUPS`) | `{ "performed": true, "granted": [], "held": [], "kept": [] }`: permission names of the requested group, each at most once across the three; `granted` and `held` are not both empty |
| `phone.uninstall_app` | `packageName` | `{ "performed": true }` |

`packageName` is at most 255 characters, matches an Android-style dotted identifier (`[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+`), and must additionally be explicitly approved natively. It is not an intent, URI, executable name or command line. The display has its own virtual navigation keys: `BACK` (display-local key event), `HOME` (opens the profile-owned AI desktop; the running app keeps running in the background) and `RECENTS` (lists the work-profile tasks on the owned display without changing it). `HOME` and `RECENTS` address the owned display, not pixels, so they need no observation; `phone.launch_app` switched back to an app needs none either. `phone.start` and `phone.launch_app` never authorize arbitrary applications.

Native behavior requirements:

- `start`: create/claim a protected virtual-display session for an approved package only; fail rather than change the user's main display.
- `pause`: stop accepting mutations, retaining enough state for the user to inspect the reason.
- `stop`: complete safe session/display teardown before returning `performed: true`; do not report a mere request acknowledgment as completion.
- `request_intervention`: open native user-intervention UI, without giving the model an approval primitive or clearing a pause.
- `grant_permission`: only a third-party app of the verified work profile (not a system app, the profile owner, another `dev.dsh.pocket.` package or a package `ActionGate.eligiblePackage` refuses: `APP_DENIED`; not installed there: `APP_UNKNOWN`), for the profile user only. Grant the group's runtime permissions that the app requests and does not hold, unless a user, policy or system flag decided them (`kept`, left as they are); read back what is held. `PERMISSION_UNAVAILABLE` when the app then holds none of the group. No observation, no input; the observations of the screen are dropped (a permission dialog may close).
- `uninstall_app`: the same app rule; uninstall for the profile user only (flags 0, never all users), parking the display first when the app is visible there. `UNINSTALL_FAILED` when the system refuses or does not answer within 15 s; `USER_CONTROL` while the user holds the screen (the plugin waits for hand-back and reports `performed: false`).
- `notify_owner`: keep the text as an owner notice for the controller (`ownerNotices` in `/v1/state`); touch no screen, claim no display, need no observation.
- `wait`: bounded wait only, not an arbitrary polling expression or script.
- `batch`: run the steps in order and check right before each one: the window set and foreground UID are still those of the observation, nothing labelled outside the previous step's element changed after that step settled, and the target is still visible and enabled at its observed bounds with its observed text and description. A tap or long press whose observed label or view id names a high-impact action (send, reply, pay, delete, confirm, agree, grant, buy, submit, publish and their Chinese equivalents), an unlabelled target that is neither a field nor a toggle, and `ENTER` unless the batch typed into a field labelled as a search box, are left to a single call (`HIGH_IMPACT_SINGLE`). Before the first input any refusal is an ordinary `ok: false` that keeps the observation; after it the result is always `performed: true` with `completed` (steps performed) and, when stopped, `stoppedAt` (= `completed`) and `stopReason` (`WINDOW_CHANGED`, `SCREEN_CHANGED_ELSEWHERE`, `SCREEN_SETTLING`, `BATCH_TIME_LIMIT` after 15 s, `USER_CONTROL`, `HIGH_IMPACT_SINGLE` or a refusal code); `uncertain: true` means the stopped step's input may have partly happened. The adapter maps an unknown `stopReason` to `BROKER_ERROR` and adds a `note`.
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

A zoomed screenshot is a PNG of the region enlarged `scale` times with nearest-neighbour pixels, framed by a white band of `margin` (60) pixels on every side with rulers labelled in screen pixels; a side of the image, band included, is at most 1,536 pixels. It adds exactly `region` and `scale` (as requested), `margin`, `imageWidth` and `imageHeight` (`(right − left) × scale + 2 × margin`, likewise for the height, matching the PNG header). `width` and `height` stay those of the display and the observation works as for a full screenshot: screen x = `left + (imageX − margin) / scale`. A plain screenshot carries none of these fields. Limits are shared by `ScreenZoom.java` and `ZOOM` in `src/broker.mjs`.

### UI tree

UI tree adds exactly `title`, `package`, `nodes`, at most 2,000 objects. Each node contains:

- `id`: unique 1–256 character identifier;
- `text`, `desc`, `role`, `rid`, `flags`: optional strings of at most 4,000 characters each (empty fields are omitted; `flags` letters: c clickable, e editable, s scrollable, f focused, k checked);
- `bounds`: exactly four integers `[left, top, right, bottom]`, with `0 <= left <= right <= width` and `0 <= top <= bottom <= height`.

All text is untrusted observed data. Omit password/secret content and unrelated main-display data. A node ID is not a permission token.

Optionally, `appeared`: 1–20 strings of 1–300 characters, the text lines that showed up on the screen by themselves since the previous tree the session was shown, while no tool of the turn ran (the Broker reads the screen about once a second then, after the same ownership and secure-window check as an observation, binding nothing). Lines the model typed this turn are left out; lines that disappeared again (an in-app banner) stay in. Absent when there are none. A UI tree inside a settled screen (`observation`) may carry it too.

## Observation and action lifecycle

1. Request `status` and verify native readiness/consent.
2. Request a new screenshot or UI tree; inspect that observation, including the image when using coordinates.
3. Send one action carrying that exact `observationId`. The adapter checks ownership, expiry and coordinate bounds and consumes the observation **before** dispatch, so a coordinate action cannot be replayed after it reached the broker. An action that is refused by argument or element validation (the native event was never injected) leaves the observation usable, and an `elementId` action verifies the tree observation instead of consuming the pixel one, so `phone_ui_tree` → `phone_screenshot` → `phone_tap(elementId)` is a valid reading flow.
4. Observe again before another action. The settled screen returned by an input counts as that observation. Never retain an old screenshot coordinate across app switches, user activity or other mutations.

**Settled screen.** Taps, long presses, swipes, scrolls, text input, `BACK`/`ENTER`/`HOME`, `phone.wait`, `phone.batch` and `phone.gesture` wait until the visible structure stops changing (first read 300 ms after the input, settled at two equal reads 120 ms apart, at most six reads) and add optional fields to their result: `observation` (exactly a `phone.ui_tree` result, with its own `observationId`; it becomes the current tree and pixel observation of the session), `changed` (whether it differs from the tree the model read last), `settled` (`false` when the content was still changing at the last read) and `observationError` (a documented code: the input was performed but the screen could not be read). The adapter validates `observation` separately from the result: an invalid one is dropped and reported as `observationError: "BROKER_ERROR"`, never as a failed input, and any `observationError` drops the session's cached observations and adds a `note` with the next step. No other tool may carry `observation`.
5. On denial, pause, timeout, mismatch or uncertain outcome, stop automatic actions and inspect real state. Do not bypass protections, clear pause flags or retry mutations blindly.

**End of a turn.** `POST /v1/task` with `operation: "end"` and outcome `completed`, `stopped` or `error` makes the Broker park the AI display as `phone.stop` does (the AI desktop covers it; the apps keep running behind it), so no app stays in the foreground there and none suppresses its own notifications. It does not park while the turn waits for the user (approval pending, paused, outcome `waiting_user`). The next turn's `phone.launch_app` returns to the app's existing task. Right before parking (also after `phone.stop`) the Broker reads the screen once more; the lines the model never saw (recorded `appeared` lines it was not shown yet, and new lines of that read, when it shows the app the model last observed) go into the Broker's notification-trigger batch as screen text of that app (`notify` in `/v1/state`, below).

The **native server must own one physical-display generation globally**, invalidate all affected observations across sessions/processes on every mutation/user change, and reject observations from any earlier generation. Client-side serialization/cache checks alone cannot detect external screen changes or serve as the ultimate stale-action boundary.

## Native authorization requirements

These are required backend behavior, not guarantees that a DSH JavaScript plugin can supply:

1. Keep exact controlled-display binding and main-display protection. Pause when main-screen ownership changes or safety cannot be established. No default-display input, global force-stop, root escalation, or automatic pause override.
2. Obtain explicit native user permission for session start and the allowed app set. Disallow terminal/code runners, installers, developer/configuration/credential screens and other routes to shell, arbitrary code, package installation or policy changes.
3. Payment, message sending, deletion, account changes, authorization grants and similarly high-impact actions require exact-action native user confirmation. Do not accept a model boolean such as `approved` or a generic earlier consent as authorization.
   - Work-profile mode: the verified work profile is the authorization boundary and the owner left these decisions inside it to the model (2026-10-23), so there is no per-action confirmation. Removing a package and granting runtime permissions are limited to `phone.uninstall_app` and `phone.grant_permission`: third-party apps of that profile only, a fixed list of runtime permissions, never special app access.
4. Revalidate the foreground package, observation generation, permissions and confirmation immediately before execution. Enforce authentication, replay protection, bounded input and global serialization on the server too.
5. Expose only the capabilities enumerated above. There is no shell endpoint, generic intent, filesystem path, arbitrary method dispatch, MCP relay or developer backdoor.

## Task-island activity

`/v1/state` now carries an `activity` object `{seq, app, text, events:[{id,text}]}` produced natively by the broker from key actions for the task island. No model call is involved and no plugin-side publication endpoint exists.

`/v1/state` also carries `ownerNotices`: the last 5 `phone.notify_owner` texts as `[{seq, text, at}]`, oldest first, `seq` counting from 1 within one broker `instanceId`, `at` in wall-clock milliseconds. The controller shows each notice once as a notification and remembers the last `instanceId` and `seq` it showed. Notices live in broker memory only.

`/v1/state` also carries `notify`: `{settings: {enabled, excluded, quietSeconds}, log: [{at, text}]}`, the notification-trigger settings in force and the newest outcomes (up to 40, newest first), or `null` when the Broker's notification listener could not register. The Broker reads notifications itself (a system notification listener for every user): ColorOS freezes Continuo while the screen is off and holds its listener callbacks. The controller edits the settings with `POST /v1/notify-settings` and exactly `{enabled: boolean, excluded: [package, …] (≤ 200), quietSeconds: 1–60}`; the Broker answers with the new `notify` object, keeps the settings in a root-only file, and refuses anything else with `409 PROTOCOL_INVALID`. Text a turn left unseen on the AI screen goes straight into the Broker's batch as a screen-text item (`origin: "screen"`, see Notification ingress).

## Offline contract checks

[The strict broker tests](<test/strict-broker.test.mjs>) independently exercise every capability, unknown arguments, correlation, observation bounds/expiry and failure behavior with fake transport. [The integration tests](<test/strict-integration.test.mjs>) check the DSH guard and canonical image delivery. These are not end-to-end Android tests.

Changes to [the argument/result schemas](<src/broker.mjs>) require coordinated native implementation updates and a protocol-version compatibility review. Unknown fields fail closed; they must never be forwarded as a convenient extension mechanism.


UI tree: title, package, nodes (compact: id, text?, desc?, role?, rid?, bounds [l,t,r,b], flags? letters c/e/s/f/k), complete and advice. Native error USER_CONTROL (user holds the screen) is not an error for the model: the plugin waits for hand-back, re-issues observations once, and reports mutations as {performed:false,userControlled:true,note}.

## Notification ingress

The notification triggers (read and batched by the Broker, settings from Continuo) reach the Cowork plugin (`src/notify.mjs`, a host-plane plugin of this bundle) over a separate loopback listener; it is not part of the Broker protocol above and shares no secret with it.

- `POST http://127.0.0.1:3082/v1/notify`, `content-type: application/json`, body at most 64 KiB.
- `x-cowork-notify-signature`: lowercase hex HMAC-SHA256, keyed with the UTF-8 text of the 64-hex-character token in `/root/.config/dsh/cowork-notify.token` (rootfs; created 0600 by `cowork-service.sh notify-token`), over `cowork-notify-v1\n` followed by the exact body bytes. The plugin re-reads the token on every request.
- Body (one batch, any apps): exactly `v` (2), `id` (UUID, never reused), `sentAt` (ms, within 60 s of the plugin clock), `count` (1–999 notifications merged), `firstAt` ≤ `lastAt` ≤ `sentAt` (ms), and `items`: 1–20 of exactly `{package, app (1–60 characters, one line), profile (work | personal), sender ≤ 80, text ≤ 400, at}`, the newest notifications of the batch. An item may add `origin: "screen"` (only that value, only with `profile: "work"`): not a notification but the lines a Cowork turn left unseen on that app's screen, joined by line breaks, newest end kept; without `origin` it is a notification. Control characters other than tab and line breaks are refused everywhere; extra or missing fields fail closed.
- Every batch goes to TypeSafe's Jev (`typesafe/jev-1.14` through OpenRouter's `/api/v1/systemone`, key from the DSH credential `JEV_API_KEY`), one yes/no question `nK` per notification (for screen text: whether it holds a new message from someone or something to answer). Answers: `202 {"queued":true,"judged":true,"meaningful":max p,"kept":k,"total":n}` when k ≥ 1 notifications have P(meaningful) ≥ 0.5 (only those k go on, each to the standing session of its app, `cowork-app-<profile>-<package>` with the dots of the package as dashes: into its running turn, or as its next turn once no Cowork turn holds the AI screen), `200 {"queued":false,"judged":true,"meaningful":max p,"kept":0,"total":n}` when none has, `200 {"queued":false,"judged":false,"judgeError":code}` when Jev could not judge, and plain-text `400` (schema, staleness, signature header), `401` (signature), `405`, `409` (replayed `id`), `413`, `415`, `503` (no token yet, or ingress unavailable).
- A queued batch's `items` reaches the session as `notifications_untrusted_json`, inside the fixed `[NOTIFICATION TRIGGER]` framing whose instruction is the plugin's own. Every value is JSON-encoded (U+2028/U+2029 escaped) and the notification text is labelled as other people's words, never instructions. The message source is `{kind: "webhook", ruleId: "cowork-notify", ...}`.

The test vector in `test/strict-notify.test.mjs` (`VECTOR`) is asserted byte-for-byte by `app/src/test/.../notify/NotifyPolicyTest.kt`; change both together. `SCREEN_BODY` there is a screen-text item as Android writes it; `NotifyPolicyTest` checks the same fields (the JVM tests use json.org's unordered `JSONObject`, so not byte-for-byte).
