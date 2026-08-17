<#
.SYNOPSIS
    基于 HttpClient 的轻量并发压测脚本
.DESCRIPTION
    使用 System.Net.Http.HttpClient + RunspacePool 实现低开销并发压测。
    阶梯加压 100 -> 500 -> 1000，输出 P50/P95/P99、TPS、错误率。
#>
param(
    [string]$BaseUrl = 'http://localhost:18086',
    [string]$ApiPrefix = '/api/v1',
    [string]$Username = 'admin',
    [string]$Password = 'admin',
    [int[]]$ConcurrencySteps = @(100, 500, 1000),
    [int]$DurationSec = 20,
    [int]$RampUpSec = 5,
    [string]$OutDir = 'F:\nexus\DataEngineBDP\tests\performance\results'
)

$ErrorActionPreference = 'Continue'
Add-Type -AssemblyName System.Net.Http

if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Force -Path $OutDir | Out-Null }

function Get-Percentile {
    param([double[]]$Values, [double]$P)
    if ($Values.Count -eq 0) { return 0 }
    $sorted = $Values | Sort-Object
    $idx = [int][Math]::Ceiling(($P / 100) * $sorted.Count) - 1
    if ($idx -lt 0) { $idx = 0 }
    if ($idx -ge $sorted.Count) { $idx = $sorted.Count - 1 }
    return [double]$sorted[$idx]
}

