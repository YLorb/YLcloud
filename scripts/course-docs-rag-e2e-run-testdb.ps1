param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [string]$Database = "ylcloud_space_test",
    [string]$AdminUsername = "YL_orb",
    [string]$LinuxRoot = "D:\沐浴露\课程\linux",
    [string]$GeologyRoot = "D:\沐浴露\课程\地球科学概论",
    [string]$RecoverProvisionPath,
    [string]$ResumeProvisionPath,
    [string]$ResumeUploadedFilesPath,
    [long]$RecoverSeedUserId,
    [string]$RecoverSeedUsername,
    [switch]$CleanupOnly,
    [int]$TimeoutMinutes = 20,
    [int]$CleanupTimeoutMinutes = 20,
    [switch]$KeepGoing
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
$OutputEncoding = [Text.UTF8Encoding]::new($false)

function Invoke-YlCloudEnvelope {
    param(
        [string]$Method,
        [string]$Path,
        [object]$Body = $null,
        [string]$Authorization,
        [switch]$Anonymous
    )

    $request = @{
        Method = $Method
        Uri = "$BaseUrl$Path"
        Headers = @{ Accept = "application/json" }
    }
    if(-not $Anonymous) {
        if([string]::IsNullOrWhiteSpace($Authorization)) {
            throw "Authorization is required for $Method $Path"
        }
        $request.Headers.Authorization = $Authorization
    }
    if($null -ne $Body) {
        $request.ContentType = "application/json; charset=utf-8"
        $request.Body = $Body | ConvertTo-Json -Depth 20 -Compress
    }

    $result = Invoke-RestMethod @request
    if([int]$result.code -ne 200) {
        throw "$Method $Path failed: code=$($result.code), message=$($result.message)"
    }
    return $result
}

function Invoke-Login {
    param([string]$Username, [string]$Password)

    $data = (Invoke-YlCloudEnvelope -Method "POST" -Path "/api/login" -Anonymous -Body @{
        username = $Username
        password = $Password
    }).data
    if([long]$data.id -le 0 -or [string]::IsNullOrWhiteSpace([string]$data.token)) {
        throw "Login response is incomplete for username=$Username"
    }
    return $data
}

function Get-AuthorizationValue {
    param([string]$RawToken)

    if([string]::IsNullOrWhiteSpace($RawToken)) { throw "JWT is missing" }
    if($RawToken -match "^Bearer\s+") { return $RawToken }
    return "Bearer $RawToken"
}

function Invoke-TestDatabaseSql {
    param([string]$Sql)

    if($Database -ne "ylcloud_space_test") {
        throw "Refusing SQL against non-test database: $Database"
    }
    if($Sql -match '[`"\r\n]') {
        throw "Unsafe SQL characters were supplied"
    }

    $mysqlCommand = 'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --batch --skip-column-names -u"$MYSQL_USER" ' + $Database
    $output = @($Sql | & docker compose exec -T mysql sh -c $mysqlCommand 2>&1)
    if($LASTEXITCODE -ne 0) {
        throw "Test database command failed (exit=$LASTEXITCODE)"
    }
    return @($output | Where-Object { $_ -notmatch '^mysql: \[Warning\]' })
}

function Assert-TestRuntime {
    $environment = @(& docker inspect ylcloud-app --format '{{range .Config.Env}}{{println .}}{{end}}')
    if($LASTEXITCODE -ne 0) { throw "Cannot inspect ylcloud-app" }

    $datasource = $environment | Where-Object { $_ -like 'SPRING_DATASOURCE_URL=*' } | Select-Object -First 1
    $purgeFlag = $environment | Where-Object { $_ -like 'YLCLOUD_E2E_ALLOW_TEST_ACCOUNT_PURGE=*' } | Select-Object -First 1
    if($datasource -notmatch '/ylcloud_space_test\?') {
        throw "ylcloud-app is not connected to ylcloud_space_test"
    }
    if($purgeFlag -ne 'YLCLOUD_E2E_ALLOW_TEST_ACCOUNT_PURGE=true') {
        throw "Test-account purge is not enabled on ylcloud-app"
    }

    $health = Invoke-RestMethod -Uri "$BaseUrl/actuator/health" -TimeoutSec 5
    if([string]$health.status -ne 'UP') { throw "ylcloud-app health is not UP" }
}

