#!/usr/bin/env bash
# TASK-013: Restore from backup
# Restores MySQL, MinIO, Qdrant and configs from encrypted backup archive
set -Eeuo pipefail

# Configuration
BACKUP_ROOT="${YLCLOUD_BACKUP_ROOT:-/var/lib/ylcloud-backup}"
ENCRYPTION_KEY_FILE="${YLCLOUD_BACKUP_KEY_FILE:-/var/lib/ylcloud-backup/.encryption-key}"
COMPOSE_FILE="${YLCLOUD_DEPLOY_COMPOSE_FILE:-docker-compose.hub.yml}"
ENV_FILE="${YLCLOUD_DEPLOY_ENV_FILE:-.env.server}"
PROJECT="${YLCLOUD_DEPLOY_PROJECT:-ylcloud}"
MYSQL_CONTAINER="${YLCLOUD_MYSQL_CONTAINER:-ylcloud-mysql}"
MINIO_CONTAINER="${YLCLOUD_MINIO_CONTAINER:-ylcloud-minio}"
QDRANT_CONTAINER="${YLCLOUD_QDRANT_CONTAINER:-ylcloud-qdrant}"
RESTORE_DIR="${YLCLOUD_RESTORE_DIR:-/var/lib/ylcloud-restore}"
LOCK_FILE="${YLCLOUD_RESTORE_LOCK_FILE:-/var/lock/ylcloud-restore.lock}"
LOG_ROOT="${YLCLOUD_RESTORE_LOG_ROOT:-/var/log/ylcloud-restore}"
BACKEND_URL="${YLCLOUD_DEPLOY_BACKEND_URL:-http://127.0.0.1:8080}"
AUTH_TOKEN="${YLCLOUD_DEPLOY_AUTH_TOKEN:-}"
MYSQL_VERIFY_IMAGE="${YLCLOUD_RESTORE_MYSQL_IMAGE:-mysql:8.3}"
MINIO_VERIFY_IMAGE="${YLCLOUD_RESTORE_MINIO_IMAGE:-minio/minio:RELEASE.2025-04-22T22-12-26Z}"
QDRANT_VERIFY_IMAGE="${YLCLOUD_RESTORE_QDRANT_IMAGE:-qdrant/qdrant:v1.15.4}"
CURL_VERIFY_IMAGE="${YLCLOUD_RESTORE_CURL_IMAGE:-curlimages/curl:8.12.1}"

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"

die() { echo "ERROR: $*" >&2; exit 1; }
log() { echo "[$(date -u +%Y-%m-%dT%H:%M:%SZ)] $*"; }

usage() {
    cat <<EOF
Usage: $0 <backup-archive> [options]

Arguments:
    backup-archive    Path to encrypted backup archive (.tar.gz.enc)

Options:
    --verify-only     Only verify backup integrity, do not restore
    --isolated-verify Restore all stores into disposable Docker containers and publish verification
    --backup-id ID    backup_run id used with --isolated-verify
    --skip-mysql      Skip MySQL restore
    --skip-minio      Skip MinIO restore
    --skip-qdrant     Skip Qdrant restore
    --skip-config     Skip config restore
    --dry-run         Show what would be restored without making changes
    --yes             Do not prompt before restoring
    --no-start        Restore data but leave the application stopped
    -h, --help        Show this help message

Environment Variables:
    YLCLOUD_BACKUP_ROOT           Backup directory (default: /var/lib/ylcloud-backup)
    YLCLOUD_BACKUP_KEY_FILE       Encryption key file (default: /var/lib/ylcloud-backup/.encryption-key)
    YLCLOUD_RESTORE_DIR           Restore working directory (default: /var/lib/ylcloud-restore)

Examples:
    $0 /var/lib/ylcloud-backup/ylcloud-backup-20260727T040000Z.tar.gz.enc
    $0 /var/lib/ylcloud-backup/ylcloud-backup-20260727T040000Z.tar.gz.enc --verify-only
    $0 /var/lib/ylcloud-backup/ylcloud-backup-20260727T040000Z.tar.gz.enc --skip-qdrant
EOF
    exit 0
}

