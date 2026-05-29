$ErrorActionPreference = "Stop"

$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$BackendRoot = Join-Path $RepoRoot "Backend"

Push-Location $BackendRoot
try {
  uv run python -m unittest discover -s tests
  uv run python -m compileall app tests
}
finally {
  Pop-Location
}
