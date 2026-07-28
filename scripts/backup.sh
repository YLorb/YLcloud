#!/usr/bin/env bash
# TASK-013: Complete backup and restore verification
# Daily 04:00 (UTC+8) online backup of MySQL, MinIO, Qdrant and configs
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
BACKUP_RETENTION_COUNT="${YLCLOUD_BACKUP_RETENTION_COUNT:-1}"
LOCK_FILE="${YLCLOUD_BACKUP_LOCK_FILE:-/var/lock/ylcloud-backup.lock}"
LOG_ROOT="${YLCLOUD_BACKUP_LOG_ROOT:-/var/log/ylcloud-backup}"
BACKEND_URL="${YLCLOUD_DEPLOY_BACKEND_URL:-http://127.0.0.1:8080}"
AUTH_TOKEN="${YLCLOUD_DEPLOY_AUTH_TOKEN:-}"

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
BACKUP_DIR="$BACKUP_ROOT/$STAMP"
MANIFEST_FILE="$BACKUP_DIR/manifest.json"
ARCHIVE_FILE="$BACKUP_ROOT/ylcloud-backup-$STAMP.tar.gz.enc"

die() { echo "ERROR: $*" >&2; exit 1; }
log() { echo "[$(date -u +%Y-%m-%dT%H:%M:%SZ)] $*" >&2; }

cleanup_staging() {
    local code=$?
    if [[ "$code" -ne 0 && -d "$BACKUP_DIR" ]]; then
        rm -rf "$BACKUP_DIR"
    fi
}
trap cleanup_staging EXIT

# Preflight checks
preflight() {
    command -v docker >/dev/null || die "docker not found"
    command -v mysqldump >/dev/null || die "mysqldump not found"
    command -v openssl >/dev/null || die "openssl not found"
    command -v sha256sum >/dev/null || die "sha256sum not found"
    command -v flock >/dev/null || die "flock not found"

    [[ -f "$COMPOSE_FILE" ]] || die "compose file not found: $COMPOSE_FILE"
    [[ -f "$ENV_FILE" ]] || die "env file not found: $ENV_FILE"

    mkdir -p "$BACKUP_ROOT" "$LOG_ROOT"

    # Check encryption key
    if [[ ! -f "$ENCRYPTION_KEY_FILE" ]]; then
        log "Generating new encryption key..."
        mkdir -p "$(dirname "$ENCRYPTION_KEY_FILE")"
        openssl rand -hex 32 > "$ENCRYPTION_KEY_FILE"
        chmod 600 "$ENCRYPTION_KEY_FILE"
        log "Encryption key generated at $ENCRYPTION_KEY_FILE"
    fi
    [[ -s "$ENCRYPTION_KEY_FILE" ]] || die "encryption key file is empty"

    # Check disk space (minimum 10GB)
    local free_kb
    free_kb="$(df -Pk "$BACKUP_ROOT" | awk 'NR==2 {print $4}')"
    [[ "$free_kb" -ge 10485760 ]] || die "insufficient disk space: ${free_kb}KB available, need 10GB"

}

# Acquire lock
acquire_lock() {
    exec 9>"$LOCK_FILE"
    flock -n 9 || die "another backup is already running"
}

# Load environment variables
load_env() {
    set -a
    source "$ENV_FILE"
    set +a
}

# Backup MySQL
backup_mysql() {
    log "Backing up MySQL..."
    local dump_file="$BACKUP_DIR/mysql-dump.sql"

    docker exec "$MYSQL_CONTAINER" mysqldump \
        -u"${YLCLOUD_MYSQL_USER:-root}" \
        -p"${YLCLOUD_MYSQL_PASSWORD:-}" \
        --all-databases \
        --single-transaction \
        --routines \
        --triggers \
        --events \
        > "$dump_file" 2>/dev/null

    [[ -s "$dump_file" ]] || die "MySQL dump failed or empty"

    local hash
    hash="$(sha256sum "$dump_file" | cut -d' ' -f1)"
    echo "$hash" > "$dump_file.sha256"
    log "MySQL backup completed: $(du -h "$dump_file" | cut -f1), hash=$hash"
}

# Backup MinIO
backup_minio() {
    log "Backing up MinIO..."
    local minio_dir="$BACKUP_DIR/minio"
    mkdir -p "$minio_dir"

    # Use docker cp to copy MinIO data directory
    docker cp "$MINIO_CONTAINER:/data" "$minio_dir/" 2>/dev/null || {
        log "WARNING: MinIO backup via docker cp failed, trying mc mirror..."
        # Alternative: use mc if available
        if command -v mc >/dev/null; then
            mc alias set local "http://localhost:9000" "${MINIO_ROOT_USER:-minioadmin}" "${MINIO_ROOT_PASSWORD:-minioadmin}"
            mc mirror --quiet local/ "$minio_dir/"
        else
            die "MinIO backup failed: neither docker cp nor mc available"
        fi
    }

    # Create hash of MinIO directory
    local hash
    hash="$(find "$minio_dir" -type f -exec sha256sum {} \; | sort | sha256sum | cut -d' ' -f1)"
    echo "$hash" > "$minio_dir.sha256"
    log "MinIO backup completed: $(du -sh "$minio_dir" | cut -f1), hash=$hash"
}

