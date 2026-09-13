set @add_total_count = (
    select if(
        exists (
            select 1 from information_schema.columns
            where table_schema = database()
              and table_name = 'space_rag_task'
              and column_name = 'total_count'
        ),
        'select 1',
        'alter table space_rag_task add column total_count int not null default 0 after task_status'
    )
);
prepare add_total_count_stmt from @add_total_count;
execute add_total_count_stmt;
deallocate prepare add_total_count_stmt;

set @add_success_count = (
    select if(
        exists (
            select 1 from information_schema.columns
            where table_schema = database()
              and table_name = 'space_rag_task'
              and column_name = 'success_count'
        ),
        'select 1',
        'alter table space_rag_task add column success_count int not null default 0 after total_count'
    )
);
prepare add_success_count_stmt from @add_success_count;
execute add_success_count_stmt;
deallocate prepare add_success_count_stmt;

set @add_failed_count = (
    select if(
        exists (
            select 1 from information_schema.columns
            where table_schema = database()
              and table_name = 'space_rag_task'
              and column_name = 'failed_count'
        ),
        'select 1',
        'alter table space_rag_task add column failed_count int not null default 0 after success_count'
    )
);
prepare add_failed_count_stmt from @add_failed_count;
execute add_failed_count_stmt;
deallocate prepare add_failed_count_stmt;
