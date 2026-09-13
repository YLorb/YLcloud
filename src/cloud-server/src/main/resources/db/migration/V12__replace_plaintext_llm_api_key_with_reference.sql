update site_setting
set setting_value = '',
    editable = 0,
    description = '旧明文密钥字段已停用；真实密钥必须由环境变量、Docker Secret 或外部密钥管理服务提供',
    update_time = current_timestamp
where setting_key = 'llm.apiKey';

insert into site_setting(setting_key, setting_value, value_type, group_name, label, description, secret, editable)
values (
    'llm.apiKeyRef',
    'env:YLCLOUD_LLM_API_KEY',
    'string',
    'ai',
    'LLM API Key 引用',
    '只保存密钥来源引用，例如 env:YLCLOUD_LLM_API_KEY、docker-secret:llm_api_key 或 vault:secret/ylcloud/llm',
    0,
    1
)
on duplicate key update
    setting_value = case
        when site_setting.setting_value is null or site_setting.setting_value = '' then values(setting_value)
        else site_setting.setting_value
    end,
    value_type = values(value_type),
    group_name = values(group_name),
    label = values(label),
    description = values(description),
    secret = values(secret),
    editable = values(editable);
