param(
    [string]$BaseUrl = "http://127.0.0.1:5173",
    [string]$MysqlContainer = "ylcloud-mysql",
    [string]$MinioContainer = "ylcloud-minio",
    [string]$AppContainer = "ylcloud-app",
    [string]$QdrantBaseUrl = "http://127.0.0.1:6333",
    [string]$MysqlJdbcUrl = "jdbc:mysql://127.0.0.1:3306/ylcloud?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",
    [string]$OutputDirectory = "outputs/pipeline-release-acceptance",
    [string]$MinioBucket,
    [string]$QdrantCollection,
    [switch]$RunMultipartFaultInjection,
    [switch]$ConfirmDisposableEnvironment
)

$ErrorActionPreference = "Stop"
$workspace = Split-Path -Parent $PSScriptRoot
$outputPath = Join-Path $workspace $OutputDirectory
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$reportPath = Join-Path $outputPath "pipeline-release-$timestamp.json"
$mavenLogPath = Join-Path $outputPath "pipeline-mysql-it-$timestamp.log"
$p0LogPath = Join-Path $outputPath "pipeline-p0-$timestamp.log"
$p1LogPath = Join-Path $outputPath "pipeline-p1-$timestamp.log"
$multipartLogPath = Join-Path $outputPath "pipeline-multipart-fi-$timestamp.log"
New-Item -ItemType Directory -Force -Path $outputPath | Out-Null

function Get-ContainerEnvironment([string]$Name) {
    $value = (& docker exec $MysqlContainer printenv $Name | Out-String).Trim()
    if($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($value)) {
        throw "Missing $Name in $MysqlContainer"
    }
    return $value
}

function Invoke-MySql([string]$Query, [string]$User, [string]$Password, [string]$Database) {
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $output = & docker exec $MysqlContainer mysql --batch --skip-column-names "-u$User" "-p$Password" $Database -e $Query 2>$null
    $exitCode = $LASTEXITCODE
    $ErrorActionPreference = $previousPreference
    if($exitCode -ne 0) { throw "MySQL release acceptance query failed" }
    return ($output | Out-String).Trim()
}

function Invoke-AcceptanceScript([string]$Path, [string]$LogPath, [string[]]$AdditionalArguments = @()) {
    $output = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $Path -BaseUrl $BaseUrl @AdditionalArguments 2>&1
    $output | Set-Content -Encoding UTF8 $LogPath
    if($LASTEXITCODE -ne 0) {
        throw "Acceptance script failed: $Path (see $LogPath)"
    }
    return (($output | Out-String).Trim() | ConvertFrom-Json)
}

$report = [ordered]@{
    generatedAt = [DateTimeOffset]::Now.ToString("o")
    status = "RUNNING"
    baseUrl = $BaseUrl
    migration = $null
    mysqlIntegration = $null
    p0 = $null
    p1 = $null
    multipartFaultInjection = $null
    logs = [ordered]@{ tests=$mavenLogPath; p0=$p0LogPath; p1=$p1LogPath; multipartFaultInjection=$multipartLogPath }
}

