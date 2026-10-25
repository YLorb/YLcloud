-- Immutable evidence generations are separate from mutable document task state.
create table knowledge_revision (
    id bigint primary key auto_increment,
    space_id bigint not null,
    space_file_id bigint not null,
    document_id bigint not null,
    file_uuid varchar(128) not null,
    file_hash varchar(128) not null,
    file_version_id bigint not null,
    minio_version_id varchar(255) not null,
    manifest_hash varchar(64) not null,
    published_at datetime(6) not null,
    superseded_at datetime(6) null,
    retained_until datetime(6) null,
    active_node_id bigint generated always as (case when superseded_at is null then space_file_id else null end) stored,
    unique key uk_knowledge_revision_manifest(space_id,space_file_id,file_version_id,manifest_hash),
    unique key uk_knowledge_revision_current(space_id,active_node_id),
    index idx_knowledge_revision_node(space_id,space_file_id,published_at),
    index idx_knowledge_revision_physical(file_uuid,retained_until)
);
create table knowledge_revision_chunk (
    revision_id bigint not null,
    chunk_id bigint not null,
    primary key(revision_id,chunk_id),
    index idx_knowledge_revision_chunk(chunk_id,revision_id)
);
-- A durable request, written in the same transaction as the file change.
-- A crashed dispatcher can safely retry; newer content makes an intent obsolete.
create table knowledge_reindex_intent (
    id bigint primary key auto_increment,
    space_id bigint not null,
    space_file_id bigint not null,
    file_hash varchar(128) not null,
    created_by bigint not null,
    state varchar(24) not null default 'PENDING',
    claim_token varchar(36) null,
    attempts int not null default 0,
    error_message varchar(1000) null,
    next_retry_at datetime(6) null,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    unique key uk_knowledge_reindex_content(space_id,space_file_id,file_hash),
    index idx_knowledge_reindex_pending(state,next_retry_at)
);
