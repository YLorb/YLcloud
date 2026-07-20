alter table knowledge_chat_session
    add column next_sequence_no bigint not null default 1 after scope_space_ids,
    add column rolling_summary longtext null after next_sequence_no,
    add column summary_upto_sequence_no bigint null after rolling_summary,
    add column summary_version int not null default 0 after summary_upto_sequence_no;

alter table knowledge_chat_message
    add column sequence_no bigint null after user_id,
    add column source_message_id bigint null after sequence_no,
    add column context_snapshot_json longtext null after request_json,
    add column context_hash varchar(64) null after context_snapshot_json,
    add column context_version int null after context_hash,
    add column context_token_count int null after context_version,
    add unique key uk_knowledge_chat_message_sequence (session_id, sequence_no),
    add index idx_knowledge_chat_message_context (session_id, user_id, sequence_no, status);

update knowledge_chat_message message
join (
    select ranked_inner.id, ranked_inner.generated_sequence
    from (
        select id, row_number() over (partition by session_id order by id) as generated_sequence
        from knowledge_chat_message
    ) ranked_inner
) ranked on ranked.id = message.id
set message.sequence_no = ranked.generated_sequence
where message.sequence_no is null;

update knowledge_chat_session session
left join (
    select session_id, coalesce(max(sequence_no), 0) + 1 as next_sequence
    from knowledge_chat_message
    group by session_id
) message_sequence on message_sequence.session_id = session.id
set session.next_sequence_no = coalesce(message_sequence.next_sequence, 1);

update knowledge_chat_message assistant
join (
    select ordered_inner.id, ordered_inner.previous_id, ordered_inner.previous_role
    from (
        select id,
               lag(id) over (partition by session_id order by sequence_no) as previous_id,
               lag(role) over (partition by session_id order by sequence_no) as previous_role
        from knowledge_chat_message
    ) ordered_inner
) ordered on ordered.id = assistant.id
set assistant.source_message_id = ordered.previous_id
where assistant.role = 'assistant'
  and assistant.source_message_id is null
  and ordered.previous_role = 'user';

alter table knowledge_chat_message
    modify column sequence_no bigint not null;
