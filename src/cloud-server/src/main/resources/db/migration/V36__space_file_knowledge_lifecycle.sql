alter table space_file
    add column knowledge_state varchar(24) not null default 'NOT_APPLICABLE' after version_enabled,
    add column knowledge_version bigint not null default 0 after knowledge_state,
    add column searchable tinyint not null default 0 after knowledge_version,
    add column last_knowledge_error varchar(1000) null after searchable,
    add column removed_at timestamp null after last_knowledge_error,
    add index idx_space_file_knowledge (space_id, knowledge_state, searchable),
    add constraint chk_space_file_knowledge_state check (
        knowledge_state in ('NOT_APPLICABLE','INDEX_PENDING','INDEXING','READY','FAILED','REMOVAL_PENDING','REMOVED')
    );

update space_file sf
left join space_rag_document d
    on d.space_id = sf.space_id and d.space_file_id = sf.id and d.status = 1
set sf.knowledge_state = case
        when sf.is_dir = 1 then 'NOT_APPLICABLE'
        when sf.status = 0 then 'REMOVED'
        when d.index_status = 'SUCCESS' and d.vector_state = 'ACTIVE' then 'READY'
        when d.index_status = 'FAILED' then 'FAILED'
        else 'INDEX_PENDING'
    end,
    sf.knowledge_version = case when sf.is_dir = 0 then 1 else 0 end,
    sf.searchable = case
        when sf.status = 1 and d.index_status = 'SUCCESS' and d.vector_state = 'ACTIVE' then 1
        else 0
    end,
    sf.removed_at = case when sf.status = 0 then sf.updatetime else null end;

create table space_file_lifecycle_outbox (
    id bigint primary key auto_increment,
    event_id varchar(64) not null,
    event_key varchar(180) not null,
    event_type varchar(32) not null,
    space_id bigint not null,
    space_file_id bigint not null,
    file_uuid varchar(64) not null,
    resource_version bigint not null,
    payload_json json not null,
    status varchar(20) not null default 'PENDING',
    retry_count int not null default 0,
    lease_token varchar(64) null,
    lease_until timestamp null,
    last_error varchar(1000) null,
    created_at timestamp not null,
    updated_at timestamp not null,
    unique key uk_space_file_lifecycle_event_id (event_id),
    unique key uk_space_file_lifecycle_event_key (event_key),
    index idx_space_file_lifecycle_pending (status, updated_at),
    index idx_space_file_lifecycle_resource (space_file_id, resource_version),
    constraint chk_space_file_lifecycle_type check (event_type in ('INDEX_REQUESTED','REMOVAL_REQUESTED')),
    constraint chk_space_file_lifecycle_status check (status in ('PENDING','PROCESSING','DONE','FAILED'))
);

create table physical_file_reference (
    id bigint primary key auto_increment,
    file_uuid varchar(64) not null,
    reference_type varchar(24) not null,
    reference_id bigint not null,
    owner_user_id bigint null,
    space_id bigint null,
    active tinyint not null default 1,
    created_at timestamp not null,
    released_at timestamp null,
    unique key uk_physical_file_reference (reference_type, reference_id),
    index idx_physical_file_reference_count (file_uuid, active),
    index idx_physical_file_reference_space (space_id, active),
    constraint chk_physical_file_reference_type check (reference_type in ('USER_FILE','SPACE_FILE'))
);

insert ignore into physical_file_reference(
    file_uuid,reference_type,reference_id,owner_user_id,space_id,active,created_at,released_at
)
select file_uuid,'USER_FILE',id,user_id,null,
       case when status in (1,2) then 1 else 0 end,
       createtime,
       case when status in (1,2) then null else updatetime end
from user_file where is_dir = 0 and file_uuid is not null;

insert ignore into physical_file_reference(
    file_uuid,reference_type,reference_id,owner_user_id,space_id,active,created_at,released_at
)
select file_uuid,'SPACE_FILE',id,created_by,space_id,status,createtime,
       case when status = 0 then updatetime else null end
from space_file where is_dir = 0 and file_uuid is not null;
