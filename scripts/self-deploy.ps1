param(
    [ValidateSet("Local", "Server")]
    [string]$Mode = "Local",
    [string]$EnvFile,
    [string]$ComposeFile,
    [string]$ProjectName = "ylcloud",
    [ValidateRange(30, 1800)]
    [int]$TimeoutSeconds = 600,
    [switch]$SkipBuild,
    [switch]$Pull,
    [switch]$RunTests,
    [switch]$RunSmoke,
    [string]$SmokePdfPath,
    [string]$SmokeTestUsername = "e2e_smoke",
    [string]$SmokeTestPassword = $env:YLCLOUD_E2E_PASSWORD,
    [switch]$AllowOfflineFallback
)

$ErrorActionPreference = "Stop"
$workspace = Split-Path -Parent $PSScriptRoot
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$outputDirectory = Join-Path $workspace "outputs/self-deploy"
$reportPath = Join-Path $outputDirectory "self-deploy-$timestamp.json"
$logPath = Join-Path $outputDirectory "compose-$timestamp.log"
New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null

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
        if ($line -match "^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$") {
            $result[$matches[1]] = $matches[2].Trim().Trim('"').Trim("'")
        }
    }
    return $result
}

function Merge-LocalEnvironmentTemplate([string]$TargetPath, [string]$TemplatePath) {
    $current = Read-DotEnv $TargetPath
    $missingLines = [System.Collections.Generic.List[string]]::new()
    foreach($line in Get-Content -LiteralPath $TemplatePath -Encoding UTF8) {
        if($line -match "^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$" -and -not $current.ContainsKey($matches[1])) {
            $missingLines.Add($line)
        }
    }
    if($missingLines.Count -gt 0) {
        Add-Content -LiteralPath $TargetPath -Encoding UTF8 -Value ([Environment]::NewLine + "# Added by scripts/self-deploy.ps1")
        Add-Content -LiteralPath $TargetPath -Encoding UTF8 -Value $missingLines
        Write-Host "Added $($missingLines.Count) missing local settings from .env.example without overwriting existing values."
    }
}

function Invoke-Compose([string[]]$Arguments, [switch]$Capture) {
    $baseArguments = @("compose", "--env-file", $script:envPath, "-f", $script:composePath)
    if (-not [string]::IsNullOrWhiteSpace($ProjectName)) {
        $baseArguments += @("--project-name", $ProjectName)
    }
    if ($Capture) {
        $previousPreference = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        $result = & docker @baseArguments @Arguments 2>&1
        $exitCode = $LASTEXITCODE
        $ErrorActionPreference = $previousPreference
        return [pscustomobject]@{ ExitCode = $exitCode; Output = @($result) }
    }
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    & docker @baseArguments @Arguments
    $exitCode = $LASTEXITCODE
    $ErrorActionPreference = $previousPreference
    if ($exitCode -ne 0) {
        throw "docker compose command failed: $($Arguments -join ' ')"
    }
}

function Wait-Http([string]$Name, [string]$Url, [datetime]$Deadline) {
    do {
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri $Url -TimeoutSec 5
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 500) {
                return
            }
        } catch {
            Start-Sleep -Seconds 2
        }
    } while ((Get-Date) -lt $Deadline)
    throw "$Name did not become reachable before timeout: $Url"
}

function Format-NativeOutput([object[]]$Items) {
    return (@($Items | ForEach-Object {
        if($_ -is [System.Management.Automation.ErrorRecord]) {
            $_.Exception.Message
        } else {
            "$_"
        }
    }) -join [Environment]::NewLine).Trim()
}

$defaultEnv = if ($Mode -eq "Server") { ".env.server" } else { ".env" }
$defaultCompose = if ($Mode -eq "Server") { "docker-compose.hub.yml" } else { "docker-compose.yml" }
$templateEnv = if ($Mode -eq "Server") { ".env.server.example" } else { ".env.example" }
$envPath = Resolve-WorkspacePath $EnvFile $defaultEnv
$composePath = Resolve-WorkspacePath $ComposeFile $defaultCompose
$report = [ordered]@{
    generatedAt = [DateTimeOffset]::Now.ToString("o")
    mode = $Mode
    status = "RUNNING"
    envFile = $envPath
    composeFile = $composePath
    projectName = $ProjectName
    services = @()
    migration = $null
    checks = [ordered]@{}
    artifacts = [ordered]@{ report = $reportPath; composeLog = $logPath }
}

