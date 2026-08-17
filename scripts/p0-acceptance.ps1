param(
    [string]$BaseUrl = "http://127.0.0.1:5173",
    [string]$MysqlContainer = "ylcloud-mysql",
    [string]$MinioContainer = "ylcloud-minio",
    [string]$AppContainer = "ylcloud-app",
    [string]$QdrantBaseUrl = "http://127.0.0.1:6333",
    [string]$MinioBucket,
    [string]$QdrantCollection
)

$ErrorActionPreference = "Stop"

function Get-AppEnvironment([string]$Name, [string]$Fallback) {
    $value = (& docker exec $AppContainer printenv $Name 2>$null | Out-String).Trim()
    if($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($value)) {
        return $Fallback
    }
    return $value
}

if([string]::IsNullOrWhiteSpace($MinioBucket)) {
    $MinioBucket = Get-AppEnvironment "YLCLOUD_MINIO_BUCKET" "localbucket1"
}
if([string]::IsNullOrWhiteSpace($QdrantCollection)) {
    $QdrantCollection = Get-AppEnvironment "YLCLOUD_RAG_QDRANT_COLLECTION_NAME" "ylcloud_rag_bge_small_zh_v15"
}
if($MinioBucket -notmatch "^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$") {
    throw "Invalid MinIO bucket: $MinioBucket"
}
if($QdrantCollection -notmatch "^[A-Za-z0-9_-]+$") {
    throw "Invalid Qdrant collection: $QdrantCollection"
}

function Invoke-Api {
    param([string]$Method,[string]$Path,[object]$Body,[string]$Token)
    $headers = @{ Accept = "application/json" }
    if($Token) { $headers.Authorization = "Bearer $Token" }
    $request = @{ Uri = "$BaseUrl$Path"; Method = $Method; Headers = $headers; UseBasicParsing = $true }
    if($null -ne $Body) {
        $request.Body = $Body | ConvertTo-Json -Depth 10 -Compress
        $request.ContentType = "application/json; charset=utf-8"
    }
    $response = Invoke-WebRequest @request
    $result = $response.Content | ConvertFrom-Json
    if($result.code -ne 200) { throw "$Method $Path failed: $($result.message)" }
    return $result.data
}

function Send-File {
    param([string]$Path,[string]$FilePath,[string]$Token,[hashtable]$Fields = @{})
    $args = @("--silent","--show-error","--fail-with-body","--request","POST","$BaseUrl$Path",
        "--header","Authorization: Bearer $Token","--form","file=@$FilePath")
    foreach($key in $Fields.Keys) { $args += @("--form","$key=$($Fields[$key])") }
    $result = (& curl.exe @args) | ConvertFrom-Json
    if($result.code -ne 200) { throw "POST $Path failed: $($result.message)" }
    return $result.data
}

function Wait-RagTask {
    param([long]$SpaceId,[long]$SpaceFileId,[string]$Token,[long]$AfterTaskId = 0)
    $deadline = (Get-Date).AddMinutes(5)
    do {
        $task = @(Invoke-Api GET "/api/space/$SpaceId/rag/tasks" $null $Token) |
            Where-Object { $_.spaceFileId -eq $SpaceFileId -and $_.id -gt $AfterTaskId } |
            Sort-Object id -Descending | Select-Object -First 1
        if($task -and $task.taskStatus -in @("SUCCESS","FAILED")) { return $task }
        Start-Sleep -Seconds 2
    } while((Get-Date) -lt $deadline)
    throw "RAG task timed out"
}

function Wait-KnowledgeTask {
    param([long]$SpaceId,[long]$TaskId,[string]$Token)
    $deadline = (Get-Date).AddMinutes(5)
    do {
        $task = @(Invoke-Api GET "/api/space/$SpaceId/knowledge/pipeline/tasks" $null $Token) |
            Where-Object { $_.id -eq $TaskId } | Select-Object -First 1
        if($task -and $task.taskStatus -in @("SUCCESS","FAILED","PARTIAL_SUCCESS")) { return $task }
        Start-Sleep -Seconds 2
    } while((Get-Date) -lt $deadline)
    throw "Knowledge task timed out: $TaskId"
}

