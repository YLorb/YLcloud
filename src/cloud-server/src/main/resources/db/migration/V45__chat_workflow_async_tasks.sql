alter table knowledge_chat_message
    add column async_task_id bigint null after generation_status,
    add column async_version bigint not null default 1 after async_task_id,
    add column async_task_type varchar(64) null after async_version,
    add index idx_chat_message_async_task (async_task_id),
    add constraint fk_chat_message_async_task foreign key (async_task_id) references async_task(id);
