param(
    [string]$BaseUrl = "http://127.0.0.1:8080"
)

$ErrorActionPreference = "Stop"
$suffix = (Get-Date -Format "yyyyMMddHHmmss") + (Get-Random -Minimum 100 -Maximum 999)
$password = "P1!" + [Guid]::NewGuid().ToString("N")
$owner = "p1owner$suffix"
$outsider = "p1other$suffix"
$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) "ylcloud-p1-$suffix"
[System.IO.Directory]::CreateDirectory($tempDir) | Out-Null
$v1Path = Join-Path $tempDir "p1-version.txt"
$v2Path = Join-Path $tempDir "p1-version-v2.txt"
[System.IO.File]::WriteAllText($v1Path,"P1 initial version $suffix",[System.Text.Encoding]::UTF8)
[System.IO.File]::WriteAllText($v2Path,"P1 second version $suffix",[System.Text.Encoding]::UTF8)
$ownerToken = $null
$spaceId = $null

function Invoke-JsonApi {
    param([string]$Method,[string]$Path,[object]$Body,[string]$Token)
    $headers = @{}
    if ($Token) { $headers["Authorization"] = "Bearer $Token" }
    $params = @{
        Method = $Method
        Uri = "$BaseUrl$Path"
        Headers = $headers
        ContentType = "application/json"
    }
    if ($null -ne $Body) { $params["Body"] = ($Body | ConvertTo-Json -Compress) }
    return Invoke-RestMethod @params
}

function Get-HttpStatus {
    param([string]$Method,[string]$Path,[object]$Body,[string]$Token)
    $headers = @{}
    if ($Token) { $headers["Authorization"] = "Bearer $Token" }
    try {
        $params = @{
            Method = $Method
            Uri = "$BaseUrl$Path"
            Headers = $headers
            UseBasicParsing = $true
        }
        if ($null -ne $Body) {
            $params["ContentType"] = "application/json"
            $params["Body"] = ($Body | ConvertTo-Json -Compress)
        }
        $response = Invoke-WebRequest @params
        return [int]$response.StatusCode
    } catch {
        if ($_.Exception.Response -and $_.Exception.Response.StatusCode) {
            return [int]$_.Exception.Response.StatusCode
        }
        throw
    }
}

function Upload-File {
    param([long]$SpaceId,[string]$Path,[string]$Name,[string]$Token,[string]$VersionPath)
    $url = if ($VersionPath) {
        "$BaseUrl/api/space/$SpaceId/files/$VersionPath/versions"
    } else {
        "$BaseUrl/api/space/$SpaceId/files/upload"
    }
    $args = @("-sS","-X","POST","-H","Authorization: Bearer $Token","-F","file=@$Path;type=text/plain")
    if (-not $VersionPath) { $args += @("-F","name=$Name") }
    $args += $url
    $raw = & curl.exe @args
    if ($LASTEXITCODE -ne 0) { throw "curl upload failed" }
    $result = $raw | ConvertFrom-Json
    if ($result.code -ne 200) { throw "upload failed: code=$($result.code), message=$($result.message)" }
    return $result
}

function Upload-And-GetStatus {
    param([long]$SpaceId,[string]$Path,[string]$Name,[string]$Token)
    $bodyFile = Join-Path $tempDir "duplicate-response.json"
    $status = & curl.exe -sS -o $bodyFile -w "%{http_code}" -X POST `
        -H "Authorization: Bearer $Token" -F "file=@$Path;type=text/plain" -F "name=$Name" `
        "$BaseUrl/api/space/$SpaceId/files/upload"
    if ($LASTEXITCODE -ne 0) { throw "curl duplicate upload failed" }
    return [int]$status
}

try {
    foreach ($username in @($owner,$outsider)) {
        $signup = Invoke-JsonApi -Method POST -Path "/api/sign" -Body @{
            username = $username
            password = $password
            nickname = $username
        }
        if ($signup.code -ne 200) { throw "signup failed for acceptance user" }
    }

    $ownerLogin = Invoke-JsonApi -Method POST -Path "/api/login" -Body @{username=$owner;password=$password}
    $otherLogin = Invoke-JsonApi -Method POST -Path "/api/login" -Body @{username=$outsider;password=$password}
    $ownerToken = $ownerLogin.data.token
    $otherToken = $otherLogin.data.token

    $spaceResponse = Invoke-JsonApi -Method POST -Path "/api/space" -Body @{
        name = "P1 deployment acceptance $suffix"
        description = "Automated P1 deployment acceptance"
    } -Token $ownerToken
    $spaceId = [long]$spaceResponse.data.id

    $upload = Upload-File -SpaceId $spaceId -Path $v1Path -Name "p1-version-$suffix.txt" -Token $ownerToken
    $fileId = [long]$upload.data.id
    $initialVersions = Invoke-JsonApi -Method GET -Path "/api/space/$spaceId/files/$fileId/versions" -Body $null -Token $ownerToken
    if ($initialVersions.data.Count -ne 1 -or $initialVersions.data[0].versionNo -ne 1) {
        throw "initial version invariant failed"
    }

    $updated = Upload-File -SpaceId $spaceId -Path $v2Path -Name "" -Token $ownerToken -VersionPath "$fileId"
    if ($updated.data.versionNo -ne 2) { throw "first update did not create version 2" }
    $versions = Invoke-JsonApi -Method GET -Path "/api/space/$spaceId/files/$fileId/versions" -Body $null -Token $ownerToken
    $versionNumbers = @($versions.data | ForEach-Object { [int]$_.versionNo })
    if ($versionNumbers.Count -ne 2 -or $versionNumbers[0] -ne 2 -or $versionNumbers[1] -ne 1) {
        throw "version history is not [2,1]"
    }

    $result = [ordered]@{
        services = "healthy"
        initialVersion = $initialVersions.data[0].versionNo
        firstUpdateVersion = $updated.data.versionNo
        versionHistory = $versionNumbers
        http400 = Get-HttpStatus -Method POST -Path "/api/space" -Body @{name=""} -Token $ownerToken
        http401 = Get-HttpStatus -Method GET -Path "/api/space/list" -Body $null -Token $null
        http403 = Get-HttpStatus -Method GET -Path "/api/space/$spaceId/files/tree" -Body $null -Token $otherToken
        http404 = Get-HttpStatus -Method GET -Path "/api/space/$spaceId/files/999999999/versions" -Body $null -Token $ownerToken
        http409 = Upload-And-GetStatus -SpaceId $spaceId -Path $v2Path -Name "p1-version-v2.txt" -Token $ownerToken
    }
    foreach ($expected in @(400,401,403,404,409)) {
        if ($result["http$expected"] -ne $expected) {
            throw "expected HTTP $expected but got $($result["http$expected"])"
        }
    }
    $result | ConvertTo-Json -Compress
} finally {
    if($ownerToken -and $spaceId) {
        try {
            Invoke-JsonApi -Method DELETE -Path "/api/space/$spaceId" -Body $null -Token $ownerToken | Out-Null
        } catch {
            Write-Warning "P1 space cleanup failed: $($_.Exception.Message)"
        }
    }
    if ($tempDir.StartsWith([System.IO.Path]::GetTempPath(),[System.StringComparison]::OrdinalIgnoreCase)) {
        Remove-Item -LiteralPath $tempDir -Recurse -Force -ErrorAction SilentlyContinue
    }
}
