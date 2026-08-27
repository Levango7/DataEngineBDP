# 验证所有 Chart 的 helm lint 通过
# 用法: powershell -File _validate_security_charts.ps1
$ErrorActionPreference = "SilentlyContinue"
$helm = "C:\Users\winge\bin\helm.exe"
$chartsDir = "F:\nexus\DataEngineBDP\design\deploy\charts"
$allOk = $true
$failCharts = @()
Get-ChildItem $chartsDir -Directory | Where-Object { $_.Name -ne "__pycache__" } | ForEach-Object {
    $result = & $helm lint $_.FullName 2>&1
    if ($result -match "ERROR") {
        Write-Output "FAIL: $($_.Name)"
        $allOk = $false
        $failCharts += $_.Name
    } else {
        Write-Output "OK: $($_.Name)"
    }
}
Write-Output ""
if ($allOk) { Write-Output "ALL CHARTS PASS" } else { Write-Output "SOME CHARTS FAILED: $($failCharts -join ', ')" }