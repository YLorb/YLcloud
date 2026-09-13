alter table space_knowledge_profile_version
    add column asset_state varchar(24) not null default 'SUPERSEDED' after quality_score,
    add column confidence decimal(5,4) not null default 0.0000 after asset_state,
    add column conflict_reason varchar(1000) null after confidence,
    add column source_file_hash varchar(128) null after conflict_reason,
    add column source_parser_version varchar(60) null after source_file_hash,
    add column reviewed_by bigint null after created_by,
    add column activated_at timestamp null after reviewed_by,
    add column superseded_at timestamp null after activated_at,
    add column active_profile_id bigint generated always as (
        case when asset_state = 'ACTIVE' then profile_id else null end
    ) stored,
    add constraint chk_knowledge_asset_state check (
        asset_state in ('ACTIVE','NEEDS_REVIEW','FAILED','SUPERSEDED')
    );

update space_knowledge_profile_version v
join space_knowledge_document_profile p on p.current_version_id = v.id
set v.asset_state = 'ACTIVE',
    v.confidence = least(1.0000,greatest(0.0000,coalesce(v.quality_score,0) / 100)),
    v.activated_at = coalesce(v.created_time,current_timestamp);

alter table space_knowledge_profile_version
    add unique key uk_knowledge_asset_active_profile (active_profile_id),
    add index idx_knowledge_asset_review (space_id,asset_state,created_time);

alter table space_knowledge_document_profile
    add column latest_asset_state varchar(24) not null default 'NEEDS_REVIEW' after latest_version_id,
    add column latest_confidence decimal(5,4) not null default 0.0000 after latest_asset_state,
    add column latest_conflict_reason varchar(1000) null after latest_confidence,
    add constraint chk_knowledge_profile_latest_state check (
        latest_asset_state in ('ACTIVE','NEEDS_REVIEW','FAILED','SUPERSEDED')
    );

update space_knowledge_document_profile p
left join space_knowledge_profile_version v on v.id = p.latest_version_id
set p.latest_asset_state = case
        when v.id is not null then v.asset_state
        when p.profile_status in ('VALID','SUCCESS') then 'ACTIVE'
        when p.profile_status in ('FAILED','INVALID') then 'FAILED'
        else 'NEEDS_REVIEW'
    end,
    p.latest_confidence = least(1.0000,greatest(0.0000,coalesce(p.quality_score,0) / 100)),
    p.latest_conflict_reason = coalesce(p.review_reason,p.error_message);
