param(
    [string]$BaseUrl = "http://127.0.0.1:5173",
    [string]$MysqlContainer = "ylcloud-mysql",
    [string]$MinioContainer = "ylcloud-minio",
    [string]$AppContainer = "ylcloud-app",
    [string]$MinioBucket,
    [string]$OutputDirectory = "outputs/multipart-fault-injection",
    [int]$ConcurrentMergeRequests = 8,
    [switch]$ConfirmDisposableEnvironment
)

$ErrorActionPreference = "Stop"
if(-not $ConfirmDisposableEnvironment) {
    throw "Fault injection mutates MySQL, MinIO, and the app container. Re-run with -ConfirmDisposableEnvironment against an isolated environment."
}
if($ConcurrentMergeRequests -lt 2) {
    throw "ConcurrentMergeRequests must be at least 2"
}
if([string]::IsNullOrWhiteSpace($MinioBucket)) {
    $MinioBucket = (& docker exec $AppContainer printenv YLCLOUD_MINIO_BUCKET 2>$null | Out-String).Trim()
    if($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($MinioBucket)) {
        $MinioBucket = "localbucket1"
    }
}
if($MinioBucket -notmatch "^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$") {
    throw "Invalid MinIO bucket: $MinioBucket"
}

Add-Type -AssemblyName System.Net.Http
$workspace = Split-Path -Parent $PSScriptRoot
$outputPath = Join-Path $workspace $OutputDirectory
$runId = "multipart-fi-$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())-$([guid]::NewGuid().ToString('N').Substring(0,6))"
$tempPath = Join-Path $env:TEMP $runId
$reportPath = Join-Path $outputPath "$runId.json"
$httpClient = [System.Net.Http.HttpClient]::new()
$fixtures = [System.Collections.Generic.List[object]]::new()
$dbUser = $null
$dbPassword = $null
$dbName = $null
$acceptanceUserId = $null

New-Item -ItemType Directory -Force -Path $tempPath,$outputPath | Out-Null

function Assert-True([bool]$Condition, [string]$Message) {
    if(-not $Condition) { throw $Message }
}

function New-HttpMethod([string]$Method) {
    switch($Method.ToUpperInvariant()) {
        "GET" { return [System.Net.Http.HttpMethod]::Get }
        "POST" { return [System.Net.Http.HttpMethod]::Post }
        "PUT" { return [System.Net.Http.HttpMethod]::Put }
        "DELETE" { return [System.Net.Http.HttpMethod]::Delete }
        default { return [System.Net.Http.HttpMethod]::new($Method) }
    }
}

function Read-HttpResponse($Response) {
    $raw = $Response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    $body = $null
    if(-not [string]::IsNullOrWhiteSpace($raw)) {
        try { $body = $raw | ConvertFrom-Json } catch { $body = $raw }
    }
    return [pscustomobject]@{
        statusCode = [int]$Response.StatusCode
        body = $body
        raw = $raw
    }
}

function Invoke-JsonRequest {
    param([string]$Method,[string]$Path,[object]$Body = $null,[string]$Token = $null)
    $request = [System.Net.Http.HttpRequestMessage]::new((New-HttpMethod $Method),"$BaseUrl$Path")
    try {
        if($Token) { $request.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new("Bearer",$Token) }
        if($null -ne $Body) {
            $json = $Body | ConvertTo-Json -Depth 12 -Compress
            $request.Content = [System.Net.Http.StringContent]::new($json,[Text.Encoding]::UTF8,"application/json")
        }
        $response = $httpClient.SendAsync($request).GetAwaiter().GetResult()
        try { return Read-HttpResponse $response } finally { $response.Dispose() }
    } finally {
        $request.Dispose()
    }
}

function Require-Success($Response, [string]$Label) {
    if($Response.statusCode -ne 200 -or $null -eq $Response.body -or $Response.body.code -ne 200) {
        $message = if($Response.body -and $Response.body.message) { $Response.body.message } else { $Response.raw }
        throw "$Label failed: HTTP $($Response.statusCode), $message"
    }
    return $Response.body.data
}

