param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [string]$AdminUsername = $env:YLCLOUD_E2E_ADMIN_USERNAME,
    [string]$AdminPassword = $env:YLCLOUD_E2E_ADMIN_PASSWORD,
    [string]$LinuxRoot = "D:\沐浴露\课程\linux",
    [string]$GeologyRoot = "D:\沐浴露\课程\地球科学概论",
    [string]$OutputRoot = (Join-Path $PSScriptRoot "..\output\rag-course-e2e"),
    [int]$TimeoutMinutes = 20,
    [int]$CleanupTimeoutMinutes = 20,
    [switch]$KeepGoing
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
$OutputEncoding = [Text.UTF8Encoding]::new($false)

function ConvertTo-JsonText {
    param([object]$Value)
    return $Value | ConvertTo-Json -Depth 30
}

function Write-JsonFile {
    param([string]$Path, [object]$Value)
    $parent = Split-Path -Parent $Path
    if($parent -and -not (Test-Path -LiteralPath $parent)) {
        New-Item -ItemType Directory -Path $parent | Out-Null
    }
    [IO.File]::WriteAllText($Path,(ConvertTo-JsonText $Value),[Text.UTF8Encoding]::new($false))
}

function Get-AuthorizationValue {
    param([string]$RawToken)
    if([string]::IsNullOrWhiteSpace($RawToken)) { throw "Login response did not contain a JWT" }
    if($RawToken -match "^Bearer\s+") { return $RawToken }
    return "Bearer $RawToken"
}

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
    if($result.code -ne 200) {
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

if([string]::IsNullOrWhiteSpace($AdminUsername) -or [string]::IsNullOrWhiteSpace($AdminPassword)) {
    throw "Set YLCLOUD_E2E_ADMIN_USERNAME and YLCLOUD_E2E_ADMIN_PASSWORD, or pass admin credentials explicitly"
}

$wrapperRunId = "provision_$((Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ'))_$([guid]::NewGuid().ToString('N').Substring(0,8))"
$wrapperDir = [IO.Path]::GetFullPath((Join-Path $OutputRoot $wrapperRunId))
New-Item -ItemType Directory -Path $wrapperDir -Force | Out-Null

$testUsername = "e2e_course_$((Get-Date).ToUniversalTime().ToString('yyyyMMddHHmmss'))_$([guid]::NewGuid().ToString('N').Substring(0,8))"
$randomBytes = [byte[]]::new(24)
$randomNumberGenerator = [Security.Cryptography.RandomNumberGenerator]::Create()
try {
    $randomNumberGenerator.GetBytes($randomBytes)
} finally {
    $randomNumberGenerator.Dispose()
}
$testPassword = [Convert]::ToBase64String($randomBytes) + "Aa1!"
$testUserId = $null
$testAuthorization = $null
$spaceId = $null
$spaceName = "Course E2E $wrapperRunId"
$coreExitCode = 1
$cleanupSucceeded = $false
$cleanup = [ordered]@{
    attempted = $false
    spaceDeleted = $false
    accountCancelled = $false
    adminIdentityVerified = $false
    purgeSubmitted = $false
    purgeCompleted = $false
    error = $null
}

try {
    # Fail before registration if the cleanup administrator cannot authenticate.
    $adminPreflight = Invoke-Login -Username $AdminUsername -Password $AdminPassword
    if([string]$adminPreflight.role -ne "ADMIN") {
        throw "Configured cleanup account is not an ADMIN"
    }
    Remove-Variable adminPreflight -ErrorAction SilentlyContinue

    Invoke-YlCloudEnvelope -Method "POST" -Path "/api/sign" -Anonymous -Body @{
        username = $testUsername
        password = $testPassword
        nickname = "Course E2E $wrapperRunId"
    } | Out-Null

    $testLogin = Invoke-Login -Username $testUsername -Password $testPassword
    if([string]$testLogin.username -ne $testUsername -or [string]$testLogin.role -ne "USER") {
        throw "Registered account identity does not match the login response"
    }
    $testUserId = [long]$testLogin.id
    $testAuthorization = Get-AuthorizationValue ([string]$testLogin.token)

    $space = (Invoke-YlCloudEnvelope -Method "POST" -Path "/api/space" -Authorization $testAuthorization -Body @{
        name = $spaceName
        description = "Disposable auto-provisioned Cloud/RAG/QA test space"
    }).data
    $spaceId = [long]$space.id
    if($spaceId -le 0) { throw "Space creation did not return a valid id" }

    Write-JsonFile (Join-Path $wrapperDir "provision.json") ([ordered]@{
        runId = $wrapperRunId
        createdAt = (Get-Date).ToUniversalTime().ToString("o")
        username = $testUsername
        userId = $testUserId
        spaceId = $spaceId
        registrationVerified = $true
        loginVerified = $true
        jwtPersisted = $false
        passwordPersisted = $false
    })

    $coreScript = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot "course-docs-rag-e2e.ps1"))
    $powerShellExecutable = (Get-Process -Id $PID).Path
    $coreArguments = @(
        "-NoProfile", "-File", $coreScript,
        "-BaseUrl", $BaseUrl,
        "-SpaceId", [string]$spaceId,
        "-LinuxRoot", $LinuxRoot,
        "-GeologyRoot", $GeologyRoot,
        "-OutputRoot", $wrapperDir,
        "-TimeoutMinutes", [string]$TimeoutMinutes
    )
    if($KeepGoing) { $coreArguments += "-KeepGoing" }

    $previousToken = $env:YLCLOUD_E2E_TOKEN
    try {
        $env:YLCLOUD_E2E_TOKEN = [string]$testLogin.token
        & $powerShellExecutable @coreArguments
        $coreExitCode = $LASTEXITCODE
    } finally {
        if($null -eq $previousToken) {
            Remove-Item Env:YLCLOUD_E2E_TOKEN -ErrorAction SilentlyContinue
        } else {
            $env:YLCLOUD_E2E_TOKEN = $previousToken
        }
        Remove-Variable previousToken -ErrorAction SilentlyContinue
    }
} finally {
    $cleanup.attempted = $null -ne $testUserId
    if($null -ne $testUserId) {
        try {
            if($null -ne $spaceId -and -not [string]::IsNullOrWhiteSpace($testAuthorization)) {
                Invoke-YlCloudEnvelope -Method "POST" -Path "/api/space/$spaceId/leave" -Authorization $testAuthorization -Body @{
                    confirmDissolve = $true
                    confirmationName = $spaceName
                } | Out-Null

                $spaceCleanupDeadline = (Get-Date).AddMinutes($CleanupTimeoutMinutes)
                do {
                    Start-Sleep -Seconds 3
                    $remainingSpaces = @((Invoke-YlCloudEnvelope -Method "GET" -Path "/api/space/list" -Authorization $testAuthorization).data)
                    if(-not ($remainingSpaces | Where-Object { [long]$_.id -eq $spaceId })) {
                        $cleanup.spaceDeleted = $true
                        break
                    }
                } while((Get-Date) -lt $spaceCleanupDeadline)
                if(-not $cleanup.spaceDeleted) {
                    throw "Timed out waiting for TEAM Space dissolution: spaceId=$spaceId"
                }
            }

            if([string]::IsNullOrWhiteSpace($testAuthorization)) {
                throw "Test user JWT is unavailable; refusing incomplete cleanup"
            }
            $cancelled = (Invoke-YlCloudEnvelope -Method "POST" -Path "/api/account/cancel" -Authorization $testAuthorization -Body @{
                reason = "Automated course E2E cleanup"
                confirmTeamOwnerTransfer = $false
            }).data
            if([string]$cancelled.accountStatus -ne "CANCELLED" -or [long]$cancelled.userId -ne $testUserId) {
                throw "Test account did not enter CANCELLED state"
            }
            $cleanup.accountCancelled = $true

            # Login again at cleanup time, as part of the requested admin-login test.
            $adminLogin = Invoke-Login -Username $AdminUsername -Password $AdminPassword
            if([string]$adminLogin.role -ne "ADMIN") { throw "Cleanup identity is no longer an ADMIN" }
            $adminAuthorization = Get-AuthorizationValue ([string]$adminLogin.token)
            $users = @((Invoke-YlCloudEnvelope -Method "GET" -Path "/api/admin/users" -Authorization $adminAuthorization).data)
            $target = @($users | Where-Object { [long]$_.id -eq $testUserId -and [string]$_.username -eq $testUsername })
            if($target.Count -ne 1 -or [string]$target[0].role -ne "USER" -or [bool]$target[0].deploymentOwner) {
                throw "Admin cleanup target failed the exact id/username/role safety check"
            }
            $cleanup.adminIdentityVerified = $true

            $purging = (Invoke-YlCloudEnvelope -Method "POST" -Path "/api/admin/users/$testUserId/purge-test-account" -Authorization $adminAuthorization -Body @{
                expectedUsername = $testUsername
            }).data
            if([string]$purging.accountStatus -ne "PURGING") {
                throw "Admin purge endpoint did not move the test account to PURGING"
            }
            $cleanup.purgeSubmitted = $true

            $deadline = (Get-Date).AddMinutes($CleanupTimeoutMinutes)
            do {
                Start-Sleep -Seconds 3
                $users = @((Invoke-YlCloudEnvelope -Method "GET" -Path "/api/admin/users" -Authorization $adminAuthorization).data)
                $current = $users | Where-Object { [long]$_.id -eq $testUserId } | Select-Object -First 1
                $accountStatus = (Invoke-YlCloudEnvelope -Method "GET" -Path "/api/admin/users/$testUserId/account-status" -Authorization $adminAuthorization).data
                if($current -and [string]$current.username -eq "deleted_$testUserId" -and [int]$current.status -eq 0 -and
                        [string]$accountStatus.accountStatus -eq "PURGED") {
                    $cleanup.purgeCompleted = $true
                    break
                }
            } while((Get-Date) -lt $deadline)
            if(-not $cleanup.purgeCompleted) {
                throw "Timed out waiting for account deletion orchestration to anonymize userId=$testUserId"
            }
            $cleanupSucceeded = $true
        } catch {
            $cleanup.error = $_.Exception.Message
            Write-Error "E2E account cleanup failed: $($_.Exception.Message)" -ErrorAction Continue
        }
    }

    Write-JsonFile (Join-Path $wrapperDir "cleanup.json") $cleanup
    Remove-Variable testPassword,AdminPassword,testLogin,adminLogin,adminAuthorization,testAuthorization -ErrorAction SilentlyContinue
}

$success = $coreExitCode -eq 0 -and $cleanupSucceeded
$summary = [ordered]@{
    runId = $wrapperRunId
    finishedAt = (Get-Date).ToUniversalTime().ToString("o")
    userId = $testUserId
    username = $testUsername
    spaceId = $spaceId
    coreExitCode = $coreExitCode
    cleanupSucceeded = $cleanupSucceeded
    success = $success
    outputDirectory = $wrapperDir
}
Write-JsonFile (Join-Path $wrapperDir "summary.json") $summary
ConvertTo-JsonText $summary
if(-not $success) { exit 1 }
