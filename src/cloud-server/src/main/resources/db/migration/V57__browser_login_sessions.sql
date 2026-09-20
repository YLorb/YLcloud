ALTER TABLE users ADD COLUMN session_version BIGINT NOT NULL DEFAULT 0;

CREATE TABLE user_login_session (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    session_id_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id BIGINT NOT NULL,
    session_version BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    last_seen_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6) NULL,
    revoke_reason VARCHAR(40) NULL,
    UNIQUE KEY uk_login_session_hash (session_id_hash),
    KEY ix_login_session_user (user_id, revoked_at),
    KEY ix_login_session_expiry (expires_at)
);

CREATE TABLE login_rate_bucket (
    bucket_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    window_start BIGINT NOT NULL,
    attempts INT NOT NULL,
    PRIMARY KEY (bucket_key, window_start),
    KEY ix_login_rate_window (window_start)
);
