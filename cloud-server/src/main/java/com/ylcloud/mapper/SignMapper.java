package com.ylcloud.mapper;

import com.ylcloud.entity.User;
import org.apache.ibatis.annotations.*;

@Mapper
public interface SignMapper {
    /**
     * 新增 insert 相关逻辑。
     * @return 影响行数
     */
    @Options(useGeneratedKeys = true,keyProperty = "id",keyColumn = "user_id")
    @Insert("insert into users(username,password,nickname,status,role,create_time,update_time) " +
            "values(#{username}, #{password}, #{nickname}, #{status}, #{role}, #{createTime}, #{updateTime})")
    int insert(User user);

    /**
     * 统计 countByUsername 相关逻辑。
     * @return 影响行数
     */
    @Select("select count(*) from users where username = #{username}")
    int countByUsername(String username);

    /**
     * Count all users for first-run bootstrap checks.
     */
    @Select("select count(*) from users")
    int countAll();

    /**
     * Serialize all user-creation paths before deciding who is the first user.
     * The lock is held by the surrounding database transaction.
     */
    @Select("select guard_id from user_registration_guard where guard_id = 1 for update")
    int lockRegistrationGuard();

    /**
     * 更新 updateAll 相关逻辑。
     * @return 影响行数
     */
    @Update("update users " +
            "set root_id = #{rootId}, email = #{email} " +
            "where user_id = #{userId}")
    int updateAll(@Param("rootId") Long rootId, @Param("userId") Long userId, @Param("email") String email);
}
