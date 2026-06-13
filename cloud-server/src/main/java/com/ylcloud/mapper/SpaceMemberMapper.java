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

    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    @Insert("insert into space_member(space_id, user_id, role, status, createtime, updatetime) " +
            "values(#{spaceId}, #{userId}, #{role}, #{status}, #{createtime}, #{updatetime})")
    int insert(SpaceMember spaceMember);

    @Select("select id, space_id as spaceId, user_id as userId, role, status, createtime, updatetime " +
            "from space_member where space_id = #{spaceId} and user_id = #{userId} and status = 1")
    SpaceMember getActive(@Param("spaceId") Long spaceId, @Param("userId") Long userId);

    @Select("select id, space_id as spaceId, user_id as userId, role, status, createtime, updatetime " +
            "from space_member where space_id = #{spaceId} and status = 1 order by role, createtime")
    List<SpaceMemberVO> listBySpaceId(@Param("spaceId") Long spaceId);

    @Update("update space_member set role = #{role}, updatetime = #{updateTime} " +
            "where space_id = #{spaceId} and user_id = #{userId} and status = 1")
    int updateRole(@Param("spaceId") Long spaceId,
                   @Param("userId") Long userId,
                   @Param("role") String role,
                   @Param("updateTime") LocalDateTime updateTime);

    @Update("update space_member set status = 0, updatetime = #{updateTime} " +
            "where space_id = #{spaceId} and user_id = #{userId} and status = 1")
    int disable(@Param("spaceId") Long spaceId,
                @Param("userId") Long userId,
                @Param("updateTime") LocalDateTime updateTime);
}
