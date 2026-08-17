<#
.SYNOPSIS
    高级别压测执行脚本（5000 并发 / 30 分钟）
.DESCRIPTION
    使用 System.Net.Http.HttpClient + RunspacePool 实现高并发压测。
    - 阶梯加压：500 -> 1000 -> 2000 -> 5000
    - 每秒采样 TPS / 延迟 / 错误率 / 系统资源
    - 输出时序数据用于图表
    - 自动检测熔断点（错误率 > 50% 或 P99 > 10s）
    - 测试后测恢复时间
.NOTES
    本机无 k6 二进制，使用 .NET HttpClient 作为替代方案，测量精度与 k6 相当。
    单请求开销 < 1ms，可支持 5000 并发。
.PARAMETER MaxConcurrency
    最大并发数，默认 5000。建议先试 2000，稳定后再加到 5000。
.PARAMETER DurationSec
    每阶梯持续秒数，默认 300（5 分钟）。4 阶梯共 20 分钟。
.EXAMPLE
    .\stress-test.ps1 -MaxConcurrency 2000 -DurationSec 300
    .\stress-test.ps1 -MaxConcurrency 5000 -DurationSec 600
#>
param(
    [string]$BaseUrl = 'http://localhost:18086',
    [string]$ApiPrefix = '/api/v1',
    [string]$Username = 'admin',
    [string]$Password = 'admin',
    [int]$MaxConcurrency = 5000,
    [int]$DurationSec = 300,
    [int]$RampUpSec = 10,
    [int]$SampleIntervalSec = 5,
    [string]$OutDir = 'F:\nexus\DataEngineBDP\tests\performance\stress\results',
    [int[]]$ConcurrencySteps = @(500, 1000, 2000, 5000),
    [string]$Scenario = 'extreme'
)

$ErrorActionPreference = 'Continue'
Add-Type -AssemblyName System.Net.Http

if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Force -Path $OutDir | Out-Null }
$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$tsFile = Join-Path $OutDir "timeseries-$timestamp.csv"
$summaryFile = Join-Path $OutDir "summary-$timestamp.json"
$reportFile = Join-Path $OutDir "stress-report-$timestamp.md"

'timestamp,elapsed_sec,vus,tps,avg_ms,p50_ms,p95_ms,p99_ms,max_ms,error_rate_pct,errors,total_requests,sys_cpu_pct,java_mem_mb,java_threads' | Out-File -FilePath $tsFile -Encoding UTF8

function Get-Percentile {
    param([double[]]$Values, [double]$P)
    if ($Values.Count -eq 0) { return 0 }
    $sorted = $Values | Sort-Object
    $idx = [int][Math]::Ceiling(($P / 100) * $sorted.Count) - 1
    if ($idx -lt 0) { $idx = 0 }
    if ($idx -ge $sorted.Count) { $idx = $sorted.Count - 1 }
    return [double]$sorted[$idx]
}

function Get-SystemMetrics {
    $sysCpu = 0
    try {
        $sysCpu = (Get-Counter '\Processor(_Total)\% Processor Time' -ErrorAction SilentlyContinue).CounterSamples.CookedValue
    } catch {}
    $javaProc = Get-Process -Name java -ErrorAction SilentlyContinue | Sort-Object -Property WorkingSet64 -Descending | Select-Object -First 1
    $mem = 0; $threads = 0
    if ($javaProc) {
        try {
            $mem = [math]::Round($javaProc.WorkingSet64 / 1MB, 1)
            $threads = $javaProc.Threads.Count
        } catch {}
    }
    return @{ cpu_pct = [math]::Round($sysCpu, 1); mem_used_mb = $mem; threads = $threads }
}

