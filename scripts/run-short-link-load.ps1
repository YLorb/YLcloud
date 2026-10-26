param(
    [string]$Concurrency = '1,8,32,64,128',
    [ValidateRange(1,120)][int]$Seconds = 10,
    [string]$OutputDirectory = 'output/short-link-load',
    [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
$createdContainers = [System.Collections.Generic.List[string]]::new()

function Invoke-Docker {
    & docker @args
    if ($LASTEXITCODE -ne 0) { throw 'Docker operation failed; existing containers are not modified.' }
}

Push-Location $repoRoot
try {
    $names = @('ylcloud-short-link-load-mysql','ylcloud-short-link-load-redis','ylcloud-short-link-load-app')
    $existing = @(& docker ps -a --format '{{.Names}}')
    if ($LASTEXITCODE -ne 0) { throw 'Docker is not available.' }
    foreach ($name in $names) {
        if ($existing -contains $name) { throw "Test container already exists: $name. Finish its run first." }
    }
    if (-not $SkipBuild) {
        & mvn -B -ntp -pl src/cloud-server -am package -DskipTests
        if ($LASTEXITCODE -ne 0) { throw 'Backend build failed.' }
    }
    $jar = (Resolve-Path 'src/cloud-server/target/cloud-server-1.0-SNAPSHOT.jar').Path
    Invoke-Docker run -d --rm --name $names[0] -p 127.0.0.1:19316:3306 -e MYSQL_ROOT_PASSWORD=short-link-test-only -e MYSQL_DATABASE=short_link_test mysql:8.4
    $createdContainers.Add($names[0])
    Invoke-Docker run -d --rm --name $names[1] -p 127.0.0.1:16389:6379 redis:7.4.7-alpine redis-server --maxmemory 128mb --maxmemory-policy noeviction
    $createdContainers.Add($names[1])
    $databaseReady = $false
    for ($attempt = 0; $attempt -lt 120; $attempt++) {
        & docker exec -e MYSQL_PWD=short-link-test-only $names[0] mysql -uroot -e 'SELECT 1' 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) { $databaseReady = $true; break }
        Start-Sleep -Milliseconds 500
    }
    if (-not $databaseReady) { throw 'Test MySQL did not become ready.' }
    Invoke-Docker run -d --rm --name $names[2] -p 127.0.0.1:18090:8080 -p 127.0.0.1:19081:9081 `
        -e 'SPRING_DATASOURCE_URL=jdbc:mysql://host.docker.internal:19316/short_link_test?allowPublicKeyRetrieval=true' `
        -e SPRING_DATASOURCE_USERNAME=root -e SPRING_DATASOURCE_PASSWORD=short-link-test-only `
        -e YLCLOUD_REDIS_HOST=host.docker.internal -e YLCLOUD_REDIS_PORT=16389 -e YLCLOUD_SHORT_LINK_NODE_ID=27 `
        -e YLCLOUD_ASYNC_MQ_ENABLED=false -e YLCLOUD_SESSION_COOKIE_SECURE=false `
        --mount "type=bind,source=$jar,target=/app/app.jar,readonly" `
        eclipse-temurin:17-jre java -Xms256m -Xmx768m -jar /app/app.jar `
        --ylcloud.minio.init-enabled=false --ylcloud.rag.vector-enabled=false --ylcloud.rag.qdrant.init-enabled=false `
        --ylcloud.llm.enabled=false --ylcloud.rag.chat.enabled=false --spring.task.scheduling.pool.size=4 `
        --management.server.port=9081 --management.server.address=0.0.0.0 --management.endpoints.web.exposure.include=health,metrics `
        --management.health.rabbit.enabled=false --management.health.redis.enabled=false
    $createdContainers.Add($names[2])
    $ready = $false
    for ($attempt = 0; $attempt -lt 240; $attempt++) {
        try {
            $response = Invoke-WebRequest 'http://127.0.0.1:18090/api/site/public-settings' -TimeoutSec 2
            if ($response.StatusCode -eq 200) { $ready = $true; break }
        } catch { }
        Start-Sleep -Milliseconds 500
    }
    if (-not $ready) {
        & docker logs --tail 60 $names[2]
        throw 'Isolated backend did not become ready.'
    }
    & python scripts/short-link-load-local.py --concurrency $Concurrency --seconds $Seconds --output-dir $OutputDirectory
    if ($LASTEXITCODE -ne 0) { throw 'Load fixture or test failed.' }
} finally {
    # Only containers successfully created by this run are stopped; --rm removes their test data.
    for ($index = $createdContainers.Count - 1; $index -ge 0; $index--) {
        & docker stop $createdContainers[$index] | Out-Null
    }
    Pop-Location
}
