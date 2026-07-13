param(
    [string]$EnvFile = ".env.server"
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path -LiteralPath $EnvFile)) {
    throw "Environment file not found: $EnvFile (copy .env.server.example first)"
}

$values = @{}
foreach ($line in Get-Content -LiteralPath $EnvFile -Encoding UTF8) {
    $trimmed = $line.Trim()
    if ($trimmed -eq "" -or $trimmed.StartsWith("#") -or -not $trimmed.Contains("=")) {
        continue
    }
    $parts = $trimmed.Split("=", 2)
    $values[$parts[0].Trim()] = $parts[1].Trim()
}

$errors = [System.Collections.Generic.List[string]]::new()
$required = @(
    "YLCLOUD_MYSQL_ROOT_PASSWORD",
    "YLCLOUD_MYSQL_PASSWORD",
    "YLCLOUD_MINIO_ACCESS_KEY",
    "YLCLOUD_MINIO_SECRET_KEY"
)
foreach ($key in $required) {
    $value = $values[$key]
    if ([string]::IsNullOrWhiteSpace($value)) {
        $errors.Add("$key is required")
    } elseif ($value -like "replace-*") {
        $errors.Add("$key still contains the template value")
    }
}

$secretDirectory = Join-Path (Get-Location) ".secrets"
$secretFiles = @("jwt_secret", "llm_api_key", "ark_api_key", "rag_query_api_key", "vlm_api_key")
foreach ($secretFile in $secretFiles) {
    $path = Join-Path $secretDirectory $secretFile
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        $errors.Add("Missing secret file: .secrets/$secretFile")
    }
}
$jwtPath = Join-Path $secretDirectory "jwt_secret"
if (Test-Path -LiteralPath $jwtPath -PathType Leaf) {
    $jwtSecret = [System.IO.File]::ReadAllText($jwtPath,[System.Text.Encoding]::UTF8).Trim()
    if ($jwtSecret.Length -lt 32 -or $jwtSecret -like "replace-*") {
        $errors.Add(".secrets/jwt_secret must be a non-template value with at least 32 characters")
    }
}

if ($values["YLCLOUD_SPRING_PROFILE"] -ne "prod") {
    $errors.Add("YLCLOUD_SPRING_PROFILE must be prod for server deployment")
}

if ($values["YLCLOUD_BOOTSTRAP_ADMIN_ENABLED"] -eq "true") {
    if ([string]::IsNullOrWhiteSpace($values["YLCLOUD_BOOTSTRAP_ADMIN_USERNAME"])) {
        $errors.Add("Bootstrap admin username is required when bootstrap is enabled")
    }
    $adminPassword = $values["YLCLOUD_BOOTSTRAP_ADMIN_PASSWORD"]
    if ([string]::IsNullOrWhiteSpace($adminPassword) -or $adminPassword.Length -lt 12 -or $adminPassword -like "replace-*") {
        $errors.Add("Bootstrap admin password must be a non-template value with at least 12 characters")
    }
}

$portKeys = @(
    "YLCLOUD_MYSQL_HOST_PORT", "YLCLOUD_MINIO_API_HOST_PORT", "YLCLOUD_MINIO_CONSOLE_HOST_PORT",
    "YLCLOUD_QDRANT_REST_HOST_PORT", "YLCLOUD_QDRANT_GRPC_HOST_PORT", "YLCLOUD_MODEL_HOST_PORT",
    "YLCLOUD_PARSER_HOST_PORT", "YLCLOUD_BACKEND_HOST_PORT", "YLCLOUD_FRONTEND_HOST_PORT"
)
$seenPorts = @{}
foreach ($key in $portKeys) {
    $port = $values[$key]
    if ($port -notmatch "^\d+$" -or [int]$port -lt 1 -or [int]$port -gt 65535) {
        $errors.Add("$key must be a valid TCP port")
    } elseif ($seenPorts.ContainsKey($port)) {
        $errors.Add("$key conflicts with $($seenPorts[$port]) on host port $port")
    } else {
        $seenPorts[$port] = $key
    }
}

if ($errors.Count -gt 0) {
    throw ("Deployment configuration is invalid:`n- " + ($errors -join "`n- "))
}

docker compose --env-file $EnvFile config --quiet
if ($LASTEXITCODE -ne 0) {
    throw "docker compose config validation failed"
}

Write-Host "Deployment configuration is valid."
