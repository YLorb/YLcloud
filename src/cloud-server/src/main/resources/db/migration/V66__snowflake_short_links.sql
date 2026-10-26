-- Preserve existing share codes and their case-sensitive unique index.
ALTER TABLE share_link MODIFY short_code VARCHAR(11) CHARACTER SET ascii COLLATE ascii_bin NULL;

CREATE TABLE preview_short_link (
    short_code VARCHAR(11) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    target_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_url VARCHAR(1024) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_preview_short_link_target (target_hash)
);