function Remove-SeedAccount {
    param(
        [long]$SeedUserId,
        [string]$SeedUsername,
        [string]$SeedPassword,
        [string]$AdminPassword
    )

    $seedLogin = Invoke-Login -Username $SeedUsername -Password $SeedPassword
    $seedAuthorization = Get-AuthorizationValue ([string]$seedLogin.token)
    $cancelled = (Invoke-YlCloudEnvelope -Method "POST" -Path "/api/account/cancel" -Authorization $seedAuthorization -Body @{
        reason = "Temporary test-database admin credential seed cleanup"
        confirmTeamOwnerTransfer = $false
    }).data
    if([long]$cancelled.userId -ne $SeedUserId -or [string]$cancelled.accountStatus -ne 'CANCELLED') {
        throw "Seed account did not enter CANCELLED state"
    }

    $adminLogin = Invoke-Login -Username $AdminUsername -Password $AdminPassword
    if([long]$adminLogin.id -ne 1 -or [string]$adminLogin.role -ne 'ADMIN' -or -not [bool]$adminLogin.deploymentOwner) {
        throw "Temporary YL_orb identity did not pass the admin safety check"
    }
    $adminAuthorization = Get-AuthorizationValue ([string]$adminLogin.token)
    $purging = (Invoke-YlCloudEnvelope -Method "POST" -Path "/api/admin/users/$SeedUserId/purge-test-account" -Authorization $adminAuthorization -Body @{
        expectedUsername = $SeedUsername
    }).data
    if([string]$purging.accountStatus -ne 'PURGING') {
        throw "Seed purge was not accepted"
    }

    $deadline = (Get-Date).AddMinutes($CleanupTimeoutMinutes)
    do {
        Start-Sleep -Seconds 3
        $status = (Invoke-YlCloudEnvelope -Method "GET" -Path "/api/admin/users/$SeedUserId/account-status" -Authorization $adminAuthorization).data
        if([string]$status.accountStatus -eq 'PURGED') { return }
    } while((Get-Date) -lt $deadline)

    throw "Timed out waiting for seed account purge"
}

