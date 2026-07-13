create table if not exists cross_store_operation (
    id bigint primary key auto_increment,
    operation_key varchar(191) not null,
    operation_type varchar(60) not null,
    operation_status varchar(20) not null default 'PENDING',
    payload_hash varchar(128) not null,
    resource_id varchar(128),
    result_ref varchar(255),
    attempt_count int not null default 0,
    lease_until timestamp null,
    error_message varchar(1000),
    createtime timestamp not null,
    updatetime timestamp not null,
    unique key uk_cross_store_operation_key (operation_key),
    index idx_cross_store_operation_status (operation_status, lease_until, updatetime)
);

alter table upload_task
    add column file_key varchar(64) null after upload_id,
    add column last_activity_time timestamp null after file_uuid,
    add column merge_started_time timestamp null after last_activity_time,
    add column active_file_key varchar(64)
        generated always as (case when status in (1, 4, 5) then file_key else null end) stored after merge_started_time;

update upload_task
set file_key = sha2(concat(user_id, ':', parent_id, ':', lower(trim(file_name))), 256),
    last_activity_time = coalesce(updatetime, createtime)
where file_key is null;

alter table upload_task
    modify column file_key varchar(64) not null,
    add unique key uk_upload_task_active_file (active_file_key),
    add index idx_upload_task_stale (status, last_activity_time);
