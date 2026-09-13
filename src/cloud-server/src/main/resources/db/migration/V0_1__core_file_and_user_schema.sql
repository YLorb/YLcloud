create table if not exists users (
    user_id bigint primary key auto_increment,
    username varchar(100) not null,
    password varchar(100) not null,
    nickname varchar(100) not null,
    root_id bigint null,
    email varchar(255) null,
    avatar varchar(1000) null,
    status int not null default 1,
    role varchar(20) not null default 'USER',
    create_time timestamp not null default current_timestamp,
    update_time timestamp not null default current_timestamp on update current_timestamp,
    unique key uk_users_username (username),
    index idx_users_status (status)
);

create table if not exists file_info (
    file_id bigint primary key auto_increment,
    file_uuid varchar(64) not null,
    name varchar(255) not null,
    type varchar(100) null,
    size bigint not null default 0,
    md5 varchar(32) null,
    sha1 varchar(40) null,
    hash varchar(128) null,
    status int not null default 1,
    count int not null default 1,
    createtime timestamp not null default current_timestamp,
    updatetime timestamp not null default current_timestamp on update current_timestamp,
    unique key uk_file_info_uuid (file_uuid),
    index idx_file_info_hash (hash),
    index idx_file_info_md5_sha1_size (md5, sha1, size),
    index idx_file_info_status (status)
);

create table if not exists user_file (
    id bigint primary key auto_increment,
    file_name varchar(255) not null,
    file_uuid varchar(64) null,
    is_dir int not null default 0,
    status int not null default 1,
    user_id bigint not null,
    parent_id bigint not null default 0,
    path varchar(1000) null,
    createtime timestamp not null default current_timestamp,
    updatetime timestamp not null default current_timestamp on update current_timestamp,
    index idx_user_file_user_parent (user_id, parent_id, status),
    index idx_user_file_uuid (file_uuid),
    index idx_user_file_status (status)
);

create table if not exists upload_task (
    id bigint primary key auto_increment,
    upload_id varchar(64) not null,
    user_id bigint not null,
    parent_id bigint not null,
    file_name varchar(255) not null,
    file_size bigint not null,
    file_md5 varchar(32) null,
    file_sha1 varchar(40) null,
    file_hash varchar(128) not null,
    chunk_size bigint not null,
    total_chunks int not null,
    uploaded_chunks int not null default 0,
    status int not null default 1,
    file_uuid varchar(64) null,
    createtime timestamp not null default current_timestamp,
    updatetime timestamp not null default current_timestamp on update current_timestamp,
    unique key uk_upload_task_upload_id (upload_id),
    index idx_upload_task_user_parent_hash (user_id, parent_id, file_hash),
    index idx_upload_task_user_upload (user_id, upload_id),
    index idx_upload_task_status (status)
);

create table if not exists upload_chunk (
    id bigint primary key auto_increment,
    upload_id varchar(64) not null,
    chunk_index int not null,
    chunk_md5 varchar(32) null,
    size bigint not null,
    object_name varchar(1000) not null,
    status int not null default 1,
    createtime timestamp not null default current_timestamp,
    updatetime timestamp not null default current_timestamp on update current_timestamp,
    unique key uk_upload_chunk (upload_id, chunk_index),
    index idx_upload_chunk_upload_id (upload_id)
);

create table if not exists file_share (
    id bigint primary key auto_increment,
    share_code varchar(64) not null,
    user_file_id bigint not null,
    file_uuid varchar(64) not null,
    owner_id bigint not null,
    status int not null default 1,
    createtime timestamp not null default current_timestamp,
    updatetime timestamp not null default current_timestamp on update current_timestamp,
    unique key uk_file_share_code (share_code),
    index idx_file_share_user_file_id (user_file_id),
    index idx_file_share_owner_id (owner_id)
);