function Remove-RecoveredProvision {
    param(
        [string]$ProvisionPath,
        [string]$TemporaryHashHex,
        [string]$TemporaryPassword,
        [string]$AdminPassword
    )

    $resolvedProvisionPath = [IO.Path]::GetFullPath($ProvisionPath)
    $allowedOutputRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\output\rag-course-e2e"))
    if(-not $resolvedProvisionPath.StartsWith($allowedOutputRoot,[StringComparison]::OrdinalIgnoreCase)) {
        throw "Recovery provision file is outside the E2E output root"
    }
    $provision = Get-Content -LiteralPath $resolvedProvisionPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $recoverUserId = [long]$provision.userId
    $recoverSpaceId = [long]$provision.spaceId
    $recoverUsername = [string]$provision.username
    $recoverRunId = [string]$provision.runId
    $recoverSpaceName = "Course E2E $recoverRunId"
    if($recoverUserId -le 0 -or $recoverSpaceId -le 0 -or
            $recoverUsername -notmatch '^e2e_course_[A-Za-z0-9_]+$' -or
            $recoverUsername -match '^e2e_course_adminseed_' -or
            $recoverRunId -notmatch '^provision_[A-Za-z0-9_]+$') {
        throw "Recovery provision identity failed validation"
    }

    $rows = @(Invoke-TestDatabaseSql "SELECT u.user_id,u.username,u.role,u.status,u.account_status,s.id,s.name,s.type,s.lifecycle_state,s.owner_id,sm.role,sm.status FROM users u JOIN spaces s ON s.id=$recoverSpaceId JOIN space_member sm ON sm.space_id=s.id AND sm.user_id=u.user_id WHERE u.user_id=$recoverUserId")
    if($rows.Count -ne 1) { throw "Recovery target relation was not found exactly once" }
    $fields = [string]$rows[0] -split "`t"
    if($fields.Count -ne 12 -or $fields[0] -ne [string]$recoverUserId -or
            $fields[1] -ne $recoverUsername -or $fields[2] -ne 'USER' -or
            $fields[3] -ne '1' -or $fields[4] -ne 'ACTIVE' -or
            $fields[5] -ne [string]$recoverSpaceId -or $fields[6] -ne $recoverSpaceName -or
            $fields[7] -ne 'TEAM' -or $fields[8] -notin @('ACTIVE','DISSOLVING') -or
            $fields[9] -ne [string]$recoverUserId -or $fields[10] -ne 'OWNER' -or $fields[11] -ne '1') {
        throw "Recovery target failed the exact account/space/owner safety check"
    }

    Invoke-TestDatabaseSql "UPDATE users SET password=0x$TemporaryHashHex WHERE user_id=$recoverUserId AND username='$recoverUsername' AND role='USER' AND status=1 AND account_status='ACTIVE'" | Out-Null
    $recoverLogin = Invoke-Login -Username $recoverUsername -Password $TemporaryPassword
    if([long]$recoverLogin.id -ne $recoverUserId -or [string]$recoverLogin.username -ne $recoverUsername) {
        throw "Recovery account login identity mismatch"
    }
    $recoverAuthorization = Get-AuthorizationValue ([string]$recoverLogin.token)

    if($fields[8] -eq 'ACTIVE') {
        Invoke-YlCloudEnvelope -Method "POST" -Path "/api/space/$recoverSpaceId/leave" -Authorization $recoverAuthorization -Body @{
            confirmDissolve = $true
            confirmationName = $recoverSpaceName
        } | Out-Null
    }
    $spaceDeadline = (Get-Date).AddMinutes($CleanupTimeoutMinutes)
    do {
        Start-Sleep -Seconds 3
        $remainingSpaces = @((Invoke-YlCloudEnvelope -Method "GET" -Path "/api/space/list" -Authorization $recoverAuthorization).data)
        if(-not ($remainingSpaces | Where-Object { [long]$_.id -eq $recoverSpaceId })) { break }
    } while((Get-Date) -lt $spaceDeadline)
    if($remainingSpaces | Where-Object { [long]$_.id -eq $recoverSpaceId }) {
        throw "Timed out waiting for recovered TEAM Space dissolution"
    }

    $cancelled = (Invoke-YlCloudEnvelope -Method "POST" -Path "/api/account/cancel" -Authorization $recoverAuthorization -Body @{
        reason = "Recover interrupted course E2E run"
        confirmTeamOwnerTransfer = $false
    }).data
    if([long]$cancelled.userId -ne $recoverUserId -or [string]$cancelled.accountStatus -ne 'CANCELLED') {
        throw "Recovered account did not enter CANCELLED state"
    }

    $adminLogin = Invoke-Login -Username $AdminUsername -Password $AdminPassword
    if([long]$adminLogin.id -ne 1 -or [string]$adminLogin.role -ne 'ADMIN') {
        throw "Recovery admin identity mismatch"
    }
    $adminAuthorization = Get-AuthorizationValue ([string]$adminLogin.token)
    $purging = (Invoke-YlCloudEnvelope -Method "POST" -Path "/api/admin/users/$recoverUserId/purge-test-account" -Authorization $adminAuthorization -Body @{
        expectedUsername = $recoverUsername
    }).data
    if([string]$purging.accountStatus -ne 'PURGING') { throw "Recovered account purge was not accepted" }

    $accountDeadline = (Get-Date).AddMinutes($CleanupTimeoutMinutes)
    do {
        Start-Sleep -Seconds 3
        $status = (Invoke-YlCloudEnvelope -Method "GET" -Path "/api/admin/users/$recoverUserId/account-status" -Authorization $adminAuthorization).data
        if([string]$status.accountStatus -eq 'PURGED') { return }
    } while((Get-Date) -lt $accountDeadline)
    throw "Timed out waiting for recovered account purge"
}

function Remove-RecoveredSeed {
    param(
        [long]$UserId,
        [string]$Username,
        [string]$TemporaryHashHex,
        [string]$TemporaryPassword,
        [string]$AdminPassword
    )

    if($UserId -le 0 -or $Username -notmatch '^e2e_course_adminseed_[A-Za-z0-9_]+$') {
        throw "Recovered seed identity failed validation"
    }
    $rows = @(Invoke-TestDatabaseSql "SELECT u.user_id,u.username,u.role,u.status,u.account_status,(SELECT COUNT(*) FROM spaces s WHERE s.owner_id=u.user_id AND s.type='TEAM' AND s.status=1) FROM users u WHERE u.user_id=$UserId")
    if($rows.Count -ne 1) { throw "Recovered seed was not found exactly once" }
    $fields = [string]$rows[0] -split "`t"
    if($fields.Count -ne 6 -or $fields[0] -ne [string]$UserId -or $fields[1] -ne $Username -or
            $fields[2] -ne 'USER' -or $fields[3] -ne '1' -or $fields[4] -ne 'ACTIVE' -or $fields[5] -ne '0') {
        throw "Recovered seed failed the exact account/no-TEAM safety check"
    }
    Invoke-TestDatabaseSql "UPDATE users SET password=0x$TemporaryHashHex WHERE user_id=$UserId AND username='$Username' AND role='USER' AND status=1 AND account_status='ACTIVE'" | Out-Null
    Remove-SeedAccount -SeedUserId $UserId -SeedUsername $Username -SeedPassword $TemporaryPassword -AdminPassword $AdminPassword
}

