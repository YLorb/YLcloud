param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [Parameter(Mandatory = $true)][string]$Token,
    [Parameter(Mandatory = $true)][long]$SpaceId,
    [Parameter(Mandatory = $true)][long]$SpaceFileId,
    [string]$Question = "请概括这个文档的主要内容",
    [string]$QdrantBaseUrl = "http://127.0.0.1:6333",
    [string]$QdrantCollection = "ylcloud_rag_bge_small_zh_v15",
    [int]$TimeoutMinutes = 12
)

$ErrorActionPreference = "Stop"
$headers = @{
    Authorization = $Token
    "Content-Type" = "application/json"
}

function Invoke-YlCloudJson {
    param([string]$Method, [string]$Path, [object]$Body = $null)
    $request = @{ Method=$Method; Uri="$BaseUrl$Path"; Headers=$headers }
    if($null -ne $Body) {
        $request.Body = $Body | ConvertTo-Json -Depth 8
    }
    $result = Invoke-RestMethod @request
    if($result.code -ne 200) {
        throw "$Method $Path failed: $($result.message)"
    }
    return $result.data
}

function Get-FileTasks {
    return @(Invoke-YlCloudJson -Method Get -Path "/api/space/$SpaceId/rag/tasks") |
        Where-Object { [long]$_.spaceFileId -eq $SpaceFileId }
}

function Get-MaxTaskId {
    $latest = Get-FileTasks | Sort-Object id -Descending | Select-Object -First 1
    if($null -eq $latest) { return [long]0 }
    return [long]$latest.id
}

function Wait-NewFileTask([long]$AfterTaskId, [string]$Operation) {
    $deadline = (Get-Date).AddMinutes($TimeoutMinutes)
    do {
        $task = Get-FileTasks |
            Where-Object { [long]$_.id -gt $AfterTaskId } |
            Sort-Object id -Descending |
            Select-Object -First 1
        if($task) {
            Write-Host "$Operation task $($task.id): $($task.taskStatus) $($task.successCount)/$($task.totalCount)"
            if($task.taskStatus -eq "SUCCESS") { return $task }
            if($task.taskStatus -eq "FAILED") { throw "$Operation failed: $($task.errorMessage)" }
        }
        Start-Sleep -Seconds 3
    } while((Get-Date) -lt $deadline)
    throw "$Operation timed out without a newer task for spaceFileId=$SpaceFileId"
}

function Assert-DocumentReady {
    $document = @(Invoke-YlCloudJson -Method Get -Path "/api/space/$SpaceId/rag/documents") |
        Where-Object { [long]$_.spaceFileId -eq $SpaceFileId } |
        Select-Object -First 1
    if($null -eq $document) {
        throw "RAG document was not created"
    }
    if($document.indexStatus -ne "SUCCESS" -or [int]$document.chunkCount -lt 1) {
        throw "RAG document is not ready: status=$($document.indexStatus), chunkCount=$($document.chunkCount), error=$($document.errorMessage)"
    }
    return $document
}

function Get-QdrantPointCount {
    if($QdrantCollection -notmatch "^[A-Za-z0-9_-]+$") {
        throw "Invalid Qdrant collection: $QdrantCollection"
    }
    $body = @{
        filter = @{ must = @(@{ key="spaceFileId"; match=@{ value=$SpaceFileId } }) }
        exact = $true
    } | ConvertTo-Json -Depth 8 -Compress
    $result = Invoke-RestMethod -Method Post -Uri "$QdrantBaseUrl/collections/$QdrantCollection/points/count" -ContentType "application/json" -Body $body
    return [int]$result.result.count
}

$rebuildBaseline = Get-MaxTaskId
Write-Host "Rebuilding file RAG index after task $rebuildBaseline..."
Invoke-YlCloudJson -Method Post -Path "/api/space/$SpaceId/rag/files/$SpaceFileId/rebuild" | Out-Null
$rebuildTask = Wait-NewFileTask $rebuildBaseline "RAG rebuild"
$document = Assert-DocumentReady
$pointCount = Get-QdrantPointCount
if($pointCount -lt 1) {
    throw "RAG rebuild reported success but Qdrant contains no points for spaceFileId=$SpaceFileId"
}

Write-Host "Querying RAG..."
$query = Invoke-YlCloudJson -Method Post -Path "/api/space/$SpaceId/rag/query" -Body @{ question=$Question; topK=5 }
if([string]::IsNullOrWhiteSpace($query.answer)) {
    throw "RAG query returned an empty answer"
}
if(-not $query.citations -or $query.citations.Count -lt 1) {
    throw "RAG query returned no citations"
}

$repairBaseline = Get-MaxTaskId
Write-Host "Repairing vectors after task $repairBaseline..."
Invoke-YlCloudJson -Method Post -Path "/api/space/$SpaceId/rag/files/$SpaceFileId/vectors/repair" | Out-Null
$repairTask = Wait-NewFileTask $repairBaseline "Vector repair"
$document = Assert-DocumentReady
$repairedPointCount = Get-QdrantPointCount
if($repairedPointCount -lt 1) {
    throw "Vector repair reported success but Qdrant contains no points"
}

[ordered]@{
    success = $true
    spaceId = $SpaceId
    spaceFileId = $SpaceFileId
    rebuildTaskId = [long]$rebuildTask.id
    repairTaskId = [long]$repairTask.id
    chunkCount = [int]$document.chunkCount
    qdrantPointCount = $repairedPointCount
    citationCount = $query.citations.Count
} | ConvertTo-Json -Depth 6