# ---------- 并发压测核心（HttpClient 版） ----------
function Run-StressTest {
    param(
        [string]$Label,
        [string]$Url,
        [string]$Method,        # GET / POST
        [string]$HeadersJson,   # JSON 字符串，runspace 内反序列化
        [string]$Body,          # 请求体（POST 用）
        [int]$Concurrency,
        [int]$DurationSec,
        [int]$RampUpSec
    )
    Write-Host ("`n=== [{0}] VUs={1} Duration={2}s RampUp={3}s ===" -f $Label, $Concurrency, $DurationSec, $RampUpSec)

    $iss = [System.Management.Automation.Runspaces.InitialSessionState]::CreateDefault()
    $iss.Variables.Add((New-Object System.Management.Automation.Runspaces.SessionStateVariableEntry('TargetUrl', $Url, $null)))
    $iss.Variables.Add((New-Object System.Management.Automation.Runspaces.SessionStateVariableEntry('TargetMethod', $Method, $null)))
    $iss.Variables.Add((New-Object System.Management.Automation.Runspaces.SessionStateVariableEntry('TargetHeadersJson', $HeadersJson, $null)))
    $iss.Variables.Add((New-Object System.Management.Automation.Runspaces.SessionStateVariableEntry('TargetBody', $Body, $null)))

    $pool = [RunspaceFactory]::CreateRunspacePool(1, $Concurrency, $iss, $Host)
    $pool.Open()

    $startAt = Get-Date
    $stopAt = $startAt.AddSeconds($DurationSec)
    $jobs = New-Object System.Collections.ArrayList

    # worker 脚本：每个 worker 创建自己的 HttpClient，循环打请求
    $workerScript = {
        param($StopAt)
        # 每个 worker 一个 HttpClient（轻量、连接池复用）
        $client = New-Object System.Net.Http.HttpClient
        $client.Timeout = [TimeSpan]::FromSeconds(15)
        # 反序列化请求头
        $hdrs = $TargetHeadersJson | ConvertFrom-Json
        foreach ($p in $hdrs.PSObject.Properties) {
            $client.DefaultRequestHeaders.Add($p.Name, [string]$p.Value)
        }
        # 预创建 POST body content
        $postContent = $null
        if ($TargetBody) {
            $postContent = New-Object System.Net.Http.StringContent($TargetBody, [System.Text.Encoding]::UTF8, 'application/json')
        }

        $list = New-Object System.Collections.Generic.List[hashtable]
        while ((Get-Date) -lt $StopAt) {
            $sw = [System.Diagnostics.Stopwatch]::StartNew()
            $status = 0; $ok = $false
            try {
                if ($TargetMethod -eq 'GET') {
                    $resp = $client.GetAsync($TargetUrl).Result
                } else {
                    # POST 需要新的 content 实例（HttpContent 不能复用）
                    $content = New-Object System.Net.Http.StringContent($TargetBody, [System.Text.Encoding]::UTF8, 'application/json')
                    $resp = $client.PostAsync($TargetUrl, $content).Result
                }
                $sw.Stop()
                $status = [int]$resp.StatusCode
                $ok = $resp.IsSuccessStatusCode
                try { $resp.Dispose() } catch {}
            } catch {
                $sw.Stop()
                $respEx = $_.Exception
                if ($respEx.InnerException -and $respEx.InnerException.Response) {
                    $status = [int]$respEx.InnerException.Response.StatusCode
                } elseif ($respEx.Response) {
                    $status = [int]$respEx.Response.StatusCode
                }
                $ok = $false
            }
            [void]$list.Add(@{ DurationMs = [double]$sw.Elapsed.TotalMilliseconds; Status = $status; Ok = $ok })
        }
        $client.Dispose()
        $list.ToArray()
    }

    for ($i = 0; $i -lt $Concurrency; $i++) {
        if ($RampUpSec -gt 0) {
            $targetActive = [int][Math]::Ceiling($Concurrency * ((Get-Date) - $startAt).TotalSeconds / $RampUpSec)
            while ((Get-Date) -lt $stopAt -and ($i + 1) -gt $targetActive) {
                Start-Sleep -Milliseconds 20
                $targetActive = [int][Math]::Ceiling($Concurrency * ((Get-Date) - $startAt).TotalSeconds / $RampUpSec)
            }
        }
        if ((Get-Date) -ge $stopAt) { break }

        $ps = [PowerShell]::Create()
        $ps.RunspacePool = $pool
        [void]$ps.AddScript($workerScript).AddArgument($stopAt)
        $handle = $ps.BeginInvoke()
        [void]$jobs.Add(@{ PS = $ps; Handle = $handle; Id = $i })
    }

    Write-Host ("  [Wait] {0} workers launched={1}" -f $Label, $jobs.Count)

    $allDurations = New-Object System.Collections.Generic.List[double]
    $totalReq = 0; $okReq = 0; $errReq = 0
    $statusBuckets = @{}

    foreach ($j in $jobs) {
        try {
            $workerOutput = $j.PS.EndInvoke($j.Handle)
            foreach ($item in $workerOutput) {
                if ($null -eq $item) { continue }
                if ($item -is [System.Array]) {
                    foreach ($row in $item) {
                        if ($null -eq $row) { continue }
                        $totalReq++
                        if ($row.Ok) { $okReq++ } else { $errReq++ }
                        $allDurations.Add([double]$row.DurationMs)
                        $sk = [string]$row.Status
                        if (-not $statusBuckets.ContainsKey($sk)) { $statusBuckets[$sk] = 0 }
                        $statusBuckets[$sk]++
                    }
                } elseif ($item -is [hashtable]) {
                    $totalReq++
                    if ($item.Ok) { $okReq++ } else { $errReq++ }
                    $allDurations.Add([double]$item.DurationMs)
                    $sk = [string]$item.Status
                    if (-not $statusBuckets.ContainsKey($sk)) { $statusBuckets[$sk] = 0 }
                    $statusBuckets[$sk]++
                }
            }
        } catch {
            Write-Host ("    worker {0} collect error: {1}" -f $j.Id, $_.Exception.Message)
        }
        try { $j.PS.Dispose() } catch {}
    }
    $pool.Close(); $pool.Dispose()

    $actualElapsed = ((Get-Date) - $startAt).TotalSeconds
    $durations = $allDurations.ToArray()
    if ($durations.Count -eq 0) {
        Write-Host "    NO RESULTS"
        return @{
            label = $Label; concurrency = $Concurrency; durationSec = $DurationSec; rampUpSec = $RampUpSec
            actualElapsed = [Math]::Round($actualElapsed, 2); totalRequests = 0; successCount = 0; errorCount = 0
            tps = 0; successRate = 0; errorRate = 0
            latencyMs = @{ avg=0; min=0; max=0; p50=0; p90=0; p95=0; p99=0 }
            statusBuckets = $statusBuckets; p99TargetMs = 200; p99Met = $false
        }
    }
    $p50 = Get-Percentile -Values $durations -P 50
    $p90 = Get-Percentile -Values $durations -P 90
    $p95 = Get-Percentile -Values $durations -P 95
    $p99 = Get-Percentile -Values $durations -P 99
    $avg = ($durations | Measure-Object -Average).Average
    $min = ($durations | Measure-Object -Minimum).Minimum
    $max = ($durations | Measure-Object -Maximum).Maximum
    $tps = if ($actualElapsed -gt 0) { $totalReq / $actualElapsed } else { 0 }
    $errRate = if ($totalReq -gt 0) { $errReq / $totalReq } else { 0 }
    $successRate = if ($totalReq -gt 0) { $okReq / $totalReq } else { 0 }

    $result = @{
        label          = $Label
        concurrency    = $Concurrency
        durationSec    = $DurationSec
        rampUpSec      = $RampUpSec
        actualElapsed  = [Math]::Round($actualElapsed, 2)
        totalRequests  = $totalReq
        successCount   = $okReq
        errorCount     = $errReq
        tps            = [Math]::Round($tps, 2)
        successRate    = [Math]::Round($successRate, 4)
        errorRate      = [Math]::Round($errRate, 4)
        latencyMs      = @{
            avg = [Math]::Round($avg, 2)
            min = [Math]::Round($min, 2)
            max = [Math]::Round($max, 2)
            p50 = [Math]::Round($p50, 2)
            p90 = [Math]::Round($p90, 2)
            p95 = [Math]::Round($p95, 2)
            p99 = [Math]::Round($p99, 2)
        }
        statusBuckets  = $statusBuckets
        p99TargetMs    = 200
        p99Met         = ($p99 -lt 200)
    }

    Write-Host ("    total={0} ok={1} err={2} tps={3:N1} errRate={4:P2}" -f $totalReq, $okReq, $errReq, $tps, $errRate)
    Write-Host ("    latency(ms): avg={0} p50={1} p95={2} p99={3} max={4}" -f $result.latencyMs.avg, $result.latencyMs.p50, $result.latencyMs.p95, $result.latencyMs.p99, $result.latencyMs.max)
    Write-Host ("    P99<200ms target: {0}" -f $(if ($p99 -lt 200) { 'MET' } else { 'NOT MET' }))
    return $result
}

