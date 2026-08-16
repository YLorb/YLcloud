insert into site_setting(setting_key,setting_value,value_type,group_name,label,description,secret,editable)
values
('api.v1.readOnlyAfter','','string','permissions','Open API v1 只读时间','ISO-8601 时间；留空表示 v1 仍可写',0,1),
('api.version.readOnlyGraceDays','30','number','permissions','旧 API 版本过渡天数','新主版本发布后旧版本保持可写的天数',0,1),
('api.maxPageSize','100','number','permissions','Open API 最大分页大小','限制单次开放 API 返回的资源数量',0,1)
on duplicate key update
    label=values(label),description=values(description),value_type=values(value_type),
    group_name=values(group_name),secret=values(secret),editable=values(editable);
