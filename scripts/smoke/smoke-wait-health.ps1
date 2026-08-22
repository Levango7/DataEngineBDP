﻿# 轮询健康检查端点
$maxAttempts = 60
$intervalSec = 2
$url = "http://127.0.0.1:18086/actuator/health"

for ($i = 1; $i -le $maxAttempts; $i++) {
    try {
        $resp = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 3 -ErrorAction Stop
        if ($resp.StatusCode -eq 200) {
            Write-Host "HEALTH_OK attempt=$i status=200 body=$($resp.Content)"
            exit 0
        }
    } catch {
        $status = $_.Exception.Response.StatusCode.value__
        if ($status) {
            Write-Host "attempt=$i http=$status"
        } else {
            Write-Host "attempt=$i not_ready"
        }
    }
    Start-Sleep -Seconds $intervalSec
}
Write-Host "HEALTH_TIMEOUT after $maxAttempts attempts"
exit 1