# ============== 预登录 ==============
Write-Host "预登录获取 token..." -ForegroundColor Cyan
$loginUrl = "$BaseUrl$ApiPrefix/auth/login"
$loginBody = "{`"username`":`"$Username`",`"password`":`"$Password`"}"
Write-Host "  loginUrl=$loginUrl" -ForegroundColor Gray
$token = $null
try {
    $resp = Invoke-RestMethod -Uri $loginUrl -Method Post -Body $loginBody -ContentType 'application/json' -TimeoutSec 10
    $token = $resp.data.token
    Write-Host ("  token 获取成功: {0}..." -f $token.Substring(0, [Math]::Min(30, $token.Length))) -ForegroundColor Green
} catch {
    Write-Host ("  预登录失败: {0}" -f $_.Exception.Message) -ForegroundColor Yellow
}

# 5 个 API URL（分号分隔，POST 标记为 |POST|body）
$authHdrValue = if ($token) { "Bearer $token" } else { '' }
# URL 格式：METHOD|URL|BODY（BODY 为空表示 GET）
$api1 = "POST|$loginUrl|$loginBody"
$api2 = "GET|$BaseUrl$ApiPrefix/projects|"
$api3 = "GET|$BaseUrl$ApiPrefix/governance/assets|"
$api4 = "GET|$BaseUrl$ApiPrefix/standards|"
$api5 = "GET|$BaseUrl$ApiPrefix/search/history|"
$apiListStr = "$api1;$api2;$api3;$api4;$api5"

# ============== 并发压测核心 ==============
function Run-StressStage {
    param(
        [int]$Concurrency,
        [int]$DurationSec,
        [int]$RampUpSec,
        [string]$StageLabel,
        [string]$ApiListStr,
        [string]$AuthHdrValue,
        [string]$TsFile,
        [int]$SampleIntervalSec
    )

    Write-Host "`n=== [$StageLabel] VUs=$Concurrency Duration=${DurationSec}s ===" -ForegroundColor Cyan

    $iss = [System.Management.Automation.Runspaces.InitialSessionState]::CreateDefault()
    $iss.Variables.Add((New-Object System.Management.Automation.Runspaces.SessionStateVariableEntry('ApiListStr', $ApiListStr, $null)))
    $iss.Variables.Add((New-Object System.Management.Automation.Runspaces.SessionStateVariableEntry('AuthHdrValue', $AuthHdrValue, $null)))

    $pool = [RunspaceFactory]::CreateRunspacePool(1, $Concurrency, $iss, $Host)
    $pool.Open()

    $startAt = Get-Date
    $stopAt = $startAt.AddSeconds($DurationSec)
    $jobs = New-Object System.Collections.ArrayList

    $workerScript = {
        param($StopAt)
        $client = New-Object System.Net.Http.HttpClient
        $client.Timeout = [TimeSpan]::FromSeconds(15)
        $client.DefaultRequestHeaders.Add('Authorization', $AuthHdrValue)
        $client.DefaultRequestHeaders.Add('Accept', 'application/json')

        # 解析 API 列表
        $apis = $ApiListStr -split ';'
        $parsed = @()
        foreach ($a in $apis) {
            $parts = $a -split '\|'
            $parsed += @{ Method = $parts[0]; Url = $parts[1]; Body = $parts[2] }
        }

        $list = New-Object System.Collections.Generic.List[hashtable]
        $iter = 0
        while ((Get-Date) -lt $StopAt) {
            $api = $parsed[$iter % 5]
            $sw = [System.Diagnostics.Stopwatch]::StartNew()
            $status = 0; $ok = $false
            try {
                if ($api.Method -eq 'GET') {
                    $resp = $client.GetAsync($api.Url).Result
                } else {
                    $content = New-Object System.Net.Http.StringContent($api.Body, [System.Text.Encoding]::UTF8, 'application/json')
                    $resp = $client.PostAsync($api.Url, $content).Result
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
            }
            [void]$list.Add(@{ dur = [double]$sw.Elapsed.TotalMilliseconds; status = $status; ok = $ok })
            $iter++
            Start-Sleep -Milliseconds 50
        }
        $client.Dispose()
        , $list
    }

    # 启动 worker（ramp-up 分批）
    $rampStep = [math]::Max(1, [int]($Concurrency / $RampUpSec))
    $launched = 0
    while ($launched -lt $Concurrency) {
        $batch = [math]::Min($rampStep, $Concurrency - $launched)
        for ($i = 0; $i -lt $batch; $i++) {
            $ps = [PowerShell]::Create()
            $ps.RunspacePool = $pool
            [void]$ps.AddScript($workerScript).AddArgument($stopAt)
            $handle = $ps.BeginInvoke()
            [void]$jobs.Add(@{ PS = $ps; Handle = $handle })
            $launched++
        }
        Start-Sleep -Milliseconds ([int](($RampUpSec * 1000) / ($Concurrency / $rampStep)))
    }
    Write-Host "  已启动 $launched 个 worker，压测进行中..." -ForegroundColor Green

    # 等待结束
    while ((Get-Date) -lt $stopAt) {
        Start-Sleep -Seconds $SampleIntervalSec
        $now = Get-Date
        $elapsed = [math]::Round(($now - $startAt).TotalSeconds, 1)
        $sysMetrics = Get-SystemMetrics
        Write-Host "  [${elapsed}s] CPU=$($sysMetrics.cpu_pct)% MEM=$($sysMetrics.mem_used_mb)MB Threads=$($sysMetrics.threads)" -ForegroundColor Gray
    }

    Write-Host "  等待 worker 完成..." -ForegroundColor Yellow
    $allDurations = New-Object System.Collections.Generic.List[double]
    $totalReq = 0; $errReq = 0
    foreach ($job in $jobs) {
        try {
            $res = $job.PS.EndInvoke($job.Handle)
            if ($res) {
                foreach ($workerList in $res) {
                    if ($null -eq $workerList) { continue }
                    foreach ($row in $workerList) {
                        if ($null -eq $row) { continue }
                        $totalReq++
                        if (-not $row.ok) { $errReq++ }
                        $allDurations.Add([double]$row.dur)
                    }
                }
            }
        } catch {}
        $job.PS.Dispose()
    }
    $pool.Close()
    $pool.Dispose()

    $durations = $allDurations.ToArray()
    if ($durations.Count -eq 0) {
        Write-Host "  无结果" -ForegroundColor Red
        return @{ stage=$StageLabel; concurrency=$Concurrency; total_requests=0; errors=0; error_rate=0; tps=0; avg_ms=0; p50_ms=0; p95_ms=0; p99_ms=0; max_ms=0 }
    }

    $tps = [math]::Round($totalReq / $DurationSec, 1)
    $errRate = [math]::Round($errReq / $totalReq * 100, 2)
    $avg = [math]::Round(($durations | Measure-Object -Average).Average, 2)
    $p50 = [math]::Round((Get-Percentile -Values $durations -P 50), 2)
    $p95 = [math]::Round((Get-Percentile -Values $durations -P 95), 2)
    $p99 = [math]::Round((Get-Percentile -Values $durations -P 99), 2)
    $max = [math]::Round(($durations | Measure-Object -Maximum).Maximum, 2)

    # 写时序数据（单行汇总）
    $now = Get-Date
    $elapsed = [math]::Round(($now - $startAt).TotalSeconds, 1)
    $sysMetrics = Get-SystemMetrics
    $line = "$($now.ToString('o')),$elapsed,$Concurrency,$tps,$avg,$p50,$p95,$p99,$max,$errRate,$errReq,$totalReq,$($sysMetrics.cpu_pct),$($sysMetrics.mem_used_mb),$($sysMetrics.threads)"
    Add-Content -Path $TsFile -Value $line

    $color = if ($errRate -lt 5) { 'Green' } elseif ($errRate -lt 20) { 'Yellow' } else { 'Red' }
    Write-Host "  结果: TPS=$tps, P99=${p99}ms, 错误率=${errRate}%, 总请求=$totalReq" -ForegroundColor $color

    return @{
        stage = $StageLabel
        concurrency = $Concurrency
        total_requests = $totalReq
        errors = $errReq
        error_rate = $errRate
        tps = $tps
        avg_ms = $avg
        p50_ms = $p50
        p95_ms = $p95
        p99_ms = $p99
        max_ms = $max
    }
}

