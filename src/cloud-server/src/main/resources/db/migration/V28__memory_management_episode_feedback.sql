create table knowledge_chat_episode (
    id bigint not null auto_increment primary key,
    session_id bigint not null,
    user_id bigint not null,
    episode_no int not null,
    start_sequence_no bigint not null,
    end_sequence_no bigint not null,
    title varchar(200) not null,
    summary varchar(2000) null,
    message_count int not null default 0,
    status tinyint not null default 1,
    createtime datetime not null,
    updatetime datetime not null,
    unique key uk_chat_episode_no (session_id, episode_no),
    key idx_chat_episode_owner (user_id, session_id, status)
);

create table knowledge_chat_feedback (
    id bigint not null auto_increment primary key,
    user_id bigint not null,
    session_id bigint not null,
    assistant_message_id bigint not null,
    rating varchar(16) not null,
    reason varchar(64) null,
    comment varchar(500) null,
    createtime datetime not null,
    updatetime datetime not null,
    unique key uk_chat_feedback_message (user_id, assistant_message_id),
    key idx_chat_feedback_rating (rating, createtime)
);
