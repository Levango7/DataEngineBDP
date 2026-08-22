﻿# P0冒烟验证 - 调用所有API并输出结果
$token = Get-Content "F:\nexus\DataEngineBDP\smoke-token.txt" -Raw
$token = $token.Trim()
$headers = @{ "Authorization" = "Bearer $token" }

$apis = @(
    @{ name = "assets";    url = "http://127.0.0.1:18086/api/v1/governance/assets" },
    @{ name = "projects";  url = "http://127.0.0.1:18086/api/v1/projects" },
    @{ name = "standards"; url = "http://127.0.0.1:18086/api/v1/standards" },
    @{ name = "projects_test_datasets"; url = "http://127.0.0.1:18086/api/v1/projects/test/datasets" },
    @{ name = "search_history"; url = "http://127.0.0.1:18086/api/v1/search/history" }
)

$results = @()
foreach ($api in $apis) {
    try {
        $resp = Invoke-WebRequest -Uri $api.url -Headers $headers -UseBasicParsing -TimeoutSec 10 -ErrorAction Stop
        $entry = [PSCustomObject]@{
            name = $api.name
            url  = $api.url
            http = $resp.StatusCode
            body = $resp.Content
        }
    } catch {
        $status = $_.Exception.Response.StatusCode.value__
        $body = ""
        try {
            $stream = $_.Exception.Response.GetResponseStream()
            $reader = New-Object System.IO.StreamReader($stream)
            $body = $reader.ReadToEnd()
        } catch {}
        $entry = [PSCustomObject]@{
            name = $api.name
            url  = $api.url
            http = $status
            body = $body
        }
    }
    $results += $entry
    Write-Host "===== $($api.name) ($($entry.http)) ====="
    Write-Host $entry.body
    Write-Host ""
}

$results | Export-Csv -Path "F:\nexus\DataEngineBDP\smoke-api-results.csv" -NoTypeInformation -Encoding UTF8
Write-Host "Results saved to smoke-api-results.csv"