create table if not exists permission_group (
    group_id bigint primary key auto_increment,
    group_name varchar(100) not null,
    description varchar(500) null,
    system_group tinyint not null default 0,
    created_by bigint null,
    create_time timestamp not null default current_timestamp,
    update_time timestamp not null default current_timestamp on update current_timestamp,
    unique key uk_permission_group_name (group_name)
);

create table if not exists permission_group_grant (
    group_id bigint not null,
    permission_key varchar(64) not null,
    allowed tinyint not null default 0,
    update_time timestamp not null default current_timestamp on update current_timestamp,
    primary key (group_id, permission_key),
    constraint fk_permission_group_grant_group foreign key (group_id) references permission_group(group_id) on delete cascade
);

create table if not exists user_permission_group (
    user_id bigint primary key,
    group_id bigint not null,
    update_time timestamp not null default current_timestamp on update current_timestamp,
    index idx_user_permission_group_group (group_id),
    constraint fk_user_permission_group_user foreign key (user_id) references users(user_id) on delete cascade,
    constraint fk_user_permission_group_group foreign key (group_id) references permission_group(group_id)
);

create table if not exists user_permission_override (
    user_id bigint not null,
    permission_key varchar(64) not null,
    allowed tinyint not null,
    update_time timestamp not null default current_timestamp on update current_timestamp,
    primary key (user_id, permission_key),
    constraint fk_user_permission_override_user foreign key (user_id) references users(user_id) on delete cascade
);

create table if not exists permission_audit_log (
    audit_id bigint primary key auto_increment,
    operator_id bigint not null,
    target_type varchar(32) not null,
    target_id bigint not null,
    action varchar(64) not null,
    before_json longtext null,
    after_json longtext null,
    create_time timestamp not null default current_timestamp,
    index idx_permission_audit_target (target_type, target_id, create_time),
    index idx_permission_audit_operator (operator_id, create_time)
);

insert into permission_group(group_name, description, system_group, created_by)
values ('默认用户组', '升级兼容组：保留现有普通用户的云盘和知识库能力', 1, null)
on duplicate key update description = values(description), system_group = 1;

insert into permission_group_grant(group_id, permission_key, allowed)
select group_id, permission_key, 1
from permission_group
cross join (
    select 'CLOUD_DRIVE' permission_key union all
    select 'FILE_UPLOAD' union all
    select 'FILE_DOWNLOAD' union all
    select 'KNOWLEDGE_USE' union all
    select 'KNOWLEDGE_FILE_ADD'
) permissions
where group_name = '默认用户组'
on duplicate key update allowed = values(allowed);

insert into user_permission_group(user_id, group_id)
select u.user_id, g.group_id
from users u
join permission_group g on g.group_name = '默认用户组'
where u.role = 'USER'
on duplicate key update group_id = values(group_id);
