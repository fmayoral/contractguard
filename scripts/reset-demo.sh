#!/usr/bin/env bash
# Materialises the bundled consumer into ./workspace as a fresh Git repository.
# Safe to re-run at any time; it discards all demo mutations (FR-022).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEMPLATE="$ROOT/samples/customer-consumer"
TARGET="$ROOT/workspace/customer-consumer"

rm -rf "$TARGET"
mkdir -p "$ROOT/workspace"
cp -R "$TEMPLATE" "$TARGET"
chmod +x "$TARGET/mvnw" 2>/dev/null || true

cd "$TARGET"
git init -q -b main
git add -A
# The demo repository gets its own throwaway identity; the user's global
# Git configuration is never touched.
git -c user.name="Demo Consumer" -c user.email="demo@contractguard.local" \
    commit -q -m "Initial consumer state"

echo "Demo repository ready at $TARGET"
