create table if not exists knowledge_chat_session (
    id bigint primary key auto_increment,
    user_id bigint not null,
    title varchar(120) not null,
    scope_mode varchar(40) not null default 'single-space',
    scope_space_ids varchar(500),
    status int not null default 1,
    createtime timestamp not null,
    updatetime timestamp not null,
    index idx_knowledge_chat_session_user (user_id),
    index idx_knowledge_chat_session_update (updatetime)
);

create table if not exists knowledge_chat_message (
    id bigint primary key auto_increment,
    session_id bigint not null,
    user_id bigint not null,
    role varchar(20) not null,
    content longtext not null,
    citations_json longtext,
    status int not null default 1,
    createtime timestamp not null,
    index idx_knowledge_chat_message_session (session_id),
    index idx_knowledge_chat_message_user (user_id)
);
