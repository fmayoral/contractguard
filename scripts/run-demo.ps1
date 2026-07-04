# One-command local demo: resets the sample repository, builds and starts the
# backend, and starts the dashboard dev server.
$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $PSScriptRoot

& (Join-Path $Root "scripts\reset-demo.ps1")

Write-Host "Building backend..."
Push-Location (Join-Path $Root "backend")
& .\mvnw.cmd -q -B -ntp package -DskipTests "-Dcheckstyle.skip" "-Dspotbugs.skip" "-Djacoco.skip"

Write-Host "Starting backend on http://127.0.0.1:7080 ..."
$Jar = Get-ChildItem target\contractguard-backend-*-SNAPSHOT.jar | Select-Object -First 1
$Backend = Start-Process java -ArgumentList "-jar", $Jar.FullName -PassThru -NoNewWindow
Pop-Location

try {
    Write-Host "Starting dashboard on http://localhost:5173 ..."
    Push-Location (Join-Path $Root "frontend")
    if (-not (Test-Path node_modules)) {
        npm install
    }
    npm run dev
} finally {
    Pop-Location
    if ($Backend -and -not $Backend.HasExited) {
        Stop-Process -Id $Backend.Id -Force
    }
}
