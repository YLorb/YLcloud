alter table async_task
    add column parent_task_id bigint null after space_id,
    add index idx_async_task_parent (parent_task_id),
    add constraint fk_async_task_parent foreign key (parent_task_id) references async_task(id) on delete set null;

alter table space_rag_task
    add column parent_task_id bigint null after document_id,
    add column resource_version bigint not null default 1 after async_task_id,
    add column clear_vectors tinyint not null default 0 after resource_version,
    add column fanout_cursor bigint not null default 0 after clear_vectors,
    add index idx_space_rag_parent (parent_task_id),
    add unique key uk_space_rag_parent_file_type (parent_task_id,space_file_id,task_type),
    add constraint fk_space_rag_parent foreign key (parent_task_id) references space_rag_task(id) on delete set null,
    add constraint fk_space_rag_async_task foreign key (async_task_id) references async_task(id);
