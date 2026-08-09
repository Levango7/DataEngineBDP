$ErrorActionPreference = "Continue"
$logFile = "F:\Agent\workbuddy\workspace\DataEngineBDP\deploy\k3s\go_build_log.txt"
"Go Build Log - $(Get-Date)" | Out-File $logFile

$jobs = @()

# llm-gateway
Write-Output "Starting: llm-gateway"
"Starting: llm-gateway" | Out-File $logFile -Append
$job1 = Start-Job -Name "llm-gateway" -ScriptBlock {
    $output = docker build -t sq/llm-gateway:0.1.0 "F:\Agent\workbuddy\workspace\DataEngineBDP\platform\llm-gateway" 2>&1
    $code = $LASTEXITCODE
    return @{Name="llm-gateway"; Code=$code; Output=($output | Select-Object -Last 5)}
}
$jobs += @{Job=$job1; Name="llm-gateway"}

# infra-provider-baremetal
Write-Output "Starting: infra-provider-baremetal"
"Starting: infra-provider-baremetal" | Out-File $logFile -Append
$job2 = Start-Job -Name "baremetal" -ScriptBlock {
    $output = docker build -t sq/infra-provider-baremetal:0.1.0 "F:\Agent\workbuddy\workspace\DataEngineBDP\platform\infra-provider-baremetal" 2>&1
    $code = $LASTEXITCODE
    return @{Name="infra-provider-baremetal"; Code=$code; Output=($output | Select-Object -Last 5)}
}
$jobs += @{Job=$job2; Name="infra-provider-baremetal"}

# 等待完成,超时1200秒
$timeout = 1200
$startTime = Get-Date

while ($jobs | Where-Object { $_.Job.State -eq "Running" }) {
    $elapsed = (Get-Date) - $startTime
    if ($elapsed.TotalSeconds -gt $timeout) {
        Write-Output "Timeout, stopping jobs..."
        $jobs | Where-Object { $_.Job.State -eq "Running" } | ForEach-Object {
            Stop-Job $_.Job
            Write-Output "  $($_.Name) -> TIMEOUT"
            "  $($_.Name) -> TIMEOUT" | Out-File $logFile -Append
        }
        break
    }
    Start-Sleep -Seconds 20
    $running = ($jobs | Where-Object { $_.Job.State -eq "Running" }).Count
    Write-Output "Waiting... $running jobs running ($([int]$elapsed.TotalSeconds)s)"
}

# 收集结果
foreach ($j in $jobs) {
    if ($j.Job.State -eq "Completed") {
        $result = Receive-Job $j.Job
        if ($result.Code -eq 0) {
            Write-Output "  $($j.Name) -> SUCCESS"
            "  $($j.Name) -> SUCCESS" | Out-File $logFile -Append
        } else {
            Write-Output "  $($j.Name) -> FAILED"
            "  $($j.Name) -> FAILED" | Out-File $logFile -Append
            $result.Output | ForEach-Object { "    $_" } | Out-File $logFile -Append
        }
    }
    Remove-Job $j.Job -Force -ErrorAction SilentlyContinue
}