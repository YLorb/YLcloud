param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [Parameter(Mandatory = $true)][string]$Token,
    [Parameter(Mandatory = $true)][long]$SpaceId,
    [Parameter(Mandatory = $true)][long]$SpaceFileId,
    [string]$Question = "请概括这个文档的主要内容"
)

$ErrorActionPreference = "Stop"
$headers = @{
    "Authorization" = $Token
    "Content-Type" = "application/json"
}

function Invoke-YlCloudJson {
    param(
        [string]$Method,
        [string]$Path,
        [object]$Body = $null
    )
    $uri = "$BaseUrl$Path"
    if($null -eq $Body) {
        return Invoke-RestMethod -Method $Method -Uri $uri -Headers $headers
    }
    return Invoke-RestMethod -Method $Method -Uri $uri -Headers $headers -Body ($Body | ConvertTo-Json -Depth 8)
}

Write-Host "Rebuilding file RAG index..."
Invoke-YlCloudJson -Method Post -Path "/api/space/$SpaceId/rag/files/$SpaceFileId/rebuild" | Out-Null

Write-Host "Polling RAG tasks..."
$deadline = (Get-Date).AddMinutes(5)
do {
    Start-Sleep -Seconds 3
    $tasks = Invoke-YlCloudJson -Method Get -Path "/api/space/$SpaceId/rag/tasks"
    $latest = $tasks.data | Select-Object -First 1
    Write-Host "Latest task:" $latest.taskType $latest.taskStatus "$($latest.successCount)/$($latest.totalCount)"
    if($latest.taskStatus -eq "SUCCESS") { break }
    if($latest.taskStatus -eq "FAILED") { throw "RAG task failed: $($latest.errorMessage)" }
} while((Get-Date) -lt $deadline)

if($latest.taskStatus -ne "SUCCESS") {
    throw "Timed out waiting for RAG indexing"
}

Write-Host "Querying RAG..."
$query = Invoke-YlCloudJson -Method Post -Path "/api/space/$SpaceId/rag/query" -Body @{ question = $Question; topK = 5 }
if(-not $query.data.answer) {
    throw "RAG query returned empty answer"
}
if(-not $query.data.citations -or $query.data.citations.Count -lt 1) {
    throw "RAG query returned no citations"
}

Write-Host "Repairing Qdrant vectors..."
Invoke-YlCloudJson -Method Post -Path "/api/space/$SpaceId/rag/vectors/repair" | Out-Null

Write-Host "RAG E2E smoke check submitted successfully."
