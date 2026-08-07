$ErrorActionPreference = "Continue"
$logFile = "F:\Agent\workbuddy\workspace\ShuqingBigDataPlatform\deploy\k3s\java_build_log.txt"
"Java Build Log - $(Get-Date)" | Out-File $logFile

$base = "F:\Agent\workbuddy\workspace\ShuqingBigDataPlatform\platform"

# 6个Java模块,分2批每批3个
$batches = @(
    @(
        @{Name="infra-orchestrator"; Path="$base\infra-orchestrator"; Image="sq/infra-orchestrator:0.1.0"},
        @{Name="infra-provider-cloud"; Path="$base\infra-provider-cloud"; Image="sq/infra-provider-cloud:0.1.0"},
        @{Name="infra-provider-private"; Path="$base\infra-provider-private"; Image="sq/infra-provider-private:0.1.0"}
    ),
    @(
        @{Name="infra-provider-xinchang"; Path="$base\infra-provider-xinchang"; Image="sq/infra-provider-xinchang:0.1.0"},
        @{Name="lineage-analyzer"; Path="$base\governance\lineage-analyzer"; Image="sq/lineage-analyzer:0.1.0"},
        @{Name="metadata-collector"; Path="$base\governance\metadata-collector"; Image="sq/metadata-collector:0.1.0"}
    )
)

$allResults = @()
$batchNum = 1

foreach ($batch in $batches) {
    Write-Output "`n=== Java Batch $batchNum ==="
    "=== Java Batch $batchNum ===" | Out-File $logFile -Append
    
    $jobs = @()
    foreach ($m in $batch) {
        $name = $m.Name
        $path = $m.Path
        $image = $m.Image
        
        Write-Output "  Starting: $name"
        "  Starting: $name" | Out-File $logFile -Append
        
        $job = Start-Job -ScriptBlock {
            param($img, $p, $n)
            $output = docker build -t $img $p 2>&1
            $code = $LASTEXITCODE
            return @{Name=$n; Image=$img; Code=$code; Output=($output | Select-Object -Last 5)}
        } -ArgumentList $image, $path, $name
        
        $jobs += @{Job=$job; Name=$name; Image=$image}
    }
    
    # Java构建超时1500秒(25分钟)
    $timeout = 1500
    $startTime = Get-Date
    
    while ($jobs | Where-Object { $_.Job.State -eq "Running" }) {
        $elapsed = (Get-Date) - $startTime
        if ($elapsed.TotalSeconds -gt $timeout) {
            Write-Output "  Batch timeout ($timeout s), stopping remaining jobs..."
            $jobs | Where-Object { $_.Job.State -eq "Running" } | ForEach-Object {
                Stop-Job $_.Job
                Write-Output "  $($_.Name) -> TIMEOUT"
                "  $($_.Name) -> TIMEOUT" | Out-File $logFile -Append
                $script:allResults += [PSCustomObject]@{Name=$_.Name; Status="Timeout"}
            }
            break
        }
        Start-Sleep -Seconds 30
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
                Write-Output "  $($j.Name) -> FAILED (code=$($result.Code))"
                "  $($j.Name) -> FAILED (code=$($result.Code))" | Out-File $logFile -Append
                $result.Output | ForEach-Object { "    $_" } | Out-File $logFile -Append
                $allResults += [PSCustomObject]@{Name=$j.Name; Status="Failed"}
            }
        } elseif ($j.Job.State -eq "Failed") {
            Write-Output "  $($j.Name) -> JOB FAILED"
            "  $($j.Name) -> JOB FAILED" | Out-File $logFile -Append
            $allResults += [PSCustomObject]@{Name=$j.Name; Status="Failed"}
        }
        Remove-Job $j.Job -Force -ErrorAction SilentlyContinue
    }
    
    $batchNum++
}

Write-Output "`n=== Java Build Summary ==="
$allResults | Format-Table -AutoSize
"=== Java Build Summary ===" | Out-File $logFile -Append
$allResults | Format-Table -AutoSize | Out-File $logFile -Append

$allResults | Export-Csv "F:\Agent\workbuddy\workspace\ShuqingBigDataPlatform\deploy\k3s\java_build_results.csv" -NoTypeInformation