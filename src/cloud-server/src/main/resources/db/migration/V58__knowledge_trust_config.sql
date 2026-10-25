alter table space_rag_config
    add column allow_stale_during_reindex tinyint not null default 0,
    add column historical_evidence_enabled tinyint not null default 0,
    add column history_retention_days int null,
    add column config_revision bigint not null default 0,
    add constraint chk_rag_history_retention_days check (history_retention_days is null or history_retention_days > 0),
    add constraint chk_rag_history_retention_required check (historical_evidence_enabled = 0 or history_retention_days is not null);
