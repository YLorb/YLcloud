package com.ylcloud.mapper;

import com.ylcloud.entity.User;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface LoginMapper {

    /**
     * 根据用户名查询用户，并返回用户信息
     * @param username
     * @return
     */
    @Select("select * from users where username = #{username}")
    User getByUsername(String username);
}
