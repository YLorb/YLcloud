param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [string]$Token = $env:YLCLOUD_E2E_TOKEN,
    [Parameter(Mandatory = $true)][long]$SpaceId,
    [string]$LinuxRoot = "D:\沐浴露\课程\linux",
    [string]$GeologyRoot = "D:\沐浴露\课程\地球科学概论",
    [string]$ManifestPath,
    [string]$QaPath,
    [string]$OutputRoot,
    [int]$TimeoutMinutes = 20,
    [int]$RequestTimeoutSeconds = 300,
    [string]$ExpectedAppContainerId,
    [ValidateSet("precise", "balanced", "broad")][string]$RetrievalMode = "balanced",
    [switch]$SkipUpload,
    [string]$UploadedFilesPath,
    [switch]$KeepGoing
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
$OutputEncoding = [Text.UTF8Encoding]::new($false)

$resolvedScriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
if([string]::IsNullOrWhiteSpace($resolvedScriptRoot)) {
    throw "Cannot resolve the E2E script directory"
}
if([string]::IsNullOrWhiteSpace($ManifestPath)) {
    $ManifestPath = Join-Path $resolvedScriptRoot "..\rag-testset\course-documents-v1.json"
}
if([string]::IsNullOrWhiteSpace($QaPath)) {
    $QaPath = Join-Path $resolvedScriptRoot "..\rag-testset\course-qa-v1.jsonl"
}
if([string]::IsNullOrWhiteSpace($OutputRoot)) {
    $OutputRoot = Join-Path $resolvedScriptRoot "..\output\rag-course-e2e"
}

function Get-AuthorizationValue {
    param([string]$RawToken)
    if([string]::IsNullOrWhiteSpace($RawToken)) {
        throw "Token cannot be empty"
    }
    if($RawToken -match "^Bearer\s+") {
        return $RawToken
    }
    return "Bearer $RawToken"
}

function ConvertTo-JsonText {
    param([object]$Value, [switch]$Compress)
    return $Value | ConvertTo-Json -Depth 30 -Compress:$Compress
}

function Write-JsonFile {
    param([string]$Path, [object]$Value)
    $parent = Split-Path -Parent $Path
    if($parent -and -not (Test-Path -LiteralPath $parent)) {
        New-Item -ItemType Directory -Path $parent | Out-Null
    }
    [IO.File]::WriteAllText($Path,(ConvertTo-JsonText $Value),[Text.UTF8Encoding]::new($false))
}

function Add-JsonLine {
    param([string]$Path, [object]$Value)
    $line = (ConvertTo-JsonText $Value -Compress) + [Environment]::NewLine
    [IO.File]::AppendAllText($Path,$line,[Text.UTF8Encoding]::new($false))
}

function Invoke-YlCloudEnvelope {
    param(
        [string]$Method,
        [string]$Path,
        [object]$Body = $null
    )
    Assert-AppContainerIdentity
    $uri = "$BaseUrl$Path"
    $headers = @{
        Authorization = $script:Authorization
        Accept = "application/json"
    }
    if($null -ne $Body) {
        $jsonText = ConvertTo-JsonText $Body -Compress
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($jsonText)
        $response = Invoke-WebRequest -Method $Method -Uri $uri -Headers $headers -ContentType "application/json; charset=utf-8" -Body $bytes -TimeoutSec $RequestTimeoutSeconds -UseBasicParsing
    } else {
        $response = Invoke-WebRequest -Method $Method -Uri $uri -Headers $headers -TimeoutSec $RequestTimeoutSeconds -UseBasicParsing
    }
    $content = [System.Text.Encoding]::UTF8.GetString($response.RawContentStream.ToArray())
    $result = $content | ConvertFrom-Json
    if($result.code -ne 200) {
        throw "$Method $Path failed: code=$($result.code), message=$($result.message)"
    }
    return $result
}

function Assert-AppContainerIdentity {
    if([string]::IsNullOrWhiteSpace($ExpectedAppContainerId)) { return }
    $actual = [string](& docker inspect ylcloud-app --format '{{.Id}}' 2>$null)
    if($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($actual)) {
        throw "TRANSPORT_ENVIRONMENT_CHANGED: ylcloud-app is not running"
    }
    if($actual.Trim() -ne $ExpectedAppContainerId.Trim()) {
        throw "TRANSPORT_ENVIRONMENT_CHANGED: ylcloud-app container identity changed"
    }
}

