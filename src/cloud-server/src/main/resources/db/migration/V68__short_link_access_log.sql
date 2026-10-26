ALTER TABLE share_link_visit
    ADD COLUMN log_type VARCHAR(32) NOT NULL DEFAULT 'SHARE_OPEN',
    ADD COLUMN access_address VARCHAR(512) NULL,
    ADD COLUMN access_info JSON NULL;

-- Old rows remain readable; business authorization no longer depends on log IDs.
ALTER TABLE preview_short_link
    ADD COLUMN checked_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    ADD INDEX idx_preview_short_link_checked (checked_at);
