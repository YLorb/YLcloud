package com.ylcloud.mapper;

import com.ylcloud.entity.KnowledgeChatMessage;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface KnowledgeChatMessageMapper {
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    @Insert("insert into knowledge_chat_message(session_id, user_id, role, content, citations_json, status, createtime) " +
            "values(#{sessionId}, #{userId}, #{role}, #{content}, #{citationsJson}, #{status}, #{createtime})")
    int insert(KnowledgeChatMessage message);

    @Select("select id, session_id as sessionId, user_id as userId, role, content, citations_json as citationsJson, status, createtime " +
            "from knowledge_chat_message where session_id = #{sessionId} and status = 1 order by id asc")
    List<KnowledgeChatMessage> listBySessionId(@Param("sessionId") Long sessionId);

    @Select("select count(1) from knowledge_chat_message where session_id = #{sessionId} and status = 1")
    Integer countBySessionId(@Param("sessionId") Long sessionId);

    @Update("update knowledge_chat_message set status = 0 where session_id = #{sessionId}")
    int disableBySessionId(@Param("sessionId") Long sessionId);
}