function Invoke-Sql([string]$Query) {
    $dbUser = (& docker exec $MysqlContainer printenv MYSQL_USER | Out-String).Trim()
    $dbPassword = (& docker exec $MysqlContainer printenv MYSQL_PASSWORD | Out-String).Trim()
    $datasourceUrl = Get-AppEnvironment "SPRING_DATASOURCE_URL" ""
    if($datasourceUrl -match '^jdbc:mysql://[^/]+/([^?;]+)') {
        $dbName = $Matches[1]
    } else {
        $dbName = (& docker exec $MysqlContainer printenv MYSQL_DATABASE | Out-String).Trim()
    }
    if($dbName -notmatch '^[A-Za-z0-9_]+$') { throw "Invalid MySQL database: $dbName" }
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $output = & docker exec $MysqlContainer mysql --batch --skip-column-names "-u$dbUser" "-p$dbPassword" $dbName -e $Query 2>$null
    $exitCode = $LASTEXITCODE
    $ErrorActionPreference = $previousPreference
    Remove-Variable dbPassword -ErrorAction SilentlyContinue
    if($exitCode -ne 0) { throw "MySQL acceptance query failed" }
    return ($output | Out-String).Trim()
}

function Remove-SpaceFile([long]$SpaceId,[long]$FileId,[string]$Token) {
    $preview = Invoke-Api POST "/api/space/$SpaceId/files/$FileId/deletion-preview" $null $Token
    return Invoke-Api DELETE "/api/space/$SpaceId/files/$FileId" @{
        confirmationName = $preview.name
        confirmationToken = $preview.confirmationToken
        expectedVersion = $preview.nodeVersion
        expiresAtEpochSecond = $preview.expiresAtEpochSecond
    } $Token
}

function Get-MinIoVersionCount([string]$ObjectName) {
    $script = 'MC_HOST_acceptance=http://$MINIO_ROOT_USER:$MINIO_ROOT_PASSWORD@127.0.0.1:9000 mc ls --versions --recursive acceptance/' + $MinioBucket
    return @(& docker exec $MinioContainer sh -c $script |
        Where-Object { $_ -match [regex]::Escape($ObjectName) }).Count
}

function Get-LatestRagTaskId([long]$SpaceId,[long]$SpaceFileId,[string]$Token) {
    $task = @(Invoke-Api GET "/api/space/$SpaceId/rag/tasks" $null $Token) |
        Where-Object { $_.spaceFileId -eq $SpaceFileId } |
        Sort-Object id -Descending |
        Select-Object -First 1
    if($task) { return [long]$task.id }
    return [long]0
}

function Get-QdrantCount([long]$SpaceFileId) {
    $body = @{ filter = @{ must = @(@{ key = "spaceFileId"; match = @{ value = $SpaceFileId } }) }; exact = $true } |
        ConvertTo-Json -Depth 8 -Compress
    $response = Invoke-RestMethod -Method Post -Uri "$QdrantBaseUrl/collections/$QdrantCollection/points/count" -ContentType "application/json" -Body $body
    return [int]$response.result.count
}

function New-MinimalDocx([string]$Path,[string]$Text,[string]$Root) {
    $rels = Join-Path $Root "_rels"
    $word = Join-Path $Root "word"
    New-Item -ItemType Directory -Force -Path $rels,$word | Out-Null
    [IO.File]::WriteAllText((Join-Path $Root "[Content_Types].xml"),
        '<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>')
    [IO.File]::WriteAllText((Join-Path $rels ".rels"),
        '<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>')
    $document = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>' + $Text + '</w:t></w:r></w:p><w:sectPr/></w:body></w:document>'
    [IO.File]::WriteAllText((Join-Path $word "document.xml"),$document)
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [IO.Compression.ZipFile]::CreateFromDirectory($Root,$Path)
}

