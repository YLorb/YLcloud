-- TASK-013: Backup and restore verification
-- Backup run tracking and restore verification

create table backup_run (
    id bigint primary key auto_increment,
    run_key varchar(191) not null,
    backup_type varchar(30) not null default 'FULL',
    status varchar(30) not null default 'PENDING',
    manifest_json json null,
    mysql_dump_path varchar(500) null,
    minio_snapshot_path varchar(500) null,
    qdrant_snapshot_path varchar(500) null,
    config_snapshot_path varchar(500) null,
    archive_path varchar(500) null,
    archive_size_bytes bigint null,
    archive_hash varchar(128) null,
    encryption_key_id varchar(100) null,
    started_at timestamp null,
    finished_at timestamp null,
    verified_at timestamp null,
    published_at timestamp null,
    last_error varchar(1000) null,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp on update current_timestamp,
    unique key uk_backup_run_key (run_key),
    index idx_backup_run_status (status),
    index idx_backup_run_type_status (backup_type, status),
    constraint chk_backup_type check (backup_type in ('FULL','INCREMENTAL','CONFIG_ONLY')),
    constraint chk_backup_status check (status in ('PENDING','RUNNING','VERIFYING','READY','FAILED','EXPIRED'))
);

create table restore_verification (
    id bigint primary key auto_increment,
    backup_run_id bigint not null,
    verification_key varchar(191) not null,
    status varchar(30) not null default 'PENDING',
    restore_environment varchar(100) null,
    mysql_restored tinyint not null default 0,
    minio_restored tinyint not null default 0,
    qdrant_restored tinyint not null default 0,
    config_restored tinyint not null default 0,
    business_sample_check tinyint not null default 0,
    started_at timestamp null,
    finished_at timestamp null,
    last_error varchar(1000) null,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp on update current_timestamp,
    unique key uk_restore_verification_key (verification_key),
    index idx_restore_verification_backup (backup_run_id),
    index idx_restore_verification_status (status),
    constraint fk_restore_verification_backup foreign key (backup_run_id) references backup_run(id),
    constraint chk_restore_status check (status in ('PENDING','RUNNING','SUCCESS','FAILED'))
);

-- Backup retention configuration
create table backup_retention_config (
    config_key varchar(64) primary key,
    max_ready_backups int not null default 1,
    retention_days int null,
    description varchar(500) null,
    updated_at timestamp not null default current_timestamp on update current_timestamp,
    constraint chk_max_ready check (max_ready_backups >= 1)
);

insert into backup_retention_config(config_key, max_ready_backups, retention_days, description)
values ('DEFAULT', 1, 7, '默认保留1份READY备份，7天过期')
on duplicate key update description = values(description);
