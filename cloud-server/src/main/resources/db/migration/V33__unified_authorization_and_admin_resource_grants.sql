create table admin_resource_grant (
    grant_id bigint primary key auto_increment,
    grantor_id bigint not null,
    admin_user_id bigint not null,
    resource_type varchar(32) not null,
    resource_id bigint not null,
    action varchar(32) not null,
    status tinyint not null default 1,
    create_time timestamp not null default current_timestamp,
    update_time timestamp not null default current_timestamp on update current_timestamp,
    constraint chk_admin_resource_grant_type
        check (resource_type in ('USER_PRIVATE', 'SPACE')),
    constraint chk_admin_resource_grant_action
        check (action in ('READ', 'DOWNLOAD')),
    constraint chk_admin_resource_grant_status
        check (status in (0, 1)),
    constraint fk_admin_resource_grant_grantor
        foreign key (grantor_id) references users(user_id),
    constraint fk_admin_resource_grant_admin
        foreign key (admin_user_id) references users(user_id),
    unique key uk_admin_resource_grant_scope
        (admin_user_id, resource_type, resource_id, action),
    index idx_admin_resource_grant_grantor
        (grantor_id, status, create_time),
    index idx_admin_resource_grant_admin
        (admin_user_id, status, resource_type, resource_id)
);

insert into user_permission_group(user_id, group_id)
select u.user_id, g.group_id
from users u
join permission_group g on g.group_name = '默认用户组'
left join user_permission_group upg on upg.user_id = u.user_id
where upg.user_id is null
on duplicate key update group_id = values(group_id);
