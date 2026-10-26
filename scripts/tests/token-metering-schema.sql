-- Minimal pre-V65 fixture for ledger tests. No production/user data is loaded.
create table quota_account (
    account_id bigint primary key auto_increment,
    account_type varchar(16) not null,
    reference_id bigint not null,
    group_id bigint null,
    createtime datetime(6) not null,
    updatetime datetime(6) not null,
    unique key uk_quota_account_reference(account_type,reference_id)
);
create table quota_usage_period (
    account_id bigint not null,
    period_start date not null,
    api_calls bigint not null default 0,
    model_tokens bigint not null default 0,
    agent_tasks bigint not null default 0,
    concurrent_agent_tasks bigint not null default 0,
    updatetime datetime(6) not null,
    primary key(account_id,period_start)
);
create table knowledge_chat_message(id bigint primary key);
