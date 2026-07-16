param(
    [ValidateSet("Development", "Production")]
    [string]$Mode = "Production",
    [string]$EnvFile,
    [string]$ComposeFile,
    [switch]$AllowOfflineFallback,
    [string]$OutputPath
)

$ErrorActionPreference = "Stop"
$workspace = Split-Path -Parent $PSScriptRoot

function Resolve-WorkspacePath([string]$Path, [string]$DefaultPath) {
    $candidate = if ([string]::IsNullOrWhiteSpace($Path)) { $DefaultPath } else { $Path }
    if ([System.IO.Path]::IsPathRooted($candidate)) {
        return [System.IO.Path]::GetFullPath($candidate)
    }
    return [System.IO.Path]::GetFullPath((Join-Path $workspace $candidate))
}

function Read-DotEnv([string]$Path) {
    $result = @{}
    foreach ($line in Get-Content -LiteralPath $Path -Encoding UTF8) {
        $trimmed = $line.Trim()
        if ($trimmed.Length -eq 0 -or $trimmed.StartsWith("#") -or -not $trimmed.Contains("=")) {
            continue
        }
        $parts = $trimmed.Split("=", 2)
        $value = $parts[1].Trim()
        if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'")))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        $result[$parts[0].Trim()] = $value
    }
    return $result
}

$defaultEnv = if ($Mode -eq "Production") { ".env.server" } else { ".env" }
$defaultCompose = if ($Mode -eq "Production") { "docker-compose.hub.yml" } else { "docker-compose.yml" }
$envPath = Resolve-WorkspacePath $EnvFile $defaultEnv
$composePath = Resolve-WorkspacePath $ComposeFile $defaultCompose

if (-not (Test-Path -LiteralPath $envPath -PathType Leaf)) {
    throw "Environment file not found: $envPath"
}
if (-not (Test-Path -LiteralPath $composePath -PathType Leaf)) {
    throw "Compose file not found: $composePath"
}

$values = Read-DotEnv $envPath
$errors = [System.Collections.Generic.List[string]]::new()
$warnings = [System.Collections.Generic.List[string]]::new()

function Add-RequiredValueError([string]$Name) {
    $value = $values[$Name]
    if ([string]::IsNullOrWhiteSpace($value)) {
        $errors.Add("$Name is required")
    } elseif ($value -match "^(replace-|change-me|your-)") {
        $errors.Add("$Name still contains a template value")
    }
}

@(
    "YLCLOUD_MYSQL_ROOT_PASSWORD",
    "YLCLOUD_MYSQL_DATABASE",
    "YLCLOUD_MYSQL_USER",
    "YLCLOUD_MYSQL_PASSWORD",
    "YLCLOUD_MINIO_ACCESS_KEY",
    "YLCLOUD_MINIO_SECRET_KEY",
    "YLCLOUD_MINIO_BUCKET"
) | ForEach-Object { Add-RequiredValueError $_ }

$bucket = $values["YLCLOUD_MINIO_BUCKET"]
if (-not [string]::IsNullOrWhiteSpace($bucket) -and $bucket -notmatch "^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$") {
    $errors.Add("YLCLOUD_MINIO_BUCKET must be a valid S3 bucket name")
}

$secretDirectory = Join-Path $workspace ".secrets"
@("jwt_secret", "llm_api_key", "ark_api_key", "rag_query_api_key", "vlm_api_key") | ForEach-Object {
    $path = Join-Path $secretDirectory $_
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        $errors.Add("Missing secret file: .secrets/$_")
    }
}
$jwtPath = Join-Path $secretDirectory "jwt_secret"
if (Test-Path -LiteralPath $jwtPath -PathType Leaf) {
    $jwtSecret = [System.IO.File]::ReadAllText($jwtPath, [System.Text.Encoding]::UTF8).Trim()
    if ($jwtSecret.Length -lt 32 -or $jwtSecret -match "^(replace-|change-me|your-)") {
        $errors.Add(".secrets/jwt_secret must be a non-template value with at least 32 characters")
    }
}