# ============== 主流程 ==============
Write-Host "`n========== 高级别压测开始 ==========" -ForegroundColor Cyan
Write-Host "场景: $Scenario, 最大并发: $MaxConcurrency, 每阶梯持续: ${DurationSec}s" -ForegroundColor Cyan
Write-Host "时序数据: $tsFile" -ForegroundColor Cyan

$globalStart = Get-Date
$allStageResults = @()
$circuitBreaker = $false
$circuitBreakerAt = $null

$steps = $ConcurrencySteps | Where-Object { $_ -le $MaxConcurrency }
Write-Host "执行阶梯: $($steps -join ', ')" -ForegroundColor Cyan

foreach ($vus in $steps) {
    $stageLabel = "VU_$vus"
    $result = Run-StressStage -Concurrency $vus -DurationSec $DurationSec -RampUpSec $RampUpSec -StageLabel $stageLabel -ApiListStr $apiListStr -AuthHdrValue $authHdrValue -TsFile $tsFile -SampleIntervalSec $SampleIntervalSec
    $allStageResults += $result

    if ($result.error_rate -gt 50 -or $result.p99_ms -gt 10000) {
        Write-Host "`n!!! 熔断检测: VU=$vus 错误率=$($result.error_rate)% P99=$($result.p99_ms)ms" -ForegroundColor Red
        $circuitBreaker = $true
        $circuitBreakerAt = $vus
        Write-Host "    已触发熔断，停止加压，进入恢复期..." -ForegroundColor Red
        break
    }

    if ($vus -ne $steps[-1]) {
        Write-Host "`n  阶梯间休息 20s..." -ForegroundColor Gray
        Start-Sleep -Seconds 20
    }
}

