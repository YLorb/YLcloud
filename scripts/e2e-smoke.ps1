param(
    [string]$BaseUrl = "http://127.0.0.1:5173",
    [Parameter(Mandatory = $true)]
    [string]$PdfPath,
    [string]$AdminUsername,
    [string]$AdminPassword
)

$ErrorActionPreference = "Stop"

function Invoke-ApiJson {
    param(
        [string]$Method,
        [string]$Path,
        [object]$Body,
        [string]$Token
    )

    $headers = @{ Accept = "application/json" }
    if ($Token) {
        $headers.Authorization = "Bearer $Token"
    }
    $request = @{
        Uri = "$BaseUrl$Path"
        Method = $Method
        Headers = $headers
        UseBasicParsing = $true
    }
    if ($null -ne $Body) {
        $json = $Body | ConvertTo-Json -Depth 12 -Compress
        $request.Body = $json
        $request.ContentType = "application/json; charset=utf-8"
    }
    try {
        $response = Invoke-WebRequest @request
    } catch {
        throw "API request failed: $Method $Path`: $($_.Exception.Message)"
    }
    $result = $response.Content | ConvertFrom-Json
    if ($result.code -ne 200) {
        throw "API returned code $($result.code) for $Method $Path`: $($result.message)"
    }
    return $result.data
}

function Send-File {
    param(
        [string]$Path,
        [string]$FilePath,
        [string]$Token,
        [hashtable]$Fields = @{}
    )

    $arguments = @("--silent", "--show-error", "--fail-with-body", "--request", "POST", "$BaseUrl$Path", "--header", "Authorization: Bearer $Token", "--form", "file=@$FilePath")
    foreach ($key in $Fields.Keys) {
        $arguments += @("--form", "$key=$($Fields[$key])")
    }
    $raw = & curl.exe @arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Multipart request failed: POST $Path"
    }
    $result = $raw | ConvertFrom-Json
    if ($result.code -ne 200) {
        throw "API returned code $($result.code) for POST $Path`: $($result.message)"
    }
    return $result.data
}

function Receive-File {
    param(
        [string]$Path,
        [string]$TargetPath,
        [string]$Token
    )

    & curl.exe --silent --show-error --fail "$BaseUrl$Path" --header "Authorization: Bearer $Token" --output $TargetPath
    if ($LASTEXITCODE -ne 0) {
        throw "Download failed: GET $Path"
    }
}

