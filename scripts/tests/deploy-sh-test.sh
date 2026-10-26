#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="${1:-/workspace}"
CASE_ROOT="/tmp/ylcloud-deploy-test"
FAKE_BIN="$CASE_ROOT/bin"
RUN_ROOT="$CASE_ROOT/run"
mkdir -p "$FAKE_BIN" "$RUN_ROOT/config/secrets" "$RUN_ROOT/log" "$RUN_ROOT/state" "$RUN_ROOT/lock"
printf 'test\n' > "$RUN_ROOT/config/secrets/rabbitmq_password"
printf 'test-jwt-secret-with-at-least-32-bytes\n' > "$RUN_ROOT/config/secrets/jwt_secret"
printf 'test-service-jwt-secret-with-at-least-32-bytes\n' > "$RUN_ROOT/config/secrets/service_jwt_active_secret"
for secret in redis_password gateway_forwarding_secret gateway_identity_secret; do
  printf 'test-gateway-secret-with-at-least-32-bytes\n' > "$RUN_ROOT/config/secrets/$secret"
done
printf 'synthetic-cookie-jar\n' > "$RUN_ROOT/session.cookies"
printf '{"username":"fixture","password":"fixture"}\n' > "$RUN_ROOT/login.json"
printf 'old-v1\n' > "$RUN_ROOT/state/current-version"
printf 'test\n' > "$RUN_ROOT/deploy.env"
printf 'verified backup payload\n' > "$RUN_ROOT/verified-backup.tar.gz.enc"
BACKUP_HASH="$(sha256sum "$RUN_ROOT/verified-backup.tar.gz.enc" | cut -d' ' -f1)"

cat > "$FAKE_BIN/docker" <<'EOF'
#!/usr/bin/env bash
set -Eeuo pipefail
args=" $* "
printf '%s\n' "$args" >> "${FAKE_DOCKER_CALLS:?}"
if [[ "$args" == *" inspect "* ]]; then
  format=""
  for arg in "$@"; do [[ "$arg" == "{{"* ]] && format="$arg"; done
  case "$format" in
    *".Image"*) printf 'sha256:old-image\n' ;;
    *".RestartCount"*) printf '0\n' ;;
    *".Config.Image"*) printf 'registry/ylcloud:test-old\n' ;;
    *".State.Health"*) printf 'healthy\n' ;;
    *) printf 'healthy\n' ;;
  esac
  exit 0
fi
[[ "$args" == *" image tag "* ]] && exit 0
[[ "$args" == *" pull "* ]] && exit 0
[[ "$args" == *" info "* ]] && exit 0
if [[ "$args" == *" compose "* ]]; then
  [[ "$args" == *" version "* ]] && exit 0
  [[ "$args" == *" config "* ]] && exit 0
  [[ "$args" == *" pull "* ]] && exit 0
  if [[ "$args" == *" ps -q "* ]]; then
    service="${!#}"
    if [[ "$service" == "gateway" && "${FAKE_GATEWAY_INITIAL_ABSENT:-}" == "1" && ! -f "${FAKE_GATEWAY_CREATED:?}" ]]; then exit 0; fi
    [[ "${FAKE_DOCKER_MISSING_SERVICE:-}" != "$service" ]] || exit 0
    printf '%s-cid\n' "$service"
    exit 0
  fi
  if [[ "$args" == *" up "* ]]; then
    service="${!#}"
    [[ "$service" != "gateway" ]] || : > "${FAKE_GATEWAY_CREATED:?}"
    if [[ "${FAKE_DOCKER_FAIL_SERVICE:-}" == "$service" ]]; then exit 71; fi
    if [[ "${FAKE_DOCKER_SLEEP_SERVICE:-}" == "$service" ]]; then
      : > "${FAKE_DOCKER_MARKER:?}"
      sleep 3
    fi
    exit 0
  fi
  [[ "$args" == *" ps "* ]] && { printf 'mock compose services healthy\n'; exit 0; }
fi
exit 0
EOF

cat > "$FAKE_BIN/curl" <<'EOF'
#!/usr/bin/env bash
set -Eeuo pipefail
args=" $* "
write_out=0
for arg in "$@"; do
  [[ "$arg" == "--write-out" || "$arg" == "-w" ]] && write_out=1
