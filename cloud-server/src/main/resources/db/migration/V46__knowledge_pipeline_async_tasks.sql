alter table space_knowledge_pipeline_task
    add column parent_task_id bigint null after document_id,
    add column resource_version bigint not null default 1 after async_task_id,
    add index idx_knowledge_pipeline_parent (parent_task_id),
    add unique key uk_knowledge_pipeline_parent_document (parent_task_id,document_id),
    add constraint fk_knowledge_pipeline_parent foreign key (parent_task_id) references space_knowledge_pipeline_task(id),
    add constraint fk_knowledge_pipeline_async_task foreign key (async_task_id) references async_task(id);
