insert into site_setting(setting_key, setting_value, value_type, group_name, label, description, secret, editable)
values
('storage.defaultUserQuotaBytes', '1073741824', 'number', 'file', '用户默认存储配额', '所有用户的个人文件最大可用空间，单位为字节；默认 1 GiB', 0, 1)
on duplicate key update
    label = values(label),
    description = values(description),
    value_type = values(value_type),
    group_name = values(group_name),
    secret = values(secret),
    editable = values(editable);

alter table knowledge_chat_message
    add column task_status varchar(20) null after citations_json,
    add column error_message varchar(1000) null after task_status,
    add column request_key varchar(100) null after error_message,
    add column request_json longtext null after request_key,
    add column retry_count int not null default 0 after request_json,
    add column updatetime timestamp null after createtime,
    add unique key uk_knowledge_chat_message_request (session_id, request_key),
    add index idx_knowledge_chat_message_task (task_status, updatetime);
