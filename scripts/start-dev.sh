#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DIR="${YLCLOUD_DEV_RUN_DIR:-/tmp/ylcloud-dev}"
BACKEND_PORT="${SERVER_PORT:-8080}"
FRONTEND_PORT="${YLCLOUD_FRONTEND_PORT:-5173}"
JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
JAVA_BIN="${JAVA_HOME}/bin/java"
MAVEN_BIN="${MAVEN_BIN:-/home/yl-orb/software/idea/idea-2026.1.3/idea-IU-261.25134.95/plugins/maven/lib/maven3/bin/mvn}"
BACKEND_JAR="${ROOT_DIR}/cloud-server/target/cloud-server-1.0-SNAPSHOT.jar"

mkdir -p "${RUN_DIR}"

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
  (cd "${ROOT_DIR}" && JAVA_HOME="${JAVA_HOME}" "${MAVEN_BIN}" -pl cloud-server -am package -DskipTests)

  [[ -f "${BACKEND_JAR}" ]] || fail "backend jar not found: ${BACKEND_JAR}"
  [[ -x "${JAVA_BIN}" ]] || fail "java not executable: ${JAVA_BIN}"

  log "starting backend on port ${BACKEND_PORT}"
  (
    cd "${ROOT_DIR}"
    env \
      JAVA_HOME="${JAVA_HOME}" \
      SERVER_PORT="${BACKEND_PORT}" \
      YLCLOUD_MINIO_ACCESS_KEY="${YLCLOUD_MINIO_ACCESS_KEY:-ylcloud_minio}" \
      YLCLOUD_MINIO_SECRET_KEY="${YLCLOUD_MINIO_SECRET_KEY:-ylcloud_minio_pwd}" \
      YLCLOUD_MINIO_ENDPOINT="${YLCLOUD_MINIO_ENDPOINT:-http://127.0.0.1:9000}" \
      YLCLOUD_MINIO_BUCKET="${YLCLOUD_MINIO_BUCKET:-localbucket1}" \
      "${JAVA_BIN}" -jar "${BACKEND_JAR}" \
      >"${RUN_DIR}/backend.log" 2>&1 &
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
  [[ -x "${MAVEN_BIN}" ]] || fail "maven not executable: ${MAVEN_BIN}"

  log "project: ${ROOT_DIR}"
  log "logs: ${RUN_DIR}"

  if [[ -z "${YLCLOUD_LLM_API_KEY:-}" ]]; then
    log "YLCLOUD_LLM_API_KEY is not set; RAG can run, but LLM chat will fall back unless you export it."
  fi

  log "starting docker dependencies"
  (cd "${ROOT_DIR}" && docker compose up -d minio qdrant model-service)

  wait_http "MinIO" "http://127.0.0.1:9000/minio/health/live" 60 2
  wait_http "Qdrant" "http://127.0.0.1:6333/collections" 60 2
  wait_http "model-service" "http://127.0.0.1:8001/health" 60 2

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
