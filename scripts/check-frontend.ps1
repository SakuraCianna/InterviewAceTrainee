$ErrorActionPreference = "Stop"

$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$FrontendRoot = Join-Path $RepoRoot "Frontend"

Push-Location $FrontendRoot
try {
  npm run build
}
finally {
  Pop-Location
}