# Backup Qdrant
backup_qdrant() {
    log "Backing up Qdrant..."
    local qdrant_dir="$BACKUP_DIR/qdrant"
    mkdir -p "$qdrant_dir"

    # Trigger Qdrant snapshot via API
    local qdrant_url="http://localhost:6333"
    local collections
    collections="$(curl -s "$qdrant_url/collections" | grep -o '"name":"[^"]*"' | cut -d'"' -f4 || true)"

    for collection in $collections; do
        log "Creating snapshot for collection: $collection"
        curl --fail --silent --show-error -X POST "$qdrant_url/collections/$collection/snapshots" > /dev/null \
            || die "Qdrant snapshot creation failed for collection: $collection"
    done

    # Copy Qdrant data
    docker cp "$QDRANT_CONTAINER:/qdrant/storage" "$qdrant_dir/" 2>/dev/null \
        || die "Qdrant snapshot copy failed"

    local hash
    hash="$(find "$qdrant_dir" -type f -exec sha256sum {} \; 2>/dev/null | sort | sha256sum | cut -d' ' -f1)"
    echo "$hash" > "$qdrant_dir.sha256"
    log "Qdrant backup completed: $(du -sh "$qdrant_dir" 2>/dev/null | cut -f1 || echo 'N/A'), hash=$hash"
}

# Backup configuration
backup_config() {
    log "Backing up configuration..."
    local config_dir="$BACKUP_DIR/config"
    mkdir -p "$config_dir"

    # Copy compose and env files
    cp "$COMPOSE_FILE" "$config_dir/"
    cp "$ENV_FILE" "$config_dir/"

    # Copy secrets (without exposing them in logs)
    if [[ -d ".secrets" ]]; then
        cp -r ".secrets" "$config_dir/secrets"
        chmod 700 "$config_dir/secrets"
    fi

    # Copy scripts
    if [[ -d "scripts" ]]; then
        cp -r "scripts" "$config_dir/scripts"
    fi

    local hash
    hash="$(find "$config_dir" -type f -exec sha256sum {} \; | sort | sha256sum | cut -d' ' -f1)"
    echo "$hash" > "$config_dir.sha256"
    log "Config backup completed: $(du -sh "$config_dir" | cut -f1), hash=$hash"
}

# Create manifest
create_manifest() {
    log "Creating manifest..."

    local mysql_hash minio_hash qdrant_hash config_hash
    mysql_hash="$(cat "$BACKUP_DIR/mysql-dump.sql.sha256" 2>/dev/null || echo 'N/A')"
    minio_hash="$(cat "$BACKUP_DIR/minio.sha256" 2>/dev/null || echo 'N/A')"
    qdrant_hash="$(cat "$BACKUP_DIR/qdrant.sha256" 2>/dev/null || echo 'N/A')"
    config_hash="$(cat "$BACKUP_DIR/config.sha256" 2>/dev/null || echo 'N/A')"

    cat > "$MANIFEST_FILE" <<EOF
{
    "backup_id": "$STAMP",
    "backup_type": "FULL",
    "created_at": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
    "version": "1.0",
    "components": {
        "mysql": {
            "path": "mysql-dump.sql",
            "hash": "$mysql_hash",
            "size_bytes": $(stat -f%z "$BACKUP_DIR/mysql-dump.sql" 2>/dev/null || stat -c%s "$BACKUP_DIR/mysql-dump.sql" 2>/dev/null || echo 0)
        },
        "minio": {
            "path": "minio/",
            "hash": "$minio_hash"
        },
        "qdrant": {
            "path": "qdrant/",
            "hash": "$qdrant_hash"
        },
        "config": {
            "path": "config/",
            "hash": "$config_hash"
        }
    },
    "environment": {
        "project": "$PROJECT",
        "compose_file": "$COMPOSE_FILE"
    }
}
EOF

    log "Manifest created at $MANIFEST_FILE"
}

# Create encrypted archive
create_archive() {
    log "Creating encrypted archive..."

    # Create tar.gz archive
    local tar_file="$BACKUP_ROOT/ylcloud-backup-$STAMP.tar.gz"
    tar -czf "$tar_file" -C "$BACKUP_ROOT" "$STAMP"

    # Encrypt with AES-256-CBC
    local key
    key="$(cat "$ENCRYPTION_KEY_FILE")"
    openssl enc -aes-256-cbc -salt -pbkdf2 -in "$tar_file" -out "$ARCHIVE_FILE" -pass "pass:$key"

    # Remove unencrypted archive
    rm -f "$tar_file"

    # Create hash of encrypted archive
    local hash
    hash="$(sha256sum "$ARCHIVE_FILE" | cut -d' ' -f1)"
    echo "$hash" > "$ARCHIVE_FILE.sha256"

    local size
    size="$(stat -f%z "$ARCHIVE_FILE" 2>/dev/null || stat -c%s "$ARCHIVE_FILE" 2>/dev/null || echo 0)"

    log "Archive created: $ARCHIVE_FILE ($(du -h "$ARCHIVE_FILE" | cut -f1)), hash=$hash"

    # Clean up temporary directory
    rm -rf "$BACKUP_DIR"

    echo "$ARCHIVE_FILE"
    echo "$hash"
    echo "$size"
}

# Verify archive integrity
verify_archive() {
    log "Verifying archive integrity..."

    local archive="$1"
    local expected_hash="$2"

    [[ -f "$archive" ]] || die "archive not found: $archive"

    local actual_hash
    actual_hash="$(sha256sum "$archive" | cut -d' ' -f1)"
    [[ "$actual_hash" == "$expected_hash" ]] || die "archive hash mismatch: expected=$expected_hash, actual=$actual_hash"

    # Test decryption
    local key
    key="$(cat "$ENCRYPTION_KEY_FILE")"
    openssl enc -aes-256-cbc -d -salt -pbkdf2 -in "$archive" -pass "pass:$key" | tar -tz > /dev/null || die "archive decryption or integrity check failed"

    log "Archive verification passed"
}

# Rotate old backups
rotate_backups() {
    log "Rotating old backups (keeping $BACKUP_RETENTION_COUNT READY backups)..."

    # List READY backups sorted by date (newest first)
    local backups
    backups="$(ls -1 "$BACKUP_ROOT"/ylcloud-backup-*.tar.gz.enc 2>/dev/null | sort -r || true)"

    local count=0
    for backup in $backups; do
        count=$((count + 1))
        if [[ $count -gt $BACKUP_RETENTION_COUNT ]]; then
            log "Removing old backup: $backup"
            rm -f "$backup" "$backup.sha256"
        fi
    done

    log "Rotation completed: $count backups found, kept $BACKUP_RETENTION_COUNT"
}

# Record backup completion to backend database
# This makes the backup discoverable by deploy.sh check_backup_gate via API query
record_backup_to_db() {
    local archive="$1" hash="$2" size="$3" manifest="$4" key_id="$5"

    if [[ -z "$AUTH_TOKEN" ]]; then
        die "YLCLOUD_DEPLOY_AUTH_TOKEN is required to publish backup state"
    fi

    log "Recording backup to database..."
    local http_code response
    response="$(curl --silent --show-error --write-out '\n%{http_code}' \
        -X POST \
        -H "Authorization: Bearer $AUTH_TOKEN" \
        -F "runKey=$STAMP" \
        -F "archivePath=$archive" \
        -F "archiveSize=$size" \
        -F "archiveHash=$hash" \
        -F "encryptionKeyId=$key_id" \
        -F "manifestJson=$manifest" \
        "$BACKEND_URL/api/admin/backup/record" 2>/dev/null || true)"
    http_code="$(echo "$response" | tail -1)"

    if [[ "$http_code" == "200" ]]; then
        local body backup_id
        body="$(echo "$response" | sed '$d')"
        backup_id="$(echo "$body" | sed -n 's/.*"id":\([0-9][0-9]*\).*/\1/p' | head -1)"
        [[ -n "$backup_id" ]] || die "backup record response did not contain an id"
        log "Backup recorded as VERIFYING: id=$backup_id"
        echo "$backup_id"
    else
        die "failed to record backup state (HTTP $http_code)"
    fi
}

# Main backup flow
main() {
    log "=== Starting backup: $STAMP ==="

    preflight
    acquire_lock
    load_env

    mkdir -p "$BACKUP_DIR"
    chmod 700 "$BACKUP_DIR"

    # Execute backup steps
    backup_mysql
    backup_minio
    backup_qdrant
    backup_config
    create_manifest

    # Save manifest before create_archive cleans up BACKUP_DIR
    local manifest_json
    manifest_json="$(cat "$MANIFEST_FILE" 2>/dev/null || echo '{}')"

    # Create and verify archive
    local archive_result
    archive_result="$(create_archive)"
    local archive hash size
    archive="$(echo "$archive_result" | sed -n '1p')"
    hash="$(echo "$archive_result" | sed -n '2p')"
    size="$(echo "$archive_result" | sed -n '3p')"

    verify_archive "$archive" "$hash"

    # Record backup completion to database for deploy.sh gate
    local backup_id
    local encryption_key_id
    encryption_key_id="sha256:$(sha256sum "$ENCRYPTION_KEY_FILE" | cut -d' ' -f1)"
    backup_id="$(record_backup_to_db "$archive" "$hash" "$size" "$manifest_json" "$encryption_key_id")"

    # A backup is not READY until every store is restored in disposable containers.
    bash "$(dirname "$0")/restore.sh" "$archive" --isolated-verify --backup-id "$backup_id"

    # Rotate old backups
    rotate_backups

    log "=== Backup completed successfully ==="
    log "Archive: $archive"
    log "Size: $size bytes"
    log "Hash: $hash"

    # Output result for external systems
    echo "BACKUP_RESULT=SUCCESS"
    echo "BACKUP_ARCHIVE=$archive"
    echo "BACKUP_HASH=$hash"
    echo "BACKUP_SIZE=$size"
}

# Run main
main "$@"
