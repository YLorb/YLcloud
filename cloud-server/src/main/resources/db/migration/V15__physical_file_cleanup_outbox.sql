create table if not exists physical_file_cleanup_task (
    id bigint primary key auto_increment,
    file_uuid varchar(64) not null,
    task_status varchar(20) not null default 'PENDING',
    retry_count int not null default 0,
    error_message varchar(1000),
    createtime timestamp not null,
    updatetime timestamp not null,
    unique key uk_physical_file_cleanup_uuid (file_uuid),
    index idx_physical_file_cleanup_status (task_status, updatetime)
);
