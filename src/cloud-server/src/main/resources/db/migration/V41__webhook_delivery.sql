insert into site_setting(setting_key,setting_value,value_type,group_name,label,description,secret,editable)
values
('webhook.enabled','true','boolean','permissions','Webhook 投递','紧急停用全部 Webhook Worker',0,1),
('webhook.allowPrivateTargets','false','boolean','permissions','允许内网 Webhook','仅部署所有者可显式开放内网、回环和链路本地目标',0,1),
('webhook.maxAttempts','8','number','permissions','Webhook 最大尝试次数','达到上限后进入死信状态',0,1),
('webhook.deliveryTimeoutSeconds','10','number','permissions','Webhook 超时秒数','单次连接与响应超时',0,1),
('webhook.secretRotationGraceHours','24','number','permissions','Webhook Secret 轮换窗口','轮换后同时发送旧签名的小时数',0,1)
on duplicate key update
    label=values(label),description=values(description),value_type=values(value_type),
    group_name=values(group_name),secret=values(secret),editable=values(editable);

create table webhook_subscription (
    subscription_id bigint primary key auto_increment,
    user_id bigint not null,
    api_key_id bigint not null,
    subscription_name varchar(100) not null,
    target_url varchar(2048) not null,
    event_types varchar(1000) not null,
    include_content tinyint not null default 0,
    subscription_status varchar(16) not null default 'ACTIVE',
    secret_cipher text not null,
    previous_secret_cipher text null,
    previous_secret_valid_until datetime(6) null,
    last_delivery_at datetime(6) null,
    createtime datetime(6) not null,
    updatetime datetime(6) not null,
    index idx_webhook_subscription_user (user_id, subscription_status),
    index idx_webhook_subscription_key (api_key_id, subscription_status),
    constraint fk_webhook_subscription_user foreign key (user_id) references users(user_id),
    constraint fk_webhook_subscription_api_key foreign key (api_key_id) references user_api_key(key_id),
    constraint chk_webhook_subscription_status check (subscription_status in ('ACTIVE','DISABLED'))
);

create table webhook_event (
    event_id char(36) primary key,
    event_key varchar(191) not null,
    user_id bigint not null,
    event_type varchar(64) not null,
    resource_type varchar(32) not null,
    resource_id varchar(128) not null,
    resource_version bigint not null,
    file_id bigint null,
    space_id bigint null,
    minimal_payload_json json not null,
    content_payload_json json null,
    occurred_at datetime(6) not null,
    createtime datetime(6) not null,
    unique key uk_webhook_event_key (event_key),
    index idx_webhook_event_user_time (user_id, occurred_at),
    constraint fk_webhook_event_user foreign key (user_id) references users(user_id)
);

create table webhook_delivery (
    delivery_id bigint primary key auto_increment,
    event_id char(36) not null,
    subscription_id bigint not null,
    delivery_status varchar(16) not null default 'PENDING',
    attempt_count int not null default 0,
    next_attempt_at datetime(6) not null,
    lease_token char(36) null,
    lease_until datetime(6) null,
    response_status int null,
    last_error varchar(1000) null,
    delivered_at datetime(6) null,
    createtime datetime(6) not null,
    updatetime datetime(6) not null,
    unique key uk_webhook_delivery_event_subscription (event_id, subscription_id),
    index idx_webhook_delivery_poll (delivery_status, next_attempt_at),
    index idx_webhook_delivery_lease (delivery_status, lease_until),
    constraint fk_webhook_delivery_event foreign key (event_id) references webhook_event(event_id),
    constraint fk_webhook_delivery_subscription foreign key (subscription_id) references webhook_subscription(subscription_id),
    constraint chk_webhook_delivery_status check (delivery_status in ('PENDING','DELIVERING','SUCCEEDED','DEAD'))
);
