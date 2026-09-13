alter table knowledge_chat_message
    add column workflow_run_id char(36) null after retry_count,
    add column workflow_execution_id char(36) null after workflow_run_id,
    add column workflow_execution_epoch int null after workflow_execution_id,
    add column workflow_status varchar(32) null after workflow_execution_epoch,
    add column workflow_degraded tinyint(1) not null default 0 after workflow_status,
    add column workflow_result_hash char(64) null after workflow_degraded,
    add column workflow_snapshot_hash char(64) null after workflow_result_hash,
    add column workflow_result_json longtext null after workflow_snapshot_hash,
    add column generation_status varchar(20) null after workflow_result_json,
    add unique key uk_knowledge_chat_message_workflow_run (workflow_run_id),
    add index idx_knowledge_chat_message_workflow_reconcile
        (workflow_status, generation_status, status, updatetime);
