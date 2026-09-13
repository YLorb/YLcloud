insert into site_setting(setting_key, setting_value, value_type, group_name, label, description, secret, editable)
values
('storage.userQuotaBytes', '1073741824', 'number', 'file', 'USER 组存储配额', 'USER 角色用户的个人文件最大可用空间；管理页以 GiB 展示', 0, 1),
('storage.adminQuotaBytes', '10737418240', 'number', 'file', 'ADMIN 组存储配额', 'ADMIN 角色用户的个人文件最大可用空间；管理页以 GiB 展示', 0, 1)
on duplicate key update
    label = values(label),
    description = values(description),
    value_type = values(value_type),
    group_name = values(group_name),
    secret = values(secret),
    editable = values(editable);

update site_setting
set label = '单文件大小上限',
    description = '单个上传文件允许的最大容量；管理页以 MB 展示，0 表示无上限'
where setting_key = 'upload.maxFileSize';

update site_setting
set editable = 0,
    label = '旧版用户默认存储配额',
    description = '兼容旧部署的回退配置；新版本请分别配置 USER 与 ADMIN 组配额'
where setting_key = 'storage.defaultUserQuotaBytes';
