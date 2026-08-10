param(
    [string]$EnvironmentFile = "performance/.env.perf.local",
    [string]$K6Path = "tmp/perf-tools/k6-v2.1.0/k6-v2.1.0-windows-amd64/k6.exe",
    [string]$JarPath = "build/libs/backEnd-0.0.1-SNAPSHOT.jar",
    [string]$MySqlPath = "C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe",
    [long]$RoomId = 9,
    [int]$TrialsPerMode = 5,
    [int]$RequestRate = 20,
    [string]$Duration = "5s",
    [int]$ObservationSeconds = 40,
    [ValidatePattern("^[a-z0-9][a-z0-9-]*$")]
    [string]$ResultSet = "repeated",
    [ValidatePattern("^[a-z0-9][a-z0-9-]*$")]
    [string]$LabelPrefix = "repeat",
    [int]$WorkerBatchSize = 4,
    [int]$WorkerMaxConcurrency = 4,
    [int]$FakeAiMaxInFlight = 4
)

$ErrorActionPreference = "Stop"
$repositoryRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$resultRoot = Join-Path $repositoryRoot "performance/results/$ResultSet"
$logRoot = Join-Path $repositoryRoot "performance/results/logs"
$environmentPath = Join-Path $repositoryRoot $EnvironmentFile
$resolvedK6Path = Join-Path $repositoryRoot $K6Path
$resolvedJarPath = Join-Path $repositoryRoot $JarPath

function Read-EnvironmentFile([string]$Path) {
    $values = @{}
    foreach ($line in Get-Content -LiteralPath $Path) {
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
        $values[$name] = $value
    }
    return $values
}

function Wait-Http([string]$Url, [int]$TimeoutSeconds = 60) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        try {
            Invoke-RestMethod -Method Get -Uri $Url -TimeoutSec 2 | Out-Null
            return
        } catch {
            if ($null -ne $_.Exception.Response) {
                return
            }
            Start-Sleep -Milliseconds 500
        }
    } while ((Get-Date) -lt $deadline)

    throw "Timed out waiting for $Url"
}

function Stop-ProcessSafely($Process) {
    if ($null -eq $Process) {
        return
    }
    try {
        if (-not $Process.HasExited) {
            Stop-Process -Id $Process.Id -Force
            $Process.WaitForExit(5000) | Out-Null
        }
    } catch {
        Write-Warning "Could not stop process $($Process.Id): $($_.Exception.Message)"
    }
}

function Start-HiddenProcess(
    [string]$FilePath,
    [string[]]$Arguments,
    [string]$WorkingDirectory
) {
    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $FilePath
    $startInfo.Arguments = ($Arguments | ForEach-Object {
        if ($_ -match '[\s"]') {
            '"' + ($_ -replace '"', '\"') + '"'
        } else {
            $_
        }
    }) -join ' '
    $startInfo.WorkingDirectory = $WorkingDirectory
    $startInfo.UseShellExecute = $true
    $startInfo.WindowStyle = [System.Diagnostics.ProcessWindowStyle]::Hidden
    return [System.Diagnostics.Process]::Start($startInfo)
}

function Clear-PerformanceRows([hashtable]$Configuration) {
    $env:MYSQL_PWD = $Configuration["DB_PASSWORD"]
    try {
        $sql = @"
DELETE FROM outbox_events
WHERE aggregate_id IN (
    SELECT ai_room_id FROM room_3d_models WHERE room_id = $RoomId
);
DELETE FROM room_3d_models WHERE room_id = $RoomId;
"@
        & $MySqlPath `
            -h 127.0.0.1 `
            -P 3306 `
            -u $Configuration["DB_USERNAME"] `
            -D spatial_asset_db `
            --execute $sql
        if ($LASTEXITCODE -ne 0) {
            throw "MySQL cleanup failed with exit code $LASTEXITCODE"
        }
    } finally {
        Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
    }
}

function Read-DatabaseSummary([hashtable]$Configuration) {
    $env:MYSQL_PWD = $Configuration["DB_PASSWORD"]
    try {
        $sql = @"
SELECT 'model' AS kind, status, COUNT(*) AS count
FROM room_3d_models
WHERE room_id = $RoomId
GROUP BY status
UNION ALL
SELECT 'outbox' AS kind, status, COUNT(*) AS count
FROM outbox_events
WHERE aggregate_id IN (
    SELECT ai_room_id FROM room_3d_models WHERE room_id = $RoomId
)
GROUP BY status;
"@
        $rows = & $MySqlPath `
            -h 127.0.0.1 `
            -P 3306 `
            -u $Configuration["DB_USERNAME"] `
            -D spatial_asset_db `
            --batch `
            --skip-column-names `
            --execute $sql
        if ($LASTEXITCODE -ne 0) {
            throw "MySQL summary failed with exit code $LASTEXITCODE"
        }

        return @($rows | ForEach-Object {
            $parts = $_ -split "`t"
            [ordered]@{
                kind = $parts[0]
                status = $parts[1]
                count = [int]$parts[2]
            }
        })
    } finally {
        Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
    }
}

if (-not (Test-Path -LiteralPath $environmentPath)) {
    throw "Missing local environment file: $environmentPath"
}
if (-not (Test-Path -LiteralPath $resolvedK6Path)) {
    throw "Missing k6 executable: $resolvedK6Path"
}
if (-not (Test-Path -LiteralPath $resolvedJarPath)) {
    throw "Missing executable jar: $resolvedJarPath"
}
if (-not (Test-Path -LiteralPath $MySqlPath)) {
    throw "Missing MySQL client: $MySqlPath"
}