# ---------- 预登录获取 token ----------
Write-Host "Pre-login to obtain token..."
$loginUrl = "$BaseUrl$ApiPrefix/auth/login"
$loginBody = "{`"username`":`"$Username`",`"password`":`"$Password`"}"
$token = $null
$preClient = New-Object System.Net.Http.HttpClient
$preClient.Timeout = [TimeSpan]::FromSeconds(10)
try {
    $content = New-Object System.Net.Http.StringContent($loginBody, [System.Text.Encoding]::UTF8, 'application/json')
    $resp = $preClient.PostAsync($loginUrl, $content).Result
    $json = $resp.Content.ReadAsStringAsync().Result | ConvertFrom-Json
    $token = $json.data.token
    Write-Host ("Token obtained: {0}..." -f $token.Substring(0, [Math]::Min(30, $token.Length)))
} catch {
    Write-Host ("Pre-login failed: {0}" -f $_.Exception.Message)
}
$preClient.Dispose()

$authHdr = @{ 'Accept' = 'application/json' }
if ($token) { $authHdr['Authorization'] = "Bearer $token" }
$authHdrJson = $authHdr | ConvertTo-Json -Compress
$noAuthHdrJson = (@{ 'Accept' = 'application/json' } | ConvertTo-Json -Compress)

# ---------- API 定义 ----------
$apis = @(
    @{ Name = 'POST /auth/login';        Method = 'POST'; Path = '/auth/login';        Body = $loginBody; HeadersJson = $noAuthHdrJson },
    @{ Name = 'GET /projects';           Method = 'GET';  Path = '/projects';           Body = $null;       HeadersJson = $authHdrJson  },
    @{ Name = 'GET /governance/assets';  Method = 'GET';  Path = '/governance/assets';  Body = $null;       HeadersJson = $authHdrJson  },
    @{ Name = 'GET /standards';          Method = 'GET';  Path = '/standards';          Body = $null;       HeadersJson = $authHdrJson  },
    @{ Name = 'GET /search/history';     Method = 'GET';  Path = '/search/history';     Body = $null;       HeadersJson = $authHdrJson  }
)

# ---------- 执行压测 ----------
$allResults = New-Object System.Collections.ArrayList
$testStart = Get-Date

foreach ($vu in $ConcurrencySteps) {
    Write-Host "`n############################################################"
    Write-Host ("# CONCURRENCY = {0}" -f $vu)
    Write-Host "############################################################"

    foreach ($api in $apis) {
        $url = "$BaseUrl$ApiPrefix$($api.Path)"
        $res = Run-StressTest -Label $api.Name -Url $url -Method $api.Method -HeadersJson $api.HeadersJson -Body $api.Body `
            -Concurrency $vu -DurationSec $DurationSec -RampUpSec $RampUpSec
        $res | Add-Member -NotePropertyName api -NotePropertyValue $api.Name -Force
        $res | Add-Member -NotePropertyName method -NotePropertyValue $api.Method -Force
        $res | Add-Member -NotePropertyName path -NotePropertyValue $api.Path -Force
        [void]$allResults.Add($res)

        $midFile = Join-Path $OutDir "intermediate_vu${vu}.json"
        $allResults.ToArray() | ConvertTo-Json -Depth 6 | Out-File -FilePath $midFile -Encoding UTF8

        Start-Sleep -Seconds 2
    }
}

$testElapsed = ((Get-Date) - $testStart).TotalSeconds
Write-Host ("`n=== ALL TESTS DONE in {0}s ===" -f [Math]::Round($testElapsed, 2))

# ---------- 保存汇总 ----------
$summary = @{
    testStart        = $testStart.ToString('o')
    testEnd          = (Get-Date).ToString('o')
    totalElapsedSec  = [Math]::Round($testElapsed, 2)
    baseUrl          = $BaseUrl
    apiPrefix        = $ApiPrefix
    concurrencySteps = $ConcurrencySteps
    durationPerStepSec = $DurationSec
    client           = 'System.Net.Http.HttpClient + RunspacePool'
    results          = $allResults.ToArray()
}
$summaryFile = Join-Path $OutDir 'stress-results.json'
$summary | ConvertTo-Json -Depth 7 | Out-File -FilePath $summaryFile -Encoding UTF8
Write-Host "Summary saved to: $summaryFile"

# CSV
$csvFile = Join-Path $OutDir 'stress-results.csv'
'api,concurrency,totalRequests,successCount,errorCount,tps,successRate,errorRate,avgMs,p50Ms,p95Ms,p99Ms,maxMs,p99Met' | Out-File -FilePath $csvFile -Encoding UTF8
foreach ($r in $allResults) {
    $line = '{0},{1},{2},{3},{4},{5},{6},{7},{8},{9},{10},{11},{12},{13}' -f `
        $r.api, $r.concurrency, $r.totalRequests, $r.successCount, $r.errorCount, `
        $r.tps, $r.successRate, $r.errorRate, `
        $r.latencyMs.avg, $r.latencyMs.p50, $r.latencyMs.p95, $r.latencyMs.p99, $r.latencyMs.max, `
        $r.p99Met
    $line | Out-File -FilePath $csvFile -Encoding UTF8 -Append
}
Write-Host "CSV saved to: $csvFile"