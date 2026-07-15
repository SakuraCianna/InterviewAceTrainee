$ErrorActionPreference = "Stop"

$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$BackendRoot = Join-Path $RepoRoot "Backend"
$MavenWrapper = Join-Path $BackendRoot "mvnw.cmd"

if (-not (Test-Path -LiteralPath $MavenWrapper -PathType Leaf)) {
  throw "未找到后端 Maven Wrapper: $MavenWrapper"
}

Push-Location $BackendRoot
try {
  & $MavenWrapper verify
  if ($LASTEXITCODE -ne 0) {
    throw "后端 Maven 验证失败, 退出码: $LASTEXITCODE"
  }
}
finally {
  Pop-Location
}
