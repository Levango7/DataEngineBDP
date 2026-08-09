$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = "git"
$psi.Arguments = "credential fill"
$psi.RedirectStandardInput = $true
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
$psi.UseShellExecute = $false
$p = [System.Diagnostics.Process]::Start($psi)
$sw = $p.StandardInput
$sw.WriteLine("protocol=https")
$sw.WriteLine("host=github.com")
$sw.WriteLine("")
$sw.Close()
$p.WaitForExit(5000)
$token = $null
while (-not $p.StandardOutput.EndOfStream) {
    $line = $p.StandardOutput.ReadLine()
    if ($line -match "^password=(.+)$") {
        $token = $Matches[1]
        Write-Output "Token found! Length: $($token.Length)"
        break
    }
}
if ($token) {
    $headers = @{ "Authorization" = "Bearer $token"; "Accept" = "application/vnd.github+json" }
    try {
        $resp = Invoke-RestMethod -Uri "https://api.github.com/user" -Headers $headers -Method Get
        Write-Output "Token valid! User: $($resp.login)"
        $token | Out-File -FilePath "$env:TEMP\_gh_token.txt" -Encoding ASCII -NoNewline
    } catch {
        Write-Output "Token test failed: $($_.Exception.Message)"
    }
} else {
    Write-Output "No token found"
}