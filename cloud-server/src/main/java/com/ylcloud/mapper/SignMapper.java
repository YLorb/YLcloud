package com.ylcloud.mapper;

import com.ylcloud.entity.User;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SignMapper {

    @Insert("insert into user(username,password,nickname,status,create_time,update_time) " +
            "values(#{username}, #{password}, #{nickname}, #{status}, #{createTime}, #{updateTime})")
    void insert(User user);
}