function New-SpaceFolder {
    param([string]$Name, [Nullable[long]]$ParentId)
    $body = @{ name = $Name }
    if($null -ne $ParentId) {
        $body.parentId = [long]$ParentId
    }
    return (Invoke-YlCloudEnvelope -Method "POST" -Path "/api/space/$SpaceId/files/folder" -Body $body).data
}

function Send-SpaceFile {
    param(
        [string]$FilePath,
        [long]$ParentId,
        [string]$Name,
        [string]$IdempotencyKey
    )
    Assert-AppContainerIdentity
    $arguments = @(
        "--silent", "--show-error", "--fail-with-body",
        "--max-time", [string]$RequestTimeoutSeconds,
        "--request", "POST", "$BaseUrl/api/space/$SpaceId/files/upload",
        "--header", "Authorization: $script:Authorization",
        "--header", "Accept: application/json",
        "--header", "Idempotency-Key: $IdempotencyKey",
        "--form", "file=@$FilePath",
        "--form", "parentId=$ParentId",
        "--form", "name=$Name"
    )
    $raw = & curl.exe @arguments
    if($LASTEXITCODE -ne 0) {
        throw "Multipart upload failed for $FilePath"
    }
    Assert-AppContainerIdentity
    $result = $raw | ConvertFrom-Json
    if($result.code -ne 200) {
        throw "Upload failed for $FilePath`: code=$($result.code), message=$($result.message)"
    }
    return $result
}

function Wait-RagDocument {
    param([long]$SpaceFileId)
    $deadline = (Get-Date).AddMinutes($TimeoutMinutes)
    do {
        $tasks = @((Invoke-YlCloudEnvelope -Method "GET" -Path "/api/space/$SpaceId/rag/tasks").data)
        $task = $tasks |
            Where-Object { [long]$_.spaceFileId -eq $SpaceFileId } |
            Sort-Object id -Descending |
            Select-Object -First 1
        if($task -and $task.taskStatus -eq "FAILED") {
            throw "RAG indexing failed for spaceFileId=$SpaceFileId`: $($task.errorMessage)"
        }
        if($task -and $task.taskStatus -eq "SUCCESS") {
            $documents = @((Invoke-YlCloudEnvelope -Method "GET" -Path "/api/space/$SpaceId/rag/documents").data)
            $document = $documents |
                Where-Object { [long]$_.spaceFileId -eq $SpaceFileId } |
                Select-Object -First 1
            if($document -and $document.indexStatus -eq "SUCCESS" -and [int]$document.chunkCount -gt 0) {
                return [ordered]@{ task=$task; document=$document }
            }
            if($document -and $document.indexStatus -eq "FAILED") {
                throw "RAG document failed for spaceFileId=$SpaceFileId`: $($document.errorMessage)"
            }
        }
        Start-Sleep -Seconds 3
    } while((Get-Date) -lt $deadline)
    throw "RAG indexing timed out for spaceFileId=$SpaceFileId"
}

