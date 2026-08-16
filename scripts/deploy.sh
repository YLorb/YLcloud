#!/usr/bin/env bash
# TASK-014: Compose upgrade, maintenance and rollback
# Extends deploy.sh with backup gate, maintenance mode, migration and enhanced verification
set -Eeuo pipefail

VERSION="${1:-}"
COMPOSE_FILE="${YLCLOUD_DEPLOY_COMPOSE_FILE:-docker-compose.hub.yml}"
ENV_FILE="${YLCLOUD_DEPLOY_ENV_FILE:-.env.server}"
PROJECT="${YLCLOUD_DEPLOY_PROJECT:-ylcloud}"
APP_SERVICE="${YLCLOUD_DEPLOY_APP_SERVICE:-ylcloud-app}"
FRONTEND_SERVICE="${YLCLOUD_DEPLOY_FRONTEND_SERVICE:-frontend}"
WAIT_TIMEOUT="${YLCLOUD_DEPLOY_WAIT_TIMEOUT:-180}"
LOG_ROOT="${YLCLOUD_DEPLOY_LOG_ROOT:-/var/log/ylcloud-deploy}"
STATE_ROOT="${YLCLOUD_DEPLOY_STATE_ROOT:-/var/lib/ylcloud-deploy}"
LOCK_FILE="${YLCLOUD_DEPLOY_LOCK_FILE:-/var/lock/ylcloud-deploy.lock}"
BACKEND_URL="${YLCLOUD_DEPLOY_BACKEND_URL:-http://127.0.0.1:8080}"
FRONTEND_URL="${YLCLOUD_DEPLOY_FRONTEND_URL:-http://127.0.0.1:5173}"
BACKUP_ROOT="${YLCLOUD_BACKUP_ROOT:-/var/lib/ylcloud-backup}"
RESTORE_SCRIPT="${YLCLOUD_DEPLOY_RESTORE_SCRIPT:-$(dirname "$0")/restore.sh}"
START_EPOCH="$(date +%s)"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
CHANGED_APP=0
CHANGED_FRONTEND=0
ROLLING_BACK=0
MAINTENANCE_MODE=0
READY_BACKUP_ARCHIVE=""

die() { echo "ERROR: $*" >&2; return 1; }
log() { echo "[$(date -u +%Y-%m-%dT%H:%M:%SZ)] $*"; }

