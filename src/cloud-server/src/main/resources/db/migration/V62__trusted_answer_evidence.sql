create table rag_evidence (
    id varchar(36) primary key,
    user_id bigint not null,
    space_id bigint not null,
    space_file_id bigint not null,
    revision_id bigint not null,
    chunk_id bigint not null,
    file_hash varchar(128) not null,
    content mediumtext not null,
    citation_json mediumtext not null,
    created_at datetime(6) not null,
    index idx_rag_evidence_revision (revision_id),
    index idx_rag_evidence_owner (user_id, created_at)
);

create table rag_trusted_answer (
    id varchar(36) primary key,
    user_id bigint not null,
    content mediumtext not null,
    answer_status varchar(32) not null default 'SERVER_RESULT',
    citations_json mediumtext not null,
    space_ids_json text not null,
    created_at datetime(6) not null,
    index idx_rag_answer_owner (user_id, created_at)
);

alter table knowledge_chat_message add column trusted_answer_id varchar(36) null;
create index idx_chat_trusted_answer on knowledge_chat_message (trusted_answer_id);
