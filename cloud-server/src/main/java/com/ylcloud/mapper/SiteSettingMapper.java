package com.ylcloud.mapper;

import com.ylcloud.entity.SiteSetting;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface SiteSettingMapper {
    @Select("select id, setting_key as settingKey, setting_value as settingValue, value_type as valueType, " +
            "group_name as groupName, label, description, secret, editable, create_time as createTime, " +
            "update_time as updateTime from site_setting order by group_name, id")
    List<SiteSetting> listAll();

    @Select("select id, setting_key as settingKey, setting_value as settingValue, value_type as valueType, " +
            "group_name as groupName, label, description, secret, editable, create_time as createTime, " +
            "update_time as updateTime from site_setting where setting_key = #{key}")
    SiteSetting getByKey(@Param("key") String key);

    @Update("update site_setting set setting_value = #{value}, update_time = now() " +
            "where setting_key = #{key} and editable = 1")
    int updateValue(@Param("key") String key, @Param("value") String value);
}
