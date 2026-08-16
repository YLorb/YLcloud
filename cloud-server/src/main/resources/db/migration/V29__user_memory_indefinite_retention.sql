update user_memory_item
set expires_at = null
where expires_at is not null
  and memory_status not in ('DELETED', 'DELETE_PENDING');

update user_memory_setting
set retention_days = 0,
    updatetime = now()
where retention_days <> 0;

alter table user_memory_setting
    modify retention_days int not null default 0;
