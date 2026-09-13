create table user_api_key (
    key_id bigint primary key auto_increment,
    user_id bigint not null,
    key_name varchar(100) not null,
    key_prefix varchar(24) not null,
    key_hash varchar(100) not null,
    drive_access varchar(16) not null default 'NONE',
    drive_root_file_id bigint null,
    key_status varchar(16) not null default 'ACTIVE',
    expires_at datetime null,
    last_used_at datetime null,
    revoked_at datetime null,
    createtime datetime not null,
    updatetime datetime not null,
    constraint chk_user_api_key_drive_access check (drive_access in ('NONE','READ','WRITE')),
    constraint chk_user_api_key_status check (key_status in ('ACTIVE','REVOKED')),
    constraint fk_user_api_key_user foreign key (user_id) references users(user_id),
    constraint fk_user_api_key_root foreign key (drive_root_file_id) references user_file(ID),
    unique key uk_user_api_key_prefix (key_prefix),
    key idx_user_api_key_user_status (user_id,key_status,expires_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table api_key_scope (
    key_id bigint not null,
    scope_key varchar(60) not null,
    createtime datetime not null,
    primary key (key_id,scope_key),
    constraint chk_api_key_scope_key check (scope_key in (
        'DRIVE_READ','DRIVE_WRITE','KNOWLEDGE_RETRIEVE','KNOWLEDGE_AGENT'
    )),
    constraint fk_api_key_scope_key foreign key (key_id) references user_api_key(key_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table api_key_space_scope (
    key_id bigint not null,
    space_id bigint not null,
    createtime datetime not null,
    primary key (key_id,space_id),
    constraint fk_api_key_space_scope_key foreign key (key_id) references user_api_key(key_id),
    constraint fk_api_key_space_scope_space foreign key (space_id) references spaces(id),
    key idx_api_key_space_scope_space (space_id,key_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

alter table agent_risk_authorization
    add constraint fk_agent_risk_authorization_api_key
        foreign key (api_key_id) references user_api_key(key_id);

insert into site_setting(setting_key,setting_value,value_type,group_name,label,description,secret,editable)
values
('apiKey.enabled','true','boolean','permissions','启用用户 API Key','关闭后所有 API Key 认证立即拒绝',0,1),
('apiKey.maxPerUser','10','number','permissions','每用户 API Key 上限','仅统计未撤销且未过期的 Key',0,1),
('apiKey.allowNeverExpires','true','boolean','permissions','允许永不过期 API Key','关闭后用户必须选择到期时间',0,1),
('apiKey.maxValidityDays','365','number','permissions','API Key 最长有效天数','0 表示不限制；永不过期策略单独控制',0,1)
on duplicate key update
    label=values(label),description=values(description),value_type=values(value_type),
    group_name=values(group_name),secret=values(secret),editable=values(editable);
