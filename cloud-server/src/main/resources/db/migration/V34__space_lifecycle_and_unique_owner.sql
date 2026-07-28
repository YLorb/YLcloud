alter table spaces
    add column lifecycle_state varchar(20) not null default 'ACTIVE' after type,
    add column active_personal_owner_id bigint
        generated always as (
            case when type = 'PERSONAL' and status = 1 then owner_id else null end
        ) stored,
    add unique key uk_spaces_single_active_personal (active_personal_owner_id),
    add constraint chk_spaces_type check (type in ('PERSONAL', 'TEAM')),
    add constraint chk_spaces_lifecycle
        check (lifecycle_state in ('ACTIVE', 'DISSOLVING', 'DISSOLVED'));

update space_member sm
join spaces s on s.id = sm.space_id
set sm.role = 'MEMBER',
    sm.updatetime = current_timestamp
where sm.role = 'OWNER'
  and sm.user_id <> s.owner_id;

insert into space_member(space_id, user_id, role, status, createtime, updatetime)
select s.id, s.owner_id, 'OWNER', 1, s.createtime, current_timestamp
from spaces s
on duplicate key update
    role = 'OWNER',
    status = 1,
    updatetime = current_timestamp;

alter table space_member
    add column active_owner_space_id bigint
        generated always as (
            case when status = 1 and role = 'OWNER' then space_id else null end
        ) stored,
    add unique key uk_space_single_active_owner (active_owner_space_id);

create table if not exists space_dissolution_outbox (
    id bigint primary key auto_increment,
    event_id varchar(64) not null,
    space_id bigint not null,
    owner_id bigint not null,
    event_type varchar(40) not null default 'SPACE_DISSOLUTION_REQUESTED',
    status varchar(20) not null default 'PENDING',
    retry_count int not null default 0,
    next_retry_at timestamp null,
    error_message varchar(1000),
    createtime timestamp not null,
    updatetime timestamp not null,
    unique key uk_space_dissolution_event (event_id),
    unique key uk_space_dissolution_space (space_id),
    index idx_space_dissolution_status_retry (status, next_retry_at),
    constraint fk_space_dissolution_space foreign key (space_id) references spaces(id),
    constraint fk_space_dissolution_owner foreign key (owner_id) references users(user_id),
    constraint chk_space_dissolution_status
        check (status in ('PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED'))
);
