-- Direct content metadata writers must also invalidate same-UUID shares.
CREATE TRIGGER file_info_share_revision BEFORE UPDATE ON file_info FOR EACH ROW
 SET NEW.share_revision = IF(NOT (OLD.hash <=> NEW.hash) OR NOT (OLD.md5 <=> NEW.md5) OR OLD.size <> NEW.size,
     OLD.share_revision + 1, NEW.share_revision),
 NEW.share_replaced_at = IF(NOT (OLD.hash <=> NEW.hash) OR NOT (OLD.md5 <=> NEW.md5) OR OLD.size <> NEW.size,
     UTC_TIMESTAMP(6), NEW.share_replaced_at);
-- Preserve a recovery copy before retiring all old share records.
-- This archive is never consulted by any public endpoint.
CREATE TABLE file_share_retired LIKE file_share;
INSERT INTO file_share_retired SELECT * FROM file_share;
DELETE FROM file_share;
