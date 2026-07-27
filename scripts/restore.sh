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
MYSQL_CONTAINER="${YLCLOUD_MYSQL_CONTAINER:-mysql}"
MINIO_CONTAINER="${YLCLOUD_MINIO_CONTAINER:-minio}"
QDRANT_CONTAINER="${YLCLOUD_QDRANT_CONTAINER:-qdrant}"
RESTORE_DIR="${YLCLOUD_RESTORE_DIR:-/var/lib/ylcloud-restore}"
LOCK_FILE="${YLCLOUD_RESTORE_LOCK_FILE:-/var/lock/ylcloud-restore.lock}"
LOG_ROOT="${YLCLOUD_RESTORE_LOG_ROOT:-/var/log/ylcloud-restore}"

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
    --skip-mysql      Skip MySQL restore
    --skip-minio      Skip MinIO restore
    --skip-qdrant     Skip Qdrant restore
    --skip-config     Skip config restore
    --dry-run         Show what would be restored without making changes
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

while [[ $# -gt 0 ]]; do
    case "$1" in
        --verify-only) VERIFY_ONLY=1; shift ;;
        --skip-mysql) SKIP_MYSQL=1; shift ;;
        --skip-minio) SKIP_MINIO=1; shift ;;
        --skip-qdrant) SKIP_QDRANT=1; shift ;;
        --skip-config) SKIP_CONFIG=1; shift ;;
        --dry-run) DRY_RUN=1; shift ;;
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
    command -v mysql >/dev/null || die "mysql client not found"
    command -v openssl >/dev/null || die "openssl not found"
    command -v sha256sum >/dev/null || die "sha256sum not found"
    command -v flock >/dev/null || die "flock not found"

    [[ -f "$ENCRYPTION_KEY_FILE" ]] || die "encryption key file not found: $ENCRYPTION_KEY_FILE"
    [[ -s "$ENCRYPTION_KEY_FILE" ]] || die "encryption key file is empty"

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
    docker exec -i "$MYSQL_CONTAINER" mysql \
        -u"${MYSQL_USER:-root}" \
        -p"${MYSQL_PASSWORD:-}" \
        < "$dump_file" || die "MySQL restore failed"

    log "MySQL restore completed"
}

# Restore MinIO
restore_minio() {
    [[ $SKIP_MINIO -eq 0 ]] || { log "Skipping MinIO restore"; return 0; }
    [[ $DRY_RUN -eq 0 ]] || { log "[DRY RUN] Would restore MinIO"; return 0; }

    log "Restoring MinIO..."
    local minio_dir="$EXTRACTED_DIR/minio"
    [[ -d "$minio_dir" ]] || { log "WARNING: MinIO data not found in backup"; return 0; }

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
    [[ -d "$qdrant_dir" ]] || { log "WARNING: Qdrant data not found in backup"; return 0; }

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
    [[ -d "$config_dir" ]] || { log "WARNING: Config not found in backup"; return 0; }

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
        if curl -s "http://localhost:8080/actuator/health" | grep -q '"status":"UP"'; then
            log "Application is healthy"
            break
        fi
        sleep 2
    done

    # Sample checks
    curl -s "http://localhost:8080/api/site/public-settings" > /dev/null || die "public settings check failed"

    log "Business sample verification passed"
}

# Cleanup
cleanup() {
    log "Cleaning up restore directory..."
    rm -rf "$RESTORE_DIR"/*
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

    # Confirm restore
    if [[ $DRY_RUN -eq 0 ]]; then
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
        verify_business_sample
        cleanup
    fi

    log "=== Restore completed successfully ==="
    echo "RESTORE_RESULT=SUCCESS"
}

# Run main
main
