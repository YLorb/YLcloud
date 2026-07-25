package com.ylcloud.mapper;

import com.ylcloud.VO.SpaceMemberVO;
import com.ylcloud.entity.SpaceMember;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 空间成员表 Mapper。
 */
@Mapper
public interface SpaceMemberMapper {

    /**
     * 新增 insert 相关逻辑。
     * @return 影响行数
     */
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    @Insert("insert into space_member(space_id, user_id, role, status, createtime, updatetime) " +
            "values(#{spaceId}, #{userId}, #{role}, #{status}, #{createtime}, #{updatetime})")
    int insert(SpaceMember spaceMember);

    /**
     * 查询 getActive 相关逻辑。
     * @return 处理结果
     */
    @Select("select sm.id, sm.space_id as spaceId, sm.user_id as userId, sm.role, sm.status, sm.createtime, sm.updatetime " +
            "from space_member sm where sm.space_id = #{spaceId} and sm.user_id = #{userId} and sm.status = 1 " +
            "and exists(select 1 from spaces s where s.id = sm.space_id and s.status = 1 and s.lifecycle_state = 'ACTIVE')")
    SpaceMember getActive(@Param("spaceId") Long spaceId, @Param("userId") Long userId);

    @Select("select id, space_id as spaceId, user_id as userId, role, status, createtime, updatetime " +
            "from space_member where space_id = #{spaceId} and user_id = #{userId} and status = 1 for update")
    SpaceMember getActiveForUpdate(@Param("spaceId") Long spaceId, @Param("userId") Long userId);

    /**
     * 查询 listBySpaceId 相关逻辑。
     * @return 列表结果
     */
    @Select("select id, space_id as spaceId, user_id as userId, role, status, createtime, updatetime " +
            "from space_member where space_id = #{spaceId} and status = 1 order by role, createtime")
    List<SpaceMemberVO> listBySpaceId(@Param("spaceId") Long spaceId);

    /**
     * 更新 updateRole 相关逻辑。
     * @return 影响行数
     */
    @Update("update space_member set role = #{role}, updatetime = #{updateTime} " +
            "where space_id = #{spaceId} and user_id = #{userId} and status = 1")
    int updateRole(@Param("spaceId") Long spaceId,
                   @Param("userId") Long userId,
                   @Param("role") String role,
                   @Param("updateTime") LocalDateTime updateTime);

    @Select("select count(*) from space_member where space_id = #{spaceId} and status = 1")
    int countActive(@Param("spaceId") Long spaceId);

    @Select("select count(*) from space_member where space_id = #{spaceId} and status = 1 and role = 'OWNER'")
    int countActiveOwners(@Param("spaceId") Long spaceId);

    @Insert("insert into space_member(space_id, user_id, role, status, createtime, updatetime) " +
            "values(#{spaceId}, #{userId}, #{role}, 1, #{now}, #{now}) " +
            "on duplicate key update role = #{role}, status = 1, updatetime = #{now}")
    int activate(@Param("spaceId") Long spaceId,
                 @Param("userId") Long userId,
                 @Param("role") String role,
                 @Param("now") LocalDateTime now);

    /**
     * 执行 disable 函数的业务处理。
     * @return 影响行数
     */
    @Update("update space_member set status = 0, updatetime = #{updateTime} " +
            "where space_id = #{spaceId} and user_id = #{userId} and status = 1")
    int disable(@Param("spaceId") Long spaceId,
                @Param("userId") Long userId,
                @Param("updateTime") LocalDateTime updateTime);
}
