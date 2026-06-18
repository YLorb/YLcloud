create table if not exists file_rag_parse_result (
    id bigint primary key auto_increment,
    file_uuid varchar(64) not null,
    file_hash varchar(128) not null,
    parser varchar(50) not null,
    parser_version varchar(50) not null,
    parse_status varchar(20) not null,
    full_text longtext,
    blocks_json longtext,
    error_message varchar(1000),
    status int not null default 1,
    createtime timestamp not null,
    updatetime timestamp not null,
    unique key uk_file_parse_version (file_uuid, file_hash, parser_version),
    index idx_file_parse_uuid_hash (file_uuid, file_hash),
    index idx_file_parse_status (parse_status)
);
