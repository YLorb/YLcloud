create table if not exists site_setting (
    id bigint primary key auto_increment,
    setting_key varchar(100) not null,
    setting_value text,
    value_type varchar(30) not null default 'string',
    group_name varchar(50) not null default 'site',
    label varchar(100),
    description varchar(255),
    secret int not null default 0,
    editable int not null default 1,
    create_time timestamp not null default current_timestamp,
    update_time timestamp not null default current_timestamp on update current_timestamp,
    unique key uk_site_setting_key (setting_key),
    index idx_site_setting_group (group_name)
);

insert into site_setting(setting_key, setting_value, value_type, group_name, label, description, secret, editable)
values
('site.name', 'YL Cloud', 'string', 'site', '站点名称', '显示在登录页、浏览器标题和侧边栏中的站点名称', 0, 1),
('site.description', '轻量、清晰、可用的个人网盘', 'string', 'site', '站点描述', '显示在登录页等公开页面的简短介绍', 0, 1),
('site.logoUrl', '', 'string', 'site', 'Logo 地址', '可选，站点 Logo 图片 URL', 0, 1),
('site.publicUrl', 'http://127.0.0.1:5173', 'string', 'site', '访问网址', '对外访问站点的基础 URL，可用于分享链接', 0, 1),
('site.allowRegister', 'true', 'boolean', 'site', '允许注册', '关闭后普通用户无法通过注册页创建账号', 0, 1),
('upload.maxFileSize', '2147483648', 'number', 'file', '单文件大小上限', '单位为字节，前端用于提示，后端底层限制仍受部署配置约束', 0, 1),
('share.publicBaseUrl', '', 'string', 'file', '分享链接基础地址', '留空时使用站点访问网址', 0, 1),
('llm.enabled', 'true', 'boolean', 'ai', '启用问答模型', '控制知识库问答能力是否可用', 0, 1),
('llm.provider', 'openai-compatible', 'string', 'ai', '模型供应商', '例如 openai-compatible、deepseek', 0, 1),
('llm.baseUrl', 'https://api.deepseek.com', 'string', 'ai', 'LLM Base URL', 'OpenAI 兼容接口的基础地址', 0, 1),
('llm.apiKey', '', 'secret', 'ai', 'LLM API Key', '用于访问模型服务的密钥，接口不会明文回显', 1, 1),
('llm.model', 'deepseek-chat', 'string', 'ai', 'LLM 模型', '聊天或问答使用的模型名称', 0, 1),
('rag.modelServiceBaseUrl', 'http://127.0.0.1:8001', 'string', 'ai', 'RAG 模型服务地址', '本项目本地模型服务的基础地址', 0, 1),
('rag.parserServiceBaseUrl', 'http://127.0.0.1:8002', 'string', 'ai', '文档解析服务地址', '文档结构化解析/OCR 服务地址', 0, 1),
('rag.qdrantHost', '127.0.0.1', 'string', 'ai', 'Qdrant Host', '向量数据库主机地址', 0, 1),
('rag.qdrantCollectionName', 'ylcloud_rag_bge_small_zh_v15', 'string', 'ai', 'Qdrant 集合名', '默认向量集合名称', 0, 1)
on duplicate key update
    label = values(label),
    description = values(description),
    value_type = values(value_type),
    group_name = values(group_name),
    secret = values(secret),
    editable = values(editable);
