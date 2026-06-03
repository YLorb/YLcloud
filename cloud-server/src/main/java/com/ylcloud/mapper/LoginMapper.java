package com.ylcloud.mapper;

import com.ylcloud.VO.UserLoginVO;
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
    @Select("select user_id as id, username, password, nickname, root_id as rootID, email, avatar, status, role, " +
            "create_time as createTime, update_time as updateTime " +
            "from users where username = #{username}")
    User getByUsername(String username);

    @Select("select user_id as id, username, password, nickname, root_id as rootID, email, avatar, status, role, " +
            "create_time as createTime, update_time as updateTime " +
            "from users where user_id = #{userId}")
    User getById(@Param("userId") Long userId);
}
