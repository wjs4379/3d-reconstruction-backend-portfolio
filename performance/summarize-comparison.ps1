param(
    [string]$ResultDirectory = "performance/results/repeated"
)

$ErrorActionPreference = "Stop"
$repositoryRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$resolvedResultDirectory = Join-Path $repositoryRoot $ResultDirectory
$manifestPath = Join-Path $resolvedResultDirectory "manifest.json"

function Get-Median([double[]]$Values) {
    if ($null -eq $Values -or $Values.Count -eq 0) {
        return $null
    }

    $sorted = @($Values | Sort-Object)
    $middle = [math]::Floor($sorted.Count / 2)
    if ($sorted.Count % 2 -eq 1) {
        return $sorted[$middle]
    }
    return ($sorted[$middle - 1] + $sorted[$middle]) / 2.0
}

function Get-Distribution([double[]]$Values) {
    if ($null -eq $Values -or $Values.Count -eq 0) {
        return $null
    }
    return [ordered]@{
        median = Get-Median $Values
        minimum = ($Values | Measure-Object -Minimum).Minimum
        maximum = ($Values | Measure-Object -Maximum).Maximum
    }
}

if (-not (Test-Path -LiteralPath $manifestPath)) {
    throw "Missing manifest: $manifestPath"
}

$manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding utf8 | ConvertFrom-Json
$runs = foreach ($entry in $manifest) {
    $k6Path = Join-Path $resolvedResultDirectory "$($entry.label)-k6.json"
    $fakePath = Join-Path $resolvedResultDirectory "$($entry.label)-fake-ai.json"
    $dbPath = Join-Path $resolvedResultDirectory "$($entry.label)-db.json"

    $k6 = Get-Content -LiteralPath $k6Path -Raw -Encoding utf8 | ConvertFrom-Json
    $fake = Get-Content -LiteralPath $fakePath -Raw -Encoding utf8 | ConvertFrom-Json
    $database = Get-Content -LiteralPath $dbPath -Raw -Encoding utf8 | ConvertFrom-Json

    $modelWaiting = @($database | Where-Object { $_.kind -eq "model" -and $_.status -eq "WAITING_FOR_CORRECTION" } | Select-Object -ExpandProperty count)
    $modelFailed = @($database | Where-Object { $_.kind -eq "model" -and $_.status -eq "FAILED" } | Select-Object -ExpandProperty count)
    $outboxSent = @($database | Where-Object { $_.kind -eq "outbox" -and $_.status -eq "SENT" } | Select-Object -ExpandProperty count)

    [pscustomobject]@{
        label = $entry.label
        mode = $entry.mode
        trial = [int]$entry.trial
        backendRequests = [int]$k6.compact.totalRequests
        backendAccepted202 = [int]$k6.compact.backendAccepted202
        backendUnexpected = [int]$k6.compact.backendUnexpectedResponse
        droppedIterations = [int]$k6.compact.droppedIterations
        averageMs = [double]$k6.compact.responseTimeMs.average
        p95Ms = [double]$k6.compact.responseTimeMs.p95
        p99Ms = [double]$k6.compact.responseTimeMs.p99
        maximumMs = [double]$k6.compact.responseTimeMs.max
        aiHttpRequests = [int]$fake.totalRequests
        aiAccepted = [int]$fake.acceptedRequests
        aiRejected = [int]$fake.rejectedRequests
        aiDuplicates = [int]$fake.duplicateRequests
        aiUniqueExecutions = [int]$fake.uniqueExecutions
        aiCompleted = [int]$fake.completedExecutions
        webhookSucceeded = [int]$fake.webhookSucceeded
        webhookFailed = [int]$fake.webhookFailed
        maxObservedInFlight = [int]$fake.maxObservedInFlight
        modelWaitingForCorrection = if ($modelWaiting.Count -eq 0) { 0 } else { [int]$modelWaiting[0] }
        modelFailed = if ($modelFailed.Count -eq 0) { 0 } else { [int]$modelFailed[0] }
        outboxSent = if ($outboxSent.Count -eq 0) { 0 } else { [int]$outboxSent[0] }
    }
}

$modeSummaries = [ordered]@{}
foreach ($mode in @("baseline", "outbox")) {
    $selected = @($runs | Where-Object { $_.mode -eq $mode })
    $backendRequests = ($selected | Measure-Object -Property backendRequests -Sum).Sum
    $aiAccepted = ($selected | Measure-Object -Property aiAccepted -Sum).Sum

    $modeSummaries[$mode] = [ordered]@{
        trialCount = $selected.Count
        backendRequests = $backendRequests
        backendAccepted202 = ($selected | Measure-Object -Property backendAccepted202 -Sum).Sum
        backendUnexpected = ($selected | Measure-Object -Property backendUnexpected -Sum).Sum
        droppedIterations = ($selected | Measure-Object -Property droppedIterations -Sum).Sum
        responseTimeMs = [ordered]@{
            runAverage = Get-Distribution @($selected | ForEach-Object { [double]$_.averageMs })
            p95 = Get-Distribution @($selected | ForEach-Object { [double]$_.p95Ms })
            p99 = Get-Distribution @($selected | ForEach-Object { [double]$_.p99Ms })
            maximum = Get-Distribution @($selected | ForEach-Object { [double]$_.maximumMs })
        }
        ai = [ordered]@{
            httpRequests = ($selected | Measure-Object -Property aiHttpRequests -Sum).Sum
            accepted = $aiAccepted
            rejected = ($selected | Measure-Object -Property aiRejected -Sum).Sum
            duplicates = ($selected | Measure-Object -Property aiDuplicates -Sum).Sum
            uniqueExecutions = ($selected | Measure-Object -Property aiUniqueExecutions -Sum).Sum
            completed = ($selected | Measure-Object -Property aiCompleted -Sum).Sum
            webhookSucceeded = ($selected | Measure-Object -Property webhookSucceeded -Sum).Sum
            webhookFailed = ($selected | Measure-Object -Property webhookFailed -Sum).Sum
            acceptanceRateAgainstBackendRequests = if ($backendRequests -eq 0) { $null } else { $aiAccepted / $backendRequests }
            maxObservedInFlight = ($selected | Measure-Object -Property maxObservedInFlight -Maximum).Maximum
        }
        database = [ordered]@{
            waitingForCorrection = ($selected | Measure-Object -Property modelWaitingForCorrection -Sum).Sum
            failed = ($selected | Measure-Object -Property modelFailed -Sum).Sum
            outboxSent = ($selected | Measure-Object -Property outboxSent -Sum).Sum
        }
    }
}

$summary = [ordered]@{
    generatedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
    methodology = [ordered]@{
        order = @($manifest | ForEach-Object { $_.mode })
        requestRatePerSecond = $manifest[0].requestRatePerSecond
        duration = $manifest[0].duration
        observationSeconds = $manifest[0].observationSeconds
        workerMaxConcurrency = $manifest[0].workerMaxConcurrency
        fakeAiMaxInFlight = $manifest[0].fakeAiMaxInFlight
        fakeAiAcceptDelayMs = $manifest[0].fakeAiAcceptDelayMs
        fakeAiAnalysisDelayMs = $manifest[0].fakeAiAnalysisDelayMs
    }
    modes = $modeSummaries
    runs = @($runs)
}

$outputPath = Join-Path $resolvedResultDirectory "comparison-summary.json"
$summary | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $outputPath -Encoding utf8
Write-Host "Saved comparison summary: $outputPath"
$summary.modes | ConvertTo-Json -Depth 8