if ($Mode -eq "Production") {
    if ($values["YLCLOUD_SPRING_PROFILE"] -ne "prod") {
        $errors.Add("YLCLOUD_SPRING_PROFILE must be prod for production deployment")
    }
    if (-not $AllowOfflineFallback -and $values["YLCLOUD_MODEL_SERVICE_OFFLINE_FALLBACK"] -eq "true") {
        $errors.Add("Production offline model fallback is enabled; pass -AllowOfflineFallback only for an intentional degraded deployment")
    }
    if ([System.IO.Path]::GetFileName($composePath) -eq "docker-compose.hub.yml") {
        @("ACR_REGISTRY", "ACR_NAMESPACE") | ForEach-Object { Add-RequiredValueError $_ }
        if($values["ACR_REGISTRY"] -match "xxxx|example|replace") {
            $errors.Add("ACR_REGISTRY still contains a template value")
        }
    }
}

if ($values["YLCLOUD_BOOTSTRAP_ADMIN_ENABLED"] -eq "true") {
    Add-RequiredValueError "YLCLOUD_BOOTSTRAP_ADMIN_USERNAME"
    $adminPassword = $values["YLCLOUD_BOOTSTRAP_ADMIN_PASSWORD"]
    if ([string]::IsNullOrWhiteSpace($adminPassword) -or $adminPassword.Length -lt 12 -or $adminPassword -match "^(replace-|change-me|your-)") {
        $errors.Add("Bootstrap admin password must be a non-template value with at least 12 characters")
    }
}

$portDefaults = [ordered]@{
    YLCLOUD_MYSQL_HOST_PORT = 3306
    YLCLOUD_MINIO_API_HOST_PORT = 9000
    YLCLOUD_MINIO_CONSOLE_HOST_PORT = 9001
    YLCLOUD_QDRANT_REST_HOST_PORT = 6333
    YLCLOUD_QDRANT_GRPC_HOST_PORT = 6334
    YLCLOUD_MODEL_HOST_PORT = 8001
    YLCLOUD_PARSER_HOST_PORT = 8002
    YLCLOUD_BACKEND_HOST_PORT = 8080
    YLCLOUD_FRONTEND_HOST_PORT = 5173
}
$seenPorts = @{}
foreach ($entry in $portDefaults.GetEnumerator()) {
    $rawPort = $values[$entry.Key]
    if ([string]::IsNullOrWhiteSpace($rawPort)) {
        $rawPort = [string]$entry.Value
        $warnings.Add("$($entry.Key) is unset; compose default $rawPort will be used")
    }
    if ($rawPort -notmatch "^\d+$" -or [int]$rawPort -lt 1 -or [int]$rawPort -gt 65535) {
        $errors.Add("$($entry.Key) must be a valid TCP port")
    } elseif ($seenPorts.ContainsKey($rawPort)) {
        $errors.Add("$($entry.Key) conflicts with $($seenPorts[$rawPort]) on host port $rawPort")
    } else {
        $seenPorts[$rawPort] = $entry.Key
    }
}

$previousPreference = $ErrorActionPreference
$ErrorActionPreference = "Continue"
$composeOutput = & docker compose --env-file $envPath -f $composePath config --quiet 2>&1
$composeExitCode = $LASTEXITCODE
$ErrorActionPreference = $previousPreference
if ($composeExitCode -ne 0) {
    $errors.Add("docker compose config validation failed: $(($composeOutput | Out-String).Trim())")
}

$report = [ordered]@{
    generatedAt = [DateTimeOffset]::Now.ToString("o")
    mode = $Mode
    envFile = $envPath
    composeFile = $composePath
    valid = $errors.Count -eq 0
    warnings = @($warnings)
    errors = @($errors)
}

if (-not [string]::IsNullOrWhiteSpace($OutputPath)) {
    $resolvedOutput = Resolve-WorkspacePath $OutputPath $OutputPath
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $resolvedOutput) | Out-Null
    $report | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $resolvedOutput -Encoding UTF8
}

if ($errors.Count -gt 0) {
    throw ("Deployment configuration is invalid:" + [Environment]::NewLine + "- " + ($errors -join ([Environment]::NewLine + "- ")))
}

$report | ConvertTo-Json -Depth 6
