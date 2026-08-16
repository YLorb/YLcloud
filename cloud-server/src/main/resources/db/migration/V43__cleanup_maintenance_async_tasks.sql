alter table physical_file_cleanup_task
    add column async_task_id bigint null after error_message,
    add column resource_version bigint not null default 1 after async_task_id,
    add index idx_physical_cleanup_async_task (async_task_id),
    add constraint fk_physical_cleanup_async_task foreign key (async_task_id) references async_task(id);

alter table upload_task
    add column cleanup_async_task_id bigint null after cleanup_attempt_count,
    add index idx_upload_cleanup_async_task (cleanup_async_task_id),
    add constraint fk_upload_cleanup_async_task foreign key (cleanup_async_task_id) references async_task(id);

alter table cross_store_operation
    add column recovery_async_task_id bigint null after error_message,
    add index idx_cross_store_recovery_async_task (recovery_async_task_id),
    add constraint fk_cross_store_recovery_async_task foreign key (recovery_async_task_id) references async_task(id);

alter table space_rag_document
    add column consistency_version bigint not null default 1 after vector_state,
    add column consistency_async_task_id bigint null after consistency_version,
    add index idx_rag_document_consistency_task (consistency_async_task_id),
    add constraint fk_rag_document_consistency_task foreign key (consistency_async_task_id) references async_task(id);
