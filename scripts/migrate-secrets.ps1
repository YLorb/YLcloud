param(
    [string]$EnvFile = "config/.env",
    [string]$SecretsDirectory = "config/secrets"
)

$ErrorActionPreference = "Stop"
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)

function Resolve-InputPath([string]$Path) {
    if([System.IO.Path]::IsPathRooted($Path)) {
        return [System.IO.Path]::GetFullPath($Path)
    }
    return [System.IO.Path]::GetFullPath((Join-Path (Get-Location) $Path))
}

$secretsPath = Resolve-InputPath $SecretsDirectory
[System.IO.Directory]::CreateDirectory($secretsPath) | Out-Null

$mapping = [ordered]@{
    YLCLOUD_JWT_SECRET = "jwt_secret"
    YLCLOUD_SERVICE_JWT_ACTIVE_SECRET = "service_jwt_active_secret"
    YLCLOUD_LLM_API_KEY = "llm_api_key"
    YLCLOUD_ARK_API_KEY = "ark_api_key"
    YLCLOUD_RAG_QUERY_API_KEY = "rag_query_api_key"
    YLCLOUD_VLM_API_KEY = "vlm_api_key"
    YLCLOUD_RABBITMQ_PASSWORD = "rabbitmq_password"
    YLCLOUD_SANDBOX_SERVICE_TOKEN = "sandbox_service_token"
    YLCLOUD_EXPORT_MASTER_KEY = "export_master_key"
}

$envPath = Resolve-InputPath $EnvFile
$lines = if ([System.IO.File]::Exists($envPath)) {
    [System.IO.File]::ReadAllLines($envPath,[System.Text.Encoding]::UTF8)
} else {
    @()
}
$values = @{}
foreach ($line in $lines) {
    if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$') {
        $values[$matches[1]] = $matches[2].Trim()
    }
}

function New-JwtSecret {
    $bytes = New-Object byte[] 48
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    return [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+','-').Replace('/','_')
}

function New-ExportMasterKey {
    $bytes = New-Object byte[] 32
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    return [Convert]::ToBase64String($bytes)
}

foreach ($entry in $mapping.GetEnumerator()) {
    $environmentName = $entry.Key
    $targetPath = Join-Path $secretsPath $entry.Value
    $sourceValue = $values[$environmentName]
    if (($environmentName -in @("YLCLOUD_JWT_SECRET", "YLCLOUD_SERVICE_JWT_ACTIVE_SECRET", "YLCLOUD_RABBITMQ_PASSWORD", "YLCLOUD_SANDBOX_SERVICE_TOKEN")) `
            -and [string]::IsNullOrWhiteSpace($sourceValue) -and -not [System.IO.File]::Exists($targetPath)) {
        $sourceValue = New-JwtSecret
    }
    if ($environmentName -eq "YLCLOUD_EXPORT_MASTER_KEY" -and [string]::IsNullOrWhiteSpace($sourceValue) `
            -and -not [System.IO.File]::Exists($targetPath)) {
        $sourceValue = New-ExportMasterKey
    }
    if ($null -eq $sourceValue) {
        $sourceValue = ""
    }

    if ([System.IO.File]::Exists($targetPath)) {
        $existingValue = [System.IO.File]::ReadAllText($targetPath,[System.Text.Encoding]::UTF8).Trim()
        if (-not [string]::IsNullOrWhiteSpace($sourceValue) -and $existingValue -ne $sourceValue) {
            throw "Secret file already exists with a different value: $($entry.Value)"
        }
    } else {
        [System.IO.File]::WriteAllText($targetPath,$sourceValue,$utf8NoBom)
    }
}

if ([System.IO.File]::Exists($envPath)) {
    $rewritten = foreach ($line in $lines) {
        $matched = $false
        foreach ($key in $mapping.Keys) {
            if ($line -match ('^\s*' + [regex]::Escape($key) + '=')) {
                "$key="
                $matched = $true
                break
            }
        }
        if (-not $matched) {
            $line
        }
    }
    $tempPath = $envPath + ".secret-migration.tmp"
    [System.IO.File]::WriteAllLines($tempPath,$rewritten,$utf8NoBom)
    Move-Item -LiteralPath $tempPath -Destination $envPath -Force
}

Write-Host "Secret migration completed. Values were not printed."