function Send-Chunk {
    param([string]$UploadId,[int]$ChunkIndex,[string]$FilePath,[string]$ChunkMd5,[string]$Token)
    $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post,"$BaseUrl/api/file/multipart/chunk")
    $form = [System.Net.Http.MultipartFormDataContent]::new()
    try {
        $request.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new("Bearer",$Token)
        $bytes = [IO.File]::ReadAllBytes($FilePath)
        $fileContent = [System.Net.Http.ByteArrayContent]::new($bytes)
        $fileContent.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::new("application/octet-stream")
        $form.Add($fileContent,"file",[IO.Path]::GetFileName($FilePath))
        $form.Add([System.Net.Http.StringContent]::new($UploadId),"uploadId")
        $form.Add([System.Net.Http.StringContent]::new($ChunkIndex.ToString()),"chunkIndex")
        $form.Add([System.Net.Http.StringContent]::new($ChunkMd5),"chunkMd5")
        $request.Content = $form
        $response = $httpClient.SendAsync($request).GetAwaiter().GetResult()
        try { return Read-HttpResponse $response } finally { $response.Dispose() }
    } finally {
        $request.Dispose()
        $form.Dispose()
    }
}

function Invoke-ConcurrentMerge([string]$UploadId,[string]$Token) {
    $pending = @()
    for($index = 0; $index -lt $ConcurrentMergeRequests; $index++) {
        $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post,"$BaseUrl/api/file/multipart/merge")
        $request.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new("Bearer",$Token)
        $request.Content = [System.Net.Http.StringContent]::new(
            (@{ uploadId=$UploadId; fileName="ignored"; partNames=@("server-resolves-parts") } | ConvertTo-Json -Compress),
            [Text.Encoding]::UTF8,"application/json")
        $pending += [pscustomobject]@{ request=$request; task=$httpClient.SendAsync($request) }
    }
    $responses = @()
    foreach($item in $pending) {
        try {
            $response = $item.task.GetAwaiter().GetResult()
            try { $responses += Read-HttpResponse $response } finally { $response.Dispose() }
        } finally {
            $item.request.Dispose()
        }
    }
    return $responses
}

function Get-Hash([string]$Path,[string]$Algorithm) {
    $algorithmInstance = [Security.Cryptography.HashAlgorithm]::Create($Algorithm)
    try {
        $stream = [IO.File]::OpenRead($Path)
        try {
            return -join ($algorithmInstance.ComputeHash($stream) | ForEach-Object { $_.ToString("x2") })
        } finally { $stream.Dispose() }
    } finally { $algorithmInstance.Dispose() }
}

function New-Fixture([string]$Scenario,[int]$Seed) {
    $chunkSize = 5MB
    $length = $chunkSize + 4096 + $Seed
    $filePath = Join-Path $tempPath "$Scenario.bin"
    $bytes = New-Object byte[] $length
    $random = [Random]::new($Seed)
    $random.NextBytes($bytes)
    [IO.File]::WriteAllBytes($filePath,$bytes)
    $chunkPaths = @()
    for($index = 0; $index -lt 2; $index++) {
        $start = $index * $chunkSize
        $count = [Math]::Min($chunkSize,$length - $start)
        $chunk = New-Object byte[] $count
        [Array]::Copy($bytes,$start,$chunk,0,$count)
        $chunkPath = Join-Path $tempPath "$Scenario.part-$index"
        [IO.File]::WriteAllBytes($chunkPath,$chunk)
        $chunkPaths += $chunkPath
    }
    $fixture = [pscustomobject]@{
        scenario = $Scenario
        uploadId = [guid]::NewGuid().ToString()
        fileName = "$runId-$Scenario.bin"
        filePath = $filePath
        fileSize = [long]$length
        chunkSize = [long]$chunkSize
        chunkPaths = $chunkPaths
        chunkMd5 = @($chunkPaths | ForEach-Object { Get-Hash $_ "MD5" })
        md5 = Get-Hash $filePath "MD5"
        sha1 = Get-Hash $filePath "SHA1"
        sha256 = Get-Hash $filePath "SHA256"
        fileUuid = $null
    }
    $fixtures.Add($fixture)
    return $fixture
}