New-Item -ItemType Directory -Path $resultRoot -Force | Out-Null
New-Item -ItemType Directory -Path $logRoot -Force | Out-Null

$configuration = Read-EnvironmentFile $environmentPath
$baseOrder = @("baseline", "outbox", "outbox", "baseline")
$runOrder = [System.Collections.Generic.List[string]]::new()
$counts = @{ baseline = 0; outbox = 0 }
$cursor = 0
while ($counts.baseline -lt $TrialsPerMode -or $counts.outbox -lt $TrialsPerMode) {
    $candidate = $baseOrder[$cursor % $baseOrder.Count]
    $cursor += 1
    if ($counts[$candidate] -ge $TrialsPerMode) {
        continue
    }
    $runOrder.Add($candidate)
    $counts[$candidate] += 1
}

$trialNumber = @{ baseline = 0; outbox = 0 }
$manifest = [System.Collections.Generic.List[object]]::new()

foreach ($mode in $runOrder) {
    $trialNumber[$mode] += 1
    $trial = $trialNumber[$mode]
    $label = "$LabelPrefix-$mode-{0:D2}" -f $trial
    $fakeAiProcess = $null
    $backendProcess = $null

    try {
        Clear-PerformanceRows $configuration

        foreach ($entry in $configuration.GetEnumerator()) {
            [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, "Process")
        }

        $outboxEnabled = $mode -eq "outbox"
        $env:OUTBOX_ENABLED = $outboxEnabled.ToString().ToLowerInvariant()
        $env:OUTBOX_WORKER_ENABLED = $outboxEnabled.ToString().ToLowerInvariant()
        $env:OUTBOX_FIXED_DELAY_MS = "500"
        $env:OUTBOX_BATCH_SIZE = $WorkerBatchSize.ToString()
        $env:OUTBOX_MAX_CONCURRENCY = $WorkerMaxConcurrency.ToString()
        $env:OUTBOX_LEASE_MS = "30000"
        $env:OUTBOX_REQUEST_TIMEOUT_MS = "10000"
        $env:OUTBOX_MAX_ATTEMPTS = "5"
        $env:OUTBOX_BASE_BACKOFF_MS = "1000"
        $env:FAKE_AI_MAX_IN_FLIGHT = $FakeAiMaxInFlight.ToString()
        $env:FAKE_AI_ACCEPT_DELAY_MS = "1000"
        $env:FAKE_AI_ANALYSIS_DELAY_MS = "300"

        $nodePath = (Get-Command node -ErrorAction Stop).Source
        $fakeAiProcess = Start-HiddenProcess `
            -FilePath $nodePath `
            -Arguments @("performance/fake-ai/server.mjs") `
            -WorkingDirectory $repositoryRoot
        Wait-Http "http://127.0.0.1:8000/__control/health" 20

        $backendProcess = Start-HiddenProcess `
            -FilePath "C:/Program Files/Java/jdk-24/bin/java.exe" `
            -Arguments @("-Xms64m", "-Xmx256m", "-XX:MaxMetaspaceSize=192m", "-jar", $resolvedJarPath) `
            -WorkingDirectory $repositoryRoot

        Wait-Http "http://127.0.0.1:8080/api/rooms/$RoomId/models/status" 90
        Start-Sleep -Seconds 2

        $startedAt = (Get-Date).ToUniversalTime()
        & (Join-Path $repositoryRoot "performance/run-experiment.ps1") `
            -Label $label `
            -K6Path $resolvedK6Path `
            -RoomId $RoomId `
            -RequestRate $RequestRate `
            -Duration $Duration `
            -DrainSeconds $ObservationSeconds
        if ($LASTEXITCODE -ne 0) {
            throw "Experiment $label failed with exit code $LASTEXITCODE"
        }

        $databaseSummary = Read-DatabaseSummary $configuration
        $databasePath = Join-Path $resultRoot "$label-db.json"
        $databaseSummary | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $databasePath -Encoding utf8

        $k6Source = Join-Path $repositoryRoot "performance/results/$label-k6.json"
        $fakeSource = Join-Path $repositoryRoot "performance/results/$label-fake-ai.json"
        Move-Item -LiteralPath $k6Source -Destination (Join-Path $resultRoot "$label-k6.json") -Force
        Move-Item -LiteralPath $fakeSource -Destination (Join-Path $resultRoot "$label-fake-ai.json") -Force

        $manifest.Add([ordered]@{
            label = $label
            mode = $mode
            trial = $trial
            startedAtUtc = $startedAt.ToString("o")
            finishedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
            requestRatePerSecond = $RequestRate
            duration = $Duration
            observationSeconds = $ObservationSeconds
            roomId = $RoomId
            outboxEnabled = $outboxEnabled
            workerEnabled = $outboxEnabled
            workerBatchSize = $WorkerBatchSize
            workerMaxConcurrency = $WorkerMaxConcurrency
            fakeAiMaxInFlight = $FakeAiMaxInFlight
            fakeAiAcceptDelayMs = 1000
            fakeAiAnalysisDelayMs = 300
            databaseSummary = $databaseSummary
        })

        $manifest | ConvertTo-Json -Depth 10 | Set-Content `
            -LiteralPath (Join-Path $resultRoot "manifest.json") `
            -Encoding utf8
    } finally {
        Stop-ProcessSafely $backendProcess
        Stop-ProcessSafely $fakeAiProcess
        Start-Sleep -Seconds 1
    }
}

Write-Host "Completed $($manifest.Count) controlled runs."
Write-Host "Results: $resultRoot"
