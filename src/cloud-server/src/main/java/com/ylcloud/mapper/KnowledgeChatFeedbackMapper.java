package com.ylcloud.mapper;

import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;

@Mapper
public interface KnowledgeChatFeedbackMapper {
    @Insert("insert into knowledge_chat_feedback(user_id,session_id,assistant_message_id,rating,reason,comment,createtime,updatetime) " +
            "values(#{userId},#{sessionId},#{messageId},#{rating},#{reason},#{comment},#{now},#{now}) " +
            "on duplicate key update rating=values(rating),reason=values(reason),comment=values(comment),updatetime=values(updatetime)")
    int save(@Param("userId") Long userId,@Param("sessionId") Long sessionId,@Param("messageId") Long messageId,
             @Param("rating") String rating,@Param("reason") String reason,@Param("comment") String comment,@Param("now") LocalDateTime now);
    @Select("select count(*) from knowledge_chat_feedback where user_id=#{userId}") Integer count(@Param("userId") Long userId);
    @Select("select count(*) from knowledge_chat_feedback where user_id=#{userId} and rating='HELPFUL'") Integer helpful(@Param("userId") Long userId);
}