function Initialize-Fixture($Fixture,[string]$Token,[switch]$WrongDigest) {
    $body = @{
        uploadId = $Fixture.uploadId
        fileName = $Fixture.fileName
        fileSize = $Fixture.fileSize
        fileMd5 = if($WrongDigest) { "00000000000000000000000000000000" } else { $Fixture.md5 }
        fileSha1 = $Fixture.sha1
        fileHash = $Fixture.sha256
        chunkSize = $Fixture.chunkSize
        totalChunks = 2
        parentId = 0
    }
    $result = Require-Success (Invoke-JsonRequest POST "/api/file/multipart/init" $body $Token) "multipart init ($($Fixture.scenario))"
    Assert-True -Condition (-not $result.instantUpload) -Message "Fixture unexpectedly used instant upload: $($Fixture.scenario)"
    Assert-True -Condition ($result.uploadId -eq $Fixture.uploadId) -Message "Server returned a different uploadId"
    $Fixture.fileUuid = Invoke-Sql "select file_uuid from upload_task where upload_id='$($Fixture.uploadId)' limit 1"
    Assert-True -Condition ($Fixture.fileUuid -match '^[0-9a-fA-F-]{36}$') -Message "Upload task has no valid fileUuid"
    return $result
}

function Upload-AllChunks($Fixture,[string]$Token) {
    for($index = 0; $index -lt $Fixture.chunkPaths.Count; $index++) {
        Require-Success (Send-Chunk $Fixture.uploadId $index $Fixture.chunkPaths[$index] $Fixture.chunkMd5[$index] $Token) "chunk $index upload" | Out-Null
    }
}

function Get-ContainerEnvironment([string]$Name) {
    $value = (& docker exec $MysqlContainer printenv $Name | Out-String).Trim()
    if($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($value)) { throw "Missing $Name in $MysqlContainer" }
    return $value
}

function Invoke-Sql([string]$Query) {
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $output = & docker exec $MysqlContainer mysql --batch --skip-column-names "-u$dbUser" "-p$dbPassword" $dbName -e $Query 2>$null
    $exitCode = $LASTEXITCODE
    $ErrorActionPreference = $previousPreference
    if($exitCode -ne 0) { throw "MySQL fault-injection query failed" }
    return ($output | Out-String).Trim()
}

function Get-MinIoVersionCount([string]$ObjectName) {
    $script = 'MC_HOST_acceptance=http://$MINIO_ROOT_USER:$MINIO_ROOT_PASSWORD@127.0.0.1:9000 mc ls --versions --recursive acceptance/' + $MinioBucket
    return @(& docker exec $MinioContainer sh -c $script |
        Where-Object { $_ -match ("\s" + [regex]::Escape($ObjectName) + "$") }).Count
}

function Remove-MinIoFixture([string]$FileUuid) {
    if($FileUuid -notmatch '^[0-9a-fA-F-]{36}$') { return }
    $script = "export MC_HOST_acceptance=http://`$MINIO_ROOT_USER:`$MINIO_ROOT_PASSWORD@127.0.0.1:9000; " +
        "mc rm --recursive --force --versions acceptance/$MinioBucket/chunks/$FileUuid/ >/dev/null 2>&1; " +
        "mc rm --force --versions acceptance/$MinioBucket/$FileUuid >/dev/null 2>&1 || true"
    & docker exec $MinioContainer sh -c $script | Out-Null
}

function Wait-AppReady {
    $deadline = (Get-Date).AddMinutes(4)
    do {
        try {
            $response = Invoke-JsonRequest GET "/api/site/public-settings"
            if($response.statusCode -eq 200) { return }
        } catch { }
        Start-Sleep -Seconds 2
    } while((Get-Date) -lt $deadline)
    throw "Application did not become ready after forced process exit"
}

$report = [ordered]@{
    runId = $runId
    generatedAt = [DateTimeOffset]::Now.ToString("o")
    status = "RUNNING"
    scenarios = [ordered]@{}
    cleanup = $null
}

