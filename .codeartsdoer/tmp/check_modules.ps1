﻿$dirs = Get-ChildItem 'F:\nexus\DataEngineBDP\platform' -Directory
$java = 0
$go = 0
$python = 0
$none = 0
$result = @()
foreach ($d in $dirs) {
    $name = $d.Name
    $hasPom = Test-Path ($d.FullName + '\pom.xml')
    $hasGoMod = Test-Path ($d.FullName + '\go.mod')
    $hasPyProject = Test-Path ($d.FullName + '\pyproject.toml')
    $hasSetupPy = Test-Path ($d.FullName + '\setup.py')
    $type = 'None'
    if ($hasPom) { $type = 'Java'; $java++ }
    elseif ($hasGoMod) { $type = 'Go'; $go++ }
    elseif ($hasPyProject -or $hasSetupPy) { $type = 'Python'; $python++ }
    else { $none++ }
    $result += ($name + ' | ' + $type)
}
Write-Output "=== Module Type Summary ==="
Write-Output ("Total: " + $dirs.Count)
Write-Output ("Java: " + $java)
Write-Output ("Go: " + $go)
Write-Output ("Python: " + $python)
Write-Output ("None: " + $none)
Write-Output "=== Module Details ==="
$result | ForEach-Object { Write-Output $_ }