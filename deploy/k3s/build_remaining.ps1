$ErrorActionPreference = "Continue"
$base = "F:\Agent\workbuddy\workspace\ShuqingBigDataPlatform\platform"
$logFile = "F:\Agent\workbuddy\workspace\ShuqingBigDataPlatform\deploy\k3s\build_remaining_log.txt"
"Remaining Build Log - $(Get-Date)" | Out-File $logFile

# 待构建的12个模块
$batches = @(
    @(
        # Python + Go 快速构建(6个并行)
        @{Name="knowledge-engine"; Path="$base\knowledge-engine"; Image="sq/knowledge-engine:0.1.0"; Type="Python"},
        @{Name="llmops"; Path="$base\llmops"; Image="sq/llmops:0.1.0"; Type="Python"},
        @{Name="ml-platform"; Path="$base\ml-platform"; Image="sq/ml-platform:0.1.0"; Type="Python"},
        @{Name="llm-gateway"; Path="$base\llm-gateway"; Image="sq/llm-gateway:0.1.0"; Type="Go"},
        @{Name="vector-engine"; Path="$base\vector-engine"; Image="sq/vector-engine:0.1.0"; Type="Go"},
        @{Name="infra-provider-baremetal"; Path="$base\infra-provider-baremetal"; Image="sq/infra-provider-baremetal:0.1.0"; Type="Go"}
    ),
    @(
        # Java 慢速构建(3个并行)
        @{Name="infra-orchestrator"; Path="$base\infra-orchestrator"; Image="sq/infra-orchestrator:0.1.0"; Type="Java"},
        @{Name="infra-provider-cloud"; Path="$base\infra-provider-cloud"; Image="sq/infra-provider-cloud:0.1.0"; Type="Java"},
        @{Name="infra-provider-private"; Path="$base\infra-provider-private"; Image="sq/infra-provider-private:0.1.0"; Type="Java"}
    ),
    @(
        # Java 慢速构建(3个并行)
        @{Name="infra-provider-xinchang"; Path="$base\infra-provider-xinchang"; Image="sq/infra-provider-xinchang:0.1.0"; Type="Java"},
        @{Name="lineage-analyzer"; Path="$base\governance\lineage-analyzer"; Image="sq/lineage-analyzer:0.1.0"; Type="Java"},
        @{Name="metadata-collector"; Path="$base\governance\metadata-collector"; Image="sq/metadata-collector:0.1.0"; Type="Java"}
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
        $type = $m.Type
        
        Write-Output "  Starting: $name ($type)"
        "  Starting: $name ($type)" | Out-File $logFile -Append
        
        $job = Start-Job -ScriptBlock {
            param($img, $p, $n)
            $output = docker build -t $img $p 2>&1
            $code = $LASTEXITCODE
            return @{Name=$n; Image=$img; Code=$code; Output=($output | Select-Object -Last 5)}
        } -ArgumentList $image, $path, $name
        
        $jobs += @{Job=$job; Name=$name; Image=$image; Type=$type}
    }
    
    # 等待所有作业完成,Python/Go超时600秒,Java超时900秒
    $isJavaBatch = $batch[0].Type -eq "Java"
    $timeout = if ($isJavaBatch) { 900 } else { 600 }
    $startTime = Get-Date
    
    while ($jobs | Where-Object { $_.Job.State -eq "Running" }) {
        $elapsed = (Get-Date) - $startTime
        if ($elapsed.TotalSeconds -gt $timeout) {
            Write-Output "  Batch timeout ($timeout s), stopping remaining jobs..."
            $jobs | Where-Object { $_.Job.State -eq "Running" } | ForEach-Object {
                Stop-Job $_.Job
                Write-Output "  $($_.Name) -> TIMEOUT"
                "  $($_.Name) -> TIMEOUT" | Out-File $logFile -Append
                $script:allResults += [PSCustomObject]@{Name=$_.Name; Status="Timeout"; Type=$_.Type}
            }
            break
        }
        Start-Sleep -Seconds 15
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
                $allResults += [PSCustomObject]@{Name=$j.Name; Status="Success"; Type=$j.Type}
            } else {
                Write-Output "  $($j.Name) -> FAILED (code=$($result.Code))"
                "  $($j.Name) -> FAILED (code=$($result.Code))" | Out-File $logFile -Append
                $result.Output | ForEach-Object { "    $_" } | Out-File $logFile -Append
                $allResults += [PSCustomObject]@{Name=$j.Name; Status="Failed"; Type=$j.Type}
            }
        } elseif ($j.Job.State -eq "Failed") {
            Write-Output "  $($j.Name) -> JOB FAILED"
            "  $($j.Name) -> JOB FAILED" | Out-File $logFile -Append
            $allResults += [PSCustomObject]@{Name=$j.Name; Status="Failed"; Type=$j.Type}
        }
        Remove-Job $j.Job -Force -ErrorAction SilentlyContinue
    }
    
    $batchNum++
}

Write-Output "`n=== Build Summary ==="
$allResults | Format-Table -AutoSize
"=== Build Summary ===" | Out-File $logFile -Append
$allResults | Format-Table -AutoSize | Out-File $logFile -Append

# 返回结果
$allResults | Export-Csv "F:\Agent\workbuddy\workspace\ShuqingBigDataPlatform\deploy\k3s\build_remaining_results.csv" -NoTypeInformation
Write-Output "`nResults saved to build_remaining_results.csv"