function Wait-RagTask {
    param(
        [long]$SpaceId,
        [long]$SpaceFileId,
        [string]$Token,
        [int]$TimeoutSeconds = 300
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $tasks = @(Invoke-ApiJson -Method "GET" -Path "/api/space/$SpaceId/rag/tasks" -Token $Token)
        $task = $tasks | Where-Object { $_.spaceFileId -eq $SpaceFileId } | Sort-Object id -Descending | Select-Object -First 1
        if ($task -and $task.taskStatus -in @("SUCCESS", "FAILED")) {
            return $task
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw "RAG task timed out for spaceFileId=$SpaceFileId"
}

function Get-Sha256 {
    param([string]$Path)
    return (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant()
}

$runId = "e2e_$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())_$([guid]::NewGuid().ToString('N').Substring(0, 8))"
$username = $runId
$password = "Tmp-$([guid]::NewGuid().ToString('N'))"
$tempDir = Join-Path $env:TEMP $runId
$token = $null
$testSpaceId = $null
$ordinaryFileUuid = $null
New-Item -ItemType Directory -Path $tempDir | Out-Null

$v1Path = Join-Path $tempDir "knowledge-v1.md"
$v2Path = Join-Path $tempDir "knowledge-v2.md"
$v3Path = Join-Path $tempDir "knowledge-v3.md"
$ordinaryDownload = Join-Path $tempDir "ordinary-download.md"
$spaceDownload = Join-Path $tempDir "space-download.md"

[IO.File]::WriteAllText($v1Path, "# E2E Knowledge`n`nThe launch code is ORBIT-NEBULA-7319.`n`nVersion: one.`n", [Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText($v2Path, "# E2E Knowledge`n`nThe launch code is ORBIT-NEBULA-7319.`n`nVersion: two.`n", [Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText($v3Path, "# E2E Knowledge`n`nThe launch code is ORBIT-NEBULA-7319.`n`nVersion: three.`n", [Text.UTF8Encoding]::new($false))

try {
    $publicSettings = Invoke-ApiJson -Method "GET" -Path "/api/site/public-settings"

    $unauthorizedStatus = & curl.exe --silent --output NUL --write-out "%{http_code}" "$BaseUrl/api/space/list"

    Invoke-ApiJson -Method "POST" -Path "/api/sign" -Body @{
        username = $username
        password = $password
        nickname = "E2E Smoke User"
    } | Out-Null

    $login = Invoke-ApiJson -Method "POST" -Path "/api/login" -Body @{
        username = $username
        password = $password
    }
    $token = $login.token
    if (-not $token) {
        throw "Login did not return a token"
    }

    $currentUserId = Invoke-ApiJson -Method "GET" -Path "/api/user/current" -Token $token
    $spaces = @(Invoke-ApiJson -Method "GET" -Path "/api/space/list" -Token $token)
    $personalSpace = $spaces | Where-Object { $_.type -eq "PERSONAL" -and $_.role -eq "OWNER" } | Select-Object -First 1
    if (-not $personalSpace) {
        throw "Default personal space was not created"
    }

    $space = Invoke-ApiJson -Method "POST" -Path "/api/space" -Token $token -Body @{
        name = "E2E smoke $runId"
        description = "Disposable automated smoke-test space"
    }
    $testSpaceId = [long]$space.id

    $ordinary = Send-File -Path "/api/file/upload" -FilePath $v1Path -Token $token -Fields @{ parentId = 0 }
    $ordinaryFileUuid = $ordinary.fileUuid
    Receive-File -Path "/api/file/download/$($ordinary.fileUuid)?parentId=0" -TargetPath $ordinaryDownload -Token $token
    $ordinaryHashMatches = (Get-Sha256 $v1Path) -eq (Get-Sha256 $ordinaryDownload)

    $spaceFile = Send-File -Path "/api/space/$($space.id)/files/upload" -FilePath $v1Path -Token $token -Fields @{ name = "$runId-knowledge.md" }
    Receive-File -Path "/api/space/$($space.id)/files/$($spaceFile.id)/download" -TargetPath $spaceDownload -Token $token
    $spaceHashMatches = (Get-Sha256 $v1Path) -eq (Get-Sha256 $spaceDownload)

    $markdownTask = Wait-RagTask -SpaceId $space.id -SpaceFileId $spaceFile.id -Token $token
    if ($markdownTask.taskStatus -ne "SUCCESS") {
        throw "Markdown RAG indexing failed: $($markdownTask.errorMessage)"
    }
    $documents = @(Invoke-ApiJson -Method "GET" -Path "/api/space/$($space.id)/rag/documents" -Token $token)
    $markdownDocument = $documents | Where-Object { $_.spaceFileId -eq $spaceFile.id } | Select-Object -First 1
    if (-not $markdownDocument -or $markdownDocument.indexStatus -ne "SUCCESS" -or $markdownDocument.chunkCount -lt 1) {
        throw "Markdown RAG document was not indexed"
    }

    $query = $null
    $queryError = $null
    try {
        $query = Invoke-ApiJson -Method "POST" -Path "/api/space/$($space.id)/rag/query" -Token $token -Body @{
            question = "What is the launch code?"
            retrievalMode = "precise"
            history = @()
        }
    } catch {
        $queryError = $_.Exception.Message
    }

    $pdfFile = Send-File -Path "/api/space/$($space.id)/files/upload" -FilePath $PdfPath -Token $token -Fields @{ name = "$runId-parser.pdf" }
    $pdfTask = Wait-RagTask -SpaceId $space.id -SpaceFileId $pdfFile.id -Token $token
    if ($pdfTask.taskStatus -ne "SUCCESS") {
        throw "PDF RAG indexing failed: $($pdfTask.errorMessage)"
    }

    $initialVersions = @(Invoke-ApiJson -Method "GET" -Path "/api/space/$($space.id)/files/$($spaceFile.id)/versions" -Token $token)
    $v2 = Send-File -Path "/api/space/$($space.id)/files/$($spaceFile.id)/versions" -FilePath $v2Path -Token $token -Fields @{ changeNote = "E2E v2" }
    $v3 = Send-File -Path "/api/space/$($space.id)/files/$($spaceFile.id)/versions" -FilePath $v3Path -Token $token -Fields @{ changeNote = "E2E v3" }
    $versionsBeforeRestore = @(Invoke-ApiJson -Method "GET" -Path "/api/space/$($space.id)/files/$($spaceFile.id)/versions" -Token $token)
    Invoke-ApiJson -Method "POST" -Path "/api/space/$($space.id)/files/$($spaceFile.id)/versions/$($v2.id)/restore?changeNote=E2E-restore-v2" -Token $token | Out-Null
    Receive-File -Path "/api/space/$($space.id)/files/$($spaceFile.id)/download" -TargetPath $spaceDownload -Token $token
    $restoreHashMatches = (Get-Sha256 $v2Path) -eq (Get-Sha256 $spaceDownload)
    $versionsAfterRestore = @(Invoke-ApiJson -Method "GET" -Path "/api/space/$($space.id)/files/$($spaceFile.id)/versions" -Token $token)

    $adminSettings = @()
    if(-not [string]::IsNullOrWhiteSpace($AdminUsername) -and -not [string]::IsNullOrWhiteSpace($AdminPassword)) {
        $adminLogin = Invoke-ApiJson -Method "POST" -Path "/api/login" -Body @{
            username = $AdminUsername
            password = $AdminPassword
        }
        $adminSettings = @(Invoke-ApiJson -Method "GET" -Path "/api/admin/settings" -Token $adminLogin.token)
    }

    $queryHitCount = if ($query) { @($query.hitChunkIds).Count } else { 0 }
    $queryContextCount = if ($query) { @($query.contexts).Count } else { 0 }
    $queryContainsLaunchCode = if ($query) {
        [bool](($query.answer -match "ORBIT-NEBULA-7319") -or (($query.contexts -join "`n") -match "ORBIT-NEBULA-7319"))
    } else {
        $false
    }

    [ordered]@{
        runId = $runId
        publicSiteName = $publicSettings.siteName
        unauthorizedStatus = [int]$unauthorizedStatus
        userId = [long]$currentUserId
        defaultSpaceId = [long]$personalSpace.id
        testSpaceId = [long]$space.id
        ordinaryFileHashMatches = $ordinaryHashMatches
        spaceFileHashMatches = $spaceHashMatches
        markdownRagStatus = $markdownTask.taskStatus
        markdownChunkCount = [int]$markdownDocument.chunkCount
        queryHitCount = $queryHitCount
        queryContextCount = $queryContextCount
        queryContainsLaunchCode = $queryContainsLaunchCode
        queryError = $queryError
        pdfSpaceFileId = [long]$pdfFile.id
        pdfRagStatus = $pdfTask.taskStatus
        initialVersionRecordCount = $initialVersions.Count
        versionRecordsBeforeRestore = $versionsBeforeRestore.Count
        versionRecordsAfterRestore = $versionsAfterRestore.Count
        restoredV2HashMatches = $restoreHashMatches
        adminSettingsCount = $adminSettings.Count
        success = $ordinaryHashMatches -and $spaceHashMatches -and $restoreHashMatches -and $markdownTask.taskStatus -eq "SUCCESS" -and $pdfTask.taskStatus -eq "SUCCESS" -and $queryHitCount -gt 0 -and $queryContainsLaunchCode
    } | ConvertTo-Json -Depth 6
} finally {
    if($token -and $ordinaryFileUuid) {
        try {
            Invoke-ApiJson -Method "DELETE" -Path "/api/file/$($ordinaryFileUuid)?parentId=0" -Token $token | Out-Null
            $recycleEntry = @(Invoke-ApiJson -Method "GET" -Path "/api/file/recycle" -Token $token) |
                Where-Object { $_.fileUuid -eq $ordinaryFileUuid } |
                Select-Object -First 1
            if($recycleEntry) {
                Invoke-ApiJson -Method "DELETE" -Path "/api/file/recycle/$($recycleEntry.fileId)" -Token $token | Out-Null
            }
        } catch {
            Write-Warning "Ordinary-file cleanup failed: $($_.Exception.Message)"
        }
    }
    if($token -and $testSpaceId) {
        try {
            Invoke-ApiJson -Method "DELETE" -Path "/api/space/$testSpaceId" -Token $token | Out-Null
        } catch {
            Write-Warning "Space cleanup failed: $($_.Exception.Message)"
        }
    }
    Remove-Item -LiteralPath $tempDir -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Variable password,token,AdminPassword -ErrorAction SilentlyContinue
}
