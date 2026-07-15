$ErrorActionPreference = "Stop"

$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$FrontendRoot = Join-Path $RepoRoot "Frontend"

& (Join-Path $PSScriptRoot "check-frontend-config.ps1")

function Invoke-NpmCheck {
  param(
    [Parameter(Mandatory = $true)]
    [string[]] $Arguments
  )

  & npm @Arguments
  if ($LASTEXITCODE -ne 0) {
    throw "前端检查失败: npm $($Arguments -join ' '), 退出码: $LASTEXITCODE"
  }
}

Push-Location $FrontendRoot
try {
  Invoke-NpmCheck -Arguments @("audit", "--audit-level=high")
  Invoke-NpmCheck -Arguments @("test", "--", "--run")
  Invoke-NpmCheck -Arguments @("run", "typecheck")
  Invoke-NpmCheck -Arguments @("run", "build")
}
finally {
  Pop-Location
}
