$ErrorActionPreference = 'Continue'
$workspace = 'D:\document\javaproj\ylcloud'
$log = Join-Path $workspace 'sandbox-docker-validation.log'
$resultFile = Join-Path $workspace 'sandbox-docker-validation-result.json'
$deps = Join-Path $workspace '.sandbox-e2e-deps'
$catalog = Join-Path $workspace 'sandbox-service\tool-catalog.local.json'
$python = 'C:\Users\Win10\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
$serviceProcess = $null
$catalogExisted = Test-Path -LiteralPath $catalog -PathType Leaf
$catalogBackup = if ($catalogExisted) { [System.IO.File]::ReadAllBytes($catalog) } else { $null }

Set-Location $workspace
if (Test-Path -LiteralPath $resultFile) { Remove-Item -LiteralPath $resultFile -Force }
"started=$(Get-Date -Format o)" | Set-Content -LiteralPath $log -Encoding UTF8
"identity=$([System.Security.Principal.WindowsIdentity]::GetCurrent().Name)" | Add-Content -LiteralPath $log -Encoding UTF8

try {
    docker version 2>&1 | Add-Content -LiteralPath $log -Encoding UTF8
    docker build -t ylcloud-sandbox-json-echo:test sandbox-tools\json-echo 2>&1 | Add-Content -LiteralPath $log -Encoding UTF8
    if ($LASTEXITCODE -ne 0) { throw 'json-echo image build failed' }
    $imageId = (docker image inspect ylcloud-sandbox-json-echo:test --format '{{.Id}}' 2>$null).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'json-echo image inspect failed' }
    if (-not $imageId.StartsWith('sha256:')) { throw 'immutable image id unavailable' }

    $catalogValue = @(@{
        name = 'data.json.echo'
        version = '1.0.0'
        image = $imageId
        entrypoint = @('python', '/app/tool.py')
        input_schema = @{ type = 'object'; required = @('value'); properties = @{ value = @{} }; additionalProperties = $false }
        output_schema = @{ type = 'object'; required = @('value'); properties = @{ value = @{} }; additionalProperties = $false }
        limits = @{ cpus = 1.0; memory_bytes = 536870912; pids = 64; tmpfs_bytes = 67108864; timeout_seconds = 60; max_result_bytes = 1048576 }
    })
    ConvertTo-Json -InputObject $catalogValue -Depth 12 | Set-Content -LiteralPath $catalog -Encoding UTF8

    if (Test-Path -LiteralPath $deps) { Remove-Item -LiteralPath $deps -Recurse -Force }
    & $python -m pip install --target $deps $workspace\sandbox-service 2>&1 | Add-Content -LiteralPath $log -Encoding UTF8
    if ($LASTEXITCODE -ne 0) { throw 'sandbox service dependencies failed' }

    $env:PYTHONPATH = $deps
    $env:SANDBOX_ENABLED = 'true'
    $env:SANDBOX_SERVICE_TOKEN = 'win10-sandbox-validation-token-0001'
    $env:SANDBOX_TOOL_CATALOG_FILE = $catalog
    $serviceProcess = Start-Process -FilePath $python -ArgumentList '-m','uvicorn','sandbox_service.api:app','--host','127.0.0.1','--port','18004' -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $workspace 'sandbox-service-validation.stdout.log') -RedirectStandardError (Join-Path $workspace 'sandbox-service-validation.stderr.log')

    $ready = $false
    for ($i = 0; $i -lt 30; $i++) {
        Start-Sleep -Seconds 1
        try { $health = Invoke-RestMethod -Uri 'http://127.0.0.1:18004/health' -TimeoutSec 2 -ErrorAction Stop; if ($health.status -eq 'UP') { $ready = $true; break } } catch {}
    }
    if (-not $ready) { throw 'sandbox service did not become ready' }

    $headers = @{ Authorization = 'Bearer win10-sandbox-validation-token-0001' }
    $body = @{
        contract_version = '1.0'; invocation_id = 'win10-e2e-001'; idempotency_key = 'win10-e2e-idempotency-001'
        tool_name = 'data.json.echo'; tool_version = '1.0.0'; arguments = @{ value = 'sandbox-ok' }
        parent_trace = @{ trace_id = ('a' * 32); span_id = ('b' * 16) }; subject_id = 'user:win10'; timeout_seconds = 60
    } | ConvertTo-Json -Depth 8
    $response = Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:18004/internal/v1/invocations' -Headers $headers -ContentType 'application/json' -Body $body -TimeoutSec 90 -ErrorAction Stop
    if ($response.status -ne 'SUCCEEDED' -or $response.result.value -ne 'sandbox-ok') { throw 'sandbox invocation failed' }

    $replay = Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:18004/internal/v1/invocations' -Headers $headers -ContentType 'application/json' -Body $body -TimeoutSec 10 -ErrorAction Stop
    if ($replay.span.span_id -ne $response.span.span_id) { throw 'idempotent replay produced a new span' }
    $history = Invoke-RestMethod -Uri 'http://127.0.0.1:18004/internal/v1/invocations?subject_id=user%3Awin10' -Headers $headers -TimeoutSec 10 -ErrorAction Stop
    if (@($history).Count -ne 1) { throw 'subject history scope failed' }

    $security = docker create --name ylcloud-sandbox-security-check --network none --read-only --user 65532:65532 --cap-drop ALL --security-opt no-new-privileges=true --pids-limit 64 --cpus 1 --memory 536870912 --memory-swap 536870912 --tmpfs /tmp:rw,noexec,nosuid,nodev,size=67108864 --entrypoint python $imageId -c "import os,socket; assert os.getuid()==65532; assert not os.access('/',os.W_OK); assert socket.socket().connect_ex(('1.1.1.1',53)) != 0"
    if ($LASTEXITCODE -ne 0) { throw 'security check container create failed' }
    $inspect = docker inspect ylcloud-sandbox-security-check | ConvertFrom-Json
    $hostConfig = $inspect[0].HostConfig
    if ($hostConfig.NetworkMode -ne 'none' -or -not $hostConfig.ReadonlyRootfs -or $hostConfig.PidsLimit -ne 64 -or $hostConfig.CapDrop -notcontains 'ALL') { throw 'container security configuration mismatch' }
    docker rm ylcloud-sandbox-security-check | Out-Null

    @{ status = 'PASS'; identity = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name; imageId = $imageId; invocationStatus = $response.status; spanId = $response.span.span_id; dockerSecurityConfig = 'PASS'; completedAt = (Get-Date -Format o) } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $resultFile -Encoding UTF8
    'validation=PASS' | Add-Content -LiteralPath $log -Encoding UTF8
}
catch {
    @{ status = 'FAIL'; identity = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name; error = $_.Exception.Message; completedAt = (Get-Date -Format o) } | ConvertTo-Json | Set-Content -LiteralPath $resultFile -Encoding UTF8
    "validation=FAIL error=$($_.Exception.Message)" | Add-Content -LiteralPath $log -Encoding UTF8
}
finally {
    if ($serviceProcess -and -not $serviceProcess.HasExited) { Stop-Process -Id $serviceProcess.Id -Force }
    docker rm --force ylcloud-sandbox-security-check 2>$null | Out-Null
    if (Test-Path -LiteralPath $deps) { Remove-Item -LiteralPath $deps -Recurse -Force }
    if ($catalogExisted) {
        [System.IO.File]::WriteAllBytes($catalog, $catalogBackup)
    }
    elseif (Test-Path -LiteralPath $catalog -PathType Leaf) {
        Remove-Item -LiteralPath $catalog -Force
    }
}
