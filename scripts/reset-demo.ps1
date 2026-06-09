# Materialises the bundled consumer into .\workspace as a fresh Git repository.
# Safe to re-run at any time; it discards all demo mutations (FR-022).
$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $PSScriptRoot
$Template = Join-Path $Root "samples\customer-consumer"
$Target = Join-Path $Root "workspace\customer-consumer"

if (Test-Path $Target) {
    Remove-Item -Recurse -Force $Target
}
New-Item -ItemType Directory -Force (Join-Path $Root "workspace") | Out-Null
Copy-Item -Recurse $Template $Target

Set-Location $Target
git init -q -b main
git add -A
# The demo repository gets its own throwaway identity; the user's global
# Git configuration is never touched.
git -c user.name="Demo Consumer" -c user.email="demo@contractguard.local" commit -q -m "Initial consumer state"

Write-Host "Demo repository ready at $Target"
