$ErrorActionPreference = "Continue"
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$base = "$repoRoot\platform"
$logFile = "$repoRoot\deploy\k3s\build_log2.txt"
"Parallel Build Log - $(Get-Date)" | Out-File $logFile

# 分批构建，每批4个并行
$batches = @(
    @(
        @{Name="business-portal"; Path="$base\business-portal"; Image="sq/business-portal:0.1.0"},
        @{Name="industry-templates"; Path="$base\industry-templates"; Image="sq/industry-templates:0.1.0"},
        @{Name="open-api-catalog"; Path="$base\open-api-catalog"; Image="sq/open-api-catalog:0.1.0"},
        @{Name="nl2sql"; Path="$base\nl2sql"; Image="sq/nl2sql:0.1.0"}
    ),
    @(
        @{Name="knowledge-engine"; Path="$base\knowledge-engine"; Image="sq/knowledge-engine:0.1.0"},
        @{Name="llmops"; Path="$base\llmops"; Image="sq/llmops:0.1.0"},
        @{Name="ml-platform"; Path="$base\ml-platform"; Image="sq/ml-platform:0.1.0"},
        @{Name="llm-gateway"; Path="$base\llm-gateway"; Image="sq/llm-gateway:0.1.0"}
    ),
    @(
        @{Name="vector-engine"; Path="$base\vector-engine"; Image="sq/vector-engine:0.1.0"},
        @{Name="infra-provider-baremetal"; Path="$base\infra-provider-baremetal"; Image="sq/infra-provider-baremetal:0.1.0"},
        @{Name="infra-orchestrator"; Path="$base\infra-orchestrator"; Image="sq/infra-orchestrator:0.1.0"},
        @{Name="infra-provider-cloud"; Path="$base\infra-provider-cloud"; Image="sq/infra-provider-cloud:0.1.0"}
    ),
    @(
        @{Name="infra-provider-private"; Path="$base\infra-provider-private"; Image="sq/infra-provider-private:0.1.0"},
        @{Name="infra-provider-xinchang"; Path="$base\infra-provider-xinchang"; Image="sq/infra-provider-xinchang:0.1.0"},
        @{Name="lineage-analyzer"; Path="$base\governance\lineage-analyzer"; Image="sq/lineage-analyzer:0.1.0"},
        @{Name="metadata-collector"; Path="$base\governance\metadata-collector"; Image="sq/metadata-collector:0.1.0"}
    )
)

$allResults = @()
$batchNum = 1

foreach ($batch in $batches) {
    Write-Output "`n=== Batch $batchNum ==="
    "=== Batch $batchNum ===" | Out-File $logFile -Append
    
    $jobs = @()
    foreach ($m in $batch) {
        $name = $m.Name
        $path = $m.Path
        $image = $m.Image
        
        Write-Output "  Starting: $name"
        "  Starting: $name" | Out-File $logFile -Append
        
        $job = Start-Job -ScriptBlock {
            param($img, $p)
            $output = docker build -t $img $p 2>&1
            $code = $LASTEXITCODE
            return @{Image=$img; Code=$code; Output=($output | Select-Object -Last 3)}
        } -ArgumentList $image, $path
        
        $jobs += @{Job=$job; Name=$name; Image=$image}
    }
    
    # 等待所有作业完成，超时1200秒
    $timeout = 1200
    $startTime = Get-Date
    
    while ($jobs | Where-Object { $_.Job.State -eq "Running" }) {
        $elapsed = (Get-Date) - $startTime
        if ($elapsed.TotalSeconds -gt $timeout) {
            Write-Output "  Batch timeout, stopping remaining jobs..."
            $jobs | Where-Object { $_.Job.State -eq "Running" } | ForEach-Object {
                Stop-Job $_.Job
                $_.Name + " -> TIMEOUT" | Out-File $logFile -Append
                $script:allResults += [PSCustomObject]@{Name=$_.Name; Status="Timeout"}
            }
            break
        }
        Start-Sleep -Seconds 10
        $running = ($jobs | Where-Object { $_.Job.State -eq "Running" }).Count
        Write-Output "  Waiting... $running jobs running ($([int]$elapsed.TotalSeconds)s)"
    }
    
    # 收集结果
    foreach ($j in $jobs) {
        if ($j.Job.State -eq "Completed") {
            $result = Receive-Job $j.Job
            if ($result.Code -eq 0) {
                Write-Output "  $($j.Name) -> SUCCESS"
                "  $($j.Name) -> SUCCESS" | Out-File $logFile -Append
                $allResults += [PSCustomObject]@{Name=$j.Name; Status="Success"}
            } else {
                Write-Output "  $($j.Name) -> FAILED"
                "  $($j.Name) -> FAILED" | Out-File $logFile -Append
                $result.Output | ForEach-Object { "    $_" } | Out-File $logFile -Append
                $allResults += [PSCustomObject]@{Name=$j.Name; Status="Failed"}
            }
        } elseif ($j.Job.State -eq "Failed") {
            Write-Output "  $($j.Name) -> JOB FAILED"
            $allResults += [PSCustomObject]@{Name=$j.Name; Status="Failed"}
        }
        Remove-Job $j.Job -Force
    }
    
    $batchNum++
}

Write-Output "`n=== Build Summary ==="
$allResults | Format-Table -AutoSize
"=== Build Summary ===" | Out-File $logFile -Append
$allResults | Format-Table -AutoSize | Out-File $logFile -Append

# 返回结果供外部使用
$allResults | Export-Csv "$repoRoot\deploy\k3s\build_results.csv" -NoTypeInformation