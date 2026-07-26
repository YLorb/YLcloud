#!/usr/bin/env bash
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
START_EPOCH="$(date +%s)"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
CHANGED_APP=0
CHANGED_FRONTEND=0
ROLLING_BACK=0

die() { echo "ERROR: $*" >&2; return 1; }

valid_version() {
  [[ "$VERSION" =~ ^mq-v[1-6]$ || "$VERSION" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || return 1
  [[ "$VERSION" != "latest" && "$VERSION" != "dev" ]]
}

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
  echo "old_version=${OLD_VERSION:-unknown} app_health=$(health "$APP_SERVICE") frontend_health=$(health "$FRONTEND_SERVICE")"
}

http_smoke() {
  curl --fail --silent --show-error "$BACKEND_URL/api/site/public-settings" >/dev/null
  curl --fail --silent --show-error "$FRONTEND_URL/" >/dev/null
  curl --fail --silent --show-error "$FRONTEND_URL/api/site/public-settings" >/dev/null
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
  echo "release failed; restoring changed services"
  if [[ "${YLCLOUD_DEPLOY_INJECT_FAILURE:-}" == "rollback" ]]; then
    echo "injected rollback failure" >&2
    exit 86
  fi
  if [[ "$CHANGED_APP" -eq 1 ]]; then rollback_service "$APP_SERVICE" "$OLD_APP_IMAGE" "$OLD_APP_REF"; fi
  if [[ "$CHANGED_FRONTEND" -eq 1 ]]; then rollback_service "$FRONTEND_SERVICE" "$OLD_FRONTEND_IMAGE" "$OLD_FRONTEND_REF"; fi
  http_smoke || { echo "rollback smoke failed" >&2; exit 86; }
  compose ps
  echo "rollback completed; current-version was not changed"
  exit "$original_code"
}
preflight() {
  command -v docker >/dev/null
  command -v curl >/dev/null
  command -v flock >/dev/null
  docker info >/dev/null
  docker compose version >/dev/null
  [[ -f "$COMPOSE_FILE" && -f "$ENV_FILE" ]] || die "compose/env file missing"
  [[ -s .secrets/rabbitmq_password && -s .secrets/jwt_secret && -s .secrets/service_jwt_active_secret ]] || die "required secret file missing or empty"
  local free_kb min_kb
  free_kb="$(df -Pk . | awk 'NR==2 {print $4}')"
  min_kb="${YLCLOUD_DEPLOY_MIN_FREE_KB:-2097152}"
  [[ "$free_kb" -ge "$min_kb" ]] || die "insufficient disk space"
  compose config -q
  capture_old
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

echo "deploy_start version=$VERSION log=$LOG_FILE"
preflight
trap 'rollback $?' ERR
trap 'rollback 130' INT
trap 'rollback 143' TERM
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
curl --fail --silent --show-error "$BACKEND_URL/actuator/health" | grep -q '"status":"UP"'
[[ "${YLCLOUD_DEPLOY_INJECT_FAILURE:-}" != "smoke" ]] || die "injected smoke failure"
task_smoke

CHANGED_FRONTEND=1
compose up -d --no-deps --wait --wait-timeout "$WAIT_TIMEOUT" "$FRONTEND_SERVICE"
[[ "${YLCLOUD_DEPLOY_INJECT_FAILURE:-}" != "frontend" ]] || die "injected frontend failure"
verify_changed "$FRONTEND_SERVICE" "$OLD_FRONTEND_CID" "$OLD_FRONTEND_IMAGE" "$OLD_FRONTEND_RESTART"
http_smoke
compose ps

tmp_version="$STATE_ROOT/.current-version.${STAMP}.tmp"
printf '%s\n' "$VERSION" > "$tmp_version"
mv -f "$tmp_version" "$STATE_ROOT/current-version"
trap - ERR INT TERM
duration=$(( $(date +%s) - START_EPOCH ))
echo "deploy_success version=$VERSION app_image=$(image_id "$APP_SERVICE") frontend_image=$(image_id "$FRONTEND_SERVICE") duration_seconds=$duration log=$LOG_FILE"
