package com.ylcloud.mapper;

import com.ylcloud.VO.UserLoginVO;
import com.ylcloud.entity.User;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface LoginMapper {

    /**
     * 查询 getByUsername 相关逻辑。
     * @return 处理结果
     */
    @Select("select user_id as id, username, password, nickname, root_id as rootID, email, avatar, status, role, " +
            "deployment_owner as deploymentOwner, " +
            "create_time as createTime, update_time as updateTime " +
            "from users where username = #{username}")
    User getByUsername(String username);

    /**
     * 查询 getById 相关逻辑。
     * @return 处理结果
     */
    @Select("select user_id as id, username, password, nickname, root_id as rootID, email, avatar, status, role, " +
            "deployment_owner as deploymentOwner, " +
            "create_time as createTime, update_time as updateTime " +
            "from users where user_id = #{userId}")
    User getById(@Param("userId") Long userId);

    @Select("select user_id from users where user_id = #{userId} for update")
    Long lockUserId(@Param("userId") Long userId);

    @Update("update users set password = #{password}, update_time = now() where user_id = #{userId}")
    int updatePassword(@Param("userId") Long userId, @Param("password") String password);
}