function Invoke-ResumeProvision {
    param(
        [string]$ProvisionPath,
        [string]$UploadedFilesPath,
        [string]$TemporaryHashHex,
        [string]$TemporaryPassword
    )

    if([string]::IsNullOrWhiteSpace($UploadedFilesPath)) {
        throw "ResumeUploadedFilesPath is required with ResumeProvisionPath"
    }
    $resolvedProvisionPath = [IO.Path]::GetFullPath($ProvisionPath)
    $resolvedUploadedFilesPath = [IO.Path]::GetFullPath($UploadedFilesPath)
    $allowedOutputRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\output\rag-course-e2e"))
    if(-not $resolvedProvisionPath.StartsWith($allowedOutputRoot,[StringComparison]::OrdinalIgnoreCase) -or
            -not $resolvedUploadedFilesPath.StartsWith($allowedOutputRoot,[StringComparison]::OrdinalIgnoreCase)) {
        throw "Resume artifacts are outside the E2E output root"
    }
    $provision = Get-Content -LiteralPath $resolvedProvisionPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $resumeUserId = [long]$provision.userId
    $resumeSpaceId = [long]$provision.spaceId
    $resumeUsername = [string]$provision.username
    $resumeRunId = [string]$provision.runId
    $resumeSpaceName = "Course E2E $resumeRunId"
    if($resumeUserId -le 0 -or $resumeSpaceId -le 0 -or
            $resumeUsername -notmatch '^e2e_course_[A-Za-z0-9_]+$' -or
            $resumeUsername -match '^e2e_course_adminseed_' -or
            $resumeRunId -notmatch '^provision_[A-Za-z0-9_]+$') {
        throw "Resume provision identity failed validation"
    }
    $rows = @(Invoke-TestDatabaseSql "SELECT u.user_id,u.username,u.role,u.status,u.account_status,s.id,s.name,s.type,s.lifecycle_state,s.owner_id,sm.role,sm.status FROM users u JOIN spaces s ON s.id=$resumeSpaceId JOIN space_member sm ON sm.space_id=s.id AND sm.user_id=u.user_id WHERE u.user_id=$resumeUserId")
    if($rows.Count -ne 1) { throw "Resume target relation was not found exactly once" }
    $fields = [string]$rows[0] -split "`t"
    if($fields.Count -ne 12 -or $fields[0] -ne [string]$resumeUserId -or
            $fields[1] -ne $resumeUsername -or $fields[2] -ne 'USER' -or
            $fields[3] -ne '1' -or $fields[4] -ne 'ACTIVE' -or
            $fields[5] -ne [string]$resumeSpaceId -or $fields[6] -ne $resumeSpaceName -or
            $fields[7] -ne 'TEAM' -or $fields[8] -ne 'ACTIVE' -or
            $fields[9] -ne [string]$resumeUserId -or $fields[10] -ne 'OWNER' -or $fields[11] -ne '1') {
        throw "Resume target failed the exact account/space/owner safety check"
    }
    $mapping = Get-Content -LiteralPath $resolvedUploadedFilesPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $mappingFiles = @($mapping.files)
    $mappingSpaceFileIds = @($mappingFiles | ForEach-Object { [long]$_.spaceFileId })
    $distinctMappingIds = @($mappingSpaceFileIds | Where-Object { $_ -gt 0 } | Sort-Object -Unique)
    if($mappingFiles.Count -lt 1 -or $distinctMappingIds.Count -ne $mappingFiles.Count) {
        throw "Resume upload mapping failed the space safety check"
    }
    $mappingIdCsv = $distinctMappingIds -join ','
    $mappedCountRows = @(Invoke-TestDatabaseSql "SELECT COUNT(*) FROM space_file WHERE space_id=$resumeSpaceId AND id IN ($mappingIdCsv) AND status=1")
    if($mappedCountRows.Count -ne 1 -or [int]$mappedCountRows[0] -ne $mappingFiles.Count) {
        throw "Resume upload mapping does not belong entirely to the verified space"
    }

    Invoke-TestDatabaseSql "UPDATE users SET password=0x$TemporaryHashHex WHERE user_id=$resumeUserId AND username='$resumeUsername' AND role='USER' AND status=1 AND account_status='ACTIVE'" | Out-Null
    $resumeLogin = Invoke-Login -Username $resumeUsername -Password $TemporaryPassword
    if([long]$resumeLogin.id -ne $resumeUserId -or [string]$resumeLogin.username -ne $resumeUsername) {
        throw "Resume account login identity mismatch"
    }

    $coreScript = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot "course-docs-rag-e2e.ps1"))
    $powerShellExecutable = (Get-Process -Id $PID).Path
    $resumeOutputRoot = Split-Path -Parent $resolvedProvisionPath
    $coreArguments = @(
        "-NoProfile", "-File", $coreScript,
        "-BaseUrl", $BaseUrl,
        "-SpaceId", [string]$resumeSpaceId,
        "-LinuxRoot", $LinuxRoot,
        "-GeologyRoot", $GeologyRoot,
        "-OutputRoot", $resumeOutputRoot,
        "-TimeoutMinutes", [string]$TimeoutMinutes,
        "-SkipUpload",
        "-UploadedFilesPath", $resolvedUploadedFilesPath,
        "-KeepGoing"
    )
    $previousToken = $env:YLCLOUD_E2E_TOKEN
    try {
        $env:YLCLOUD_E2E_TOKEN = [string]$resumeLogin.token
        & $powerShellExecutable @coreArguments | Out-Host
        $resumeExitCode = $LASTEXITCODE
        return $resumeExitCode
    } finally {
        if($null -eq $previousToken) { Remove-Item Env:YLCLOUD_E2E_TOKEN -ErrorAction SilentlyContinue }
        else { $env:YLCLOUD_E2E_TOKEN = $previousToken }
        Remove-Variable previousToken,resumeLogin -ErrorAction SilentlyContinue
    }
}

