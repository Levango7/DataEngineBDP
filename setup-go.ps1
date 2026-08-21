$goroot = 'F:\Program Files (x86)\CodeArts\go'
$gobin = "$goroot\bin"
[Environment]::SetEnvironmentVariable('GOROOT', $goroot, 'User')
$currentPath = [Environment]::GetEnvironmentVariable('Path', 'User')
if ($currentPath -notlike "*$gobin*") {
    [Environment]::SetEnvironmentVariable('Path', "$currentPath;$gobin", 'User')
}
Write-Host "GOROOT = $goroot"
Write-Host "GO bin added to PATH"
& "$gobin\go.exe" version