done

if [[ "$args" == *"/api/admin/backup/ready?limit=1"* ]]; then
  [[ "$args" == *" --cookie "* && "$args" == *"X-YLCloud-Request: 1"* ]] || exit 91
  if [[ "${FAKE_EXPIRE_SESSION:-}" == "1" && ! -f "${FAKE_SESSION_EXPIRED_MARKER:?}" ]]; then
    : > "$FAKE_SESSION_EXPIRED_MARKER"
    printf '{"code":401}\n401\n'; exit 0
  fi
  printf '{"code":200,"data":[{"id":7,"status":"READY","archivePath":"%s","archiveHash":"%s","archiveSizeBytes":24}]}' \
    "${FAKE_BACKUP_ARCHIVE:?}" "${FAKE_BACKUP_HASH:?}"
elif [[ "$args" == *"/api/admin/backup/7/restore-verification"* ]]; then
  printf '{"code":200,"data":{"status":"%s","restoreEnvironment":"ISOLATED_DOCKER"}}' \
    "${FAKE_RESTORE_STATUS:-SUCCESS}"
elif [[ "$args" == *"/api/admin/maintenance/enable"* ]]; then
  printf 'true\n' > "${FAKE_MAINTENANCE_STATE:?}"
  printf '{"code":200,"data":{"status":"enabled"}}'
elif [[ "$args" == *"/api/admin/maintenance/disable"* ]]; then
  printf 'false\n' > "${FAKE_MAINTENANCE_STATE:?}"
  printf '{"code":200,"data":{"status":"disabled"}}'
elif [[ "$args" == *"/api/admin/maintenance/status"* ]]; then
  state="$(cat "${FAKE_MAINTENANCE_STATE:?}" 2>/dev/null || printf 'false')"
  printf '{"code":200,"data":{"active":%s}}' "$state"
elif [[ "$args" == *"/api/admin/async/smoke"* ]]; then
  printf '{"code":200,"data":{"id":42}}'
elif [[ "$args" == *"/api/async/unified/42"* ]]; then
  printf '{"code":200,"data":{"status":"SUCCESS"}}'
elif [[ "$args" == *"/api/async/page"* ]]; then
  printf '{"code":200,"data":{"records":[]}}'
elif [[ "$args" == *"/api/site/public-settings"* ]]; then
  printf '{"code":200,"data":{}}'
elif [[ "$args" == *"/api/login"* ]]; then
  [[ "$args" == *"--data-binary @"* && "$args" == *"--cookie-jar "* ]] || exit 92
  printf '{"code":200,"data":{}}'
elif [[ "$args" == *"/actuator/health"* ]]; then
  printf '{"status":"UP"}'
else
  printf '<html>ok</html>'
fi
[[ "$write_out" -eq 0 ]] || printf '\n200'
printf '\n'
EOF

cat > "$FAKE_BIN/restore" <<'EOF'
#!/usr/bin/env bash
set -Eeuo pipefail
: > "${FAKE_RESTORE_MARKER:?}"
[[ " $* " == *" --yes "* ]]
[[ " $* " == *" --no-start "* ]]
[[ " $* " == *" --skip-config "* ]]
EOF

chmod +x "$FAKE_BIN/docker" "$FAKE_BIN/curl" "$FAKE_BIN/restore"

