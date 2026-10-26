insert into site_setting(setting_key,setting_value,value_type,group_name,label,description,secret,editable)
values ('storage.downloadMode','proxy','string','file','下载方式',
        '代理：经本站传输；直链：授权后跳转至短期有效的存储链接。目录打包始终经本站传输，已签发直链在到期前仍可使用。',0,1)
on duplicate key update label=values(label),description=values(description);
