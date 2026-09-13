-- TASK-010: Account cancellation, deletion and data export
-- User lifecycle: ACTIVE(1) -> CANCELLED(2) -> PURGING(3) -> PURGED(4)

-- Add lifecycle fields to users table
alter table users
    add column account_status varchar(20) not null default 'ACTIVE' after status,
    add column cancelled_at timestamp null after account_status,
    add column cancel_requested_by bigint null after cancelled_at,
    add column recoverable_until timestamp null after cancel_requested_by,
    add column purging_started_at timestamp null after recoverable_until,
    add column purged_at timestamp null after purging_started_at,
    add index idx_users_account_status (account_status),
    add index idx_users_recoverable (account_status, recoverable_until),
    add constraint chk_users_account_status check (account_status in ('ACTIVE','CANCELLED','PURGING','PURGED'));

-- Account deletion job: orchestrates the async deletion process
create table account_deletion_job (
    id bigint primary key auto_increment,
    user_id bigint not null,
    job_key varchar(191) not null,
    status varchar(30) not null default 'PENDING',
    current_step varchar(50) null,
    step_result_json json null,
    async_task_id bigint null,
    started_at timestamp null,
    finished_at timestamp null,
    last_error varchar(1000) null,
    retry_count int not null default 0,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp on update current_timestamp,
    unique key uk_deletion_job_key (job_key),
    unique key uk_deletion_job_user (user_id),
    index idx_deletion_job_status (status),
    constraint fk_deletion_job_user foreign key (user_id) references users(user_id),
    constraint fk_deletion_job_async_task foreign key (async_task_id) references async_task(id),
    constraint chk_deletion_job_status check (status in ('PENDING','RUNNING','COMPLETED','FAILED','CANCELED'))
);

-- Data export job: async encrypted export with download expiry
create table data_export_job (
    id bigint primary key auto_increment,
    user_id bigint not null,
    job_key varchar(191) not null,
    status varchar(30) not null default 'PENDING',
    export_scope varchar(50) not null default 'FULL',
    encryption_key_id varchar(100) null,
    storage_path varchar(500) null,
    file_size_bytes bigint null,
    file_hash varchar(128) null,
    download_url varchar(1000) null,
    download_expires_at timestamp null,
    async_task_id bigint null,
    started_at timestamp null,
    finished_at timestamp null,
    last_error varchar(1000) null,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp on update current_timestamp,
    unique key uk_export_job_key (job_key),
    index idx_export_job_user (user_id),
    index idx_export_job_status (status),
    constraint fk_export_job_user foreign key (user_id) references users(user_id),
    constraint fk_export_job_async_task foreign key (async_task_id) references async_task(id),
    constraint chk_export_job_status check (status in ('PENDING','RUNNING','COMPLETED','FAILED','EXPIRED')),
    constraint chk_export_scope check (export_scope in ('FULL','PERSONAL_FILES','SPACE_DATA','CHAT_HISTORY','MEMORY'))
);

-- Account recovery audit log
create table account_recovery_log (
    id bigint primary key auto_increment,
    user_id bigint not null,
    recovered_by bigint not null,
    previous_status varchar(20) not null,
    recovery_reason varchar(500) null,
    created_at timestamp not null default current_timestamp,
    index idx_recovery_log_user (user_id),
    constraint fk_recovery_log_user foreign key (user_id) references users(user_id),
    constraint fk_recovery_log_admin foreign key (recovered_by) references users(user_id)
);
