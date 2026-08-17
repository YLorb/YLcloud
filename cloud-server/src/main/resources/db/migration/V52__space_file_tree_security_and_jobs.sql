alter table space_file
    add column node_version bigint not null default 1 after path,
    add column depth int not null default 0 after node_version,
    add column content_hash varchar(128) null after depth,
    add column lifecycle_state varchar(32) not null default 'ACTIVE' after content_hash,
    add column deletion_batch_id bigint null after lifecycle_state,
    add column legacy_duplicate int not null default 0 after deletion_batch_id,
    add index idx_space_file_browse (space_id,parent_id,lifecycle_state,is_dir,updatetime),
    add index idx_space_file_content (space_id,content_hash,lifecycle_state);

update space_file sf
left join file_info fi on fi.file_uuid=sf.file_uuid
set sf.content_hash=fi.hash
where sf.is_dir=0 and sf.content_hash is null;

update space_file
set depth=case
    when path='/' then 0
    when trim(both '/' from path)='' then 0
    else length(trim(both '/' from path))-length(replace(trim(both '/' from path),'/',''))+1
end;

create table space_file_content_guard (
    space_id bigint not null,
    content_hash varchar(128) not null,
    space_file_id bigint null,
    guard_state varchar(32) not null,
    created_at timestamp not null,
    updated_at timestamp not null,
    primary key (space_id,content_hash),
    index idx_space_content_guard_file (space_file_id)
);

insert ignore into space_file_content_guard(space_id,content_hash,space_file_id,guard_state,created_at,updated_at)
select space_id,content_hash,min(id),'ACTIVE',min(createtime),max(updatetime)
from space_file
where status=1 and is_dir=0 and content_hash is not null
group by space_id,content_hash;

update space_file sf
join (
    select space_id,content_hash,min(id) keep_id,count(*) duplicate_count
    from space_file
    where status=1 and is_dir=0 and content_hash is not null
    group by space_id,content_hash having count(*) > 1
) d on d.space_id=sf.space_id and d.content_hash=sf.content_hash
set sf.legacy_duplicate=case when sf.id=d.keep_id then 0 else 1 end;

create table space_file_import_batch (
    id bigint primary key auto_increment,
    batch_key varchar(128) not null,
    space_id bigint not null,
    target_parent_id bigint not null,
    failure_policy varchar(32) not null,
    batch_status varchar(32) not null,
    total_count int not null default 0,
    passed_count int not null default 0,
    failed_count int not null default 0,
    imported_count int not null default 0,
    error_message varchar(1000),
    async_task_id bigint null,
    created_by bigint not null,
    resource_version bigint not null default 1,
    createtime timestamp not null,
    updatetime timestamp not null,
    unique key uk_space_import_batch_key (batch_key),
    index idx_space_import_batch_space (space_id,batch_status,updatetime)
);

create table space_file_import_item (
    id bigint primary key auto_increment,
    batch_id bigint not null,
    source_user_file_id bigint null,
    source_type varchar(32) not null,
    relative_path varchar(1024) not null,
    relative_path_hash char(64) not null,
    file_uuid varchar(64),
    content_hash varchar(128),
    file_size bigint,
    item_status varchar(32) not null,
    error_code varchar(64),
    error_message varchar(1000),
    space_file_id bigint null,
    sandbox_invocation_id varchar(128),
    createtime timestamp not null,
    updatetime timestamp not null,
    unique key uk_space_import_item_path (batch_id,relative_path_hash),
    index idx_space_import_item_status (batch_id,item_status,id)
);

create table space_file_delete_batch (
    id bigint primary key auto_increment,
    batch_key varchar(128) not null,
    space_id bigint not null,
    root_file_id bigint not null,
    root_node_version bigint not null,
    subtree_digest varchar(128) not null,
    folder_count int not null,
    file_count int not null,
    knowledge_count int not null,
    batch_status varchar(32) not null,
    processed_count int not null default 0,
    error_message varchar(1000),
    async_task_id bigint null,
    created_by bigint not null,
    createtime timestamp not null,
    updatetime timestamp not null,
    unique key uk_space_delete_batch_key (batch_key),
    index idx_space_delete_batch_space (space_id,batch_status,updatetime)
);
