-- Logical archive; retain task, attempts and message evidence for admin review.
alter table async_task
    add column archived_at timestamp null,
    add column archived_by bigint null,
    add index idx_async_task_archive_created (archived_at, created_at, id);