$runId = "p0_$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())_$([guid]::NewGuid().ToString('N').Substring(0,6))"
$username = $runId
$password = "P0-$([guid]::NewGuid().ToString('N'))"
$tokenPhrase = "DOCX-UNIQUE-$($runId.ToUpperInvariant())"
$temp = Join-Path $env:TEMP $runId
$docxRoot = Join-Path $temp "docx"
$docxPath = Join-Path $temp "$runId.docx"
$textPath = Join-Path $temp "$runId.txt"
New-Item -ItemType Directory -Force -Path $temp | Out-Null
New-MinimalDocx $docxPath "The unique acceptance token is $tokenPhrase." $docxRoot
[IO.File]::WriteAllText($textPath,"Recycle acceptance $runId",[Text.UTF8Encoding]::new($false))

try {
    Invoke-Api POST "/api/sign" @{ username=$username; password=$password; nickname="P0 Acceptance" } $null | Out-Null
    $login = Invoke-Api POST "/api/login" @{ username=$username; password=$password } $null
    $token = $login.token
    $space = @(Invoke-Api GET "/api/space/list" $null $token) | Where-Object { $_.role -eq "OWNER" } | Select-Object -First 1
    $defaultRagConfig = Invoke-Api GET "/api/space/$($space.id)/rag/config" $null $token
    $knowledgeProfileDefaultEnabled = [int]$defaultRagConfig.knowledgeProfileEnabled -eq 1

    $docx = Send-File "/api/space/$($space.id)/files/upload" $docxPath $token @{ name="$runId.docx" }
    $ragTask = Wait-RagTask $space.id $docx.id $token
    if($ragTask.taskStatus -ne "SUCCESS") { throw "DOCX RAG failed: $($ragTask.errorMessage)" }
    $document = @(Invoke-Api GET "/api/space/$($space.id)/rag/documents" $null $token) |
        Where-Object { $_.spaceFileId -eq $docx.id } | Select-Object -First 1
    if(-not $document -or $document.chunkCount -lt 1) { throw "DOCX produced no chunks" }
    $initialVectorState = Invoke-Sql "select concat(index_status,',',vector_state,',',chunk_count) from space_rag_document where id=$($document.id)"
    $vectorStateActive = $initialVectorState -match '^SUCCESS,ACTIVE,[1-9][0-9]*$'
    $docxParser = Invoke-Sql "select parser from file_rag_parse_result where file_uuid='$($docx.fileUuid)' and status=1 order by id desc limit 1"
    if($docxParser -ne "docx-structured") { throw "Unexpected DOCX parser: $docxParser" }
    $query = Invoke-Api POST "/api/space/$($space.id)/rag/query" @{ question="Return this exact token from the document: $tokenPhrase"; retrievalMode="precise"; history=@() } $token
    $docxQueryHit = ((($query.contexts -join "`n") + "`n" + $query.answer) -match [regex]::Escape($tokenPhrase))

    $initialKnowledge = $null
    $deadline = (Get-Date).AddMinutes(5)
    do {
        $initialKnowledge = @(Invoke-Api GET "/api/space/$($space.id)/knowledge/pipeline/tasks" $null $token) |
            Where-Object { $_.documentId -eq $document.id } | Sort-Object id -Descending | Select-Object -First 1
        if($initialKnowledge -and $initialKnowledge.taskStatus -in @("SUCCESS","FAILED")) { break }
        Start-Sleep -Seconds 2
    } while((Get-Date) -lt $deadline)
    if(-not $initialKnowledge -or $initialKnowledge.taskStatus -ne "SUCCESS") { throw "Initial knowledge task failed" }
    $initialKnowledgeCountsValid = [int]$initialKnowledge.totalCount -eq 1 -and
        [int]$initialKnowledge.successCount -eq 1 -and [int]$initialKnowledge.failedCount -eq 0
    $initialProfile = Invoke-Api GET "/api/space/$($space.id)/knowledge/documents/$($document.id)/profile" $null $token
    $initialEvents = @(Invoke-Api GET "/api/space/$($space.id)/knowledge/pipeline/tasks/$($initialKnowledge.id)/events" $null $token)
    $initialEventStages = @($initialEvents.stage | Sort-Object -Unique)
    $requiredEventStages = @("LOAD_CHUNKS","CHECK_INCREMENTAL","GENERATE_PROFILE","SAVE_PROFILE")
    $eventStagesComplete = @($requiredEventStages | Where-Object { $initialEventStages -notcontains $_ }).Count -eq 0

    Invoke-Api PUT "/api/space/$($space.id)/rag/config" @{ knowledgeProfileEnabled=0 } $token | Out-Null
    $disabledKnowledgeBaseline = [long]$initialKnowledge.id
    $disabledRagBaseline = Get-LatestRagTaskId $space.id $docx.id $token
    Invoke-Api POST "/api/space/$($space.id)/rag/files/$($docx.id)/rebuild" $null $token | Out-Null
    $disabledRagTask = Wait-RagTask $space.id $docx.id $token $disabledRagBaseline
    Start-Sleep -Seconds 2
    $disabledKnowledgeTask = @(Invoke-Api GET "/api/space/$($space.id)/knowledge/pipeline/tasks" $null $token) |
        Where-Object { $_.documentId -eq $document.id -and $_.id -gt $disabledKnowledgeBaseline } |
        Select-Object -First 1
    $profileDisabledSkipped = $disabledRagTask.taskStatus -eq "SUCCESS" -and
        $disabledKnowledgeTask.taskStatus -eq "SKIPPED" -and
        $disabledKnowledgeTask.terminalReason -eq "PROFILE_SKIPPED_DISABLED"
    Invoke-Api PUT "/api/space/$($space.id)/rag/config" @{ knowledgeProfileEnabled=1 } $token | Out-Null

    Invoke-Sql "update space_knowledge_document_profile set source_chunk_count=999, source_snapshot_signature=null, source_snapshot_revision=0 where space_id=$($space.id) and document_id=$($document.id)" | Out-Null
    $rebuildBaseline = Get-LatestRagTaskId $space.id $docx.id $token
    Invoke-Api POST "/api/space/$($space.id)/rag/files/$($docx.id)/rebuild" $null $token | Out-Null
    $rebuiltRagTask = Wait-RagTask $space.id $docx.id $token $rebuildBaseline
    if($rebuiltRagTask.taskStatus -ne "SUCCESS") { throw "DOCX rebuild failed" }
    $deadline = (Get-Date).AddMinutes(5)
    do {
        $refreshTask = @(Invoke-Api GET "/api/space/$($space.id)/knowledge/pipeline/tasks" $null $token) |
            Where-Object { $_.documentId -eq $document.id -and $_.id -gt $initialKnowledge.id } |
            Sort-Object id -Descending | Select-Object -First 1
        if($refreshTask -and $refreshTask.taskStatus -in @("SUCCESS","FAILED","PARTIAL_SUCCESS")) { break }
        Start-Sleep -Seconds 2
    } while((Get-Date) -lt $deadline)
    if(-not $refreshTask) { throw "Source snapshot sync task was not submitted" }
    $profile = Invoke-Api GET "/api/space/$($space.id)/knowledge/documents/$($document.id)/profile" $null $token
    $sourceSyncPreservedProfile = $profile.title -eq $initialProfile.title -and
        $profile.summary -eq $initialProfile.summary -and $profile.profileVersion -eq $initialProfile.profileVersion

    $revisionAfterSync = [long]$profile.sourceSnapshotRevision
    $secondRebuildBaseline = Get-LatestRagTaskId $space.id $docx.id $token
    Invoke-Api POST "/api/space/$($space.id)/rag/files/$($docx.id)/rebuild" $null $token | Out-Null
    $secondRagTask = Wait-RagTask $space.id $docx.id $token $secondRebuildBaseline
    if($secondRagTask.taskStatus -ne "SUCCESS") { throw "Second DOCX rebuild failed" }
    $deadline = (Get-Date).AddMinutes(5)
    do {
        $idempotentTask = @(Invoke-Api GET "/api/space/$($space.id)/knowledge/pipeline/tasks" $null $token) |
            Where-Object { $_.documentId -eq $document.id -and $_.id -gt $refreshTask.id } |
            Sort-Object id -Descending | Select-Object -First 1
        if($idempotentTask -and $idempotentTask.taskStatus -in @("SUCCESS","FAILED","PARTIAL_SUCCESS")) { break }
        Start-Sleep -Seconds 2
    } while((Get-Date) -lt $deadline)
    if(-not $idempotentTask) { throw "Idempotent knowledge task was not submitted" }
    $profileAfterIdempotentRun = Invoke-Api GET "/api/space/$($space.id)/knowledge/documents/$($document.id)/profile" $null $token
    $idempotentRevisionStable = [long]$profileAfterIdempotentRun.sourceSnapshotRevision -eq $revisionAfterSync

    $manualCategory = "acceptance-$runId"
    $profileOnlyToken = "PROFILE-ONLY-$($runId.ToUpperInvariant())"
    $classified = Invoke-Api PUT "/api/space/$($space.id)/knowledge/documents/$($document.id)/classify" @{
        title=$profileAfterIdempotentRun.title
        summary="$profileOnlyToken profile management metadata"
        category=$manualCategory
        tags=@("acceptance","manual")
        keywords=@("pipeline","release")
        questions=@("What is $profileOnlyToken?")
        profileStatus="VALID"
    } $token
    $categoryFacets = @(Invoke-Api GET "/api/space/$($space.id)/knowledge/facets/categories" $null $token)
    $manualCategoryVisible = @($categoryFacets | Where-Object { $_.name -eq $manualCategory -and $_.count -ge 1 }).Count -eq 1
    $reviewed = Invoke-Api POST "/api/space/$($space.id)/knowledge/documents/$($document.id)/reviewed" $null $token
    $auditActions = Invoke-Sql "select group_concat(distinct action order by action separator ',') from space_knowledge_audit_log where space_id=$($space.id) and resource_id=$($classified.id)"
    $auditComplete = $auditActions -match 'PROFILE_EDIT'

    $ownerId = Invoke-Sql "select user_id from users where username='$username' limit 1"
    $retryMarker = "acceptance-retry-$runId"
    Invoke-Sql "insert into space_knowledge_pipeline_task(space_id,document_id,task_type,task_status,stage,progress,total_count,success_count,failed_count,error_message,force_rebuild,incremental_detail,created_by,createtime,updatetime) values($($space.id),$($document.id),'PROFILE_DOCUMENT','FAILED','FAILED',0,1,0,1,'acceptance injected failure',0,'$retryMarker',$ownerId,now(),now())" | Out-Null
    $failedTaskId = Invoke-Sql "select id from space_knowledge_pipeline_task where incremental_detail='$retryMarker' order by id desc limit 1"
    $retrySubmission = Invoke-Api POST "/api/space/$($space.id)/knowledge/pipeline/tasks/$failedTaskId/retry" $null $token
    $retriedTask = Wait-KnowledgeTask $space.id $retrySubmission.id $token

    $legacyMarker = "acceptance-legacy-$runId"
    Invoke-Sql "insert into space_knowledge_pipeline_task(space_id,document_id,task_type,task_status,stage,progress,total_count,success_count,failed_count,force_rebuild,terminal_stage,terminal_reason,incremental_action,incremental_detail,created_by,finished_time,createtime,updatetime) values($($space.id),$($document.id),'PROFILE_DOCUMENT','SUCCESS','BUILD_RETRIEVAL_ENHANCEMENT',100,1,1,0,0,'BUILD_RETRIEVAL_ENHANCEMENT','RETRIEVAL_ONLY','REBUILD_RETRIEVAL_ONLY','$legacyMarker',$ownerId,now(),now(),now())" | Out-Null
    $legacyTask = @(Invoke-Api GET "/api/space/$($space.id)/knowledge/pipeline/tasks" $null $token) |
        Where-Object { $_.incrementalDetail -eq $legacyMarker } | Select-Object -First 1
    $legacyTaskReadable = $legacyTask -and $legacyTask.incrementalAction -eq "REBUILD_RETRIEVAL_ONLY"

    $asyncTasks = @(Invoke-Api GET "/api/async?spaceId=$($space.id)" $null $token)
    $asyncSources = @($asyncTasks.source | Sort-Object -Unique)
    $asyncTypesValid = @($asyncTasks | Where-Object { $_.type -match 'UPLOAD|CHUNK' }).Count -eq 0

    $ordinary = Send-File "/api/file/upload" $textPath $token @{ parentId=0 }
    $versionsBeforeRecycle = Get-MinIoVersionCount $ordinary.fileUuid
    Invoke-Api DELETE "/api/file/$($ordinary.fileUuid)?parentId=$($ordinary.parentId)" $null $token | Out-Null
    $recycle = @(Invoke-Api GET "/api/file/recycle" $null $token) | Where-Object { $_.fileUuid -eq $ordinary.fileUuid } | Select-Object -First 1
    $versionsInRecycle = Get-MinIoVersionCount $ordinary.fileUuid
    Invoke-Api PUT "/api/file/recycle/$($recycle.fileId)/restore" $null $token | Out-Null
    $versionsAfterRestore = Get-MinIoVersionCount $ordinary.fileUuid
    Invoke-Api DELETE "/api/file/$($ordinary.fileUuid)?parentId=$($ordinary.parentId)" $null $token | Out-Null
    $recycle = @(Invoke-Api GET "/api/file/recycle" $null $token) | Where-Object { $_.fileUuid -eq $ordinary.fileUuid } | Select-Object -First 1
    Invoke-Api DELETE "/api/file/recycle/$($recycle.fileId)" $null $token | Out-Null

    $deadline = (Get-Date).AddMinutes(2)
    do {
        $personalCleanupStatus = Invoke-Sql "select task_status from physical_file_cleanup_task where file_uuid='$($ordinary.fileUuid)'"
        if($personalCleanupStatus -eq "SUCCESS") { break }
        Start-Sleep -Seconds 1
    } while((Get-Date) -lt $deadline)
    $personalVersionsAfterPurge = Get-MinIoVersionCount $ordinary.fileUuid

    $qdrantBefore = Get-QdrantCount $docx.id
    $docxVersionsBefore = Get-MinIoVersionCount $docx.fileUuid
    Remove-SpaceFile $space.id $docx.id $token | Out-Null
    $deadline = (Get-Date).AddMinutes(2)
    do {
        $spaceCleanupStatus = Invoke-Sql "select task_status from physical_file_cleanup_task where file_uuid='$($docx.fileUuid)'"
        if($spaceCleanupStatus -eq "SUCCESS") { break }
        Start-Sleep -Seconds 1
    } while((Get-Date) -lt $deadline)
    $qdrantAfter = Get-QdrantCount $docx.id
    $docxVersionsAfter = Get-MinIoVersionCount $docx.fileUuid
    $dbState = Invoke-Sql "select concat((select count(*) from file_info where file_uuid='$($docx.fileUuid)'),',',(select count(*) from space_rag_chunk_ref where space_file_id=$($docx.id) and status=1),',',(select count(*) from file_rag_chunk where file_uuid='$($docx.fileUuid)' and status=1))"

    $passwordLeaked = [bool](& docker logs $AppContainer 2>&1 | Select-String -SimpleMatch $password)
    $success = $ragTask.taskStatus -eq "SUCCESS" -and $docxParser -eq "docx-structured" -and $docxQueryHit -and
        $knowledgeProfileDefaultEnabled -and $initialKnowledgeCountsValid -and $profileDisabledSkipped -and $vectorStateActive -and
        $refreshTask.taskStatus -eq "SUCCESS" -and $refreshTask.incrementalAction -eq "SYNC_RETRIEVAL_SOURCE" -and
        $refreshTask.terminalReason -eq "SOURCE_SNAPSHOT_SYNCED" -and $profile.sourceChunkCount -eq $document.chunkCount -and
        -not [string]::IsNullOrWhiteSpace($profile.sourceSnapshotSignature) -and
        $profile.sourceSnapshotRevision -eq 1 -and $sourceSyncPreservedProfile -and
        $idempotentTask.taskStatus -eq "SUCCESS" -and $idempotentTask.incrementalAction -eq "SKIP_PROFILE" -and
        $idempotentTask.terminalReason -eq "UNCHANGED_DOCUMENT" -and $idempotentRevisionStable -and
        $eventStagesComplete -and $manualCategoryVisible -and $reviewed.profileStatus -eq "VALID" -and
        $reviewed.reviewStatus -eq "APPROVED" -and $auditComplete -and
        $retriedTask.taskStatus -eq "SUCCESS" -and $legacyTaskReadable -and
        $asyncSources -contains "rag" -and $asyncSources -contains "knowledge" -and $asyncTypesValid -and
        $versionsBeforeRecycle -gt 0 -and $versionsInRecycle -eq $versionsBeforeRecycle -and
        $versionsAfterRestore -eq $versionsBeforeRecycle -and $personalCleanupStatus -eq "SUCCESS" -and
        $personalVersionsAfterPurge -eq 0 -and $qdrantBefore -gt 0 -and $qdrantAfter -eq 0 -and
        $docxVersionsBefore -gt 0 -and $docxVersionsAfter -eq 0 -and $dbState -eq "0,0,0" -and -not $passwordLeaked

    [ordered]@{
        runId=$runId; docxRagStatus=$ragTask.taskStatus; docxParser=$docxParser; docxChunkCount=$document.chunkCount; docxQueryHit=$docxQueryHit
        knowledgeProfile=@{ defaultEnabled=$knowledgeProfileDefaultEnabled; initialCountsValid=$initialKnowledgeCountsValid; disabledSkipped=$profileDisabledSkipped }
        vectorState=@{ databaseState=$initialVectorState; active=$vectorStateActive }
        sourceSyncAction=$refreshTask.incrementalAction; sourceChunkCount=$profile.sourceChunkCount
        sourceSnapshotRevision=$profile.sourceSnapshotRevision; sourceSnapshotSignature=$profile.sourceSnapshotSignature
        sourceSyncPreservedProfile=$sourceSyncPreservedProfile
        idempotentAction=$idempotentTask.incrementalAction; idempotentRevisionStable=$idempotentRevisionStable
        initialEventStages=$initialEventStages; eventStagesComplete=$eventStagesComplete
        manualCategoryVisible=$manualCategoryVisible; reviewedStatus=$reviewed.reviewStatus; auditActions=$auditActions
        retriedTaskStatus=$retriedTask.taskStatus; legacyTaskReadable=[bool]$legacyTaskReadable
        asyncSources=$asyncSources; asyncTypesValid=$asyncTypesValid
        recycleVersions=@{ before=$versionsBeforeRecycle; inRecycle=$versionsInRecycle; afterRestore=$versionsAfterRestore; afterPurge=$personalVersionsAfterPurge }
        spaceDelete=@{ qdrantBefore=$qdrantBefore; qdrantAfter=$qdrantAfter; minioBefore=$docxVersionsBefore; minioAfter=$docxVersionsAfter; dbState=$dbState }
        passwordLeaked=$passwordLeaked; success=$success
    } | ConvertTo-Json -Depth 6
    if(-not $success) { throw "P0 acceptance assertions failed" }
} finally {
    if($token -and $space -and $docx) {
        try { Remove-SpaceFile $space.id $docx.id $token | Out-Null } catch { }
    }
    Remove-Item -LiteralPath $temp -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Variable password,token -ErrorAction SilentlyContinue
}
