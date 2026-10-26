[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$secretDirectory = Join-Path $projectRoot 'config/secrets'
New-Item -ItemType Directory -Force -Path $secretDirectory | Out-Null
foreach ($secretName in @('redis_password', 'gateway_forwarding_secret', 'gateway_identity_secret')) {
    $destination = Join-Path $secretDirectory $secretName
    if (Test-Path -LiteralPath $destination) {
        Write-Host "$secretName already exists; preserved."
        continue
    }
    $randomBytes = New-Object byte[] 32
    $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($randomBytes) } finally { $generator.Dispose() }
    $secretValue = -join ($randomBytes | ForEach-Object { $_.ToString('x2') })
    [System.IO.File]::WriteAllText($destination, $secretValue, [System.Text.UTF8Encoding]::new($false))
    Write-Host "$secretName created (value hidden)."
}
