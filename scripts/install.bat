@echo off
setlocal EnableExtensions DisableDelayedExpansion

cd /d "%~dp0.."
set "YLCLOUD_QUICK_MODE=%~1"
if not defined YLCLOUD_QUICK_MODE set "YLCLOUD_QUICK_MODE=up"

if /I "%YLCLOUD_QUICK_MODE%"=="-h" goto :help
if /I "%YLCLOUD_QUICK_MODE%"=="--help" goto :help
if /I "%YLCLOUD_QUICK_MODE%"=="up" goto :mode_ok
if /I "%YLCLOUD_QUICK_MODE%"=="--check" goto :mode_ok
if /I "%YLCLOUD_QUICK_MODE%"=="--skip-build" goto :mode_ok
echo [ylcloud] ERROR: Unknown option: %YLCLOUD_QUICK_MODE% 1>&2
goto :help_error

:mode_ok
docker --version >nul 2>&1
if errorlevel 1 (
  echo [ylcloud] ERROR: Docker was not found in PATH. 1>&2
  exit /b 1
)

docker compose version >nul 2>&1
if errorlevel 1 (
  echo [ylcloud] ERROR: Docker Compose v2 is required. 1>&2
  exit /b 1
)

if not exist "config\.env.example" (
  echo [ylcloud] ERROR: Missing config\.env.example. 1>&2
  exit /b 1
)
if not exist "config\docker-compose.yml" (
  echo [ylcloud] ERROR: Missing config\docker-compose.yml. 1>&2
  exit /b 1
)

if not exist "config\.env" (
  copy /Y "config\.env.example" "config\.env" >nul
  if errorlevel 1 exit /b 1
  echo [ylcloud] Created config\.env from config\.env.example
) else (
  echo [ylcloud] Keeping existing config\.env
)

if not exist "config\secrets" mkdir "config\secrets"

call :ensure_random_secret "config\secrets\jwt_secret" 48
if errorlevel 1 exit /b 1
call :ensure_random_secret "config\secrets\service_jwt_active_secret" 48
if errorlevel 1 exit /b 1
call :ensure_random_secret "config\secrets\rabbitmq_password" 32
if errorlevel 1 exit /b 1
call :ensure_random_secret "config\secrets\export_master_key" 32
if errorlevel 1 exit /b 1
call :ensure_random_secret "config\secrets\sandbox_service_token" 48
if errorlevel 1 exit /b 1
call :ensure_optional_secret "config\secrets\llm_api_key"
call :ensure_optional_secret "config\secrets\ark_api_key"
call :ensure_optional_secret "config\secrets\rag_query_api_key"
call :ensure_optional_secret "config\secrets\vlm_api_key"

echo [ylcloud] Validating Docker Compose configuration
call :compose config --quiet
if errorlevel 1 exit /b 1

if /I "%~1"=="--check" (
  echo [ylcloud] Configuration check passed. No containers were started.
  exit /b 0
)

docker info >nul 2>&1
if errorlevel 1 (
  echo [ylcloud] ERROR: Docker Engine is not running or is not accessible. 1>&2
  exit /b 1
)

if /I "%~1"=="--skip-build" (
  echo [ylcloud] Starting YLcloud with existing images
  call :compose up -d --wait --wait-timeout 600
) else (
  echo [ylcloud] Building images and starting YLcloud
  call :compose up -d --build --wait --wait-timeout 600
)
if errorlevel 1 exit /b 1

call :compose ps -a
if errorlevel 1 exit /b 1
echo [ylcloud] YLcloud is ready: http://127.0.0.1:5173
echo [ylcloud] Backend health entry: http://127.0.0.1:8080/api/site/public-settings
exit /b 0

:ensure_random_secret
set "YLCLOUD_BOOTSTRAP_SECRET_PATH=%~1"
set "YLCLOUD_BOOTSTRAP_SECRET_BYTES=%~2"
powershell.exe -NoProfile -Command "$p=$env:YLCLOUD_BOOTSTRAP_SECRET_PATH; if ((-not (Test-Path -LiteralPath $p)) -or ((Get-Item -LiteralPath $p).Length -eq 0)) { $n=[int]$env:YLCLOUD_BOOTSTRAP_SECRET_BYTES; $b=New-Object byte[] $n; $r=[Security.Cryptography.RandomNumberGenerator]::Create(); try { $r.GetBytes($b) } finally { $r.Dispose() }; [IO.File]::WriteAllText($p,[Convert]::ToBase64String($b),(New-Object Text.UTF8Encoding $false)); Write-Host ('[ylcloud] Created ' + [IO.Path]::GetFileName($p)) }"
set "YLCLOUD_BOOTSTRAP_SECRET_PATH="
set "YLCLOUD_BOOTSTRAP_SECRET_BYTES="
exit /b %errorlevel%

:ensure_optional_secret
if not exist "%~1" (
  type nul >"%~1"
  echo [ylcloud] Created empty optional secret %~nx1
)
exit /b 0

:compose
docker compose -p ylcloud --env-file config/.env -f config/docker-compose.yml %*
exit /b %errorlevel%

:help
echo Usage: scripts\install.bat [up^|--check^|--skip-build]
echo.
echo   up            Initialize missing local config/secrets and start YLcloud.
echo   --check       Initialize missing files and validate Compose without starting.
echo   --skip-build  Start by reusing existing local images.
echo.
echo Existing config and non-empty secrets are never overwritten.
exit /b 0

:help_error
call :help
exit /b 2
