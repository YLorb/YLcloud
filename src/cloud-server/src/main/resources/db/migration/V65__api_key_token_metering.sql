alter table quota_usage_period add column model_tokens_reserved bigint not null default 0;
alter table knowledge_chat_message
    add column metering_api_key_id bigint null,
    add column metering_request_id char(36) null;

-- Ledger deliberately survives key revocation and business/task rollback.
create table api_key_token_usage (
    call_id char(36) primary key,
    user_id bigint not null,
    key_id bigint not null,
    account_id bigint not null,
    period_start date not null,
    request_id char(36) not null,
    message_id bigint null,
    operation varchar(16) not null,
    model varchar(255) not null,
    usage_status varchar(16) not null,
    reserved_tokens bigint not null,
    prompt_tokens bigint null,
    completion_tokens bigint null,
    total_tokens bigint null,
    error_code varchar(64) null,
    resolved_by bigint null,
    resolution_note varchar(500) null,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    constraint chk_token_usage_status check (usage_status in ('RESERVED','SETTLED','UNRESOLVED')),
    constraint chk_token_usage_reserved check (reserved_tokens>=0),
    constraint chk_token_usage_actual check (prompt_tokens>=0 and completion_tokens>=0 and total_tokens=prompt_tokens+completion_tokens),
    index idx_token_usage_key (key_id,created_at,call_id),
    index idx_token_usage_user (user_id,period_start),
    index idx_token_usage_request (request_id),
    index idx_token_usage_task (message_id,usage_status),
    index idx_token_usage_unresolved (usage_status,updated_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;
