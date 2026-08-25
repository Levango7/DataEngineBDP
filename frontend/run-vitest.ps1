$ErrorActionPreference = 'SilentlyContinue'
Set-Location 'F:\nexus\DataEngineBDP\frontend'
npx vitest run --reporter=tap 2>&1 | Out-File -FilePath 'F:\nexus\DataEngineBDP\frontend\vitest-tap.txt' -Encoding utf8
Write-Host "VitestExitCode: $LASTEXITCODE"