# Parse arguments
ARCHIVE_FILE=""
VERIFY_ONLY=0
SKIP_MYSQL=0
SKIP_MINIO=0
SKIP_QDRANT=0
SKIP_CONFIG=0
DRY_RUN=0
ISOLATED_VERIFY=0
BACKUP_ID=""
ASSUME_YES=0
NO_START=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --verify-only) VERIFY_ONLY=1; shift ;;
        --isolated-verify) ISOLATED_VERIFY=1; shift ;;
        --backup-id) BACKUP_ID="${2:-}"; shift 2 ;;
        --skip-mysql) SKIP_MYSQL=1; shift ;;
        --skip-minio) SKIP_MINIO=1; shift ;;
        --skip-qdrant) SKIP_QDRANT=1; shift ;;
        --skip-config) SKIP_CONFIG=1; shift ;;
        --dry-run) DRY_RUN=1; shift ;;
        --yes) ASSUME_YES=1; shift ;;
        --no-start) NO_START=1; shift ;;
        -h|--help) usage ;;
        -*) die "Unknown option: $1" ;;
        *) ARCHIVE_FILE="$1"; shift ;;
    esac
done

[[ -n "$ARCHIVE_FILE" ]] || { usage; die "backup archive is required"; }
[[ -f "$ARCHIVE_FILE" ]] || die "backup archive not found: $ARCHIVE_FILE"

# Preflight checks
preflight() {
    command -v docker >/dev/null || die "docker not found"
    command -v openssl >/dev/null || die "openssl not found"
    command -v sha256sum >/dev/null || die "sha256sum not found"
    command -v flock >/dev/null || die "flock not found"

    [[ -f "$ENCRYPTION_KEY_FILE" ]] || die "encryption key file not found: $ENCRYPTION_KEY_FILE"
    [[ -s "$ENCRYPTION_KEY_FILE" ]] || die "encryption key file is empty"
    [[ -f "$ENV_FILE" ]] || die "environment file not found: $ENV_FILE"

    mkdir -p "$RESTORE_DIR" "$LOG_ROOT"
}

# Acquire lock
acquire_lock() {
    exec 9>"$LOCK_FILE"
    flock -n 9 || die "another restore is already running"
}

# Load environment variables
load_env() {
    set -a
    source "$ENV_FILE"
    set +a
}

# Verify backup integrity
verify_backup() {
    log "Verifying backup integrity..."

    # Check hash if available
    if [[ -f "$ARCHIVE_FILE.sha256" ]]; then
        local expected_hash actual_hash
        expected_hash="$(cat "$ARCHIVE_FILE.sha256")"
        actual_hash="$(sha256sum "$ARCHIVE_FILE" | cut -d' ' -f1)"
        [[ "$actual_hash" == "$expected_hash" ]] || die "archive hash mismatch"
        log "Hash verification passed"
    fi

    # Test decryption and list contents
    local key
    key="$(cat "$ENCRYPTION_KEY_FILE")"
    openssl enc -aes-256-cbc -d -salt -pbkdf2 -in "$ARCHIVE_FILE" -pass "pass:$key" | tar -tz > /dev/null || die "archive decryption failed"

    log "Backup integrity verified"
}