run_case() {
  local name="$1" expected="$2" injection="${3:-}" fail_service="${4:-}" missing_service="${5:-}" legacy="${6:-}" expired="${7:-}"
  local case_state="$RUN_ROOT/state-$name" output="$RUN_ROOT/$name.out"
  mkdir -p "$case_state"
  rm -f "$case_state/gateway-created" "$case_state/docker-calls" "$case_state/restore-called" "$case_state/session-expired"
  printf 'old-v1\n' > "$case_state/current-version"
  printf 'false\n' > "$case_state/maintenance-api-state"
  set +e
  (
    cd "$RUN_ROOT"
    PATH="$FAKE_BIN:$PATH" \
    YLCLOUD_DEPLOY_COMPOSE_FILE="$ROOT/config/docker-compose.hub.yml" \
    YLCLOUD_DEPLOY_ENV_FILE="$RUN_ROOT/deploy.env" \
    YLCLOUD_DEPLOY_LOG_ROOT="$RUN_ROOT/log" \
    YLCLOUD_DEPLOY_STATE_ROOT="$case_state" \
    YLCLOUD_DEPLOY_LOCK_FILE="$RUN_ROOT/lock/$name.lock" \
    YLCLOUD_DEPLOY_MIN_FREE_KB=1 \
    YLCLOUD_DEPLOY_COOKIE_FILE="$RUN_ROOT/session.cookies" \
    YLCLOUD_DEPLOY_LOGIN_FILE="$RUN_ROOT/login.json" \
    YLCLOUD_DEPLOY_RESTORE_SCRIPT="$FAKE_BIN/restore" \
    YLCLOUD_DEPLOY_INJECT_FAILURE="$injection" \
    FAKE_DOCKER_FAIL_SERVICE="$fail_service" \
    FAKE_DOCKER_MISSING_SERVICE="$missing_service" \
    FAKE_GATEWAY_INITIAL_ABSENT="$legacy" \
    FAKE_EXPIRE_SESSION="$expired" \
    FAKE_SESSION_EXPIRED_MARKER="$case_state/session-expired" \
    FAKE_BACKUP_ARCHIVE="$RUN_ROOT/verified-backup.tar.gz.enc" \
    FAKE_BACKUP_HASH="$BACKUP_HASH" \
    FAKE_MAINTENANCE_STATE="$case_state/maintenance-api-state" \
    FAKE_DOCKER_CALLS="$case_state/docker-calls" \
    FAKE_GATEWAY_CREATED="$case_state/gateway-created" \
    FAKE_RESTORE_MARKER="$case_state/restore-called" \
    bash "$ROOT/scripts/deploy.sh" mq-v1
  ) > "$output" 2>&1
  code=$?
  set -e
  [[ "$code" -eq "$expected" ]] || {
    printf '%s: expected exit %s, got %s\n' "$name" "$expected" "$code" >&2
    sed -n '1,160p' "$output" >&2
    return 1
  }
  if [[ "$expected" -eq 0 ]]; then
    grep -q 'deploy_success' "$output"
    grep -qx 'mq-v1' "$case_state/current-version"
    [[ ! -f "$case_state/restore-called" ]]
    grep -q 'pull ylcloud-app frontend gateway gateway-redis' "$case_state/docker-calls"
    grep -q 'up .* gateway ' "$case_state/docker-calls"
  elif [[ "$injection" != "rollback" ]]; then
    grep -q 'Rollback completed' "$output"
    grep -qx 'old-v1' "$case_state/current-version"
    grep -qx 'false' "$case_state/maintenance-api-state"
    [[ -f "$case_state/restore-called" ]]
  else
    grep -q 'injected rollback failure' "$output"
    grep -qx 'true' "$case_state/maintenance-api-state"
  fi
}

run_case success 0
run_case app-failure 1 app
run_case frontend-failure 1 frontend
run_case gateway-failure 1 gateway
run_case legacy-upgrade 0 "" "" "" 1
run_case legacy-rollback 1 frontend "" "" 1
run_case session-recovery 0 "" "" "" "" 1
[[ -f "$RUN_ROOT/state-session-recovery/session-expired" ]]
grep -q '.rollback-network.' "$RUN_ROOT/state-legacy-rollback/docker-calls"
grep -q 'stop gateway' "$RUN_ROOT/state-legacy-rollback/docker-calls"
grep -q 'gateway' "$RUN_ROOT/state-gateway-failure/docker-calls"
run_case smoke-failure 1 smoke
run_case rollback-failure 86 rollback ylcloud-app
run_case critical-service-missing 1 "" "" mysql

