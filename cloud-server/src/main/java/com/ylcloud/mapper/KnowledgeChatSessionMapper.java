package com.ylcloud.mapper;

import com.ylcloud.entity.KnowledgeChatSession;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface KnowledgeChatSessionMapper {
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    @Insert("insert into knowledge_chat_session(user_id, title, scope_mode, scope_space_ids, status, createtime, updatetime) " +
            "values(#{userId}, #{title}, #{scopeMode}, #{scopeSpaceIds}, #{status}, #{createtime}, #{updatetime})")
    int insert(KnowledgeChatSession session);

    @Select("select id, user_id as userId, title, scope_mode as scopeMode, scope_space_ids as scopeSpaceIds, status, createtime, updatetime " +
            "from knowledge_chat_session where id = #{sessionId} and user_id = #{userId} and status = 1")
    KnowledgeChatSession getActive(@Param("sessionId") Long sessionId, @Param("userId") Long userId);

    @Select("select id, user_id as userId, title, scope_mode as scopeMode, scope_space_ids as scopeSpaceIds, status, createtime, updatetime " +
            "from knowledge_chat_session " +
            "where user_id = #{userId} and status = 1 and (#{keyword} is null or #{keyword} = '' or title like concat('%', #{keyword}, '%')) " +
            "order by updatetime desc limit #{limit}")
    List<KnowledgeChatSession> listByUser(@Param("userId") Long userId, @Param("keyword") String keyword, @Param("limit") Integer limit);

    @Update("update knowledge_chat_session set title = #{title}, updatetime = #{updateTime} " +
            "where id = #{sessionId} and user_id = #{userId} and status = 1")
    int updateTitle(@Param("sessionId") Long sessionId, @Param("userId") Long userId, @Param("title") String title, @Param("updateTime") LocalDateTime updateTime);

    @Update("update knowledge_chat_session set status = 0, updatetime = #{updateTime} where id = #{sessionId} and user_id = #{userId} and status = 1")
    int disable(@Param("sessionId") Long sessionId, @Param("userId") Long userId, @Param("updateTime") LocalDateTime updateTime);

    @Update("update knowledge_chat_session set updatetime = #{updateTime} where id = #{sessionId} and user_id = #{userId} and status = 1")
    int touch(@Param("sessionId") Long sessionId, @Param("userId") Long userId, @Param("updateTime") LocalDateTime updateTime);
}
