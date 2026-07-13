update site_setting
set setting_value = '',
    description = '留空时使用部署环境中的 YLCLOUD_RAG_MODEL_SERVICE_BASE_URL',
    update_time = current_timestamp
where setting_key = 'rag.modelServiceBaseUrl'
  and setting_value = 'http://127.0.0.1:8001';

update site_setting
set setting_value = '',
    description = '留空时使用部署环境中的 YLCLOUD_RAG_PARSER_SERVICE_BASE_URL',
    update_time = current_timestamp
where setting_key = 'rag.parserServiceBaseUrl'
  and setting_value = 'http://127.0.0.1:8002';

update site_setting
set setting_value = '',
    description = '留空时使用部署环境中的 YLCLOUD_RAG_QDRANT_HOST',
    update_time = current_timestamp
where setting_key = 'rag.qdrantHost'
  and setting_value = '127.0.0.1';
