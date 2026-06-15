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
    @Insert("insert into users(username,password,nickname,status,create_time,update_time) " +
            "values(#{username}, #{password}, #{nickname}, #{status}, #{createTime}, #{updateTime})")
    int insert(User user);

    /**
     * 统计 countByUsername 相关逻辑。
     * @return 影响行数
     */
    @Select("select count(*) from users where username = #{username}")
    int countByUsername(String username);

    /**
     * 更新 updateAll 相关逻辑。
     * @return 影响行数
     */
    @Update("update users " +
            "set root_id = #{rootId} " +
            "where user_id = #{userId}")
    int updateAll(@Param("rootId") Long rootId,@Param("userId") Long userId);
}