try {
    $dbUser = Get-ContainerEnvironment "MYSQL_USER"
    $dbPassword = Get-ContainerEnvironment "MYSQL_PASSWORD"
    $dbName = Get-ContainerEnvironment "MYSQL_DATABASE"
    $password = "FI-$([guid]::NewGuid().ToString('N'))"
    $username = "$runId-user"
    Require-Success (Invoke-JsonRequest POST "/api/sign" @{ username=$username; password=$password; nickname="Multipart Fault Injection" }) "registration" | Out-Null
    $login = Require-Success (Invoke-JsonRequest POST "/api/login" @{ username=$username; password=$password }) "login"
    $token = $login.token
    $acceptanceUserId = Invoke-Sql "select user_id from users where username='$username' limit 1"

    $missing = New-Fixture "missing-chunk" 101
    Initialize-Fixture $missing $token | Out-Null
    Require-Success (Send-Chunk $missing.uploadId 0 $missing.chunkPaths[0] $missing.chunkMd5[0] $token) "missing-chunk first upload" | Out-Null
    $missingMerge = Invoke-JsonRequest POST "/api/file/multipart/merge" @{ uploadId=$missing.uploadId; fileName=$missing.fileName; partNames=@("server-resolves-parts") } $token
    $missingStatus = Require-Success (Invoke-JsonRequest GET "/api/file/multipart/status/$($missing.uploadId)" $null $token) "missing-chunk status"
    $missingDb = Invoke-Sql "select concat(status,',',uploaded_chunks,',',(select count(*) from file_info where file_uuid='$($missing.fileUuid)'),',',(select count(*) from user_file where file_uuid='$($missing.fileUuid)')) from upload_task where upload_id='$($missing.uploadId)'"
    Assert-True -Condition ($missingMerge.statusCode -eq 400 -and $missingMerge.body.code -eq 400 -and -not [string]::IsNullOrWhiteSpace($missingMerge.body.message)) -Message "Missing chunk merge was not rejected explicitly"
    Assert-True -Condition ($missingStatus.uploadedCount -eq 1 -and $missingStatus.uploadedChunks -contains 0) -Message "Missing chunk status lost the resumable part"
    Assert-True -Condition ($missingDb -eq "1,1,0,0") -Message "Missing chunk created metadata or changed task state: $missingDb"
    Assert-True -Condition ((Get-MinIoVersionCount $missing.fileUuid) -eq 0) -Message "Missing chunk unexpectedly created a final object"
    $report.scenarios.missingChunk = [ordered]@{ passed=$true; httpStatus=$missingMerge.statusCode; taskState=$missingDb; resumableChunks=$missingStatus.uploadedChunks }

    $digest = New-Fixture "wrong-digest" 202
    Initialize-Fixture $digest $token -WrongDigest | Out-Null
    Upload-AllChunks $digest $token
    $digestMerge = Invoke-JsonRequest POST "/api/file/multipart/merge" @{ uploadId=$digest.uploadId; fileName=$digest.fileName; partNames=@("server-resolves-parts") } $token
    $digestDb = Invoke-Sql "select concat(status,',',(select count(*) from file_info where file_uuid='$($digest.fileUuid)'),',',(select count(*) from user_file where file_uuid='$($digest.fileUuid)')) from upload_task where upload_id='$($digest.uploadId)'"
    Assert-True -Condition ($digestMerge.statusCode -eq 400 -and $digestMerge.body.code -eq 400 -and -not [string]::IsNullOrWhiteSpace($digestMerge.body.message)) -Message "Wrong whole-file digest was not rejected explicitly"
    Assert-True -Condition ($digestDb -eq "1,0,0") -Message "Wrong digest leaked metadata or committed the task: $digestDb"
    Assert-True -Condition ((Get-MinIoVersionCount $digest.fileUuid) -eq 0) -Message "Wrong digest left a final object version"
    $report.scenarios.wrongDigest = [ordered]@{ passed=$true; httpStatus=$digestMerge.statusCode; taskState=$digestDb; finalObjectVersions=0 }

    $concurrent = New-Fixture "concurrent-merge" 303
    Initialize-Fixture $concurrent $token | Out-Null
    Upload-AllChunks $concurrent $token
    $mergeResponses = @(Invoke-ConcurrentMerge $concurrent.uploadId $token)
    $unexpected = @($mergeResponses | Where-Object { $_.statusCode -notin @(200,409) })
    $successful = @($mergeResponses | Where-Object { $_.statusCode -eq 200 -and $_.body.code -eq 200 })
    $conflicts = @($mergeResponses | Where-Object { $_.statusCode -eq 409 })
    $successfulUuids = @($successful | ForEach-Object { $_.body.data.fileUuid } | Sort-Object -Unique)
    $concurrentDb = Invoke-Sql "select concat(status,',',(select count(*) from file_info where file_uuid='$($concurrent.fileUuid)'),',',(select count(*) from user_file where file_uuid='$($concurrent.fileUuid)' and user_id=$acceptanceUserId)) from upload_task where upload_id='$($concurrent.uploadId)'"
    Assert-True -Condition ($unexpected.Count -eq 0) -Message "Concurrent merge returned an unexpected status: $($unexpected.statusCode -join ',')"
    Assert-True -Condition ($successful.Count -ge 1 -and $successfulUuids.Count -eq 1 -and $successfulUuids[0] -eq $concurrent.fileUuid) -Message "Concurrent merge did not converge on one file"
    Assert-True -Condition ($concurrentDb -eq "2,1,1") -Message "Concurrent merge duplicated metadata: $concurrentDb"
    $report.scenarios.concurrentMerge = [ordered]@{ passed=$true; requests=$mergeResponses.Count; success=$successful.Count; conflict=$conflicts.Count; taskState=$concurrentDb; fileUuid=$concurrent.fileUuid }

    $power = New-Fixture "process-exit" 404
    Initialize-Fixture $power $token | Out-Null
    Require-Success (Send-Chunk $power.uploadId 0 $power.chunkPaths[0] $power.chunkMd5[0] $token) "process-exit first upload" | Out-Null
    & docker kill $AppContainer | Out-Null
    if($LASTEXITCODE -ne 0) { throw "Failed to kill $AppContainer" }
    Start-Sleep -Seconds 2
    & docker start $AppContainer | Out-Null
    if($LASTEXITCODE -ne 0) { throw "Failed to restart $AppContainer" }
    Wait-AppReady
    $resumed = Require-Success (Invoke-JsonRequest GET "/api/file/multipart/status/$($power.uploadId)" $null $token) "process-exit resume status"
    Assert-True -Condition ($resumed.uploadedCount -eq 1 -and $resumed.uploadedChunks -contains 0) -Message "Process exit lost the committed chunk"
    Require-Success (Send-Chunk $power.uploadId 1 $power.chunkPaths[1] $power.chunkMd5[1] $token) "process-exit resumed upload" | Out-Null
    $powerMerged = Require-Success (Invoke-JsonRequest POST "/api/file/multipart/merge" @{ uploadId=$power.uploadId; fileName=$power.fileName; partNames=@("server-resolves-parts") } $token) "process-exit merge"
    $powerDb = Invoke-Sql "select concat(status,',',(select count(*) from file_info where file_uuid='$($power.fileUuid)'),',',(select count(*) from user_file where file_uuid='$($power.fileUuid)' and user_id=$acceptanceUserId)) from upload_task where upload_id='$($power.uploadId)'"
    Assert-True -Condition ($powerDb -eq "2,1,1" -and $powerMerged.fileUuid -eq $power.fileUuid) -Message "Process-exit resume did not converge: $powerDb"
    $report.scenarios.processExit = [ordered]@{ passed=$true; resumedChunks=$resumed.uploadedChunks; taskState=$powerDb; fileUuid=$power.fileUuid }

    $rollback = New-Fixture "metadata-rollback" 505
    Initialize-Fixture $rollback $token | Out-Null
    Upload-AllChunks $rollback $token
    Invoke-Sql "insert into file_info(file_uuid,name,type,size,md5,sha1,hash,status,count,createtime,updatetime) values('$($rollback.fileUuid)','fault-placeholder','bin',1,null,null,'${runId}-placeholder',1,1,now(),now())" | Out-Null
    $rollbackMerge = Invoke-JsonRequest POST "/api/file/multipart/merge" @{ uploadId=$rollback.uploadId; fileName=$rollback.fileName; partNames=@("server-resolves-parts") } $token
    $rollbackDb = Invoke-Sql "select concat(status,',',(select count(*) from file_info where file_uuid='$($rollback.fileUuid)'),',',(select count(*) from user_file where file_uuid='$($rollback.fileUuid)')) from upload_task where upload_id='$($rollback.uploadId)'"
    Assert-True -Condition ($rollbackMerge.statusCode -eq 400 -and $rollbackMerge.body.code -eq 400 -and -not [string]::IsNullOrWhiteSpace($rollbackMerge.body.message)) -Message "Injected metadata failure was not surfaced"
    Assert-True -Condition ($rollbackDb -eq "1,1,0") -Message "Metadata transaction did not roll back atomically: $rollbackDb"
    Assert-True -Condition ((Get-MinIoVersionCount $rollback.fileUuid) -gt 0) -Message "Compose result was not retained for an idempotent retry"
    Invoke-Sql "delete from file_info where file_uuid='$($rollback.fileUuid)' and hash='${runId}-placeholder'" | Out-Null
    $rollbackRetry = Require-Success (Invoke-JsonRequest POST "/api/file/multipart/merge" @{ uploadId=$rollback.uploadId; fileName=$rollback.fileName; partNames=@("server-resolves-parts") } $token) "metadata rollback retry"
    $rollbackRecovered = Invoke-Sql "select concat(status,',',(select count(*) from file_info where file_uuid='$($rollback.fileUuid)'),',',(select count(*) from user_file where file_uuid='$($rollback.fileUuid)' and user_id=$acceptanceUserId)) from upload_task where upload_id='$($rollback.uploadId)'"
    Assert-True -Condition ($rollbackRecovered -eq "2,1,1" -and $rollbackRetry.fileUuid -eq $rollback.fileUuid) -Message "Metadata rollback retry did not converge: $rollbackRecovered"
    $report.scenarios.metadataRollback = [ordered]@{ passed=$true; failedState=$rollbackDb; recoveredState=$rollbackRecovered; reusedComposedObject=$true; fileUuid=$rollback.fileUuid }

    $report.status = "PASSED"
} catch {
    $report.status = "FAILED"
    $report.error = $_.Exception.Message
    throw
} finally {
    $cleanupErrors = @()
    foreach($fixture in $fixtures) {
        try {
            if($dbUser -and $fixture.uploadId) {
                Invoke-Sql "delete from user_file where file_uuid='$($fixture.fileUuid)'; delete from file_info where file_uuid='$($fixture.fileUuid)'; delete from upload_chunk where upload_id='$($fixture.uploadId)'; delete from upload_task where upload_id='$($fixture.uploadId)'" | Out-Null
            }
            Remove-MinIoFixture $fixture.fileUuid
        } catch { $cleanupErrors += "$($fixture.scenario): $($_.Exception.Message)" }
    }
    if($acceptanceUserId -match '^\d+$') {
        try {
            Invoke-Sql "delete src from space_rag_config src join spaces s on s.id=src.space_id where s.owner_id=$acceptanceUserId; delete sm from space_member sm join spaces s on s.id=sm.space_id where s.owner_id=$acceptanceUserId; delete sf from space_file sf join spaces s on s.id=sf.space_id where s.owner_id=$acceptanceUserId; delete from spaces where owner_id=$acceptanceUserId; delete from user_file where user_id=$acceptanceUserId; delete from users where user_id=$acceptanceUserId" | Out-Null
        } catch { $cleanupErrors += "acceptance-user: $($_.Exception.Message)" }
    }
    $report.cleanup = [ordered]@{ passed=$cleanupErrors.Count -eq 0; errors=$cleanupErrors }
    if($cleanupErrors.Count -gt 0 -and $report.status -eq "PASSED") { $report.status = "FAILED" }
    $report | ConvertTo-Json -Depth 12 | Set-Content -Encoding UTF8 $reportPath
    if((Resolve-Path $tempPath).Path.StartsWith((Resolve-Path $env:TEMP).Path,[StringComparison]::OrdinalIgnoreCase)) {
        Remove-Item -LiteralPath $tempPath -Recurse -Force -ErrorAction SilentlyContinue
    }
    $httpClient.Dispose()
    Remove-Variable token,password,dbPassword -ErrorAction SilentlyContinue
    Write-Output ($report | ConvertTo-Json -Depth 12)
    Write-Output "Multipart fault-injection report: $reportPath"
}
