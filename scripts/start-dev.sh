#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DIR="${YLCLOUD_DEV_RUN_DIR:-/tmp/ylcloud-dev}"
BACKEND_PORT="${SERVER_PORT:-8080}"
FRONTEND_PORT="${YLCLOUD_FRONTEND_PORT:-5173}"
JAVA_BIN="${JAVA_BIN:-$(command -v java || true)}"
MAVEN_BIN="${MAVEN_BIN:-$(command -v mvn || true)}"
BACKEND_JAR="${ROOT_DIR}/cloud-server/target/cloud-server-1.0-SNAPSHOT.jar"

mkdir -p "${RUN_DIR}"

ENV_FILE="${YLCLOUD_ENV_FILE:-${ROOT_DIR}/.env}"
if [[ -f "${ENV_FILE}" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "${ENV_FILE}"
  set +a
fi

log() {
  printf '[YLcloud] %s\n' "$*"
}

fail() {
  printf '[YLcloud] ERROR: %s\n' "$*" >&2
  exit 1
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || fail "missing command: $1"
}

wait_http() {
  local name="$1"
  local url="$2"
  local attempts="${3:-60}"
  local delay="${4:-2}"

  for _ in $(seq 1 "${attempts}"); do
    if curl -fsS --max-time 3 "${url}" >/dev/null 2>&1; then
      log "${name} is ready: ${url}"
      return 0
    fi
    sleep "${delay}"
  done
  fail "${name} did not become ready: ${url}"
}

port_has_http() {
  local port="$1"
  curl -sS --max-time 2 "http://127.0.0.1:${port}/" >/dev/null 2>&1
}

port_listeners() {
  local port="$1"
  ss -ltnp 2>/dev/null | grep -E "[:*]${port}[[:space:]]" || true
}

start_backend() {
  if port_has_http "${BACKEND_PORT}"; then
    log "backend appears to be running on http://127.0.0.1:${BACKEND_PORT}"
    return 0
  fi

  local listeners
  listeners="$(port_listeners "${BACKEND_PORT}")"
  if [[ -n "${listeners}" ]]; then
    printf '%s\n' "${listeners}" >&2
    fail "port ${BACKEND_PORT} is occupied. Stop that process or run with SERVER_PORT=another_port."
  fi

  log "building backend jar"
  (cd "${ROOT_DIR}" && "${MAVEN_BIN}" -pl cloud-server -am package -DskipTests)

  [[ -f "${BACKEND_JAR}" ]] || fail "backend jar not found: ${BACKEND_JAR}"
  [[ -x "${JAVA_BIN}" ]] || fail "java not executable: ${JAVA_BIN}"

  log "starting backend on port ${BACKEND_PORT}"
  (
    cd "${ROOT_DIR}"
    setsid env \
      SERVER_PORT="${BACKEND_PORT}" \
      YLCLOUD_DATASOURCE_URL="${YLCLOUD_DATASOURCE_URL:-jdbc:mysql://127.0.0.1:3306/${YLCLOUD_MYSQL_DATABASE:-ylcloud}?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai}" \
      YLCLOUD_DATASOURCE_USERNAME="${YLCLOUD_DATASOURCE_USERNAME:-${YLCLOUD_MYSQL_USER:-ylcloud}}" \
      YLCLOUD_DATASOURCE_PASSWORD="${YLCLOUD_DATASOURCE_PASSWORD:-${YLCLOUD_MYSQL_PASSWORD:-ylcloud_pwd}}" \
      YLCLOUD_MINIO_ACCESS_KEY="${YLCLOUD_MINIO_ACCESS_KEY:-ylcloud_minio}" \
      YLCLOUD_MINIO_SECRET_KEY="${YLCLOUD_MINIO_SECRET_KEY:-ylcloud_minio_pwd}" \
      YLCLOUD_MINIO_ENDPOINT="${YLCLOUD_MINIO_ENDPOINT:-http://172.18.0.1:9000}" \
      YLCLOUD_MINIO_BUCKET="${YLCLOUD_MINIO_BUCKET:-localbucket1}" \
      YLCLOUD_RAG_INDEX_CONCURRENCY="${YLCLOUD_RAG_INDEX_CONCURRENCY:-5}" \
      YLCLOUD_RAG_INDEX_TASK_TIMEOUT_MINUTES="${YLCLOUD_RAG_INDEX_TASK_TIMEOUT_MINUTES:-10}" \
      "${JAVA_BIN}" -jar "${BACKEND_JAR}" \
      >"${RUN_DIR}/backend.log" 2>&1 < /dev/null &
    echo $! >"${RUN_DIR}/backend.pid"
  )

  for _ in $(seq 1 60); do
    if port_has_http "${BACKEND_PORT}"; then
      log "backend started: http://127.0.0.1:${BACKEND_PORT}"
      return 0
    fi
    if [[ -f "${RUN_DIR}/backend.pid" ]] && ! kill -0 "$(cat "${RUN_DIR}/backend.pid")" 2>/dev/null; then
      tail -n 120 "${RUN_DIR}/backend.log" >&2 || true
      fail "backend exited during startup"
    fi
    sleep 2
  done

  tail -n 120 "${RUN_DIR}/backend.log" >&2 || true
  fail "backend did not become ready"
}

start_frontend() {
  if port_has_http "${FRONTEND_PORT}"; then
    log "frontend appears to be running on http://127.0.0.1:${FRONTEND_PORT}"
    return 0
  fi

  local listeners
  listeners="$(port_listeners "${FRONTEND_PORT}")"
  if [[ -n "${listeners}" ]]; then
    printf '%s\n' "${listeners}" >&2
    fail "port ${FRONTEND_PORT} is occupied. Stop that process or run with YLCLOUD_FRONTEND_PORT=another_port."
  fi

  log "starting frontend on port ${FRONTEND_PORT}"
  (
    cd "${ROOT_DIR}/cloud-frontend"
    npm run dev -- --host 127.0.0.1 --port "${FRONTEND_PORT}" \
      >"${RUN_DIR}/frontend.log" 2>&1 &
    echo $! >"${RUN_DIR}/frontend.pid"
  )
  wait_http "frontend" "http://127.0.0.1:${FRONTEND_PORT}/" 60 1
}

main() {
  require_cmd docker
  require_cmd curl
  require_cmd ss
  require_cmd npm
  [[ -n "${JAVA_BIN}" && -x "${JAVA_BIN}" ]] || fail "java is not installed or JAVA_BIN is invalid"
  [[ -n "${MAVEN_BIN}" && -x "${MAVEN_BIN}" ]] || fail "maven is not installed or MAVEN_BIN is invalid"

  log "project: ${ROOT_DIR}"
  log "logs: ${RUN_DIR}"
  if [[ -f "${ENV_FILE}" ]]; then
    log "env: ${ENV_FILE}"
  fi
  log "rag index concurrency: ${YLCLOUD_RAG_INDEX_CONCURRENCY:-5}"
  log "rag index timeout minutes: ${YLCLOUD_RAG_INDEX_TASK_TIMEOUT_MINUTES:-10}"

  if [[ -z "${YLCLOUD_LLM_API_KEY:-}" ]]; then
    log "YLCLOUD_LLM_API_KEY is not set; RAG can run, but LLM chat will fall back unless you export it."
  fi

  log "starting docker dependencies"
  (cd "${ROOT_DIR}" && docker compose up -d mysql minio qdrant model-service document-parser-service)

  for _ in $(seq 1 60); do
    if [[ "$(docker inspect --format '{{.State.Health.Status}}' ylcloud-mysql 2>/dev/null || true)" == "healthy" ]]; then
      log "MySQL is ready"
      break
    fi
    sleep 2
  done
  [[ "$(docker inspect --format '{{.State.Health.Status}}' ylcloud-mysql 2>/dev/null || true)" == "healthy" ]] \
    || fail "MySQL did not become healthy"

  wait_http "MinIO" "http://127.0.0.1:9000/minio/health/live" 60 2
  wait_http "Qdrant" "http://127.0.0.1:6333/collections" 60 2
  wait_http "model-service" "http://127.0.0.1:8001/health" 60 2
  wait_http "document-parser-service" "http://127.0.0.1:8002/health" 60 2

  start_backend
  start_frontend

  log "done"
  log "frontend: http://127.0.0.1:${FRONTEND_PORT}"
  log "backend:  http://127.0.0.1:${BACKEND_PORT}"
  log "backend log:  ${RUN_DIR}/backend.log"
  log "frontend log: ${RUN_DIR}/frontend.log"
  if [[ -f "${RUN_DIR}/backend.pid" ]]; then
    log "stop backend:  kill \$(cat ${RUN_DIR}/backend.pid)"
  else
    log "stop backend:  ss -ltnp | grep ':${BACKEND_PORT}'"
  fi
  if [[ -f "${RUN_DIR}/frontend.pid" ]]; then
    log "stop frontend: kill \$(cat ${RUN_DIR}/frontend.pid)"
  else
    log "stop frontend: ss -ltnp | grep ':${FRONTEND_PORT}'"
  fi
}

main "$@"
