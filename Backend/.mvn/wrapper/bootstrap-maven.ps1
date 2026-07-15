param(
  [Parameter(Mandatory = $true)]
  [string] $WrapperDirectory
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

# Accept only the repository-pinned Maven Central HTTPS URL and verify SHA-512 before extraction.
$ResolvedWrapperDirectory = (Resolve-Path -LiteralPath $WrapperDirectory).Path
$PropertiesPath = Join-Path $ResolvedWrapperDirectory "maven-wrapper.properties"
$Properties = ConvertFrom-StringData (Get-Content -Raw -LiteralPath $PropertiesPath)
$DistributionUri = [Uri] $Properties.distributionUrl
$ExpectedHash = [string] $Properties.distributionSha512Sum
if ($DistributionUri.Scheme -ne "https" -or $DistributionUri.Host -ne "repo.maven.apache.org") {
  throw "Maven distribution URL must use the approved Maven Central HTTPS host"
}
if ($ExpectedHash -notmatch "^[0-9a-fA-F]{128}$") {
  throw "Maven distribution SHA-512 is missing or malformed"
}

$ArchivePath = Join-Path $ResolvedWrapperDirectory "apache-maven-3.9.11-bin.zip"
try {
  Invoke-WebRequest -UseBasicParsing $DistributionUri.AbsoluteUri -OutFile $ArchivePath
  $ArchiveStream = $null
  $Sha512 = $null
  try {
    $ArchiveStream = [System.IO.File]::OpenRead($ArchivePath)
    $Sha512 = [System.Security.Cryptography.SHA512]::Create()
    $ActualHashBytes = $Sha512.ComputeHash($ArchiveStream)
    $ActualHash = [System.BitConverter]::ToString($ActualHashBytes).Replace("-", "")
  }
  finally {
    if ($null -ne $ArchiveStream) {
      $ArchiveStream.Dispose()
    }
    if ($null -ne $Sha512) {
      $Sha512.Dispose()
    }
  }
  if (-not $ActualHash.Equals($ExpectedHash, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Maven distribution SHA-512 verification failed"
  }
  Expand-Archive -LiteralPath $ArchivePath -DestinationPath $ResolvedWrapperDirectory -Force
}
finally {
  if (Test-Path -LiteralPath $ArchivePath -PathType Leaf) {
    Remove-Item -LiteralPath $ArchivePath -Force
  }
}

$MavenCommand = Join-Path $ResolvedWrapperDirectory "apache-maven-3.9.11\bin\mvn.cmd"
if (-not (Test-Path -LiteralPath $MavenCommand -PathType Leaf)) {
  throw "Verified Maven distribution did not contain mvn.cmd"
}
