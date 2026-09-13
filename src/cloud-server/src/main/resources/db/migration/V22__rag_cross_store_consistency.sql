alter table space_rag_document
    add column vector_state varchar(24) not null default 'CLEAN' after index_status,
    add index idx_space_rag_document_vector_state (vector_state, index_status, status);

update space_rag_document
set vector_state = case
    when status = 1 and index_status = 'SUCCESS' then 'ACTIVE'
    when status = 1 and index_status = 'INDEXING' then 'CLEANUP_PENDING'
    when status = 1 and index_status = 'FAILED' then 'CLEANUP_PENDING'
    else 'CLEAN'
end;

alter table space_knowledge_pipeline_task
    add column active_scope_key varchar(191)
        generated always as (
            case
                when task_status in ('PENDING', 'RUNNING') and document_id is not null
                    then concat('PROFILE_DOCUMENT:', space_id, ':', document_id)
                when task_status in ('PENDING', 'RUNNING') and document_id is null
                    then concat('PROFILE_SPACE:', space_id)
                else null
            end
        ) stored,
    add unique key uk_knowledge_pipeline_active_scope (active_scope_key),
    add index idx_knowledge_pipeline_stale (task_status, updatetime);
