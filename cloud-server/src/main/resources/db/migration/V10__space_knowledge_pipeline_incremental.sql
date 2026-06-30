alter table space_knowledge_document_profile
    add column source_file_hash varchar(128) null,
    add column source_parser_version varchar(80) null,
    add column profile_schema_version varchar(80) null;

alter table space_knowledge_pipeline_task
    add column force_rebuild tinyint(1) not null default 0,
    add column terminal_stage varchar(60) null,
    add column terminal_reason varchar(120) null,
    add column incremental_action varchar(60) null,
    add column incremental_detail varchar(1000) null;
