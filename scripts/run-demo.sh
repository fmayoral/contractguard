#!/usr/bin/env bash
# One-command local demo: resets the sample repository, builds and starts the
# backend, and starts the dashboard dev server. Ctrl-C stops both.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

"$ROOT/scripts/reset-demo.sh"

echo "Building backend..."
(cd "$ROOT/backend" && ./mvnw -q -B -ntp package -DskipTests \
    -Dcheckstyle.skip -Dspotbugs.skip -Djacoco.skip)

echo "Starting backend on http://127.0.0.1:8091 ..."
(cd "$ROOT/backend" && java -jar target/contractguard-backend-*-SNAPSHOT.jar) &
BACKEND_PID=$!
trap 'kill $BACKEND_PID 2>/dev/null || true' EXIT

echo "Starting dashboard on http://localhost:5173 ..."
cd "$ROOT/frontend"
if [ ! -d node_modules ]; then
  npm install
fi
npm run dev