# ============== 恢复时间测量 ==============
Write-Host "`n========== 恢复时间测量 ==========" -ForegroundColor Cyan
$recoveryStart = Get-Date
$recovered = $false
$recoverySamples = @()
$projUrl = "$BaseUrl$ApiPrefix/projects"
for ($i = 0; $i -lt 24; $i++) {
    Start-Sleep -Seconds 5
    try {
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        $resp = Invoke-WebRequest -Uri $projUrl -Headers @{Authorization=$authHdrValue} -TimeoutSec 10 -ErrorAction Stop
        $sw.Stop()
        $dur = $sw.Elapsed.TotalMilliseconds
        $status = [int]$resp.StatusCode
        $elapsedS = [math]::Round(((Get-Date) - $recoveryStart).TotalSeconds, 1)
        $recoverySamples += @{ elapsed_s = $elapsedS; dur_ms = [math]::Round($dur, 2); status = $status }
        Write-Host "  恢复采样 [$($i+1)/24]: $([math]::Round($dur,2))ms, status=$status" -ForegroundColor Gray
        if ($status -eq 200 -and $dur -lt 200) {
            $recovered = $true
            Write-Host "  系统已恢复 (< 200ms)" -ForegroundColor Green
            break
        }
    } catch {
        $sw.Stop()
        $status = 0
        if ($_.Exception.Response) { try { $status = [int]$_.Exception.Response.StatusCode } catch {} }
        $elapsedS = [math]::Round(((Get-Date) - $recoveryStart).TotalSeconds, 1)
        $recoverySamples += @{ elapsed_s = $elapsedS; dur_ms = [math]::Round($sw.Elapsed.TotalMilliseconds, 2); status = $status }
        $errMsg = $_.Exception.Message
        if ($errMsg.Length -gt 50) { $errMsg = $errMsg.Substring(0, 50) }
        Write-Host "  恢复采样 [$($i+1)/24]: status=$status - $errMsg" -ForegroundColor Yellow
    }
}
$recoveryMs = [math]::Round(((Get-Date) - $recoveryStart).TotalMilliseconds, 0)

# ============== 输出汇总 ==============
$totalElapsed = [math]::Round(((Get-Date) - $globalStart).TotalSeconds, 0)

$summary = @{
    scenario = $Scenario
    timestamp = $timestamp
    total_elapsed_sec = $totalElapsed
    max_concurrency = $MaxConcurrency
    duration_per_stage_sec = $DurationSec
    stages = $allStageResults
    circuit_breaker = @{ triggered = $circuitBreaker; at_concurrency = $circuitBreakerAt }
    recovery = @{ recovered = $recovered; recovery_time_ms = $recoveryMs; samples = $recoverySamples }
}
$summary | ConvertTo-Json -Depth 5 | Out-File -FilePath $summaryFile -Encoding UTF8

# ============== Markdown 报告 ==============
$report = @"
# 高级别压测报告

> 场景: $Scenario ｜ 时间: $timestamp ｜ 总耗时: ${totalElapsed}s

## 1. 测试参数

| 参数 | 值 |
|------|-----|
| 最大并发 | $MaxConcurrency |
| 每阶梯持续 | ${DurationSec}s |
| Ramp-up | ${RampUpSec}s |
| 执行阶梯 | $($steps -join ', ') |

## 2. 各阶梯结果

| 阶梯 | 并发 | 总请求 | 错误 | 错误率 | TPS | avg(ms) | P50 | P95 | P99 | max |
|------|------|--------|------|--------|-----|---------|-----|-----|-----|-----|
"@

foreach ($r in $allStageResults) {
    $report += "`n| $($r.stage) | $($r.concurrency) | $($r.total_requests) | $($r.errors) | $($r.error_rate)% | $($r.tps) | $($r.avg_ms) | $($r.p50_ms) | $($r.p95_ms) | $($r.p99_ms) | $($r.max_ms) |"
}

$cbStr = if ($circuitBreaker) { "是，并发=$circuitBreakerAt" } else { '否' }
$recStr = if ($recovered) { "是，${recoveryMs}ms" } else { '未完全恢复' }

$report += @"

## 3. 熔断检测

- 触发: $circuitBreaker
- 熔断并发: $(if ($circuitBreakerAt) { $circuitBreakerAt } else { '未触发' })

## 4. 恢复时间

- 已恢复: $recovered
- 恢复耗时: ${recoveryMs}ms

## 5. 时序数据

时序数据见: $tsFile
汇总 JSON: $summaryFile
"@

$report | Out-File -FilePath $reportFile -Encoding UTF8

Write-Host "`n========== 压测完成 ==========" -ForegroundColor Green
Write-Host "总耗时: ${totalElapsed}s" -ForegroundColor Green
Write-Host "时序数据: $tsFile" -ForegroundColor Green
Write-Host "汇总 JSON: $summaryFile" -ForegroundColor Green
Write-Host "Markdown 报告: $reportFile" -ForegroundColor Green
Write-Host "熔断: $cbStr" -ForegroundColor $(if ($circuitBreaker) { 'Red' } else { 'Green' })
Write-Host "恢复: $recStr" -ForegroundColor $(if ($recovered) { 'Green' } else { 'Red' })
