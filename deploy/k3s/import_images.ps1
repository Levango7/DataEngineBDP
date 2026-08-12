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
    $tmpFile = "F:\Agent\workbuddy\workspace\DataEngineBDP\deploy\k3s\tmp_image.tar"
    docker save -o $tmpFile $img 2>&1
    if ($LASTEXITCODE -eq 0) {
        $wslPath = "/mnt/f/Agent/workbuddy/workspace/DataEngineBDP/deploy/k3s/tmp_image.tar"
        wsl -d Ubuntu-24.04 -- bash -c "sudo k3s ctr images import $wslPath" 2>&1
        Write-Output "  -> Imported $img"
    } else {
        Write-Output "  -> FAILED to save $img"
    }
    Remove-Item $tmpFile -Force -ErrorAction SilentlyContinue
}
Write-Output "Done."