$ErrorActionPreference = "Stop"

$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$ExamplePath = Join-Path $RepoRoot ".env.example"
$LocalEnvPath = Join-Path $RepoRoot ".env"
$ComposePath = Join-Path $RepoRoot "docker-compose.yml"

function Read-EnvSchema {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "环境变量文件不存在: $Path"
    }

    $lines = Get-Content -LiteralPath $Path -Encoding UTF8
    $keys = New-Object System.Collections.Generic.List[string]
    $seen = @{}

    for ($index = 0; $index -lt $lines.Count; $index++) {
        $line = $lines[$index]
        if ([string]::IsNullOrWhiteSpace($line) -or $line.StartsWith("#")) {
            continue
        }

        if ($line -notmatch '^([A-Z][A-Z0-9_]*)=(.*)$') {
            throw "环境变量格式无效: $Path 第 $($index + 1) 行"
        }

        $key = $Matches[1]
        if ($seen.ContainsKey($key)) {
            throw "环境变量字段重复: $Path 字段 $key"
        }

        if ($index -eq 0 -or $lines[$index - 1] -notmatch '^#\s*(.+)$') {
            throw "环境变量缺少紧邻的中文注释: $Path 字段 $key"
        }

        $comment = $Matches[1]
        if ($comment -notmatch '[\u4e00-\u9fff]') {
            throw "环境变量注释必须包含中文: $Path 字段 $key"
        }
        if ($comment -match '[，。；：！？、]') {
            throw "环境变量注释必须使用英文标点: $Path 字段 $key"
        }

        $seen[$key] = $true
        $keys.Add($key)
    }

    if ($keys.Count -eq 0) {
        throw "环境变量文件没有有效字段: $Path"
    }

    return ,$keys.ToArray()
}

function Assert-SameOrderedSchema {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ExpectedName,
        [Parameter(Mandatory = $true)]
        [string[]]$Expected,
        [Parameter(Mandatory = $true)]
        [string]$ActualName,
        [Parameter(Mandatory = $true)]
        [string[]]$Actual
    )

    if ($Expected.Count -ne $Actual.Count) {
        throw "$ActualName 字段数量与 $ExpectedName 不一致"
    }

    for ($index = 0; $index -lt $Expected.Count; $index++) {
        if ($Expected[$index] -cne $Actual[$index]) {
            throw "$ActualName 第 $($index + 1) 个字段应为 $($Expected[$index]), 实际为 $($Actual[$index])"
        }
    }
}

$exampleKeys = Read-EnvSchema -Path $ExamplePath

if (Test-Path -LiteralPath $LocalEnvPath -PathType Leaf) {
    $localKeys = Read-EnvSchema -Path $LocalEnvPath
    Assert-SameOrderedSchema -ExpectedName ".env.example" -Expected $exampleKeys `
        -ActualName ".env" -Actual $localKeys
}

$composeContent = Get-Content -LiteralPath $ComposePath -Raw -Encoding UTF8
$composeKeys = [regex]::Matches($composeContent, '\$\{([A-Z][A-Z0-9_]*)') |
    ForEach-Object { $_.Groups[1].Value } |
    Sort-Object -Unique
$exampleKeySet = $exampleKeys | Sort-Object -Unique

if ($composeKeys.Count -ne $exampleKeySet.Count) {
    throw "docker-compose.yml 插值字段数量与 .env.example 不一致"
}

for ($index = 0; $index -lt $exampleKeySet.Count; $index++) {
    if ($exampleKeySet[$index] -cne $composeKeys[$index]) {
        throw "docker-compose.yml 插值字段与 .env.example 不一致"
    }
}

Write-Host "生产环境字段校验通过: $($exampleKeys.Count) 个字段名称和顺序一致"
