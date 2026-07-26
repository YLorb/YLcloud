create table quota_policy (
    group_id bigint primary key,
    storage_bytes bigint not null default 1073741824,
    max_file_bytes bigint not null default 20971520,
    space_limit bigint not null default 10,
    monthly_api_calls bigint not null default 10000,
    monthly_model_tokens bigint not null default 1000000,
    monthly_agent_tasks bigint not null default 1000,
    concurrent_agent_tasks bigint not null default 3,
    updatetime datetime(6) not null,
    constraint fk_quota_policy_group foreign key (group_id) references permission_group(group_id),
    constraint chk_quota_policy_nonnegative check (
        storage_bytes>=0 and max_file_bytes>=0 and space_limit>=0 and monthly_api_calls>=0 and
        monthly_model_tokens>=0 and monthly_agent_tasks>=0 and concurrent_agent_tasks>=0)
);

insert into quota_policy(group_id,storage_bytes,max_file_bytes,space_limit,monthly_api_calls,
                         monthly_model_tokens,monthly_agent_tasks,concurrent_agent_tasks,updatetime)
select group_id,1073741824,20971520,10,10000,1000000,1000,3,now(6) from permission_group;

create table quota_account (
    account_id bigint primary key auto_increment,
    account_type varchar(16) not null,
    reference_id bigint not null,
    group_id bigint null,
    createtime datetime(6) not null,
    updatetime datetime(6) not null,
    unique key uk_quota_account_reference (account_type,reference_id),
    index idx_quota_account_group (group_id),
    constraint chk_quota_account_type check (account_type in ('USER','TEAM'))
);

insert into quota_account(account_type,reference_id,group_id,createtime,updatetime)
select 'USER',u.user_id,upg.group_id,now(6),now(6) from users u
left join user_permission_group upg on upg.user_id=u.user_id;

insert into quota_account(account_type,reference_id,group_id,createtime,updatetime)
select 'TEAM',s.id,upg.group_id,now(6),now(6) from spaces s
left join user_permission_group upg on upg.user_id=s.owner_id where s.type='TEAM';

create table quota_blob_reference (
    reference_type varchar(16) not null,
    reference_id bigint not null,
    account_id bigint not null,
    content_key varchar(191) not null,
    file_uuid varchar(64) not null,
    size_bytes bigint not null,
    active tinyint not null default 1,
    createtime datetime(6) not null,
    updatetime datetime(6) not null,
    primary key (reference_type,reference_id),
    index idx_quota_blob_reference_content (content_key,active),
    index idx_quota_blob_reference_account (account_id,active),
    constraint chk_quota_blob_reference_type check (reference_type in ('USER_FILE','SPACE_FILE'))
);

insert into quota_blob_reference(reference_type,reference_id,account_id,content_key,file_uuid,size_bytes,active,createtime,updatetime)
select 'USER_FILE',uf.id,qa.account_id,coalesce(nullif(fi.hash,''),concat('uuid:',fi.file_uuid)),fi.file_uuid,fi.size,1,now(6),now(6)
from user_file uf join file_info fi on fi.file_uuid=uf.file_uuid and fi.status=1
join quota_account qa on qa.account_type='USER' and qa.reference_id=uf.user_id
where uf.is_dir=0 and uf.status=1;

insert into quota_blob_reference(reference_type,reference_id,account_id,content_key,file_uuid,size_bytes,active,createtime,updatetime)
select 'SPACE_FILE',sf.id,qa.account_id,coalesce(nullif(fi.hash,''),concat('uuid:',fi.file_uuid)),fi.file_uuid,fi.size,1,now(6),now(6)
from space_file sf join file_info fi on fi.file_uuid=sf.file_uuid and fi.status=1
join quota_account qa on qa.account_type='TEAM' and qa.reference_id=sf.space_id
where sf.is_dir=0 and sf.status=1;

create table quota_blob_ledger (
    content_key varchar(191) primary key,
    file_uuid varchar(64) not null,
    size_bytes bigint not null,
    owner_account_id bigint not null,
    reference_count bigint not null,
    createtime datetime(6) not null,
    updatetime datetime(6) not null,
    index idx_quota_blob_ledger_owner (owner_account_id),
    constraint chk_quota_blob_ledger_values check (size_bytes>=0 and reference_count>=0)
);

insert into quota_blob_ledger(content_key,file_uuid,size_bytes,owner_account_id,reference_count,createtime,updatetime)
select content_key,min(file_uuid),max(size_bytes),min(account_id),count(*),now(6),now(6)
from quota_blob_reference where active=1 group by content_key;

create table quota_usage_period (
    account_id bigint not null,
    period_start date not null,
    api_calls bigint not null default 0,
    model_tokens bigint not null default 0,
    agent_tasks bigint not null default 0,
    concurrent_agent_tasks bigint not null default 0,
    updatetime datetime(6) not null,
    primary key (account_id,period_start),
    constraint chk_quota_usage_nonnegative check (
        api_calls>=0 and model_tokens>=0 and agent_tasks>=0 and concurrent_agent_tasks>=0)
);

create table quota_reconcile_run (
    run_id bigint primary key auto_increment,
    run_status varchar(16) not null,
    difference_count bigint not null default 0,
    detail_json json null,
    started_at datetime(6) not null,
    completed_at datetime(6) null,
    constraint chk_quota_reconcile_status check (run_status in ('RUNNING','SUCCEEDED','FAILED'))
);
