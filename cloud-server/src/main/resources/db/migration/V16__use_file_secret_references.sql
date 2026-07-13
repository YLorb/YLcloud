update site_setting
set setting_value = 'docker-secret:llm_api_key',
    description = '只保存密钥来源引用；Compose 默认使用 docker-secret:llm_api_key，其他部署可使用 env:、vault: 等引用',
    update_time = now()
where setting_key = 'llm.apiKeyRef'
  and (setting_value is null or setting_value = '' or setting_value = 'env:YLCLOUD_LLM_API_KEY');
