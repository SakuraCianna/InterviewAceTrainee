$ErrorActionPreference = "Stop"

$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$ConfigPath = Join-Path $RepoRoot "Frontend\src\config\productConfig.ts"
$ForbiddenPaths = @(
    (Join-Path $RepoRoot "Frontend\Dockerfile"),
    (Join-Path $RepoRoot "Frontend\src"),
    (Join-Path $RepoRoot ".github\workflows\ci.yml")
)
$ForbiddenNames = @(
    "VITE_ADMIN_ENTRY_PATH",
    "VITE_ICP_NUMBER",
    "VITE_POLICE_RECORD_NUMBER"
)

if (-not (Test-Path -LiteralPath $ConfigPath -PathType Leaf)) {
    throw "前端产品配置文件不存在: $ConfigPath"
}

foreach ($path in $ForbiddenPaths) {
    $files = if (Test-Path -LiteralPath $path -PathType Container) {
        Get-ChildItem -LiteralPath $path -Recurse -File -Include "*.ts", "*.tsx"
    }
    else {
        Get-Item -LiteralPath $path
    }
    foreach ($name in $ForbiddenNames) {
        $matches = $files | Select-String -Pattern $name -SimpleMatch
        if ($matches) {
            throw "前端固定产品元数据不得继续使用构建期字段 $name"
        }
    }
}

$AppPath = Join-Path $RepoRoot "Frontend\src\App.tsx"
$HomePath = Join-Path $RepoRoot "Frontend\src\pages\HomePage.tsx"
if ((Get-Content -LiteralPath $AppPath -Raw) -notmatch 'productConfig\.adminEntryPath') {
    throw "管理员入口没有使用唯一产品配置"
}
if ((Get-Content -LiteralPath $HomePath -Raw) -notmatch 'productConfig\.filing') {
    throw "备案信息没有使用唯一产品配置"
}

Write-Host "前端固定产品配置校验通过"
