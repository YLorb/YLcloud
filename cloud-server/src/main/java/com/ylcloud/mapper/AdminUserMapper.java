package com.ylcloud.mapper;

import com.ylcloud.entity.User;
import com.ylcloud.VO.AdminUserVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface AdminUserMapper {
    @Select("select u.user_id as id, u.username, u.nickname, u.email, u.status, u.role, " +
            "u.create_time as createTime, u.update_time as updateTime, pg.group_id as groupId, pg.group_name as groupName " +
            "from users u left join user_permission_group upg on upg.user_id = u.user_id " +
            "left join permission_group pg on pg.group_id = upg.group_id order by u.create_time asc, u.user_id asc")
    List<AdminUserVO> listAll();

    @Select("select u.user_id as id, u.username, u.nickname, u.email, u.status, u.role, " +
            "u.create_time as createTime, u.update_time as updateTime, pg.group_id as groupId, pg.group_name as groupName " +
            "from users u left join user_permission_group upg on upg.user_id = u.user_id " +
            "left join permission_group pg on pg.group_id = upg.group_id where u.user_id = #{userId}")
    AdminUserVO getView(@Param("userId") Long userId);

    @Select("select user_id as id, username, nickname, email, status, role, create_time as createTime, update_time as updateTime " +
            "from users where user_id = #{userId} for update")
    User lockById(@Param("userId") Long userId);

    @Select("select count(1) from users where role = 'ADMIN' and status = 1")
    int countActiveAdmins();

    @Update("update users set role = #{role}, update_time = now() where user_id = #{userId}")
    int updateRole(@Param("userId") Long userId, @Param("role") String role);

    @Update("update users set status = #{status}, update_time = now() where user_id = #{userId}")
    int updateStatus(@Param("userId") Long userId, @Param("status") Integer status);
}
