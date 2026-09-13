alter table user_memory_extraction_task
    add column async_task_id bigint null after error_message,
    add column resource_version bigint not null default 1 after async_task_id,
    add index idx_memory_extraction_async_task (async_task_id),
    add constraint fk_memory_extraction_async_task foreign key (async_task_id) references async_task(id);

alter table user_memory_item
    add column origin_async_task_id bigint null after error_message,
    add column profile_async_task_id bigint null after origin_async_task_id,
    add column vector_async_task_id bigint null after profile_async_task_id,
    add column async_version bigint not null default 1 after vector_async_task_id,
    add index idx_memory_origin_async_task (origin_async_task_id),
    add index idx_memory_profile_async_task (profile_async_task_id),
    add index idx_memory_vector_async_task (vector_async_task_id),
    add constraint fk_memory_origin_async_task foreign key (origin_async_task_id) references async_task(id),
    add constraint fk_memory_profile_async_task foreign key (profile_async_task_id) references async_task(id),
    add constraint fk_memory_vector_async_task foreign key (vector_async_task_id) references async_task(id);