gate_state="$RUN_ROOT/state-gate-failure"
gate_output="$RUN_ROOT/gate-failure.out"
mkdir -p "$gate_state"
printf 'old-v1\n' > "$gate_state/current-version"
printf 'false\n' > "$gate_state/maintenance-api-state"
set +e
(
  cd "$RUN_ROOT"
  PATH="$FAKE_BIN:$PATH" \
  YLCLOUD_DEPLOY_COMPOSE_FILE="$ROOT/config/docker-compose.hub.yml" \
  YLCLOUD_DEPLOY_ENV_FILE="$RUN_ROOT/deploy.env" \
  YLCLOUD_DEPLOY_LOG_ROOT="$RUN_ROOT/log" \
  YLCLOUD_DEPLOY_STATE_ROOT="$gate_state" \
  YLCLOUD_DEPLOY_LOCK_FILE="$RUN_ROOT/lock/gate-failure.lock" \
  YLCLOUD_DEPLOY_MIN_FREE_KB=1 \
  YLCLOUD_DEPLOY_COOKIE_FILE="$RUN_ROOT/session.cookies" \
    YLCLOUD_DEPLOY_LOGIN_FILE="$RUN_ROOT/login.json" \
  FAKE_BACKUP_ARCHIVE="$RUN_ROOT/verified-backup.tar.gz.enc" \
  FAKE_BACKUP_HASH="$BACKUP_HASH" \
  FAKE_RESTORE_STATUS=FAILED \
  FAKE_MAINTENANCE_STATE="$gate_state/maintenance-api-state" \
  FAKE_DOCKER_CALLS="$gate_state/docker-calls" \
  FAKE_GATEWAY_CREATED="$gate_state/gateway-created" \
  bash "$ROOT/scripts/deploy.sh" mq-v1
) > "$gate_output" 2>&1
gate_code=$?
set -e
[[ "$gate_code" -ne 0 ]]
grep -q 'no successful isolated restore verification' "$gate_output"
grep -qx 'old-v1' "$gate_state/current-version"
grep -qx 'false' "$gate_state/maintenance-api-state"

signal_state="$RUN_ROOT/state-signal"
signal_output="$RUN_ROOT/signal.out"
signal_marker="$RUN_ROOT/signal.marker"
rm -f "$signal_marker"
mkdir -p "$signal_state"
printf 'old-v1\n' > "$signal_state/current-version"
printf 'false\n' > "$signal_state/maintenance-api-state"
set +e
(
  cd "$RUN_ROOT"
  PATH="$FAKE_BIN:$PATH" \
  FAKE_DOCKER_SLEEP_SERVICE=ylcloud-app \
  FAKE_DOCKER_MARKER="$signal_marker" \
  YLCLOUD_DEPLOY_COMPOSE_FILE="$ROOT/config/docker-compose.hub.yml" \
  YLCLOUD_DEPLOY_ENV_FILE="$RUN_ROOT/deploy.env" \
  YLCLOUD_DEPLOY_LOG_ROOT="$RUN_ROOT/log" \
  YLCLOUD_DEPLOY_STATE_ROOT="$signal_state" \
  YLCLOUD_DEPLOY_LOCK_FILE="$RUN_ROOT/lock/signal.lock" \
  YLCLOUD_DEPLOY_MIN_FREE_KB=1 \
  YLCLOUD_DEPLOY_COOKIE_FILE="$RUN_ROOT/session.cookies" \
    YLCLOUD_DEPLOY_LOGIN_FILE="$RUN_ROOT/login.json" \
  YLCLOUD_DEPLOY_RESTORE_SCRIPT="$FAKE_BIN/restore" \
  FAKE_BACKUP_ARCHIVE="$RUN_ROOT/verified-backup.tar.gz.enc" \
  FAKE_BACKUP_HASH="$BACKUP_HASH" \
  FAKE_MAINTENANCE_STATE="$signal_state/maintenance-api-state" \
  FAKE_DOCKER_CALLS="$signal_state/docker-calls" \
  FAKE_GATEWAY_CREATED="$signal_state/gateway-created" \
  FAKE_RESTORE_MARKER="$signal_state/restore-called" \
  bash "$ROOT/scripts/deploy.sh" mq-v1
) > "$signal_output" 2>&1 &
signal_pid=$!
for _ in $(seq 1 15); do [[ -f "$signal_marker" ]] && break; sleep 1; done
kill -TERM "$signal_pid"
wait "$signal_pid"
signal_code=$?
set -e
[[ "$signal_code" -eq 143 ]]
grep -q 'Rollback completed' "$signal_output"
grep -qx 'old-v1' "$signal_state/current-version"
grep -qx 'false' "$signal_state/maintenance-api-state"
[[ -f "$signal_state/restore-called" ]]

printf 'deploy.sh test matrix: 12/12 passed\n'
