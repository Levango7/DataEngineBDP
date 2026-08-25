﻿$root = 'F:\nexus\DataEngineBDP\platform'
# 查找所有构建文件
$pomFiles = Get-ChildItem $root -Recurse -Filter 'pom.xml' -File | Where-Object { $_.DirectoryName -notlike '*\target\*' -and $_.DirectoryName -notlike '*\src\*' }
$goModFiles = Get-ChildItem $root -Recurse -Filter 'go.mod' -File
$pyProjectFiles = Get-ChildItem $root -Recurse -Filter 'pyproject.toml' -File
$setupPyFiles = Get-ChildItem $root -Recurse -Filter 'setup.py' -File

Write-Output "=== pom.xml files (excluding target/src) ==="
$pomFiles | ForEach-Object { Write-Output ($_.FullName.Substring($root.Length + 1)) }
Write-Output ("Total pom.xml: " + $pomFiles.Count)

Write-Output ""
Write-Output "=== go.mod files ==="
$goModFiles | ForEach-Object { Write-Output ($_.FullName.Substring($root.Length + 1)) }
Write-Output ("Total go.mod: " + $goModFiles.Count)

Write-Output ""
Write-Output "=== pyproject.toml files ==="
$pyProjectFiles | ForEach-Object { Write-Output ($_.FullName.Substring($root.Length + 1)) }
Write-Output ("Total pyproject.toml: " + $pyProjectFiles.Count)

Write-Output ""
Write-Output "=== setup.py files ==="
$setupPyFiles | ForEach-Object { Write-Output ($_.FullName.Substring($root.Length + 1)) }
Write-Output ("Total setup.py: " + $setupPyFiles.Count)