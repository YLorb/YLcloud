alter table space_knowledge_document_profile
    add column source_snapshot_signature varchar(128) null after source_character_count,
    add column source_snapshot_revision bigint not null default 0 after source_snapshot_signature;
