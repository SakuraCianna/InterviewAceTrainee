$ErrorActionPreference = "Stop"

$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")

& (Join-Path $RepoRoot "scripts\check-env-schema.ps1")
& (Join-Path $RepoRoot "scripts\check-backend.ps1")
& (Join-Path $RepoRoot "scripts\check-frontend.ps1")
