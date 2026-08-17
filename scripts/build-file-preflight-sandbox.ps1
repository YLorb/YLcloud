$ErrorActionPreference = "Stop"
$imageName = "ylcloud-file-preflight:local"
docker build --tag $imageName "$PSScriptRoot/../sandbox-tools/file-preflight"
if ($LASTEXITCODE -ne 0) { throw "file.preflight image build failed" }
$imageId = (docker image inspect $imageName --format '{{.Id}}').Trim()
if (-not $imageId.StartsWith("sha256:")) { throw "Docker did not return an immutable image id" }
$catalog = @(
    @{
        name = "file.preflight"
        version = "1.0.0"
        image = $imageId
        entrypoint = @("python", "/app/tool.py")
        network_policy = "none"
        input_schema = @{
            type = "object"
            additionalProperties = $false
            required = @("fileName", "sha256", "size", "contentBase64")
            properties = @{
                fileName = @{ type = "string"; minLength = 1; maxLength = 255 }
                sha256 = @{ type = "string"; pattern = "^[a-f0-9]{64}$" }
                size = @{ type = "integer"; minimum = 0 }
                contentBase64 = @{ type = "string"; minLength = 1 }
            }
        }
        output_schema = @{
            type = "object"
            additionalProperties = $false
            required = @("safe", "reasons")
            properties = @{
                safe = @{ type = "boolean" }
                reasons = @{ type = "array"; items = @{ type = "string" } }
            }
        }
        limits = @{ cpus = 1.0; memory_bytes = 536870912; pids = 32; tmpfs_bytes = 1073741824; timeout_seconds = 120; max_result_bytes = 65536 }
    }
)
$catalogPath = Join-Path $PSScriptRoot "../sandbox-service/tool-catalog.local.json"
$catalogJson = ConvertTo-Json -InputObject $catalog -Depth 12
[System.IO.File]::WriteAllText($catalogPath, $catalogJson, [System.Text.UTF8Encoding]::new($false))
Write-Host "Pinned file.preflight catalog written to $catalogPath ($imageId)"
