﻿$modules = Get-ChildItem 'F:\Agent\workbuddy\workspace\DataEngineBDP\platform' -Directory
foreach ($m in $modules) {
    $name = $m.Name
    $hasPom = Test-Path (Join-Path $m.FullName 'pom.xml')
    $hasGo = Test-Path (Join-Path $m.FullName 'go.mod')
    $hasReq = Test-Path (Join-Path $m.FullName 'requirements.txt')
    $hasMain = Test-Path (Join-Path $m.FullName 'main.py')
    $hasDocker = Test-Path (Join-Path $m.FullName 'Dockerfile')
    $hasK8s = Test-Path (Join-Path $m.FullName 'deploy')
    $type = 'Unknown'
    if ($hasPom) { $type = 'Java' }
    elseif ($hasGo) { $type = 'Go' }
    elseif ($hasReq -or $hasMain) { $type = 'Python' }
    Write-Output ("{0}|{1}|Docker={2}|K8s={3}" -f $name, $type, $hasDocker, $hasK8s)
}