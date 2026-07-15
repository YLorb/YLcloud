alter table space_rag_config
    add column knowledge_profile_enabled tinyint not null default 1 after enabled;
