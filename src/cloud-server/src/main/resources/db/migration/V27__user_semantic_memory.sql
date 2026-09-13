create table user_memory_setting (
    user_id bigint not null primary key,
    enabled tinyint not null default 1,
    retention_days int not null default 365,
    createtime datetime not null,
    updatetime datetime not null
);

create table user_memory_item (
    id bigint not null auto_increment primary key,
    user_id bigint not null,
    source_session_id bigint not null,
    source_message_id bigint not null,
    memory_type varchar(32) not null,
    content varchar(2000) not null,
    normalized_key varchar(190) not null,
    content_hash char(64) not null,
    source_hash char(64) not null,
    confidence decimal(5,4) not null default 0,
    user_confirmed tinyint not null default 0,
    pinned tinyint not null default 0,
    expires_at datetime null,
    version int not null default 1,
    memory_status varchar(32) not null,
    embedding_status varchar(32) not null,
    qdrant_point_id varchar(36) null,
    supersedes_id bigint null,
    retry_count int not null default 0,
    next_retry_time datetime null,
    error_message varchar(1000) null,
    status tinyint not null default 1,
    createtime datetime not null,
    updatetime datetime not null,
    unique key uk_user_memory_source (user_id, normalized_key, source_hash),
    key idx_user_memory_active (user_id, memory_status, expires_at),
    key idx_user_memory_retry (embedding_status, next_retry_time),
    key idx_user_memory_session (user_id, source_session_id, pinned)
);

create table user_memory_extraction_task (
    id bigint not null auto_increment primary key,
    assistant_message_id bigint not null,
    user_id bigint not null,
    session_id bigint not null,
    source_message_id bigint not null,
    task_status varchar(32) not null,
    retry_count int not null default 0,
    next_retry_time datetime null,
    error_message varchar(1000) null,
    createtime datetime not null,
    updatetime datetime not null,
    unique key uk_user_memory_extraction_message (assistant_message_id),
    key idx_user_memory_extraction_retry (task_status, next_retry_time)
);
