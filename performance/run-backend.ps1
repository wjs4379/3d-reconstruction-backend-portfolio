param(
    [string]$EnvironmentFile = "performance/.env.perf.local"
)

$ErrorActionPreference = "Stop"
$repositoryRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$resolvedEnvironmentFile = Join-Path $repositoryRoot $EnvironmentFile

if (-not (Test-Path -LiteralPath $resolvedEnvironmentFile)) {
    throw "Missing $resolvedEnvironmentFile. Copy performance/.env.perf.example and enter local-only values."
}

foreach ($line in Get-Content -LiteralPath $resolvedEnvironmentFile) {
    $trimmed = $line.Trim()
    if ($trimmed.Length -eq 0 -or $trimmed.StartsWith("#")) {
        continue
    }

    $separator = $trimmed.IndexOf("=")
    if ($separator -lt 1) {
        throw "Invalid environment line: $trimmed"
    }

    $name = $trimmed.Substring(0, $separator).Trim()
    $value = $trimmed.Substring($separator + 1).Trim()
    [Environment]::SetEnvironmentVariable($name, $value, "Process")
}

Set-Location -LiteralPath $repositoryRoot
& .\gradlew.bat bootRun