Assert-TestRuntime
if(-not [string]::IsNullOrWhiteSpace($RecoverProvisionPath) -and -not [string]::IsNullOrWhiteSpace($ResumeProvisionPath)) {
    throw "RecoverProvisionPath and ResumeProvisionPath are mutually exclusive"
}
if($CleanupOnly -and ([string]::IsNullOrWhiteSpace($RecoverProvisionPath) -or -not [string]::IsNullOrWhiteSpace($ResumeProvisionPath))) {
    throw "CleanupOnly requires RecoverProvisionPath and cannot be combined with ResumeProvisionPath"
}
if(($RecoverSeedUserId -gt 0) -xor (-not [string]::IsNullOrWhiteSpace($RecoverSeedUsername))) {
    throw "RecoverSeedUserId and RecoverSeedUsername must be provided together"
}

$originalAdminHashHex = $null
$adminHashWasChanged = $false
$seedUserId = $null
$seedUsername = "e2e_course_adminseed_$((Get-Date).ToUniversalTime().ToString('yyyyMMddHHmmss'))_$([guid]::NewGuid().ToString('N').Substring(0,8))"
$randomBytes = [byte[]]::new(24)
$randomNumberGenerator = [Security.Cryptography.RandomNumberGenerator]::Create()
try {
    $randomNumberGenerator.GetBytes($randomBytes)
} finally {
    $randomNumberGenerator.Dispose()
}
$temporaryPassword = [Convert]::ToBase64String($randomBytes) + "Aa1!"
$wrapperExitCode = 1
$seedCleanupSucceeded = $false
$adminHashRestored = $false
$recoveredProvisionPurged = [string]::IsNullOrWhiteSpace($RecoverProvisionPath)

