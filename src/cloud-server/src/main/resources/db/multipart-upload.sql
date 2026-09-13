create table if not exists upload_task (
    id bigint primary key auto_increment,
    upload_id varchar(64) not null unique,
    user_id bigint not null,
    parent_id bigint not null,
    file_name varchar(255) not null,
    file_size bigint not null,
    file_md5 varchar(32),
    file_sha1 varchar(40),
    file_hash varchar(64) not null,
    chunk_size bigint not null,
    total_chunks int not null,
    uploaded_chunks int not null default 0,
    status int not null default 1,
    file_uuid varchar(64),
    createtime timestamp not null,
    updatetime timestamp not null,
    index idx_upload_task_user_parent_hash (user_id, parent_id, file_hash),
    index idx_upload_task_user_upload (user_id, upload_id),
    index idx_upload_task_status (status)
);

create table if not exists upload_chunk (
    id bigint primary key auto_increment,
    upload_id varchar(64) not null,
    chunk_index int not null,
    chunk_md5 varchar(32),
    size bigint not null,
    object_name varchar(255) not null,
    status int not null default 1,
    createtime timestamp not null,
    updatetime timestamp not null,
    unique key uk_upload_chunk (upload_id, chunk_index),
    index idx_upload_chunk_upload_id (upload_id)
);
