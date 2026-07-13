create table if not exists user_registration_guard (
    guard_id tinyint primary key,
    description varchar(100) not null,
    create_time timestamp not null default current_timestamp
);

insert into user_registration_guard(guard_id, description)
values (1, 'Serialize first-user role assignment')
on duplicate key update guard_id = values(guard_id);
