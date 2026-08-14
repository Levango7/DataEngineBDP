$ErrorActionPreference = "Continue"
$images = @(
    "sq/catalog:0.1.0",
    "sq/encaps-layer:0.1.0",
    "sq/sql-gateway:0.1.0",
    "sq/rule-engine:0.1.0",
    "sq/asset-exchange:0.1.0"
)

foreach ($img in $images) {
    Write-Output "Importing $img..."
    # 修复：脚本所在目录推导仓库根（评估报告 10.2），不再硬编码旧沙箱路径
    $k3sDir = $PSScriptRoot
    $tmpFile = Join-Path $k3sDir "tmp_image.tar"
    docker save -o $tmpFile $img 2>&1
    if ($LASTEXITCODE -eq 0) {
        # 转换为 WSL 路径：C:\foo → /mnt/c/foo
        $driveLetter = $k3sDir.Substring(0, 1).ToLower()
        $wslPath = "/mnt/" + $driveLetter + "/" + ($k3sDir.Substring(3) -replace '\\', '/') + "/tmp_image.tar"
        wsl -d Ubuntu-24.04 -- bash -c "sudo k3s ctr images import $wslPath" 2>&1
        Write-Output "  -> Imported $img"
    } else {
        Write-Output "  -> FAILED to save $img"
    }
    Remove-Item $tmpFile -Force -ErrorAction SilentlyContinue
}
Write-Output "Done."