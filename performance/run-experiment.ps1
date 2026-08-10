param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern("^[a-z0-9][a-z0-9-]*$")]
    [string]$Label,

    [string]$K6Path = "k6",
    [long]$RoomId = 1,
    [int]$RequestRate = 20,
    [string]$Duration = "5s",
    [int]$DrainSeconds = 8,
    [string]$BackendBaseUrl = "http://127.0.0.1:8080",
    [string]$FakeAiBaseUrl = "http://127.0.0.1:8000"
)

$ErrorActionPreference = "Stop"
$performanceRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$resultDirectory = Join-Path $performanceRoot "results"
$k6Script = Join-Path $performanceRoot "k6\analyze-burst.js"
$k6Result = Join-Path $resultDirectory "$Label-k6.json"
$fakeAiResult = Join-Path $resultDirectory "$Label-fake-ai.json"

New-Item -ItemType Directory -Path $resultDirectory -Force | Out-Null

Invoke-RestMethod -Method Post -Uri "$FakeAiBaseUrl/__control/reset" | Out-Null

& $K6Path run `
    -e "BASE_URL=$BackendBaseUrl" `
    -e "ROOM_ID=$RoomId" `
    -e "REQUEST_RATE=$RequestRate" `
    -e "DURATION=$Duration" `
    -e "RESULT_FILE=$k6Result" `
    $k6Script

$k6ExitCode = $LASTEXITCODE

Start-Sleep -Seconds $DrainSeconds

$fakeAiStats = Invoke-RestMethod -Method Get -Uri "$FakeAiBaseUrl/__control/stats"
$fakeAiStats | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $fakeAiResult -Encoding utf8

Write-Host "Saved k6 summary: $k6Result"
Write-Host "Saved Fake AI stats: $fakeAiResult"
$fakeAiStats | Format-List

if ($k6ExitCode -ne 0) {
    throw "k6 failed with exit code $k6ExitCode (results were still captured)"
}
