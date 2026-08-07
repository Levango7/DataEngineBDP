$ErrorActionPreference = "Continue"
Start-Job -Name "build-llm-gateway" -ScriptBlock {
    $output = docker build -t sq/llm-gateway:0.1.0 "F:\Agent\workbuddy\workspace\ShuqingBigDataPlatform\platform\llm-gateway" 2>&1
    $code = $LASTEXITCODE
    $output | Out-File "F:\Agent\workbuddy\workspace\ShuqingBigDataPlatform\deploy\k3s\llm-gateway-build.log"
    return @{Name="llm-gateway"; Code=$code}
}
Start-Job -Name "build-baremetal" -ScriptBlock {
    $output = docker build -t sq/infra-provider-baremetal:0.1.0 "F:\Agent\workbuddy\workspace\ShuqingBigDataPlatform\platform\infra-provider-baremetal" 2>&1
    $code = $LASTEXITCODE
    $output | Out-File "F:\Agent\workbuddy\workspace\ShuqingBigDataPlatform\deploy\k3s\baremetal-build.log"
    return @{Name="infra-provider-baremetal"; Code=$code}
}
Write-Output "Started 2 Go build jobs in background"
Get-Job | Format-Table Name, State -AutoSize