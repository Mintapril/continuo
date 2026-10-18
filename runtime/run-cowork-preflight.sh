#!/bin/bash
# Fixed read-only preflight invocation, not a DSH boot or configuration writer.
set -euo pipefail
cd /workspace
exec /usr/bin/env -i HOME=/root DSH_HOME=/root/.dsh PATH=/opt/node/bin:/usr/bin:/bin DSH_TELEMETRY_DISABLED=1 NODE_DISABLE_COMPILE_CACHE=1 /opt/node/bin/node --permission --allow-fs-read=/ --no-warnings /opt/cowork-preflight-v1.mjs
