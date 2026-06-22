set @schema_name = database();

set @ddl = (
    select if(
        exists(select 1 from information_schema.tables where table_schema = @schema_name and table_name = 'space_rag_config')
        and not exists(select 1 from information_schema.columns where table_schema = @schema_name and table_name = 'space_rag_config' and column_name = 'temperature'),
        'alter table space_rag_config add column temperature decimal(3,2) not null default 0.20 after top_k',
        'select 1'
    )
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

set @ddl = (
    select if(
        exists(select 1 from information_schema.tables where table_schema = @schema_name and table_name = 'space_rag_query_log')
        and not exists(select 1 from information_schema.columns where table_schema = @schema_name and table_name = 'space_rag_query_log' and column_name = 'top_k'),
        'alter table space_rag_query_log add column top_k int after model_name',
        'select 1'
    )
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

set @ddl = (
    select if(
        exists(select 1 from information_schema.tables where table_schema = @schema_name and table_name = 'space_rag_query_log')
        and not exists(select 1 from information_schema.columns where table_schema = @schema_name and table_name = 'space_rag_query_log' and column_name = 'temperature'),
        'alter table space_rag_query_log add column temperature decimal(3,2) after top_k',
        'select 1'
    )
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

create table if not exists space_rag_config_log (
    id bigint primary key auto_increment,
    space_id bigint not null,
    operator_id bigint not null,
    changed_fields varchar(500),
    before_json longtext,
    after_json longtext,
    createtime timestamp not null,
    index idx_space_rag_config_log_space_id (space_id),
    index idx_space_rag_config_log_operator_id (operator_id),
    index idx_space_rag_config_log_createtime (createtime)
);
