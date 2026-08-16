create table workflow_tool_invocation (
    invocation_id char(36) primary key,
    run_id char(36) not null,
    execution_id char(36) not null,
    node_id varchar(64) not null,
    user_id bigint not null,
    session_id bigint not null,
    tool_name varchar(128) not null,
    risk_level varchar(16) not null,
    arguments_hash char(64) not null,
    invocation_status varchar(16) not null,
    response_json longtext null,
    error_code varchar(64) null,
    createtime datetime not null,
    updatetime datetime not null,
    key idx_workflow_tool_invocation_run (run_id, execution_id),
    key idx_workflow_tool_invocation_user (user_id, createtime)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table workflow_confirmation_grant (
    grant_id char(36) primary key,
    user_id bigint not null,
    tool_name varchar(128) not null,
    parameter_hash char(64) not null,
    grant_mode varchar(24) not null,
    similarity_scope_json varchar(2000) null,
    expires_at datetime not null,
    revoked tinyint(1) not null default 0,
    consumed_invocation_id char(36) null,
    createtime datetime not null,
    updatetime datetime not null,
    key idx_workflow_confirmation_lookup (user_id, tool_name, expires_at),
    unique key uk_workflow_confirmation_consumed (consumed_invocation_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;
