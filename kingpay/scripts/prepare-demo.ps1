#requires -Version 5.1
param(
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$Password = $env:KINGPAY_DEMO_PASSWORD
)
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($Password)) {
    $demoSecretPath = Join-Path (Split-Path -Parent $PSScriptRoot) '.env.demo'
    if (Test-Path -LiteralPath $demoSecretPath) {
        $demoSecretLine = Get-Content -LiteralPath $demoSecretPath | Where-Object { $_ -match '^KINGPAY_DEMO_PASSWORD=' } | Select-Object -First 1
        if ($demoSecretLine) { $Password = $demoSecretLine.Substring('KINGPAY_DEMO_PASSWORD='.Length) }
    } else {
        $demoRandom = [Security.Cryptography.RandomNumberGenerator]::Create()
        $demoBytes = New-Object byte[] 32
        try { $demoRandom.GetBytes($demoBytes) } finally { $demoRandom.Dispose() }
        $Password = [Convert]::ToBase64String($demoBytes)
        [IO.File]::WriteAllText($demoSecretPath, 'KINGPAY_DEMO_PASSWORD=' + $Password + [Environment]::NewLine)
    }
}
if ([string]::IsNullOrWhiteSpace($Password) -or $Password.Length -lt 8 -or [Text.Encoding]::UTF8.GetByteCount($Password) -gt 72) {
    throw 'Set KINGPAY_DEMO_PASSWORD to a password of 8+ characters and at most 72 UTF-8 bytes.'
}
$BaseUrl = $BaseUrl.TrimEnd('/')

# Repeated runs only load the difference. Existing balances above the target fail instead of being rewritten.
foreach ($demo in @(
    @{ Username = 'demo-customer'; FullName = 'Demo Customer'; Hero = $false; Target = [decimal]1000.00 },
    @{ Username = 'demo-superhero'; FullName = 'Demo Superhero'; Hero = $true; Target = [decimal]250.00 }
)) {
    $registration = @{
        username = $demo.Username; password = $Password; fullName = $demo.FullName
        phone = '000'; email = ($demo.Username + '@example.test'); isSuperhero = $demo.Hero
    } | ConvertTo-Json
    try {
        $null = Invoke-RestMethod -Uri ($BaseUrl + '/api/v1/auth/register') -Method Post -ContentType 'application/json' -Body $registration
    } catch {
        if ($null -eq $_.Exception.Response -or [int]$_.Exception.Response.StatusCode -ne 409) { throw 'Demo registration failed; inspect the application safely without logging request bodies.' }
    }
    try {
        $login = Invoke-RestMethod -Uri ($BaseUrl + '/api/v1/auth/login') -Method Post -ContentType 'application/json' -Body (@{username=$demo.Username; password=$Password} | ConvertTo-Json)
    } catch { throw 'Demo login failed. An existing demo identity may use a different password or be inactive.' }
    $headers = @{ Authorization = ('Bearer ' + $login.data.token) }
    $demoProfileResponse = Invoke-RestMethod -Uri ($BaseUrl + '/api/v1/users/me') -Headers $headers
    if ($demoProfileResponse.data.isSuperhero -ne $demo.Hero) { throw 'Existing demo eligibility differs from the requested fixture; no profile was overwritten.' }
    $wallet = Invoke-RestMethod -Uri ($BaseUrl + '/api/v1/accounts/me') -Headers $headers
    $balance = [decimal]$wallet.data.balance
    if ($balance -gt $demo.Target) { throw 'Existing demo balance exceeds its target; this script will not debit or replace balances.' }
    if ($balance -lt $demo.Target) {
        $loadBody = @{ amount = ($demo.Target - $balance) } | ConvertTo-Json
        $wallet = Invoke-RestMethod -Uri ($BaseUrl + '/api/v1/accounts/me/load') -Method Post -Headers $headers -ContentType 'application/json' -Body $loadBody
    }
    if ([decimal]$wallet.data.balance -ne $demo.Target) { throw 'Demo funding verification failed.' }
    if ($demo.Hero) {
        $demoFlatPrice = [decimal]100.00
        $rate = $null
        try { $rate = Invoke-RestMethod -Uri ($BaseUrl + '/api/v1/users/' + $demoProfileResponse.data.id + '/rate') -Headers $headers }
        catch { if ($null -eq $_.Exception.Response -or [int]$_.Exception.Response.StatusCode -ne 404) { throw 'Demo rate lookup failed.' } }
        if ($null -eq $rate) {
            $rate = Invoke-RestMethod -Uri ($BaseUrl + '/api/v1/users/me/rate') -Method Put -Headers $headers -ContentType 'application/json' -Body (@{amount=$demoFlatPrice}|ConvertTo-Json)
        }
        if ([decimal]$rate.data.amount -ne $demoFlatPrice) { throw 'Existing demo rate differs from 100.00; it was not overwritten.' }
    }
    [pscustomobject]@{ Username = $demo.Username; AccountId = $wallet.data.id; Balance = [decimal]$wallet.data.balance }
}
