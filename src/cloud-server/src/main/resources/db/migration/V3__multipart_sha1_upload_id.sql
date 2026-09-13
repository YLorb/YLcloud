set @schema_name = database();

set @ddl = (
    select if(
        exists(select 1 from information_schema.tables where table_schema = @schema_name and table_name = 'file_info')
        and not exists(select 1 from information_schema.columns where table_schema = @schema_name and table_name = 'file_info' and column_name = 'sha1'),
        'alter table file_info add column sha1 varchar(40) after md5',
        'select 1'
    )
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

set @ddl = (
    select if(
        exists(select 1 from information_schema.tables where table_schema = @schema_name and table_name = 'upload_task')
        and not exists(select 1 from information_schema.columns where table_schema = @schema_name and table_name = 'upload_task' and column_name = 'file_sha1'),
        'alter table upload_task add column file_sha1 varchar(40) after file_md5',
        'select 1'
    )
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

set @ddl = (
    select if(
        exists(select 1 from information_schema.tables where table_schema = @schema_name and table_name = 'file_info')
        and not exists(select 1 from information_schema.statistics where table_schema = @schema_name and table_name = 'file_info' and index_name = 'idx_file_info_md5_sha1_size'),
        'create index idx_file_info_md5_sha1_size on file_info (md5, sha1, size, status)',
        'select 1'
    )
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

set @ddl = (
    select if(
        exists(select 1 from information_schema.tables where table_schema = @schema_name and table_name = 'upload_task')
        and not exists(select 1 from information_schema.statistics where table_schema = @schema_name and table_name = 'upload_task' and index_name = 'idx_upload_task_user_upload'),
        'create index idx_upload_task_user_upload on upload_task (user_id, upload_id)',
        'select 1'
    )
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;