function Resolve-SourcePath {
    param([object]$Document, [hashtable]$Roots)
    if(-not $Roots.ContainsKey([string]$Document.source_root)) {
        throw "Unknown source root '$($Document.source_root)' for $($Document.id)"
    }
    $root = [IO.Path]::GetFullPath([string]$Roots[[string]$Document.source_root])
    $candidate = [IO.Path]::GetFullPath((Join-Path $root ([string]$Document.relative_path)))
    $rootPrefix = $root.TrimEnd([IO.Path]::DirectorySeparatorChar,[IO.Path]::AltDirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    if(-not $candidate.StartsWith($rootPrefix,[StringComparison]::OrdinalIgnoreCase)) {
        throw "Source path escapes configured root: $candidate"
    }
    if(-not (Test-Path -LiteralPath $candidate -PathType Leaf)) {
        throw "Source document not found: $candidate"
    }
    $extension = [IO.Path]::GetExtension($candidate).ToLowerInvariant()
    if($extension -notin @(".pdf", ".pptx", ".docx", ".md")) {
        throw "Source type is not allowed by this document-only test: $candidate"
    }
    return $candidate
}

function Read-JsonLines {
    param([string]$Path)
    $items = @()
    $lineNumber = 0
    foreach($line in [IO.File]::ReadLines([IO.Path]::GetFullPath($Path),[Text.Encoding]::UTF8)) {
        $lineNumber++
        if([string]::IsNullOrWhiteSpace($line)) { continue }
        try {
            $items += ($line | ConvertFrom-Json)
        } catch {
            throw "Invalid JSONL at $Path line $lineNumber`: $($_.Exception.Message)"
        }
    }
    return $items
}

function Test-RequiredPoints {
    param([string]$Answer, [object[]]$Patterns)
    $results = [ordered]@{}
    foreach($patternValue in @($Patterns)) {
        $pattern = [string]$patternValue
        $results[$pattern] = [bool]($Answer -match $pattern)
    }
    return $results
}

function Get-DistinctCitationNames {
    param([object[]]$Citations)
    return @($Citations |
        ForEach-Object { [string]$_.fileName } |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
        Sort-Object -Unique)
}

$runId = "course_$((Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ'))_$([guid]::NewGuid().ToString('N').Substring(0,8))"
$script:Authorization = Get-AuthorizationValue $Token

$runDir = [IO.Path]::GetFullPath((Join-Path $OutputRoot $runId))
$rawDir = Join-Path $runDir "raw-responses"
$uploadResultsPath = Join-Path $runDir "upload-results.jsonl"
$qaResultsPath = Join-Path $runDir "qa-results.jsonl"
$uploadedFilesOutputPath = Join-Path $runDir "uploaded-files.json"
New-Item -ItemType Directory -Path $rawDir -Force | Out-Null

$manifestFullPath = [IO.Path]::GetFullPath($ManifestPath)
$qaFullPath = [IO.Path]::GetFullPath($QaPath)
if(-not (Test-Path -LiteralPath $manifestFullPath -PathType Leaf)) { throw "Manifest not found: $manifestFullPath" }
if(-not (Test-Path -LiteralPath $qaFullPath -PathType Leaf)) { throw "QA dataset not found: $qaFullPath" }

$manifest = Get-Content -LiteralPath $manifestFullPath -Raw -Encoding UTF8 | ConvertFrom-Json
$qaCases = @(Read-JsonLines $qaFullPath)
$roots = @{
    linux = [IO.Path]::GetFullPath($LinuxRoot)
    geology = [IO.Path]::GetFullPath($GeologyRoot)
}

$runMetadata = [ordered]@{
    runId = $runId
    startedAt = (Get-Date).ToUniversalTime().ToString("o")
    baseUrl = $BaseUrl
    spaceId = $SpaceId
    manifestPath = $manifestFullPath
    qaPath = $qaFullPath
    linuxRoot = $roots.linux
    geologyRoot = $roots.geology
    retrievalMode = $RetrievalMode
    skipUpload = [bool]$SkipUpload
    tokenPersisted = $false
}
Write-JsonFile (Join-Path $runDir "run.json") $runMetadata

$uploadedFiles = @()
$uploadFailureCount = 0
$indexFailureCount = 0

if($SkipUpload) {
    if([string]::IsNullOrWhiteSpace($UploadedFilesPath)) {
        throw "-UploadedFilesPath is required with -SkipUpload"
    }
    $existingMapping = Get-Content -LiteralPath ([IO.Path]::GetFullPath($UploadedFilesPath)) -Raw -Encoding UTF8 | ConvertFrom-Json
    $uploadedFiles = @($existingMapping.files)
    if($uploadedFiles.Count -lt 1) {
        throw "Uploaded file mapping contains no files: $UploadedFilesPath"
    }
    Write-JsonFile $uploadedFilesOutputPath ([ordered]@{ runId=$runId; reusedFrom=[IO.Path]::GetFullPath($UploadedFilesPath); files=$uploadedFiles })
} else {
    $preflightPaths = @{}
    $documentIds = @($manifest.documents | ForEach-Object { [string]$_.id })
    if(@($documentIds | Sort-Object -Unique).Count -ne $documentIds.Count) {
        throw "Document manifest contains duplicate ids"
    }
    foreach($document in @($manifest.documents)) {
        if(([string]$document.id) -notmatch "^[A-Za-z0-9_-]+$") {
            throw "Unsafe document id in manifest: $($document.id)"
        }
        $preflightPaths[[string]$document.id] = Resolve-SourcePath $document $roots
    }

    $runFolder = New-SpaceFolder -Name "course-e2e-$runId" -ParentId $null
    $linuxFolder = New-SpaceFolder -Name "linux" -ParentId ([long]$runFolder.id)
    $geologyFolder = New-SpaceFolder -Name "geology" -ParentId ([long]$runFolder.id)
    $folderIds = @{ linux=[long]$linuxFolder.id; geology=[long]$geologyFolder.id }

    foreach($document in @($manifest.documents)) {
        $started = Get-Date
        $stage = "source-validation"
        try {
            $sourcePath = [string]$preflightPaths[[string]$document.id]
            $hash = (Get-FileHash -LiteralPath $sourcePath -Algorithm SHA256).Hash.ToLowerInvariant()
            $sourceFile = Get-Item -LiteralPath $sourcePath
            $idempotencyKey = "course-e2e-$runId-$($document.id)"
            $stage = "upload"
            $envelope = Send-SpaceFile -FilePath $sourcePath -ParentId $folderIds[[string]$document.category] -Name ([string]$document.expected_name) -IdempotencyKey $idempotencyKey
            $spaceFile = $envelope.data
            $stage = "index"
            $rag = Wait-RagDocument -SpaceFileId ([long]$spaceFile.id)
            $entry = [ordered]@{
                id = [string]$document.id
                category = [string]$document.category
                sourcePath = $sourcePath
                sourceSize = [long]$sourceFile.Length
                sourceSha256 = $hash
                spaceFileId = [long]$spaceFile.id
                fileUuid = [string]$spaceFile.fileUuid
                uploadedName = [string]$spaceFile.name
                parentId = [long]$spaceFile.parentId
                ragTaskId = [long]$rag.task.id
                ragStatus = [string]$rag.task.taskStatus
                documentId = [long]$rag.document.id
                indexStatus = [string]$rag.document.indexStatus
                chunkCount = [int]$rag.document.chunkCount
            }
            $uploadedFiles += [pscustomobject]$entry
            Add-JsonLine $uploadResultsPath ([ordered]@{
                id=$document.id
                passed=$true
                durationMs=[math]::Round(((Get-Date)-$started).TotalMilliseconds)
                result=$entry
            })
            Write-Host "[$($document.id)] uploaded and indexed: spaceFileId=$($spaceFile.id), chunks=$($rag.document.chunkCount)"
        } catch {
            if($stage -eq "index") {
                $indexFailureCount++
            } else {
                $uploadFailureCount++
            }
            Add-JsonLine $uploadResultsPath ([ordered]@{
                id=$document.id
                stage=$stage
                passed=$false
                durationMs=[math]::Round(((Get-Date)-$started).TotalMilliseconds)
                error=$_.Exception.Message
            })
            Write-Error "[$($document.id)] $($_.Exception.Message)" -ErrorAction Continue
            if(-not $KeepGoing) { throw }
        }
    }
    Write-JsonFile $uploadedFilesOutputPath ([ordered]@{
        runId=$runId
        spaceId=$SpaceId
        runFolderId=[long]$runFolder.id
        files=$uploadedFiles
    })
}

$qaFailureCount = 0
$manualReviewCount = 0
$qaPassedCount = 0

foreach($case in $qaCases) {
    $started = Get-Date
    try {
        if(([string]$case.id) -notmatch "^[A-Za-z0-9_-]+$") {
            throw "Unsafe QA case id: $($case.id)"
        }
        $envelope = Invoke-YlCloudEnvelope -Method "POST" -Path "/api/space/$SpaceId/rag/query" -Body @{
            question = [string]$case.question
            retrievalMode = $RetrievalMode
            history = @()
        }
        Write-JsonFile (Join-Path $rawDir "$($case.id).json") $envelope
        $data = $envelope.data
        $answer = [string]$data.answer
        $citations = @($data.citations)
        $contexts = @($data.contexts)
        $hitChunkIds = @($data.hitChunkIds)
        $retrievedChunkIds = @($data.retrievedChunkIds)
        $actualSources = @(Get-DistinctCitationNames $citations)
        $pointResults = Test-RequiredPoints $answer @($case.required_point_patterns)
        $requiredPointsPassed = -not (@($pointResults.Values) -contains $false)

        if([bool]$case.answerable) {
            $answerabilityPassed = -not [string]::IsNullOrWhiteSpace($answer)
            $matchedExpectedSources = @($actualSources | Where-Object { $_ -in @($case.expected_sources) })
            $citationPassed = $matchedExpectedSources.Count -ge [int]$case.min_expected_sources
            $evidencePassed = $citations.Count -gt 0 -and $contexts.Count -gt 0 -and $hitChunkIds.Count -gt 0
        } else {
            $answerabilityPassed = $citations.Count -eq 0 -and $contexts.Count -eq 0 -and $hitChunkIds.Count -eq 0
            $citationPassed = $citations.Count -eq 0
            $evidencePassed = $contexts.Count -eq 0 -and $hitChunkIds.Count -eq 0
        }

        $automaticPassed = $answerabilityPassed -and $requiredPointsPassed -and $citationPassed -and $evidencePassed
        $manualReviewRequired = [bool]$case.manual_review
        if($manualReviewRequired) { $manualReviewCount++ }
        if($automaticPassed) { $qaPassedCount++ } else { $qaFailureCount++ }

        $result = [ordered]@{
            runId = $runId
            caseId = [string]$case.id
            type = [string]$case.type
            question = [string]$case.question
            answerable = [bool]$case.answerable
            answer = $answer
            requiredPointResults = $pointResults
            expectedSources = @($case.expected_sources)
            actualSources = $actualSources
            citationCount = $citations.Count
            contextCount = $contexts.Count
            hitChunkCount = $hitChunkIds.Count
            retrievedChunkCount = $retrievedChunkIds.Count
            rewriteDurationMs = $data.rewriteDurationMs
            retrievalDurationMs = $data.retrievalDurationMs
            generationDurationMs = $data.generationDurationMs
            serverTotalDurationMs = $data.totalDurationMs
            answerabilityPassed = $answerabilityPassed
            requiredPointsPassed = $requiredPointsPassed
            citationPassed = $citationPassed
            evidencePassed = $evidencePassed
            automaticPassed = $automaticPassed
            manualReviewRequired = $manualReviewRequired
            durationMs = [math]::Round(((Get-Date)-$started).TotalMilliseconds)
        }
        Add-JsonLine $qaResultsPath $result
        Write-Host "[$($case.id)] automaticPassed=$automaticPassed manualReviewRequired=$manualReviewRequired"
        if(-not $automaticPassed -and -not $KeepGoing) {
            break
        }
    } catch {
        $qaFailureCount++
        $failureKind = if($_.Exception.Message -like 'TRANSPORT_ENVIRONMENT_CHANGED:*') {
            'TRANSPORT_ENVIRONMENT_CHANGED'
        } elseif($_.Exception.Message -match 'sending the request|timed out|connection|transport') {
            'TRANSPORT_FAILURE'
        } else {
            'BUSINESS_OR_ASSERTION_FAILURE'
        }
        Add-JsonLine $qaResultsPath ([ordered]@{
            runId=$runId
            caseId=[string]$case.id
            question=[string]$case.question
            automaticPassed=$false
            manualReviewRequired=[bool]$case.manual_review
            failureKind=$failureKind
            durationMs=[math]::Round(((Get-Date)-$started).TotalMilliseconds)
            error=$_.Exception.Message
        })
        Write-Error "[$($case.id)] $($_.Exception.Message)" -ErrorAction Continue
        if(-not $KeepGoing) { break }
    }
}

$summary = [ordered]@{
    runId = $runId
    finishedAt = (Get-Date).ToUniversalTime().ToString("o")
    spaceId = $SpaceId
    documentCount = @($manifest.documents).Count
    uploadedOrReusedCount = $uploadedFiles.Count
    uploadFailureCount = $uploadFailureCount
    indexFailureCount = $indexFailureCount
    qaCaseCount = $qaCases.Count
    qaPassedCount = $qaPassedCount
    qaFailureCount = $qaFailureCount
    manualReviewCount = $manualReviewCount
    automaticSuccess = ($uploadFailureCount -eq 0 -and $indexFailureCount -eq 0 -and $qaFailureCount -eq 0)
    gateStatus = if($uploadFailureCount -gt 0 -or $indexFailureCount -gt 0 -or $qaFailureCount -gt 0) {
        "FAILED"
    } elseif($manualReviewCount -gt 0) {
        "MANUAL_REVIEW_REQUIRED"
    } else {
        "PASSED"
    }
    outputDirectory = $runDir
}
Write-JsonFile (Join-Path $runDir "summary.json") $summary
ConvertTo-JsonText $summary

Remove-Variable Token -ErrorAction SilentlyContinue
$script:Authorization = $null
if(-not $summary.automaticSuccess) {
    exit 1
}
