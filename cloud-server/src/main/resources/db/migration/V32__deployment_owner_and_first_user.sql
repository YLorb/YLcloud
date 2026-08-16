alter table users
    add column deployment_owner tinyint not null default 0 after role,
    add column deployment_owner_slot tinyint
        generated always as (case when deployment_owner = 1 then 1 else null end) stored,
    add constraint chk_users_deployment_owner_flag check (deployment_owner in (0, 1)),
    add constraint chk_users_deployment_owner_active_admin
        check (deployment_owner = 0 or (status = 1 and role = 'ADMIN')),
    add unique key uk_users_single_deployment_owner (deployment_owner_slot);

update users
set deployment_owner = 1,
    status = 1,
    role = 'ADMIN',
    update_time = current_timestamp
where user_id = (
    select first_user.user_id
    from (select min(user_id) as user_id from users) first_user
)
and not exists (
    select 1
    from (select user_id from users where deployment_owner = 1 limit 1) existing_owner
);
