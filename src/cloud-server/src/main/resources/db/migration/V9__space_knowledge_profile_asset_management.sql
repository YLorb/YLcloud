alter table space_knowledge_document_profile
    add column current_version_id bigint null,
    add column latest_version_id bigint null;

create table if not exists space_knowledge_profile_version (
    id bigint primary key auto_increment,
    profile_id bigint not null,
    space_id bigint not null,
    document_id bigint not null,
    version_no int not null,
    document_version_id bigint null,
    source_type varchar(40) not null,
    model_name varchar(100),
    prompt_version varchar(60),
    schema_version varchar(60),
    quality_score decimal(5,2) default 0.00,
    profile_snapshot longtext not null,
    change_summary varchar(1000),
    created_by bigint,
    created_time timestamp not null,
    unique key uk_knowledge_profile_version_no (profile_id, version_no),
    index idx_knowledge_profile_version_profile_id (profile_id),
    index idx_knowledge_profile_version_document_id (document_id),
    index idx_knowledge_profile_version_space_id (space_id)
);

create table if not exists space_knowledge_audit_log (
    id bigint primary key auto_increment,
    space_id bigint not null,
    operator_id bigint,
    action varchar(60) not null,
    resource_type varchar(60) not null,
    resource_id bigint not null,
    before_snapshot longtext,
    after_snapshot longtext,
    ip_address varchar(100),
    user_agent varchar(500),
    created_time timestamp not null,
    index idx_knowledge_audit_space_id (space_id),
    index idx_knowledge_audit_operator_id (operator_id),
    index idx_knowledge_audit_resource (resource_type, resource_id),
    index idx_knowledge_audit_action (action)
);
