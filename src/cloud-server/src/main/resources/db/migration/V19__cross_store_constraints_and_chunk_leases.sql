alter table upload_chunk
    add column lease_until timestamp null after status,
    add column upload_token varchar(64) null after lease_until;

alter table cross_store_operation
    add column external_ref varchar(255) null after resource_id;

alter table upload_task
    add column parts_cleaned_time timestamp null after merge_started_time,
    add column cleanup_attempt_count int not null default 0 after parts_cleaned_time;

alter table user_file
    add column active_status tinyint
        generated always as (case when status = 1 then 1 else null end) stored,
    add unique key uk_user_file_active_name (user_id, parent_id, is_dir, file_name, active_status);

alter table space_file
    add column active_status tinyint
        generated always as (case when status = 1 then 1 else null end) stored,
    add unique key uk_space_file_active_name (space_id, parent_id, is_dir, file_name, active_status);

alter table file_version
    add column current_file_uuid varchar(64)
        generated always as (case when status = 1 and is_current = 1 then file_uuid else null end) stored,
    add unique key uk_file_version_single_current (current_file_uuid);

alter table space_rag_task
    add column active_scope_key varchar(191)
        generated always as (
            case
                when task_status in ('PENDING', 'RUNNING')
                     and task_type in ('INDEX_FILE', 'REBUILD_FILE')
                    then concat('INDEX_FILE:', space_id, ':', coalesce(space_file_id, 0))
                when task_status in ('PENDING', 'RUNNING') and task_type = 'REBUILD_SPACE'
                    then concat('INDEX_SPACE:', space_id)
                when task_status in ('PENDING', 'RUNNING') and task_type = 'DELETE_FILE'
                    then concat('DELETE_FILE:', space_id, ':', coalesce(space_file_id, 0))
                when task_status in ('PENDING', 'RUNNING') and task_type = 'DELETE_SPACE'
                    then concat('DELETE_SPACE:', space_id)
                else null
            end
        ) stored,
    add unique key uk_space_rag_task_active_scope (active_scope_key);
