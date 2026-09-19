CREATE TABLE share_link (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 creator_id BIGINT NOT NULL,
 source_type VARCHAR(16) NOT NULL,
 source_id BIGINT NOT NULL,
 space_id BIGINT NULL,
 file_uuid VARCHAR(64) NULL,
 content_revision BIGINT NOT NULL DEFAULT 0,
 short_code VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NULL,
 algorithm_version INT NOT NULL DEFAULT 1,
 expires_at DATETIME(6) NULL,
 max_downloads BIGINT NULL,
 download_count BIGINT NOT NULL DEFAULT 0,
 password_enabled BOOLEAN NOT NULL DEFAULT FALSE,
 password_hash VARCHAR(255) NULL,
 force_download BOOLEAN NOT NULL DEFAULT FALSE,
 revoked_at DATETIME(6) NULL,
 created_at DATETIME(6) NOT NULL,
 updated_at DATETIME(6) NOT NULL,
 version BIGINT NOT NULL DEFAULT 1,
 UNIQUE KEY uk_share_link_code (short_code),
 INDEX idx_share_link_source (creator_id, source_type, source_id),
 INDEX idx_share_link_created (creator_id, id),
 CHECK (max_downloads IS NULL OR max_downloads > 0),
 CHECK (download_count >= 0),
 CHECK (NOT (password_enabled AND force_download))
);
CREATE TABLE share_link_visit (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 link_id BIGINT NULL,
 requested_code VARCHAR(64) NOT NULL,
 user_id BIGINT NULL,
 ip VARCHAR(64) NULL,
 city VARCHAR(100) NOT NULL DEFAULT '-',
 visited_at DATETIME(6) NOT NULL,
 outcome VARCHAR(32) NOT NULL,
 INDEX idx_share_visit_time (visited_at),
 INDEX idx_share_visit_link (link_id, id)
);
ALTER TABLE user_file ADD COLUMN share_revision BIGINT NOT NULL DEFAULT 0,
 ADD COLUMN share_replaced_at DATETIME(6) NULL;
ALTER TABLE space_file ADD COLUMN share_revision BIGINT NOT NULL DEFAULT 0,
 ADD COLUMN share_replaced_at DATETIME(6) NULL;
-- Database triggers cover every writer, including restore/version APIs.
CREATE TRIGGER user_file_share_revision BEFORE UPDATE ON user_file FOR EACH ROW
 SET NEW.share_revision = IF(NOT (OLD.file_uuid <=> NEW.file_uuid), OLD.share_revision + 1, OLD.share_revision),
 NEW.share_replaced_at = IF(NOT (OLD.file_uuid <=> NEW.file_uuid), UTC_TIMESTAMP(6), OLD.share_replaced_at);
CREATE TRIGGER space_file_share_revision BEFORE UPDATE ON space_file FOR EACH ROW
 SET NEW.share_revision = IF(NOT (OLD.file_uuid <=> NEW.file_uuid), OLD.share_revision + 1, OLD.share_revision),
 NEW.share_replaced_at = IF(NOT (OLD.file_uuid <=> NEW.file_uuid), UTC_TIMESTAMP(6), OLD.share_replaced_at);
ALTER TABLE file_info ADD COLUMN share_revision BIGINT NOT NULL DEFAULT 0,
 ADD COLUMN share_replaced_at DATETIME(6) NULL;
CREATE TRIGGER file_version_share_revision AFTER INSERT ON file_version FOR EACH ROW
 UPDATE file_info SET share_revision = share_revision + 1, share_replaced_at = UTC_TIMESTAMP(6)
 WHERE file_uuid = NEW.file_uuid AND NEW.version_no > 1;
