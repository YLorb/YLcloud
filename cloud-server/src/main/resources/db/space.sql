create table if not exists spaces (
    id bigint primary key auto_increment,
    name varchar(100) not null,
    description varchar(500),
    type varchar(20) not null default 'TEAM',
    owner_id bigint not null,
    root_dir_id bigint,
    rag_status int not null default 1,
    version_enabled int not null default 1,
    status int not null default 1,
    createtime timestamp not null,
    updatetime timestamp not null,
    index idx_spaces_owner_id (owner_id)
);

create table if not exists space_member (
    id bigint primary key auto_increment,
    space_id bigint not null,
    user_id bigint not null,
    role varchar(20) not null,
    status int not null default 1,
    createtime timestamp not null,
    updatetime timestamp not null,
    unique key uk_space_user (space_id, user_id),
    index idx_space_member_user_id (user_id)
);

create table if not exists space_file (
    id bigint primary key auto_increment,
    space_id bigint not null,
    file_uuid varchar(64),
    file_name varchar(255) not null,
    is_dir int not null,
    parent_id bigint not null,
    path varchar(512),
    version_enabled int null,
    status int not null default 1,
    created_by bigint not null,
    createtime timestamp not null,
    updatetime timestamp not null,
    index idx_space_file_parent (space_id, parent_id),
    index idx_space_file_uuid (file_uuid)
);

create table if not exists space_rag_config (
    id bigint primary key auto_increment,
    space_id bigint not null unique,
    embedding_model varchar(100),
    chat_model varchar(100),
    vector_collection varchar(100) not null,
    chunk_size int not null default 1000,
    chunk_overlap int not null default 100,
    top_k int not null default 5,
    score_threshold decimal(6,4) default 0.0000,
    enabled int not null default 1,
    status int not null default 1,
    createtime timestamp not null,
    updatetime timestamp not null,
    index idx_space_rag_config_space_id (space_id)
);

create table if not exists space_rag_document (
    id bigint primary key auto_increment,
    space_id bigint not null,
    space_file_id bigint not null,
    file_uuid varchar(64) not null,
    file_name varchar(255) not null,
    file_hash varchar(128),
    file_type varchar(50),
    index_status varchar(20) not null default 'PENDING',
    chunk_count int not null default 0,
    error_message varchar(1000),
    created_by bigint not null,
    status int not null default 1,
    createtime timestamp not null,
    updatetime timestamp not null,
    unique key uk_space_rag_document_file (space_id, space_file_id),
    index idx_space_rag_document_space_id (space_id),
    index idx_space_rag_document_file_uuid (file_uuid),
    index idx_space_rag_document_status (index_status)
);

create table if not exists file_rag_chunk (
    id bigint primary key auto_increment,
    file_uuid varchar(64) not null,
    file_hash varchar(128) not null,
    chunk_index int not null,
    content longtext not null,
    content_hash varchar(128),
    token_count int default 0,
    metadata json,
    vector_id varchar(128),
    embedding_model varchar(100),
    chunk_size int,
    chunk_overlap int,
    status int not null default 1,
    createtime timestamp not null,
    updatetime timestamp not null,
    unique key uk_file_rag_chunk_index (file_uuid, file_hash, chunk_index),
    index idx_file_rag_chunk_file_uuid (file_uuid),
    index idx_file_rag_chunk_file_hash (file_hash),
    index idx_file_rag_chunk_vector_id (vector_id)
);

create table if not exists file_version (
    id bigint primary key auto_increment,
    file_uuid varchar(64) not null,
    version_no int not null,
    minio_version_id varchar(255) not null,
    file_name varchar(255) not null,
    file_hash varchar(128),
    file_md5 varchar(128),
    file_type varchar(50),
    file_size bigint,
    change_note varchar(500),
    created_by bigint not null,
    is_current int not null default 0,
    status int not null default 1,
    createtime timestamp not null,
    unique key uk_file_version_no (file_uuid, version_no),
    index idx_file_version_file_uuid (file_uuid),
    index idx_file_version_minio_version_id (minio_version_id),
    index idx_file_version_current (file_uuid, is_current)
);

create table if not exists space_rag_chunk_ref (
    id bigint primary key auto_increment,
    space_id bigint not null,
    document_id bigint not null,
    space_file_id bigint not null,
    file_chunk_id bigint not null,
    status int not null default 1,
    createtime timestamp not null,
    updatetime timestamp not null,
    unique key uk_space_rag_chunk_ref (space_id, document_id, file_chunk_id),
    index idx_space_rag_chunk_ref_space_id (space_id),
    index idx_space_rag_chunk_ref_document_id (document_id),
    index idx_space_rag_chunk_ref_space_file_id (space_file_id),
    index idx_space_rag_chunk_ref_file_chunk_id (file_chunk_id)
);

create table if not exists space_rag_task (
    id bigint primary key auto_increment,
    space_id bigint not null,
    space_file_id bigint,
    document_id bigint,
    task_type varchar(30) not null,
    task_status varchar(20) not null default 'PENDING',
    error_message varchar(1000),
    created_by bigint not null,
    started_time timestamp null,
    finished_time timestamp null,
    createtime timestamp not null,
    updatetime timestamp not null,
    index idx_space_rag_task_space_id (space_id),
    index idx_space_rag_task_space_file_id (space_file_id),
    index idx_space_rag_task_status (task_status),
    index idx_space_rag_task_type (task_type)
);

create table if not exists space_rag_query_log (
    id bigint primary key auto_increment,
    space_id bigint not null,
    user_id bigint not null,
    question text not null,
    answer longtext,
    hit_chunk_ids varchar(1000),
    model_name varchar(100),
    prompt_tokens int default 0,
    completion_tokens int default 0,
    total_tokens int default 0,
    success int not null default 1,
    error_message varchar(1000),
    createtime timestamp not null,
    index idx_space_rag_query_log_space_id (space_id),
    index idx_space_rag_query_log_user_id (user_id),
    index idx_space_rag_query_log_createtime (createtime)
);
