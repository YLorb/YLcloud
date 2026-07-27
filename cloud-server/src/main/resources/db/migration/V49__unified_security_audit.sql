-- TASK-012: Unified security audit and retention
-- Immutable audit event model with retention policies

create table security_audit_event (
    event_id bigint primary key auto_increment,
    event_key varchar(191) not null,
    event_type varchar(60) not null,
    subject_type varchar(30) not null default 'USER',
    subject_id bigint null,
    subject_name varchar(100) null,
    target_type varchar(50) not null,
    target_id varchar(191) null,
    target_name varchar(200) null,
    action varchar(80) not null,
    result varchar(20) not null default 'SUCCESS',
    trace_id varchar(64) null,
    request_id varchar(64) null,
    ip_address varchar(45) null,
    user_agent varchar(500) null,
    detail_json json null,
    error_message varchar(1000) null,
    retention_policy varchar(20) not null default 'STANDARD',
    occurred_at timestamp not null default current_timestamp,
    created_at timestamp not null default current_timestamp,
    unique key uk_audit_event_key (event_key),
    index idx_audit_event_type (event_type, occurred_at),
    index idx_audit_subject (subject_type, subject_id, occurred_at),
    index idx_audit_target (target_type, target_id, occurred_at),
    index idx_audit_retention (retention_policy, occurred_at),
    index idx_audit_trace (trace_id),
    constraint chk_audit_subject_type check (subject_type in ('USER','API_KEY','SYSTEM','SERVICE')),
    constraint chk_audit_result check (result in ('SUCCESS','FAILURE','DENIED','ERROR')),
    constraint chk_audit_retention check (retention_policy in ('STANDARD','PERMANENT'))
) engine=InnoDB row_format=compressed;

-- Audit retention configuration
create table audit_retention_config (
    config_key varchar(64) primary key,
    retention_days int null,
    permanent tinyint not null default 0,
    description varchar(500) null,
    updated_at timestamp not null default current_timestamp on update current_timestamp,
    constraint chk_retention_days check (retention_days is null or retention_days > 0)
);

-- Default retention configuration
insert into audit_retention_config(config_key, retention_days, permanent, description)
values
    ('DEFAULT', 7, 0, '默认审计保留7天'),
    ('SECURITY_CRITICAL', null, 1, '安全关键事件永久保留'),
    ('COMPLIANCE', 90, 0, '合规审计保留90天')
on duplicate key update description = values(description);

-- Grant INSERT only to application user (no UPDATE/DELETE on audit table)
-- This is enforced at application level; database user should have limited privileges