# Extract backup
extract_backup() {
    log "Extracting backup to $RESTORE_DIR..."

    rm -rf "$RESTORE_DIR"/*
    mkdir -p "$RESTORE_DIR"

    local key
    key="$(cat "$ENCRYPTION_KEY_FILE")"
    openssl enc -aes-256-cbc -d -salt -pbkdf2 -in "$ARCHIVE_FILE" -pass "pass:$key" | tar -xzf - -C "$RESTORE_DIR"

    # Find extracted directory
    EXTRACTED_DIR="$(find "$RESTORE_DIR" -mindepth 1 -maxdepth 1 -type d | head -1)"
    [[ -n "$EXTRACTED_DIR" ]] || die "extraction failed: no directory found"

    log "Backup extracted to $EXTRACTED_DIR"
}

# Read manifest
read_manifest() {
    local manifest="$EXTRACTED_DIR/manifest.json"
    [[ -f "$manifest" ]] || die "manifest not found in backup"

    log "Reading manifest..."
    cat "$manifest"
}

# Restore MySQL
restore_mysql() {
    [[ $SKIP_MYSQL -eq 0 ]] || { log "Skipping MySQL restore"; return 0; }
    [[ $DRY_RUN -eq 0 ]] || { log "[DRY RUN] Would restore MySQL"; return 0; }

    log "Restoring MySQL..."
    local dump_file="$EXTRACTED_DIR/mysql-dump.sql"
    [[ -f "$dump_file" ]] || die "MySQL dump not found in backup"

    # Verify hash
    if [[ -f "$dump_file.sha256" ]]; then
        local expected_hash actual_hash
        expected_hash="$(cat "$dump_file.sha256")"
        actual_hash="$(sha256sum "$dump_file" | cut -d' ' -f1)"
        [[ "$actual_hash" == "$expected_hash" ]] || die "MySQL dump hash mismatch"
    fi

    # Stop application to prevent writes during restore
    log "Stopping application..."
    docker compose --project-name "$PROJECT" -f "$COMPOSE_FILE" stop ylcloud-app 2>/dev/null || true

    # Restore MySQL
    log "Importing MySQL dump..."
    local mysql_user="${YLCLOUD_BACKUP_MYSQL_USER:-root}"
    local mysql_password="${YLCLOUD_BACKUP_MYSQL_PASSWORD:-${YLCLOUD_MYSQL_ROOT_PASSWORD:-}}"
    [[ -n "$mysql_password" ]] || die "MySQL restore password is empty"
    docker exec -i "$MYSQL_CONTAINER" mysql \
        -u"$mysql_user" \
        -p"$mysql_password" \
        < "$dump_file" || die "MySQL restore failed"

    log "MySQL restore completed"
}

# Restore MinIO
restore_minio() {
    [[ $SKIP_MINIO -eq 0 ]] || { log "Skipping MinIO restore"; return 0; }
    [[ $DRY_RUN -eq 0 ]] || { log "[DRY RUN] Would restore MinIO"; return 0; }

    log "Restoring MinIO..."
    local minio_dir="$EXTRACTED_DIR/minio"
    [[ -d "$minio_dir/data" ]] || die "MinIO data not found in backup"

    # Stop MinIO
    docker compose --project-name "$PROJECT" -f "$COMPOSE_FILE" stop minio 2>/dev/null || true

    # Copy data back
    log "Copying MinIO data..."
    docker cp "$minio_dir/data/." "$MINIO_CONTAINER:/data/" || die "MinIO restore failed"

    # Restart MinIO
    docker compose --project-name "$PROJECT" -f "$COMPOSE_FILE" start minio

    log "MinIO restore completed"
}

# Restore Qdrant
restore_qdrant() {
    [[ $SKIP_QDRANT -eq 0 ]] || { log "Skipping Qdrant restore"; return 0; }
    [[ $DRY_RUN -eq 0 ]] || { log "[DRY RUN] Would restore Qdrant"; return 0; }

    log "Restoring Qdrant..."
    local qdrant_dir="$EXTRACTED_DIR/qdrant"
    [[ -d "$qdrant_dir/storage" ]] || die "Qdrant data not found in backup"

    # Stop Qdrant
    docker compose --project-name "$PROJECT" -f "$COMPOSE_FILE" stop qdrant 2>/dev/null || true

    # Copy data back
    log "Copying Qdrant data..."
    docker cp "$qdrant_dir/storage/." "$QDRANT_CONTAINER:/qdrant/storage/" || die "Qdrant restore failed"

    # Restart Qdrant
    docker compose --project-name "$PROJECT" -f "$COMPOSE_FILE" start qdrant

    log "Qdrant restore completed"
}

# Restore configuration
restore_config() {
    [[ $SKIP_CONFIG -eq 0 ]] || { log "Skipping config restore"; return 0; }
    [[ $DRY_RUN -eq 0 ]] || { log "[DRY RUN] Would restore config"; return 0; }

    log "Restoring configuration..."
    local config_dir="$EXTRACTED_DIR/config"
    [[ -d "$config_dir" ]] || die "Config not found in backup"

    local compose_snapshot="$config_dir/$(basename "$COMPOSE_FILE")"
    local env_snapshot="$config_dir/$(basename "$ENV_FILE")"
    [[ -s "$compose_snapshot" ]] || die "Compose snapshot not found in backup"
    [[ -s "$env_snapshot" ]] || die "Environment snapshot not found in backup"
    cp "$compose_snapshot" "$COMPOSE_FILE"
    cp "$env_snapshot" "$ENV_FILE"

    # Restore secrets
    if [[ -d "$config_dir/secrets" ]]; then
        log "Restoring secrets..."
        cp -r "$config_dir/secrets/." ".secrets/"
        chmod 700 ".secrets"
    fi

    log "Config restore completed"
}

# Business sample verification
verify_business_sample() {
    log "Running business sample verification..."

    # Start application
    docker compose --project-name "$PROJECT" -f "$COMPOSE_FILE" up -d ylcloud-app

    # Wait for health
    local deadline=$((SECONDS + 180))
    while (( SECONDS < deadline )); do
        if curl --fail --silent "$BACKEND_URL/actuator/health" | grep -q '"status":"UP"'; then
            log "Application is healthy"
            break
        fi
        sleep 2
    done

    # Sample checks
    curl --fail --silent --show-error "$BACKEND_URL/api/site/public-settings" > /dev/null \
        || die "public settings check failed"

    log "Business sample verification passed"
}

# Cleanup
cleanup() {
    log "Cleaning up restore directory..."
    rm -rf "$RESTORE_DIR"/*
}

ISOLATED_NETWORK=""
ISOLATED_MYSQL=""
ISOLATED_MINIO=""
ISOLATED_QDRANT=""
MYSQL_OK=false
MINIO_OK=false
QDRANT_OK=false
CONFIG_OK=false
SAMPLE_OK=false

cleanup_isolated() {
    [[ -z "$ISOLATED_MYSQL" ]] || docker rm -f "$ISOLATED_MYSQL" >/dev/null 2>&1 || true
    [[ -z "$ISOLATED_MINIO" ]] || docker rm -f "$ISOLATED_MINIO" >/dev/null 2>&1 || true
    [[ -z "$ISOLATED_QDRANT" ]] || docker rm -f "$ISOLATED_QDRANT" >/dev/null 2>&1 || true
    [[ -z "$ISOLATED_NETWORK" ]] || docker network rm "$ISOLATED_NETWORK" >/dev/null 2>&1 || true
}

report_verification() {
    local status="$1" error="${2:-}" key="restore-$BACKUP_ID-$STAMP"
    [[ -n "$AUTH_TOKEN" && -n "$BACKUP_ID" ]] || die "AUTH token and --backup-id are required to publish restore verification"
    local escaped_error
    escaped_error="$(printf '%s' "$error" | sed 's/\\/\\\\/g; s/"/\\"/g')"
    curl --fail --silent --show-error -X POST \
        -H "Authorization: Bearer $AUTH_TOKEN" -H "Content-Type: application/json" \
        --data "{\"verificationKey\":\"$key\",\"status\":\"$status\",\"restoreEnvironment\":\"ISOLATED_DOCKER\",\"mysqlRestored\":$MYSQL_OK,\"minioRestored\":$MINIO_OK,\"qdrantRestored\":$QDRANT_OK,\"configRestored\":$CONFIG_OK,\"businessSampleCheck\":$SAMPLE_OK,\"errorMessage\":\"$escaped_error\"}" \
        "$BACKEND_URL/api/admin/backup/$BACKUP_ID/restore-verification"
}

isolated_failure() {
    local code=$?
    trap - ERR
    set +e
    report_verification FAILED "isolated restore failed (exit $code)" >/dev/null
    exit "$code"
}

wait_mysql() {
    for _ in $(seq 1 60); do
        docker exec "$ISOLATED_MYSQL" mysqladmin ping -h 127.0.0.1 -uroot -pverify-root --silent >/dev/null 2>&1 && return 0
        sleep 2
    done
    die "isolated MySQL did not become ready"
}

isolated_restore_verify() {
    [[ -n "$BACKUP_ID" ]] || die "--backup-id is required with --isolated-verify"
    [[ -n "$AUTH_TOKEN" ]] || die "YLCLOUD_DEPLOY_AUTH_TOKEN is required with --isolated-verify"
    ISOLATED_NETWORK="ylcloud-restore-$STAMP"
    ISOLATED_MYSQL="ylcloud-restore-mysql-$STAMP"
    ISOLATED_MINIO="ylcloud-restore-minio-$STAMP"
    ISOLATED_QDRANT="ylcloud-restore-qdrant-$STAMP"
    trap cleanup_isolated EXIT
    trap isolated_failure ERR
    docker network create "$ISOLATED_NETWORK" >/dev/null

    docker run -d --name "$ISOLATED_MYSQL" --network "$ISOLATED_NETWORK" \
        -e MYSQL_ROOT_PASSWORD=verify-root "$MYSQL_VERIFY_IMAGE" >/dev/null
    wait_mysql
    docker exec -i "$ISOLATED_MYSQL" mysql -uroot -pverify-root < "$EXTRACTED_DIR/mysql-dump.sql"
    MYSQL_OK=true

    [[ -d "$EXTRACTED_DIR/minio/data" ]] || die "isolated MinIO data is missing"
    docker run -d --name "$ISOLATED_MINIO" --network "$ISOLATED_NETWORK" \
        -e MINIO_ROOT_USER=verify -e MINIO_ROOT_PASSWORD=verify-password \
        -v "$EXTRACTED_DIR/minio/data:/data" "$MINIO_VERIFY_IMAGE" server /data >/dev/null
    docker run --rm --network "$ISOLATED_NETWORK" "$CURL_VERIFY_IMAGE" \
        --retry 30 --retry-delay 2 --retry-connrefused --fail http://"$ISOLATED_MINIO":9000/minio/health/ready >/dev/null
    MINIO_OK=true

    [[ -d "$EXTRACTED_DIR/qdrant/storage" ]] || die "isolated Qdrant data is missing"
    docker run -d --name "$ISOLATED_QDRANT" --network "$ISOLATED_NETWORK" \
        -v "$EXTRACTED_DIR/qdrant/storage:/qdrant/storage" "$QDRANT_VERIFY_IMAGE" >/dev/null
    docker run --rm --network "$ISOLATED_NETWORK" "$CURL_VERIFY_IMAGE" \
        --retry 30 --retry-delay 2 --retry-connrefused --fail http://"$ISOLATED_QDRANT":6333/collections >/dev/null
    QDRANT_OK=true

    [[ -s "$EXTRACTED_DIR/config/$(basename "$COMPOSE_FILE")" ]] || die "isolated config snapshot is missing"
    [[ -d "$EXTRACTED_DIR/config/secrets" ]] || die "isolated secret snapshot is missing"
    find "$EXTRACTED_DIR/config/secrets" -type f -empty -print -quit | grep -q . && die "isolated secret snapshot contains empty files"
    CONFIG_OK=true

    docker exec "$ISOLATED_MYSQL" mysql -uroot -pverify-root -Nse \
        "select count(*) from information_schema.tables where table_schema not in ('mysql','information_schema','performance_schema','sys')" \
        | grep -Eq '^[1-9][0-9]*$' || die "restored MySQL contains no application tables"
    SAMPLE_OK=true

    report_verification SUCCESS ""
    trap - ERR
    log "=== Isolated restore verification completed successfully ==="
}

# Main restore flow
main() {
    log "=== Starting restore from: $ARCHIVE_FILE ==="

    preflight
    acquire_lock
    load_env

    verify_backup

    if [[ $VERIFY_ONLY -eq 1 ]]; then
        log "=== Verification completed (verify-only mode) ==="
        exit 0
    fi

    extract_backup
    read_manifest

    if [[ $ISOLATED_VERIFY -eq 1 ]]; then
        isolated_restore_verify
        cleanup
        exit 0
    fi

    # Confirm restore
    if [[ $DRY_RUN -eq 0 && $ASSUME_YES -eq 0 ]]; then
        echo ""
        echo "WARNING: This will restore data from backup and may overwrite current data."
        echo "Press Ctrl+C to cancel, or Enter to continue..."
        read -r
    fi

    restore_mysql
    restore_minio
    restore_qdrant
    restore_config

    if [[ $DRY_RUN -eq 0 ]]; then
        if [[ $NO_START -eq 0 ]]; then
            verify_business_sample
        else
            log "Application start and business sample check deferred to deployment rollback"
        fi
        cleanup
    fi

    log "=== Restore completed successfully ==="
    echo "RESTORE_RESULT=SUCCESS"
}

# Run main
main
