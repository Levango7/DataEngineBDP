$env:NL2SQL_LLM_MODE = "mock"
$env:NL2SQL_HOST = "127.0.0.1"
$env:NL2SQL_PORT = "8093"
$job = Start-Job -ScriptBlock {
    $env:NL2SQL_LLM_MODE = "mock"
    $env:NL2SQL_HOST = "127.0.0.1"
    $env:NL2SQL_PORT = "8093"
    Set-Location "F:\Agent\workbuddy\workspace\ShuqingBigDataPlatform\platform\nl2sql"
    python app.py
}
Write-Output ("JobId=" + $job.Id)
Start-Sleep -Seconds 8
try {
    $r = Invoke-WebRequest -Uri "http://127.0.0.1:8093/api/v1/health" -UseBasicParsing -TimeoutSec 5
    Write-Output ("HEALTH " + $r.StatusCode)
    Write-Output $r.Content
} catch {
    Write-Output ("HEALTH FAIL " + $_.Exception.Message)
}