valid_version() {
  # TASK-014: Maintain mq-v1~mq-v6 version parameter contract
  [[ "$VERSION" =~ ^mq-v[1-6]$ || "$VERSION" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || return 1
  [[ "$VERSION" != "latest" && "$VERSION" != "dev" ]]
}

# TASK-014: Default to latest version when not specified
if [[ -z "$VERSION" ]]; then
  VERSION="${YLCLOUD_DEPLOY_DEFAULT_VERSION:-mq-v6}"
  log "No version specified, using default: $VERSION"
fi

valid_version || die "usage: ./scripts/deploy.sh <mq-v1..mq-v6|vN.N.N>"
[[ "$(id -u)" -ne 0 ]] || die "run as a dedicated deployment user, not root"

mkdir -p "$LOG_ROOT" "$STATE_ROOT" "$(dirname "$LOCK_FILE")"
LOG_FILE="$LOG_ROOT/${VERSION}-${STAMP}.log"
touch "$LOG_FILE"
chmod 600 "$LOG_FILE"
exec > >(tee -a "$LOG_FILE") 2>&1
exec 9>"$LOCK_FILE"
flock -n 9 || die "another deployment is already running"

compose() {
  YLCLOUD_IMAGE_TAG="${COMPOSE_TAG_OVERRIDE:-$VERSION}" docker compose --project-name "$PROJECT" --env-file "$ENV_FILE" -f "$COMPOSE_FILE" "$@"
}

container_id() { compose ps -q "$1"; }
image_id() {
  local cid
  cid="$(container_id "$1")"
  [[ -n "$cid" ]] || return 1
  docker inspect --format '{{.Image}}' "$cid"
}
restart_count() {
  local cid
  cid="$(container_id "$1")"
  [[ -n "$cid" ]] || return 1
  docker inspect --format '{{.RestartCount}}' "$cid"
}
health() {
  local cid
  cid="$(container_id "$1")"
  [[ -n "$cid" ]] || return 1
  docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$cid"
}

OLD_VERSION="$(test -f "$STATE_ROOT/current-version" && sed -n '1p' "$STATE_ROOT/current-version" || true)"
OLD_APP_CID=""
OLD_FRONTEND_CID=""
OLD_APP_IMAGE=""
OLD_FRONTEND_IMAGE=""
OLD_APP_REF=""
OLD_FRONTEND_REF=""
OLD_APP_RESTART=0
OLD_FRONTEND_RESTART=0

capture_old() {
  OLD_APP_CID="$(container_id "$APP_SERVICE")"
  OLD_FRONTEND_CID="$(container_id "$FRONTEND_SERVICE")"
  [[ -n "$OLD_APP_CID" && -n "$OLD_FRONTEND_CID" ]] || die "existing app/frontend containers are required for a recoverable release"
  OLD_APP_IMAGE="$(image_id "$APP_SERVICE")"
  OLD_FRONTEND_IMAGE="$(image_id "$FRONTEND_SERVICE")"
  OLD_APP_REF="$(docker inspect --format '{{.Config.Image}}' "$OLD_APP_CID")"
  OLD_FRONTEND_REF="$(docker inspect --format '{{.Config.Image}}' "$OLD_FRONTEND_CID")"
  [[ -n "$OLD_APP_IMAGE" && -n "$OLD_FRONTEND_IMAGE" ]] || die "unable to resolve rollback image IDs"
  [[ -n "$OLD_VERSION" ]] || die "current-version is missing; refusing an unrecoverable release"
  OLD_APP_RESTART="$(restart_count "$APP_SERVICE")"
  OLD_FRONTEND_RESTART="$(restart_count "$FRONTEND_SERVICE")"
  log "old_version=${OLD_VERSION:-unknown} app_health=$(health "$APP_SERVICE") frontend_health=$(health "$FRONTEND_SERVICE")"
}

http_smoke() {
  curl --fail --silent --show-error "$BACKEND_URL/api/site/public-settings" >/dev/null
  curl --fail --silent --show-error "$FRONTEND_URL/" >/dev/null
  curl --fail --silent --show-error "$FRONTEND_URL/api/site/public-settings" >/dev/null
}

admin_api() {
  local method="$1" path="$2"
  shift 2
  local token="${YLCLOUD_DEPLOY_AUTH_TOKEN:-}"
  [[ -n "$token" ]] || die "YLCLOUD_DEPLOY_AUTH_TOKEN is required for admin API calls"
  curl --silent --show-error --write-out $'\n%{http_code}' \
    -X "$method" -H "Authorization: Bearer $token" "$@" "$BACKEND_URL$path"
}

require_api_success() {
  local response="$1" operation="$2"
  local http_code body
  http_code="$(printf '%s\n' "$response" | tail -1)"
  body="$(printf '%s\n' "$response" | sed '$d')"
  [[ "$http_code" == "200" ]] || die "$operation failed (HTTP $http_code)"
  [[ "$body" == *'"code":200'* ]] || die "$operation returned an unsuccessful response"
  printf '%s' "$body"
}

# TASK-014: Check for READY backup before upgrade — MANDATORY, no skip allowed
# Queries backup_run table via API (status='READY'), verifies archive integrity,
# and confirms at least one restore verification has passed.
check_backup_gate() {
  log "Checking backup gate..."

  local token="${YLCLOUD_DEPLOY_AUTH_TOKEN:-}"
  if [[ -z "$token" ]]; then
    die "YLCLOUD_DEPLOY_AUTH_TOKEN is required for backup gate check"
  fi

  # 1. Query READY backups from API
  local response http_code
  response="$(admin_api GET "/api/admin/backup/ready?limit=1")" \
    || die "Backup API query failed. Check backend availability"
  http_code="$(echo "$response" | tail -1)"

  if [[ "$http_code" != "200" ]]; then
    die "Backup API query failed (HTTP $http_code). Check backend availability"
  fi

  # 2. Extract the latest READY backup
  local body
  body="$(echo "$response" | sed '$d')"

  # Check if the list is empty
  if echo "$body" | grep -q '"data":\[\]'; then
    die "No READY backup found in backup_run table. Run scripts/backup.sh first"
  fi

  # 3. Extract backup details: id, archivePath, archiveHash, archiveSizeBytes
  local backup_id archive_path archive_hash archive_size
  backup_id="$(echo "$body" | sed -n 's/.*"id":\([0-9]*\).*/\1/p' | head -1)"
  archive_path="$(echo "$body" | sed -n 's/.*"archivePath":"\([^"]*\)".*/\1/p' | head -1)"
  archive_hash="$(echo "$body" | sed -n 's/.*"archiveHash":"\([^"]*\)".*/\1/p' | head -1)"
  archive_size="$(echo "$body" | sed -n 's/.*"archiveSizeBytes":\([0-9]*\).*/\1/p' | head -1)"

  if [[ -z "$backup_id" || -z "$archive_path" || -z "$archive_hash" ]]; then
    die "Failed to parse backup details from API response"
  fi

  log "Found READY backup: id=$backup_id archive=$archive_path"

  # 4. Verify backup archive exists and hash matches (MANDATORY, not optional)
  if [[ ! -f "$archive_path" ]]; then
    die "Backup archive not found on disk: $archive_path"
  fi

  local actual_hash
  actual_hash="$(sha256sum "$archive_path" | cut -d' ' -f1)"
  if [[ "$actual_hash" != "$archive_hash" ]]; then
    die "Backup archive hash mismatch: expected=$archive_hash actual=$actual_hash"
  fi

  log "Backup archive integrity verified: hash matches"
  READY_BACKUP_ARCHIVE="$archive_path"

  # 5. Check restore verification — confirm isolation restore was successful
  local verify_response verify_code verify_body
  verify_response="$(admin_api GET "/api/admin/backup/${backup_id}/restore-verification")" \
    || die "Restore verification API query failed"
  verify_code="$(echo "$verify_response" | tail -1)"
  verify_body="$(echo "$verify_response" | sed '$d')"

  [[ "$verify_code" == "200" ]] \
    || die "Restore verification API query failed (HTTP $verify_code)"
  [[ "$verify_body" == *'"code":200'* ]] \
    || die "Restore verification API returned an unsuccessful response"
  [[ "$verify_body" == *'"status":"SUCCESS"'* ]] \
    || die "Backup $backup_id has no successful isolated restore verification"
  [[ "$verify_body" == *'"restoreEnvironment":"ISOLATED"'* ]] \
    || die "Backup $backup_id was not verified in an isolated restore environment"

  log "Backup gate passed: backup_id=$backup_id hash_verified=yes isolated_restore=yes"
}

# TASK-014: Enable maintenance mode
enable_maintenance_mode() {
  log "Enabling maintenance mode..."
  local response status_response status_body
  response="$(admin_api POST "/api/admin/maintenance/enable" \
    --data-urlencode "reason=Controlled deployment of $VERSION")" \
    || die "Maintenance enable API call failed"
  require_api_success "$response" "Maintenance enable" >/dev/null
  status_response="$(admin_api GET "/api/admin/maintenance/status")" \
    || die "Maintenance status confirmation failed"
  status_body="$(require_api_success "$status_response" "Maintenance status confirmation")"
  [[ "$status_body" == *'"active":true'* ]] \
    || die "Maintenance mode was not persisted by the application"
  MAINTENANCE_MODE=1
  log "Maintenance mode enabled and confirmed"
}

# TASK-014: Disable maintenance mode
disable_maintenance_mode() {
  log "Disabling maintenance mode..."
  local response status_response status_body
  response="$(admin_api POST "/api/admin/maintenance/disable")" \
    || die "Maintenance disable API call failed"
  require_api_success "$response" "Maintenance disable" >/dev/null
  status_response="$(admin_api GET "/api/admin/maintenance/status")" \
    || die "Maintenance status confirmation failed"
  status_body="$(require_api_success "$status_response" "Maintenance status confirmation")"
  [[ "$status_body" == *'"active":false'* ]] \
    || die "Maintenance mode remains active after disable"
  MAINTENANCE_MODE=0
  log "Maintenance mode disabled and confirmed"
}

# TASK-014: Run database migrations
run_migrations() {
  log "Verifying startup migration gate..."
  # Flyway runs before Spring reports the application ready. A failed migration
  # prevents the new container from becoming healthy; require both container and
  # application health here instead of treating an unavailable actuator endpoint
  # as a successful migration.
  [[ "$(health "$APP_SERVICE")" == "healthy" ]] \
    || die "Application did not become healthy after Flyway startup migration"
  curl --fail --silent --show-error "$BACKEND_URL/actuator/health" \
    | grep -q '"status":"UP"' \
    || die "Application health endpoint failed after Flyway startup migration"
  log "Startup migration gate passed"
}

rollback_service() {
  local service="$1" image="$2" image_ref="$3"
  docker image tag "$image" "$image_ref"
  COMPOSE_TAG_OVERRIDE="$OLD_VERSION" compose up -d --no-deps --force-recreate --wait --wait-timeout "$WAIT_TIMEOUT" "$service"
}

rollback() {
  local original_code="${1:-1}"
  [[ "$ROLLING_BACK" -eq 0 ]] || exit 86
  ROLLING_BACK=1
  trap - ERR INT TERM

  log "Release failed; restoring changed services"

  if [[ "${YLCLOUD_DEPLOY_INJECT_FAILURE:-}" == "rollback" ]]; then
    echo "injected rollback failure" >&2
    exit 86
  fi

  if [[ "$CHANGED_APP" -eq 1 ]]; then
    [[ -n "$READY_BACKUP_ARCHIVE" ]] \
      || { echo "rollback backup archive is missing" >&2; exit 86; }
    log "Restoring data stores from the pre-release READY backup"
    bash "$RESTORE_SCRIPT" "$READY_BACKUP_ARCHIVE" --yes --no-start --skip-config \
      || { echo "rollback data restore failed" >&2; exit 86; }
  fi

  if [[ "$CHANGED_APP" -eq 1 ]]; then rollback_service "$APP_SERVICE" "$OLD_APP_IMAGE" "$OLD_APP_REF"; fi
  if [[ "$CHANGED_FRONTEND" -eq 1 ]]; then rollback_service "$FRONTEND_SERVICE" "$OLD_FRONTEND_IMAGE" "$OLD_FRONTEND_REF"; fi

  # Keep writes fenced until the old application is healthy again.
  if [[ "$MAINTENANCE_MODE" -eq 1 ]]; then
    disable_maintenance_mode
  fi

  http_smoke || { echo "rollback smoke failed" >&2; exit 86; }
  compose ps

  log "Rollback completed; current-version was not changed"
  exit "$original_code"
}

# TASK-014: Enhanced preflight with backup gate
preflight() {
  log "Running preflight checks..."

  command -v docker >/dev/null
  command -v curl >/dev/null
  command -v flock >/dev/null
  docker info >/dev/null
  docker compose version >/dev/null
  [[ -f "$COMPOSE_FILE" && -f "$ENV_FILE" ]] || die "compose/env file missing"
  [[ -f "$RESTORE_SCRIPT" ]] || die "restore script missing: $RESTORE_SCRIPT"
  [[ -s .secrets/rabbitmq_password && -s .secrets/jwt_secret && -s .secrets/service_jwt_active_secret ]] || die "required secret file missing or empty"

  local free_kb min_kb
  free_kb="$(df -Pk . | awk 'NR==2 {print $4}')"
  min_kb="${YLCLOUD_DEPLOY_MIN_FREE_KB:-2097152}"
  [[ "$free_kb" -ge "$min_kb" ]] || die "insufficient disk space"

  compose config -q
  capture_old

  # TASK-014: Check backup gate
  check_backup_gate

  log "Preflight checks passed"
}

verify_changed() {
  local service="$1" old_cid="$2" old_image="$3" old_restart="$4"
  local new_cid new_image new_restart
  new_cid="$(container_id "$service")"
  new_image="$(image_id "$service")"
  new_restart="$(restart_count "$service")"
  [[ "$(health "$service")" == "healthy" ]] || die "$service is not healthy"
  [[ "$new_restart" -le "$old_restart" ]] || die "$service restart count increased"
  if [[ "$new_image" != "$old_image" ]]; then
    [[ "$new_cid" != "$old_cid" ]] || die "$service image changed but container was not recreated"
  fi
}

# TASK-014: Enhanced health verification
verify_platform_health() {
  log "Verifying platform health..."

  # Check all critical services
  local services=("mysql" "minio" "qdrant" "rabbitmq" "$APP_SERVICE" "$FRONTEND_SERVICE")
  for service in "${services[@]}"; do
    local cid
    cid="$(container_id "$service" 2>/dev/null || true)"
    if [[ -z "$cid" ]]; then
      die "Critical service $service not found"
    fi

    local service_health
    service_health="$(health "$service")"
    if [[ "$service_health" != "healthy" && "$service_health" != "running" ]]; then
      die "Service $service is not healthy: $service_health"
    fi
  done

  # Check application health endpoint
  curl --fail --silent --show-error "$BACKEND_URL/actuator/health" | grep -q '"status":"UP"' || die "Application health check failed"

  # Check RabbitMQ queues
  local token="${YLCLOUD_DEPLOY_AUTH_TOKEN:-}"
  if [[ -n "$token" ]]; then
    curl --fail --silent --show-error -H "Authorization: Bearer $token" \
      "$BACKEND_URL/api/async/page?page=1&pageSize=1" >/dev/null || die "Async task API check failed"
  fi

  log "Platform health verification passed"
}

task_smoke() {
  local token="${YLCLOUD_DEPLOY_AUTH_TOKEN:-}"
  [[ -n "$token" ]] || die "YLCLOUD_DEPLOY_AUTH_TOKEN is required for task smoke"
  curl --fail --silent --show-error -H "Authorization: Bearer $token" "$BACKEND_URL/api/async/page?page=1&pageSize=1" >/dev/null
  local response task_id
  response="$(curl --fail --silent --show-error -X POST -H "Authorization: Bearer $token" \
    "$BACKEND_URL/api/admin/async/smoke?release=$VERSION")"
  task_id="$(printf '%s' "$response" | sed -n 's/.*"id":\([0-9][0-9]*\).*/\1/p' | head -1)"
  [[ -n "$task_id" ]] || die "smoke task ID missing"
  local deadline=$((SECONDS + WAIT_TIMEOUT)) body
  while (( SECONDS < deadline )); do
    body="$(curl --fail --silent --show-error -H "Authorization: Bearer $token" "$BACKEND_URL/api/async/unified/$task_id")"
    [[ "$body" == *'"status":"SUCCESS"'* ]] && return 0
    [[ "$body" == *'"status":"FAILED"'* || "$body" == *'"status":"CANCELED"'* ]] && die "smoke task ended unsuccessfully"
    sleep 2
  done
  die "smoke task timed out"
}

# Main deployment flow
log "=== Starting deployment: version=$VERSION ==="
log "Log file: $LOG_FILE"

preflight
trap 'rollback $?' ERR
trap 'rollback 130' INT
trap 'rollback 143' TERM

# TASK-014: Enable maintenance mode before upgrade
enable_maintenance_mode

compose pull "$APP_SERVICE" "$FRONTEND_SERVICE"

if ! container_id rabbitmq >/dev/null 2>&1 || [[ -z "$(container_id rabbitmq)" ]]; then
  docker pull rabbitmq:4.3.4-management
  compose up -d --wait --wait-timeout "$WAIT_TIMEOUT" rabbitmq
else
  [[ "$(health rabbitmq)" == "healthy" ]] || die "existing RabbitMQ is unhealthy"
fi

CHANGED_APP=1
compose up -d --no-deps --wait --wait-timeout "$WAIT_TIMEOUT" "$APP_SERVICE"
[[ "${YLCLOUD_DEPLOY_INJECT_FAILURE:-}" != "app" ]] || die "injected app failure"
verify_changed "$APP_SERVICE" "$OLD_APP_CID" "$OLD_APP_IMAGE" "$OLD_APP_RESTART"

# TASK-014: Run migrations after app startup
run_migrations

curl --fail --silent --show-error "$BACKEND_URL/actuator/health" | grep -q '"status":"UP"'
[[ "${YLCLOUD_DEPLOY_INJECT_FAILURE:-}" != "smoke" ]] || die "injected smoke failure"
task_smoke

CHANGED_FRONTEND=1
compose up -d --no-deps --wait --wait-timeout "$WAIT_TIMEOUT" "$FRONTEND_SERVICE"
[[ "${YLCLOUD_DEPLOY_INJECT_FAILURE:-}" != "frontend" ]] || die "injected frontend failure"
verify_changed "$FRONTEND_SERVICE" "$OLD_FRONTEND_CID" "$OLD_FRONTEND_IMAGE" "$OLD_FRONTEND_RESTART"

# TASK-014: Enhanced platform health verification
verify_platform_health

http_smoke
compose ps

# TASK-014: Disable maintenance mode after successful deployment
disable_maintenance_mode

tmp_version="$STATE_ROOT/.current-version.${STAMP}.tmp"
printf '%s\n' "$VERSION" > "$tmp_version"
mv -f "$tmp_version" "$STATE_ROOT/current-version"
trap - ERR INT TERM

duration=$(( $(date +%s) - START_EPOCH ))
log "=== Deployment completed successfully ==="
log "version=$VERSION app_image=$(image_id "$APP_SERVICE") frontend_image=$(image_id "$FRONTEND_SERVICE") duration_seconds=$duration"
echo "deploy_success version=$VERSION duration_seconds=$duration log=$LOG_FILE"
