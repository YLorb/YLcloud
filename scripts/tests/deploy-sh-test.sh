#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="${1:-/workspace}"
CASE_ROOT="/tmp/ylcloud-deploy-test"
FAKE_BIN="$CASE_ROOT/bin"
RUN_ROOT="$CASE_ROOT/run"
mkdir -p "$FAKE_BIN" "$RUN_ROOT/.secrets" "$RUN_ROOT/log" "$RUN_ROOT/state" "$RUN_ROOT/lock"
printf 'test\n' > "$RUN_ROOT/.secrets/rabbitmq_password"
printf 'test-jwt-secret-with-at-least-32-bytes\n' > "$RUN_ROOT/.secrets/jwt_secret"
printf 'test-service-jwt-secret-with-at-least-32-bytes\n' > "$RUN_ROOT/.secrets/service_jwt_active_secret"
printf 'old-v1\n' > "$RUN_ROOT/state/current-version"
printf 'test\n' > "$RUN_ROOT/deploy.env"

cat > "$FAKE_BIN/docker" <<'EOF'
#!/usr/bin/env bash
set -Eeuo pipefail
args=" $* "
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
    printf '%s-cid\n' "$service"
    exit 0
  fi
  if [[ "$args" == *" up "* ]]; then
    service="${!#}"
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
if [[ "$args" == *"/api/admin/async/smoke"* ]]; then
  printf '{"code":200,"data":{"id":42}}\n'
elif [[ "$args" == *"/api/async/unified/42"* ]]; then
  printf '{"code":200,"data":{"status":"SUCCESS"}}\n'
else
  printf '{"status":"UP"}\n'
fi
EOF

chmod +x "$FAKE_BIN/docker" "$FAKE_BIN/curl"

run_case() {
  local name="$1" expected="$2" injection="${3:-}" fail_service="${4:-}"
  local case_state="$RUN_ROOT/state-$name" output="$RUN_ROOT/$name.out"
  mkdir -p "$case_state"
  printf 'old-v1\n' > "$case_state/current-version"
  set +e
  (
    cd "$RUN_ROOT"
    PATH="$FAKE_BIN:$PATH" \
    YLCLOUD_DEPLOY_COMPOSE_FILE="$ROOT/docker-compose.hub.yml" \
    YLCLOUD_DEPLOY_ENV_FILE="$RUN_ROOT/deploy.env" \
    YLCLOUD_DEPLOY_LOG_ROOT="$RUN_ROOT/log" \
    YLCLOUD_DEPLOY_STATE_ROOT="$case_state" \
    YLCLOUD_DEPLOY_LOCK_FILE="$RUN_ROOT/lock/$name.lock" \
    YLCLOUD_DEPLOY_MIN_FREE_KB=1 \
    YLCLOUD_DEPLOY_AUTH_TOKEN=test-token \
    YLCLOUD_DEPLOY_INJECT_FAILURE="$injection" \
    FAKE_DOCKER_FAIL_SERVICE="$fail_service" \
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
  elif [[ "$injection" != "rollback" ]]; then
    grep -q 'rollback completed' "$output"
    grep -qx 'old-v1' "$case_state/current-version"
  else
    grep -q 'injected rollback failure' "$output"
  fi
}

run_case success 0
run_case app-failure 1 app
run_case frontend-failure 1 frontend
run_case smoke-failure 1 smoke
run_case rollback-failure 86 rollback ylcloud-app

signal_state="$RUN_ROOT/state-signal"
signal_output="$RUN_ROOT/signal.out"
signal_marker="$RUN_ROOT/signal.marker"
mkdir -p "$signal_state"
printf 'old-v1\n' > "$signal_state/current-version"
set +e
(
  cd "$RUN_ROOT"
  PATH="$FAKE_BIN:$PATH" \
  FAKE_DOCKER_SLEEP_SERVICE=ylcloud-app \
  FAKE_DOCKER_MARKER="$signal_marker" \
  YLCLOUD_DEPLOY_COMPOSE_FILE="$ROOT/docker-compose.hub.yml" \
  YLCLOUD_DEPLOY_ENV_FILE="$RUN_ROOT/deploy.env" \
  YLCLOUD_DEPLOY_LOG_ROOT="$RUN_ROOT/log" \
  YLCLOUD_DEPLOY_STATE_ROOT="$signal_state" \
  YLCLOUD_DEPLOY_LOCK_FILE="$RUN_ROOT/lock/signal.lock" \
  YLCLOUD_DEPLOY_MIN_FREE_KB=1 \
  YLCLOUD_DEPLOY_AUTH_TOKEN=test-token \
  bash "$ROOT/scripts/deploy.sh" mq-v1
) > "$signal_output" 2>&1 &
signal_pid=$!
for _ in 1 2 3 4 5; do [[ -f "$signal_marker" ]] && break; sleep 1; done
kill -TERM "$signal_pid"
wait "$signal_pid"
signal_code=$?
set -e
[[ "$signal_code" -eq 143 ]]
grep -q 'rollback completed' "$signal_output"
grep -qx 'old-v1' "$signal_state/current-version"

printf 'deploy.sh test matrix: 6/6 passed\n'
