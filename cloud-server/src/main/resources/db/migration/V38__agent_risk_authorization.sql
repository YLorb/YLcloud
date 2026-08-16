create table agent_risk_authorization (
    authorization_id bigint primary key auto_increment,
    user_id bigint not null,
    api_key_id bigint null,
    authorization_mode varchar(24) not null,
    authorization_status varchar(24) not null,
    expires_at datetime null,
    consumed_invocation_id char(36) null,
    consumed_at datetime null,
    revoked_by bigint null,
    revoked_at datetime null,
    risk_acknowledged tinyint(1) not null,
    createtime datetime not null,
    updatetime datetime not null,
    active_subject_key varchar(100) generated always as (
        case when authorization_status = 'ACTIVE'
             then concat(user_id, ':', coalesce(api_key_id, 0)) else null end
    ) stored,
    constraint chk_agent_risk_authorization_mode
        check (authorization_mode in ('ALLOW_ONCE', 'PERSISTENT')),
    constraint chk_agent_risk_authorization_status
        check (authorization_status in ('ACTIVE', 'CONSUMED', 'REVOKED', 'EXPIRED')),
    constraint chk_agent_risk_authorization_subject
        check (api_key_id is null or api_key_id > 0),
    constraint fk_agent_risk_authorization_user
        foreign key (user_id) references users(user_id),
    unique key uk_agent_risk_authorization_active_subject (active_subject_key),
    unique key uk_agent_risk_authorization_consumed_invocation (consumed_invocation_id),
    key idx_agent_risk_authorization_user_history (user_id, createtime),
    key idx_agent_risk_authorization_api_key (api_key_id, authorization_status)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

alter table workflow_tool_invocation
    add column api_key_id bigint null after user_id,
    add key idx_workflow_tool_invocation_api_key (api_key_id, createtime);

-- 旧逐 Tool/参数授权不得被扩大或转换；升级后直接失效并移除。
drop table if exists workflow_confirmation_grant;
