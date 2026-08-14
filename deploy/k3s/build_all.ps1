$ErrorActionPreference = "Continue"
# 修复：不再硬编码旧沙箱路径 F:\Agent\workbuddy（评估报告 10.2），
# 改为脚本所在目录推导仓库根，可在任意工作区运行
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$base = Join-Path $repoRoot "platform"
$results = @()

# 模块列表: name, dockerfilePath, image
$modules = @(
    @{Name="tag-engine"; Path="$base\tag-engine"; Image="sq/tag-engine:0.1.0"},
    @{Name="infra-orchestrator"; Path="$base\infra-orchestrator"; Image="sq/infra-orchestrator:0.1.0"},
    @{Name="infra-provider-cloud"; Path="$base\infra-provider-cloud"; Image="sq/infra-provider-cloud:0.1.0"},
    @{Name="infra-provider-private"; Path="$base\infra-provider-private"; Image="sq/infra-provider-private:0.1.0"},
    @{Name="infra-provider-xinchang"; Path="$base\infra-provider-xinchang"; Image="sq/infra-provider-xinchang:0.1.0"},
    @{Name="lineage-analyzer"; Path="$base\governance\lineage-analyzer"; Image="sq/lineage-analyzer:0.1.0"},
    @{Name="metadata-collector"; Path="$base\governance\metadata-collector"; Image="sq/metadata-collector:0.1.0"},
    @{Name="llm-gateway"; Path="$base\llm-gateway"; Image="sq/llm-gateway:0.1.0"},
    @{Name="vector-engine"; Path="$base\vector-engine"; Image="sq/vector-engine:0.1.0"},
    @{Name="infra-provider-baremetal"; Path="$base\infra-provider-baremetal"; Image="sq/infra-provider-baremetal:0.1.0"},
    @{Name="business-portal"; Path="$base\business-portal"; Image="sq/business-portal:0.1.0"},
    @{Name="industry-templates"; Path="$base\industry-templates"; Image="sq/industry-templates:0.1.0"},
    @{Name="knowledge-engine"; Path="$base\knowledge-engine"; Image="sq/knowledge-engine:0.1.0"},
    @{Name="llmops"; Path="$base\llmops"; Image="sq/llmops:0.1.0"},
    @{Name="ml-platform"; Path="$base\ml-platform"; Image="sq/ml-platform:0.1.0"},
    @{Name="open-api-catalog"; Path="$base\open-api-catalog"; Image="sq/open-api-catalog:0.1.0"},
    @{Name="nl2sql"; Path="$base\nl2sql"; Image="sq/nl2sql:0.1.0"}
)

$logFile = Join-Path $repoRoot "deploy\k3s\build_log.txt"
"Build Log - $(Get-Date)" | Out-File $logFile

foreach ($m in $modules) {
    $name = $m.Name
    $path = $m.Path
    $image = $m.Image
    
    Write-Output "[$(Get-Date -Format 'HH:mm:ss')] Building $name..."
    "[$(Get-Date -Format 'HH:mm:ss')] Building $name from $path" | Out-File $logFile -Append
    
    $startTime = Get-Date
    $buildOutput = docker build -t $image $path 2>&1
    $endTime = Get-Date
    $duration = ($endTime - $startTime).TotalSeconds
    
    $lastLines = $buildOutput | Select-Object -Last 5
    $lastLines | ForEach-Object { "  $_" } | Out-File $logFile -Append
    
    if ($LASTEXITCODE -eq 0) {
        Write-Output "  -> SUCCESS (${duration}s)"
        "  -> SUCCESS (${duration}s)" | Out-File $logFile -Append
        $results += [PSCustomObject]@{Name=$name; Status="Success"; Duration="${duration}s"}
    } else {
        Write-Output "  -> FAILED (${duration}s)"
        "  -> FAILED (${duration}s)" | Out-File $logFile -Append
        $results += [PSCustomObject]@{Name=$name; Status="Failed"; Duration="${duration}s"}
    }
}

Write-Output "`n=== Build Summary ==="
$results | Format-Table -AutoSize
"=== Build Summary ===" | Out-File $logFile -Append
$results | Format-Table -AutoSize | Out-File $logFile -Append