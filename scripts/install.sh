#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROJECT_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
ENV_FILE="$PROJECT_ROOT/config/.env"
ENV_TEMPLATE="$PROJECT_ROOT/config/.env.example"
COMPOSE_FILE="$PROJECT_ROOT/config/docker-compose.yml"
SECRETS_DIR="$PROJECT_ROOT/config/secrets"
PROJECT_NAME="ylcloud"
MODE="${1:-up}"

usage() {
  cat <<'EOF'
Usage: ./scripts/install.sh [up|--check|--skip-build]

  up            Initialize missing local config/secrets and start YLcloud.
  --check       Initialize missing files and validate Compose without starting.
  --skip-build  Start by reusing existing local images.

Existing config and non-empty secrets are never overwritten.
EOF
}

log() {
  printf '[ylcloud] %s\n' "$*"
}

die() {
  printf '[ylcloud] ERROR: %s\n' "$*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "Required command not found: $1"
}

ensure_random_secret() {
  secret_path=$1
  secret_bytes=$2
  if [ -s "$secret_path" ]; then
    return
  fi
  require_command openssl
  openssl rand -base64 "$secret_bytes" >"$secret_path"
  chmod 600 "$secret_path" 2>/dev/null || true
  log "Created $(basename "$secret_path")"
}

ensure_optional_secret() {
  secret_path=$1
  if [ ! -e "$secret_path" ]; then
    : >"$secret_path"
    chmod 600 "$secret_path" 2>/dev/null || true
    log "Created empty optional secret $(basename "$secret_path")"
  fi
}

case "$MODE" in
  up|--check|--skip-build) ;;
  -h|--help)
    usage
    exit 0
    ;;
  *)
    usage >&2
    exit 2
    ;;
esac

require_command docker
docker compose version >/dev/null 2>&1 || die "Docker Compose v2 is required."
[ -f "$ENV_TEMPLATE" ] || die "Missing template: $ENV_TEMPLATE"
[ -f "$COMPOSE_FILE" ] || die "Missing Compose file: $COMPOSE_FILE"

if [ ! -f "$ENV_FILE" ]; then
  cp "$ENV_TEMPLATE" "$ENV_FILE"
  log "Created config/.env from config/.env.example"
else
  log "Keeping existing config/.env"
fi

mkdir -p "$SECRETS_DIR"
chmod 700 "$SECRETS_DIR" 2>/dev/null || true
umask 077

ensure_random_secret "$SECRETS_DIR/jwt_secret" 48
ensure_random_secret "$SECRETS_DIR/service_jwt_active_secret" 48
ensure_random_secret "$SECRETS_DIR/rabbitmq_password" 32
ensure_random_secret "$SECRETS_DIR/export_master_key" 32
ensure_random_secret "$SECRETS_DIR/sandbox_service_token" 48
ensure_optional_secret "$SECRETS_DIR/llm_api_key"
ensure_optional_secret "$SECRETS_DIR/ark_api_key"
ensure_optional_secret "$SECRETS_DIR/rag_query_api_key"
ensure_optional_secret "$SECRETS_DIR/vlm_api_key"

compose() {
  docker compose -p "$PROJECT_NAME" --env-file "$ENV_FILE" -f "$COMPOSE_FILE" "$@"
}

log "Validating Docker Compose configuration"
compose config --quiet

if [ "$MODE" = "--check" ]; then
  log "Configuration check passed. No containers were started."
  exit 0
fi

docker info >/dev/null 2>&1 || die "Docker Engine is not running or is not accessible."

if [ "$MODE" = "--skip-build" ]; then
  log "Starting YLcloud with existing images"
  compose up -d --wait --wait-timeout 600
else
  log "Building images and starting YLcloud"
  compose up -d --build --wait --wait-timeout 600
fi

compose ps -a
log "YLcloud is ready: http://127.0.0.1:5173"
log "Backend health entry: http://127.0.0.1:8080/api/site/public-settings"
