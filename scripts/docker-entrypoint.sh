#!/usr/bin/env bash
# Container startup: materialises the bundled demo repository/specs unless
# opted out, then starts the backend. Splitting this out of the Dockerfile's
# CMD keeps the CONTRACTGUARD_DEMO_CONTENT branch (and its own directory
# setup) readable instead of a single conditional-laden shell one-liner.
set -euo pipefail

SPECS_DIR=/app/samples/openapi

if [ "${CONTRACTGUARD_DEMO_CONTENT:-true}" = "true" ]; then
    ./scripts/reset-demo.sh
else
    echo "CONTRACTGUARD_DEMO_CONTENT=false: skipping the bundled demo repository and specs"
    rm -rf /app/workspace
    mkdir -p /app/workspace
    SPECS_DIR=/app/local-specs
    mkdir -p "$SPECS_DIR"
fi

exec java -jar app.jar \
    --server.address=0.0.0.0 \
    --contractguard.workspace.roots[0]=/app/workspace \
    --contractguard.storage.directory=/app/data \
    --contractguard.specs.directory="$SPECS_DIR" \
    --spring.datasource.url="jdbc:h2:file:/app/data/contractguard;MODE=PostgreSQL"
