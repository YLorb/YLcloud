-- TASK-013 P0 Fix: Add encrypted key storage columns to data_export_job
-- The AES-256-GCM per-export key is wrapped with the server master key before storage,
-- so a database compromise alone cannot decrypt exported files.
alter table data_export_job
    add column encrypted_key varchar(512) null after encryption_key_id,
    add column encrypted_iv varchar(128) null after encrypted_key;
