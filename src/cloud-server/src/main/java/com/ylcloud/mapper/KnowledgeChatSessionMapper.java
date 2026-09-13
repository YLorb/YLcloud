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
    @Insert("insert into knowledge_chat_session(user_id, title, scope_mode, scope_space_ids, next_sequence_no, summary_version, status, createtime, updatetime) " +
            "values(#{userId}, #{title}, #{scopeMode}, #{scopeSpaceIds}, coalesce(#{nextSequenceNo},1), coalesce(#{summaryVersion},0), #{status}, #{createtime}, #{updatetime})")
    int insert(KnowledgeChatSession session);

    @Select("select id, user_id as userId, title, scope_mode as scopeMode, scope_space_ids as scopeSpaceIds, next_sequence_no as nextSequenceNo, rolling_summary as rollingSummary, summary_upto_sequence_no as summaryUptoSequenceNo, summary_version as summaryVersion, status, createtime, updatetime " +
            "from knowledge_chat_session where id = #{sessionId} and user_id = #{userId} and status = 1")
    KnowledgeChatSession getActive(@Param("sessionId") Long sessionId, @Param("userId") Long userId);

    @Select("select id, user_id as userId, title, scope_mode as scopeMode, scope_space_ids as scopeSpaceIds, next_sequence_no as nextSequenceNo, rolling_summary as rollingSummary, summary_upto_sequence_no as summaryUptoSequenceNo, summary_version as summaryVersion, status, createtime, updatetime " +
            "from knowledge_chat_session " +
            "where user_id = #{userId} and status = 1 and (#{keyword} is null or #{keyword} = '' or title like concat('%', #{keyword}, '%')) " +
            "order by updatetime desc limit #{limit}")
    List<KnowledgeChatSession> listByUser(@Param("userId") Long userId, @Param("keyword") String keyword, @Param("limit") Integer limit);

    @Select("select id, user_id as userId, title, scope_mode as scopeMode, scope_space_ids as scopeSpaceIds, next_sequence_no as nextSequenceNo, rolling_summary as rollingSummary, summary_upto_sequence_no as summaryUptoSequenceNo, summary_version as summaryVersion, status, createtime, updatetime " +
            "from knowledge_chat_session where user_id = #{userId} and status = 1 and id > #{afterId} " +
            "order by id asc limit #{limit}")
    List<KnowledgeChatSession> listForExportAfterId(@Param("userId") Long userId,
                                                    @Param("afterId") Long afterId,
                                                    @Param("limit") Integer limit);

    @Select("select id, user_id as userId, title, scope_mode as scopeMode, scope_space_ids as scopeSpaceIds, next_sequence_no as nextSequenceNo, rolling_summary as rollingSummary, summary_upto_sequence_no as summaryUptoSequenceNo, summary_version as summaryVersion, status, createtime, updatetime " +
            "from knowledge_chat_session where id = #{sessionId} and user_id = #{userId} and status = 1 for update")
    KnowledgeChatSession getActiveForUpdate(@Param("sessionId") Long sessionId, @Param("userId") Long userId);

    @Update("update knowledge_chat_session set next_sequence_no = next_sequence_no + #{count}, updatetime = #{updateTime} " +
            "where id = #{sessionId} and user_id = #{userId} and status = 1")
    int reserveSequences(@Param("sessionId") Long sessionId, @Param("userId") Long userId,
                         @Param("count") int count, @Param("updateTime") LocalDateTime updateTime);

    @Update("update knowledge_chat_session set rolling_summary = #{summary}, summary_upto_sequence_no = #{uptoSequenceNo}, " +
            "summary_version = summary_version + 1, updatetime = #{updateTime} where id = #{sessionId} and user_id = #{userId} " +
            "and status = 1 and summary_version = #{expectedVersion}")
    int updateSummary(@Param("sessionId") Long sessionId, @Param("userId") Long userId,
                      @Param("summary") String summary, @Param("uptoSequenceNo") Long uptoSequenceNo,
                      @Param("expectedVersion") Integer expectedVersion, @Param("updateTime") LocalDateTime updateTime);

    @Update("update knowledge_chat_session set title = #{title}, updatetime = #{updateTime} " +
            "where id = #{sessionId} and user_id = #{userId} and status = 1")
    int updateTitle(@Param("sessionId") Long sessionId, @Param("userId") Long userId, @Param("title") String title, @Param("updateTime") LocalDateTime updateTime);

    @Update("update knowledge_chat_session set scope_mode = #{scopeMode}, scope_space_ids = #{scopeSpaceIds}, updatetime = #{updateTime} " +
            "where id = #{sessionId} and user_id = #{userId} and status = 1")
    int updateScope(@Param("sessionId") Long sessionId, @Param("userId") Long userId,
                    @Param("scopeMode") String scopeMode, @Param("scopeSpaceIds") String scopeSpaceIds,
                    @Param("updateTime") LocalDateTime updateTime);

    @Update("update knowledge_chat_session set status = 0, rolling_summary = null, summary_upto_sequence_no = null, " +
            "updatetime = #{updateTime} where id = #{sessionId} and user_id = #{userId} and status = 1")
    int disable(@Param("sessionId") Long sessionId, @Param("userId") Long userId, @Param("updateTime") LocalDateTime updateTime);

    @Update("update knowledge_chat_session set updatetime = #{updateTime} where id = #{sessionId} and user_id = #{userId} and status = 1")
    int touch(@Param("sessionId") Long sessionId, @Param("userId") Long userId, @Param("updateTime") LocalDateTime updateTime);

    @Update("update knowledge_chat_session set status = 0, title = '', scope_space_ids = null, " +
            "rolling_summary = null, summary_upto_sequence_no = null, updatetime = #{updateTime} " +
            "where user_id = #{userId} and status = 1")
    int disableAllByUserId(@Param("userId") Long userId, @Param("updateTime") LocalDateTime updateTime);
}
