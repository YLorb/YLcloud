param(
    [string]$BaseUrl = "http://127.0.0.1:5173",
    [string]$MysqlContainer = "ylcloud-mysql",
    [string]$MinioContainer = "ylcloud-minio",
    [string]$AppContainer = "ylcloud-app",
    [string]$QdrantBaseUrl = "http://127.0.0.1:6333",
    [string]$MysqlJdbcUrl = "jdbc:mysql://127.0.0.1:3306/ylcloud?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",
    [string]$OutputDirectory = "outputs/pipeline-release-acceptance"
)

$ErrorActionPreference = "Stop"
$workspace = Split-Path -Parent $PSScriptRoot
$outputPath = Join-Path $workspace $OutputDirectory
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$reportPath = Join-Path $outputPath "pipeline-release-$timestamp.json"
$mavenLogPath = Join-Path $outputPath "pipeline-mysql-it-$timestamp.log"
$p0LogPath = Join-Path $outputPath "pipeline-p0-$timestamp.log"
$p1LogPath = Join-Path $outputPath "pipeline-p1-$timestamp.log"
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
    logs = [ordered]@{ mysqlIntegration=$mavenLogPath; p0=$p0LogPath; p1=$p1LogPath }
}

try {
    $dbUser = Get-ContainerEnvironment "MYSQL_USER"
    $dbPassword = Get-ContainerEnvironment "MYSQL_PASSWORD"
    $dbName = Get-ContainerEnvironment "MYSQL_DATABASE"

    $v17Applied = Invoke-MySql "select count(*) from flyway_schema_history where version='17' and success=1" $dbUser $dbPassword $dbName
    $snapshotColumns = Invoke-MySql "select count(*) from information_schema.columns where table_schema=database() and table_name='space_knowledge_document_profile' and column_name in ('source_snapshot_signature','source_snapshot_revision')" $dbUser $dbPassword $dbName
    $report.migration = [ordered]@{
        v17Applied = [int]$v17Applied -eq 1
        snapshotColumnCount = [int]$snapshotColumns
    }
    if(-not $report.migration.v17Applied -or $report.migration.snapshotColumnCount -ne 2) {
        throw "V17 migration is not applied or snapshot columns are missing"
    }

    $env:YLCLOUD_PIPELINE_MYSQL_TEST = "true"
    $env:YLCLOUD_PIPELINE_MYSQL_URL = $MysqlJdbcUrl
    $env:YLCLOUD_PIPELINE_MYSQL_USER = $dbUser
    $env:YLCLOUD_PIPELINE_MYSQL_PASSWORD = $dbPassword
    try {
        $mavenOutput = & mvn -pl cloud-server -am "-Dtest=KnowledgeProfileWriteServiceMysqlIT" `
            "-Dsurefire.failIfNoSpecifiedTests=false" test 2>&1
        $mavenOutput | Set-Content -Encoding UTF8 $mavenLogPath
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
    $report.p0 = Invoke-AcceptanceScript (Join-Path $PSScriptRoot "p0-acceptance.ps1") $p0LogPath $p0Arguments
    $report.p1 = Invoke-AcceptanceScript (Join-Path $PSScriptRoot "p1-deploy-acceptance.ps1") $p1LogPath
    if(-not $report.p0.success) { throw "P0 acceptance report did not pass" }

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