Push-Location $workspace
try {
    if (-not (Test-Path -LiteralPath $envPath -PathType Leaf)) {
        $templatePath = Join-Path $workspace $templateEnv
        if (-not (Test-Path -LiteralPath $templatePath -PathType Leaf)) {
            throw "Environment template not found: $templatePath"
        }
        Copy-Item -LiteralPath $templatePath -Destination $envPath
        if ($Mode -eq "Server") {
            throw "Created $envPath from the server template. Fill production values, then run this command again."
        }
        Write-Host "Created local environment file: $envPath"
    }
    if($Mode -eq "Local") {
        Merge-LocalEnvironmentTemplate $envPath (Join-Path $workspace ".env.example")
    }

    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "migrate-secrets.ps1") -EnvFile $envPath -SecretsDirectory (Join-Path $workspace ".secrets")
    if ($LASTEXITCODE -ne 0) {
        throw "Secret migration failed"
    }
    $report.checks.secretMigration = "PASSED"

    $checkArguments = @(
        "-Mode", $(if ($Mode -eq "Server") { "Production" } else { "Development" }),
        "-EnvFile", $envPath,
        "-ComposeFile", $composePath
    )
    if ($AllowOfflineFallback) {
        $checkArguments += "-AllowOfflineFallback"
    }
    $checkOutput = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "deploy-check.ps1") @checkArguments
    if ($LASTEXITCODE -ne 0) {
        throw "Deployment preflight failed"
    }
    $report.checks.preflight = "PASSED"

    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $dockerInfo = & docker info --format "{{.ServerVersion}}" 2>&1
    $dockerInfoExitCode = $LASTEXITCODE
    $ErrorActionPreference = $previousPreference
    if ($dockerInfoExitCode -ne 0) {
        throw "Docker daemon is not available: $(Format-NativeOutput $dockerInfo)"
    }
    $report.dockerServerVersion = (($dockerInfo | Where-Object { "$_" -notmatch "^WARNING:" }) | Out-String).Trim()

    if ($RunTests) {
        & mvn test
        if ($LASTEXITCODE -ne 0) {
            throw "Backend tests failed"
        }
        Push-Location (Join-Path $workspace "cloud-frontend")
        try {
            & npm run build
            if ($LASTEXITCODE -ne 0) {
                throw "Frontend build failed"
            }
        } finally {
            Pop-Location
        }
        $report.checks.tests = "PASSED"
    }

    if ($Mode -eq "Server" -or $Pull) {
        Invoke-Compose @("pull")
        $report.checks.pull = "PASSED"
    }

    $upArguments = @("up", "-d", "--remove-orphans")
    if ($Mode -eq "Local" -and -not $SkipBuild) {
        $upArguments += "--build"
    }
    Invoke-Compose $upArguments

    $servicesResult = Invoke-Compose @("config", "--services") -Capture
    if ($servicesResult.ExitCode -ne 0) {
        throw "Unable to enumerate compose services"
    }
    $services = @($servicesResult.Output |
        Where-Object { $_ -isnot [System.Management.Automation.ErrorRecord] } |
        ForEach-Object { "$_".Trim() } |
        Where-Object { $_ -and $_ -notmatch "^WARNING:" })
    $report.services = $services
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)

    do {
        $pending = [System.Collections.Generic.List[string]]::new()
        foreach ($service in $services) {
            $idResult = Invoke-Compose @("ps", "-q", $service) -Capture
            $containerId = (($idResult.Output |
                Where-Object { $_ -isnot [System.Management.Automation.ErrorRecord] -and "$_" -notmatch "^WARNING:" }) | Out-String).Trim()
            if ($idResult.ExitCode -ne 0 -or [string]::IsNullOrWhiteSpace($containerId)) {
                $pending.Add("$($service):not-created")
                continue
            }
            $previousPreference = $ErrorActionPreference
            $ErrorActionPreference = "Continue"
            $stateOutput = & docker inspect --format "{{.State.Status}}|{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}" $containerId 2>&1
            $inspectExitCode = $LASTEXITCODE
            $ErrorActionPreference = $previousPreference
            if ($inspectExitCode -ne 0) {
                $pending.Add("$($service):inspect-failed")
                continue
            }
            $state = (($stateOutput | Where-Object { "$_" -match "^[^|]+\|[^|]+$" }) | Select-Object -Last 1 | Out-String).Trim()
            if ($state -notin @("running|healthy", "running|none")) {
                $pending.Add("$($service):$state")
            }
        }
        if ($pending.Count -eq 0) {
            break
        }
        if ((Get-Date) -ge $deadline) {
            throw "Services did not become healthy: $($pending -join ', ')"
        }
        Start-Sleep -Seconds 3
    } while ($true)
    $report.checks.containerHealth = "PASSED"

    $values = Read-DotEnv $envPath
    $backendPort = if ($values["YLCLOUD_BACKEND_HOST_PORT"]) { $values["YLCLOUD_BACKEND_HOST_PORT"] } else { "8080" }
    $frontendPort = if ($values["YLCLOUD_FRONTEND_HOST_PORT"]) { $values["YLCLOUD_FRONTEND_HOST_PORT"] } else { "5173" }
    Wait-Http "backend" "http://127.0.0.1:$backendPort/api/site/public-settings" $deadline
    Wait-Http "frontend" "http://127.0.0.1:$frontendPort/" $deadline
    $report.checks.http = "PASSED"

    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $dbUser = (& docker exec ylcloud-mysql printenv MYSQL_USER 2>$null | Out-String).Trim()
    $dbPassword = (& docker exec ylcloud-mysql printenv MYSQL_PASSWORD 2>$null | Out-String).Trim()
    $dbName = (& docker exec ylcloud-mysql printenv MYSQL_DATABASE 2>$null | Out-String).Trim()
    $ErrorActionPreference = $previousPreference
    if ([string]::IsNullOrWhiteSpace($dbUser) -or [string]::IsNullOrWhiteSpace($dbName)) {
        throw "Unable to read MySQL container configuration"
    }
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $migrationOutput = & docker exec ylcloud-mysql mysql --batch --skip-column-names "-u$dbUser" "-p$dbPassword" $dbName -e "select count(*) from flyway_schema_history where version='22' and success=1" 2>$null
    $migrationExitCode = $LASTEXITCODE
    $ErrorActionPreference = $previousPreference
    if ($migrationExitCode -ne 0 -or [int](($migrationOutput | Out-String).Trim()) -ne 1) {
        throw "Flyway V22 is not applied successfully"
    }
    $report.migration = [ordered]@{ version = "22"; applied = $true }

    if ($RunSmoke) {
        if ([string]::IsNullOrWhiteSpace($SmokePdfPath) -or -not (Test-Path -LiteralPath $SmokePdfPath -PathType Leaf)) {
            throw "-RunSmoke requires an existing -SmokePdfPath"
        }
        if([string]::IsNullOrWhiteSpace($SmokeTestPassword)) {
            throw "-RunSmoke requires -SmokeTestPassword or YLCLOUD_E2E_PASSWORD for the controlled smoke account"
        }
        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "e2e-smoke.ps1") -BaseUrl "http://127.0.0.1:$frontendPort" -PdfPath $SmokePdfPath -TestUsername $SmokeTestUsername -TestPassword $SmokeTestPassword
        if ($LASTEXITCODE -ne 0) {
            throw "E2E smoke test failed"
        }
        $report.checks.smoke = "PASSED"
    }

    $report.status = "PASSED"
    Write-Host "YLCloud deployment is healthy."
    Write-Host "Frontend: http://127.0.0.1:$frontendPort/"
    Write-Host "Backend:  http://127.0.0.1:$backendPort/"
} catch {
    $report.status = "FAILED"
    $report.error = $_.Exception.Message
    try {
        $diagnostics = @()
        $psResult = Invoke-Compose @("ps", "-a") -Capture
        $diagnostics += $psResult.Output
        $logsResult = Invoke-Compose @("logs", "--no-color", "--tail", "200") -Capture
        $diagnostics += $logsResult.Output
        $diagnostics | Set-Content -LiteralPath $logPath -Encoding UTF8
    } catch {
        "Unable to collect compose diagnostics: $($_.Exception.Message)" | Set-Content -LiteralPath $logPath -Encoding UTF8
    }
    throw
} finally {
    $report | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $reportPath -Encoding UTF8
    Remove-Variable dbPassword -ErrorAction SilentlyContinue
    Pop-Location
    Write-Host "Deployment report: $reportPath"
}
