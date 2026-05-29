$ErrorActionPreference = "Stop"

$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")

& (Join-Path $RepoRoot "scripts\check-backend.ps1")
& (Join-Path $RepoRoot "scripts\check-frontend.ps1")

Push-Location $RepoRoot
try {
  docker compose config --quiet
}
finally {
  Pop-Location
}