try {
    Write-Host "[testdb] reading and validating YL_orb metadata"
    $adminRows = @(Invoke-TestDatabaseSql "SELECT user_id,username,role,status,deployment_owner,HEX(password) FROM users WHERE username='YL_orb'")
    if($adminRows.Count -ne 1) { throw "Expected exactly one YL_orb row in the test database" }
    $adminFields = [string]$adminRows[0] -split "`t"
    if($adminFields.Count -ne 6 -or $adminFields[0] -ne '1' -or $adminFields[2] -ne 'ADMIN' -or
            $adminFields[3] -ne '1' -or $adminFields[4] -ne '1' -or $adminFields[5] -notmatch '^[0-9A-F]{120}$') {
        throw "YL_orb test-database row failed the safety check"
    }
    $originalAdminHashHex = $adminFields[5]
    Remove-Variable adminRows,adminFields -ErrorAction SilentlyContinue

    Write-Host "[testdb] registering temporary credential seed"
    Invoke-YlCloudEnvelope -Method "POST" -Path "/api/sign" -Anonymous -Body @{
        username = $seedUsername
        password = $temporaryPassword
        nickname = "Temporary E2E admin credential seed"
    } | Out-Null

    $seedLogin = Invoke-Login -Username $seedUsername -Password $temporaryPassword
    if([string]$seedLogin.username -ne $seedUsername -or [string]$seedLogin.role -ne 'USER') {
        throw "Seed registration/login identity mismatch"
    }
    $seedUserId = [long]$seedLogin.id

    Write-Host "[testdb] reading temporary credential seed metadata"
    $seedRows = @(Invoke-TestDatabaseSql "SELECT user_id,HEX(password) FROM users WHERE user_id=$seedUserId AND username='$seedUsername'")
    if($seedRows.Count -ne 1) { throw "Cannot locate the seed account in the test database" }
    $seedFields = [string]$seedRows[0] -split "`t"
    if($seedFields.Count -ne 2 -or $seedFields[0] -ne [string]$seedUserId -or $seedFields[1] -notmatch '^[0-9A-F]{120}$') {
        throw "Seed password hash failed validation"
    }
    $temporaryHashHex = $seedFields[1]
    Remove-Variable seedRows,seedFields,seedLogin -ErrorAction SilentlyContinue

    Write-Host "[testdb] applying and verifying temporary YL_orb credential"
    Invoke-TestDatabaseSql "UPDATE users SET password=0x$temporaryHashHex WHERE user_id=1 AND username='YL_orb' AND HEX(password)='$originalAdminHashHex'" | Out-Null
    $changedHash = @(Invoke-TestDatabaseSql "SELECT HEX(password) FROM users WHERE user_id=1 AND username='YL_orb'")
    if($changedHash.Count -ne 1 -or [string]$changedHash[0] -ne $temporaryHashHex) {
        throw "Temporary admin hash update could not be verified"
    }
    $adminHashWasChanged = $true
    Remove-Variable changedHash -ErrorAction SilentlyContinue

    $adminLogin = Invoke-Login -Username $AdminUsername -Password $temporaryPassword
    if([long]$adminLogin.id -ne 1 -or [string]$adminLogin.role -ne 'ADMIN' -or -not [bool]$adminLogin.deploymentOwner) {
        throw "Temporary admin login failed its identity check"
    }
    Remove-Variable adminLogin -ErrorAction SilentlyContinue

    if($RecoverSeedUserId -gt 0) {
        Write-Host "[testdb] safely recovering the exact interrupted credential seed"
        Remove-RecoveredSeed -UserId $RecoverSeedUserId -Username $RecoverSeedUsername -TemporaryHashHex $temporaryHashHex -TemporaryPassword $temporaryPassword -AdminPassword $temporaryPassword
    }

    if(-not [string]::IsNullOrWhiteSpace($RecoverProvisionPath)) {
        Write-Host "[testdb] safely recovering the exact interrupted provision"
        Remove-RecoveredProvision -ProvisionPath $RecoverProvisionPath -TemporaryHashHex $temporaryHashHex -TemporaryPassword $temporaryPassword -AdminPassword $temporaryPassword
        $recoveredProvisionPurged = $true
    }
    if($CleanupOnly) {
        Write-Host "[testdb] cleanup-only mode completed the requested recovery targets"
        $wrapperExitCode = 0
    } elseif(-not [string]::IsNullOrWhiteSpace($ResumeProvisionPath)) {
        Write-Host "[testdb] resuming QA from the verified uploaded-file mapping"
        $wrapperExitCode = Invoke-ResumeProvision -ProvisionPath $ResumeProvisionPath -UploadedFilesPath $ResumeUploadedFilesPath -TemporaryHashHex $temporaryHashHex -TemporaryPassword $temporaryPassword
        Write-Host "[testdb] purging the resumed provision"
        Remove-RecoveredProvision -ProvisionPath $ResumeProvisionPath -TemporaryHashHex $temporaryHashHex -TemporaryPassword $temporaryPassword -AdminPassword $temporaryPassword
        $recoveredProvisionPurged = $true
    } else {
        Write-Host "[testdb] starting course Cloud/RAG/QA wrapper"
        $wrapperScript = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot "course-docs-rag-e2e-autoprovision.ps1"))
        $powerShellExecutable = (Get-Process -Id $PID).Path
        $wrapperArguments = @(
            "-NoProfile", "-File", $wrapperScript,
            "-BaseUrl", $BaseUrl,
            "-LinuxRoot", $LinuxRoot,
            "-GeologyRoot", $GeologyRoot,
            "-TimeoutMinutes", [string]$TimeoutMinutes,
            "-CleanupTimeoutMinutes", [string]$CleanupTimeoutMinutes
        )
        if($KeepGoing) { $wrapperArguments += "-KeepGoing" }

        $previousAdminUsername = $env:YLCLOUD_E2E_ADMIN_USERNAME
        $previousAdminPassword = $env:YLCLOUD_E2E_ADMIN_PASSWORD
        try {
            $env:YLCLOUD_E2E_ADMIN_USERNAME = $AdminUsername
            $env:YLCLOUD_E2E_ADMIN_PASSWORD = $temporaryPassword
            & $powerShellExecutable @wrapperArguments
            $wrapperExitCode = $LASTEXITCODE
        } finally {
            if($null -eq $previousAdminUsername) { Remove-Item Env:YLCLOUD_E2E_ADMIN_USERNAME -ErrorAction SilentlyContinue }
            else { $env:YLCLOUD_E2E_ADMIN_USERNAME = $previousAdminUsername }
            if($null -eq $previousAdminPassword) { Remove-Item Env:YLCLOUD_E2E_ADMIN_PASSWORD -ErrorAction SilentlyContinue }
            else { $env:YLCLOUD_E2E_ADMIN_PASSWORD = $previousAdminPassword }
            Remove-Variable previousAdminUsername,previousAdminPassword -ErrorAction SilentlyContinue
        }
    }
    Remove-Variable temporaryHashHex -ErrorAction SilentlyContinue

    Write-Host "[testdb] purging temporary credential seed"
    Remove-SeedAccount -SeedUserId $seedUserId -SeedUsername $seedUsername -SeedPassword $temporaryPassword -AdminPassword $temporaryPassword
    $seedCleanupSucceeded = $true
} finally {
    if($adminHashWasChanged -and -not [string]::IsNullOrWhiteSpace($originalAdminHashHex)) {
        try {
            Write-Host "[testdb] restoring and verifying original YL_orb password hash"
            Invoke-TestDatabaseSql "UPDATE users SET password=0x$originalAdminHashHex WHERE user_id=1 AND username='YL_orb'" | Out-Null
            $restoredHash = @(Invoke-TestDatabaseSql "SELECT HEX(password) FROM users WHERE user_id=1 AND username='YL_orb'")
            $adminHashRestored = $restoredHash.Count -eq 1 -and [string]$restoredHash[0] -eq $originalAdminHashHex
            if(-not $adminHashRestored) { throw "YL_orb hash restoration verification failed" }
        } catch {
            Write-Error "CRITICAL: test-database YL_orb hash restoration failed: $($_.Exception.Message)" -ErrorAction Continue
        }
    }

    Remove-Variable temporaryPassword,originalAdminHashHex,restoredHash -ErrorAction SilentlyContinue
}

$result = [ordered]@{
    database = $Database
    formalDatabaseModified = $false
    wrapperExitCode = $wrapperExitCode
    seedAccountPurged = $seedCleanupSucceeded
    recoveredProvisionPurged = $recoveredProvisionPurged
    adminHashRestored = $adminHashRestored
    success = ($wrapperExitCode -eq 0 -and $seedCleanupSucceeded -and $recoveredProvisionPurged -and $adminHashRestored)
}
$result | ConvertTo-Json -Depth 10
if(-not $result.success) { exit 1 }