try {
    $dbUser = Get-ContainerEnvironment "MYSQL_USER"
    $dbPassword = Get-ContainerEnvironment "MYSQL_PASSWORD"
    $dbName = Get-ContainerEnvironment "MYSQL_DATABASE"

    $migrationCount = Invoke-MySql "select count(*) from flyway_schema_history where version in ('17','21','22') and success=1" $dbUser $dbPassword $dbName
    $snapshotColumns = Invoke-MySql "select count(*) from information_schema.columns where table_schema=database() and table_name='space_knowledge_document_profile' and column_name in ('source_snapshot_signature','source_snapshot_revision')" $dbUser $dbPassword $dbName
    $profileToggleColumns = Invoke-MySql "select count(*) from information_schema.columns where table_schema=database() and table_name='space_rag_config' and column_name='knowledge_profile_enabled'" $dbUser $dbPassword $dbName
    $vectorStateColumns = Invoke-MySql "select count(*) from information_schema.columns where table_schema=database() and table_name='space_rag_document' and column_name='vector_state'" $dbUser $dbPassword $dbName
    $activeScopeColumns = Invoke-MySql "select count(*) from information_schema.columns where table_schema=database() and table_name='space_knowledge_pipeline_task' and column_name='active_scope_key'" $dbUser $dbPassword $dbName
    $report.migration = [ordered]@{
        requiredVersionsApplied = [int]$migrationCount -eq 3
        snapshotColumnCount = [int]$snapshotColumns
        profileToggleColumnCount = [int]$profileToggleColumns
        vectorStateColumnCount = [int]$vectorStateColumns
        activeScopeColumnCount = [int]$activeScopeColumns
    }
    if(-not $report.migration.requiredVersionsApplied -or $report.migration.snapshotColumnCount -ne 2 -or
        $report.migration.profileToggleColumnCount -ne 1 -or $report.migration.vectorStateColumnCount -ne 1 -or
        $report.migration.activeScopeColumnCount -ne 1) {
        throw "Required V17/V21/V22 migrations or consistency columns are missing"
    }

    $mavenOutput = & mvn test 2>&1
    $mavenOutput | Set-Content -Encoding UTF8 $mavenLogPath
    if($LASTEXITCODE -ne 0) { throw "Full reactor tests failed" }

    $env:YLCLOUD_PIPELINE_MYSQL_TEST = "true"
    $env:YLCLOUD_PIPELINE_MYSQL_URL = $MysqlJdbcUrl
    $env:YLCLOUD_PIPELINE_MYSQL_USER = $dbUser
    $env:YLCLOUD_PIPELINE_MYSQL_PASSWORD = $dbPassword
    try {
        $mavenOutput = & mvn -pl cloud-server -am "-Dtest=KnowledgeProfileWriteServiceMysqlIT" "-Dsurefire.failIfNoSpecifiedTests=false" test 2>&1
        $mavenOutput | Add-Content -Encoding UTF8 $mavenLogPath
        if($LASTEXITCODE -ne 0) { throw "MySQL concurrency/rollback integration tests failed" }
        $report.mysqlIntegration = [ordered]@{ status="PASSED"; tests=4 }
    } finally {
        Remove-Item Env:YLCLOUD_PIPELINE_MYSQL_TEST -ErrorAction SilentlyContinue
        Remove-Item Env:YLCLOUD_PIPELINE_MYSQL_URL -ErrorAction SilentlyContinue
        Remove-Item Env:YLCLOUD_PIPELINE_MYSQL_USER -ErrorAction SilentlyContinue
        Remove-Item Env:YLCLOUD_PIPELINE_MYSQL_PASSWORD -ErrorAction SilentlyContinue
    }

    $p0Arguments = @(
        "-MysqlContainer",$MysqlContainer,
        "-MinioContainer",$MinioContainer,
        "-AppContainer",$AppContainer,
        "-QdrantBaseUrl",$QdrantBaseUrl
    )
    if(-not [string]::IsNullOrWhiteSpace($MinioBucket)) { $p0Arguments += @("-MinioBucket",$MinioBucket) }
    if(-not [string]::IsNullOrWhiteSpace($QdrantCollection)) { $p0Arguments += @("-QdrantCollection",$QdrantCollection) }
    $report.p0 = Invoke-AcceptanceScript (Join-Path $PSScriptRoot "p0-acceptance.ps1") $p0LogPath $p0Arguments
    $report.p1 = Invoke-AcceptanceScript (Join-Path $PSScriptRoot "p1-deploy-acceptance.ps1") $p1LogPath
    if(-not $report.p0.success) { throw "P0 acceptance report did not pass" }

    if($RunMultipartFaultInjection) {
        if(-not $ConfirmDisposableEnvironment) {
            throw "-RunMultipartFaultInjection requires -ConfirmDisposableEnvironment"
        }
        $multipartArguments = @(
            "-BaseUrl",$BaseUrl,
            "-MysqlContainer",$MysqlContainer,
            "-MinioContainer",$MinioContainer,
            "-AppContainer",$AppContainer,
            "-ConfirmDisposableEnvironment"
        )
        if(-not [string]::IsNullOrWhiteSpace($MinioBucket)) { $multipartArguments += @("-MinioBucket",$MinioBucket) }
        $multipartOutput = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "multipart-fault-injection-e2e.ps1") @multipartArguments 2>&1
        $multipartOutput | Set-Content -Encoding UTF8 $multipartLogPath
        if($LASTEXITCODE -ne 0) { throw "Multipart fault-injection E2E failed (see $multipartLogPath)" }
        $report.multipartFaultInjection = [ordered]@{ status="PASSED" }
    } else {
        $report.multipartFaultInjection = [ordered]@{ status="SKIPPED"; reason="Requires disposable environment opt-in" }
    }

    $report.status = "PASSED"
} catch {
    $report.status = "FAILED"
    $report.error = $_.Exception.Message
    throw
} finally {
    $report | ConvertTo-Json -Depth 12 | Set-Content -Encoding UTF8 $reportPath
    Remove-Variable dbPassword -ErrorAction SilentlyContinue
    Write-Output "Pipeline release acceptance report: $reportPath"
}
