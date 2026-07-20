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
    @Insert("insert into knowledge_chat_message(session_id, user_id, sequence_no, source_message_id, role, content, citations_json, task_status, " +
            "error_message, request_key, request_json, context_snapshot_json, context_hash, context_version, context_token_count, retry_count, status, createtime, updatetime) " +
            "values(#{sessionId}, #{userId}, #{sequenceNo}, #{sourceMessageId}, #{role}, #{content}, #{citationsJson}, #{taskStatus}, " +
            "#{errorMessage}, #{requestKey}, #{requestJson}, #{contextSnapshotJson}, #{contextHash}, #{contextVersion}, #{contextTokenCount}, coalesce(#{retryCount},0), #{status}, #{createtime}, " +
            "coalesce(#{updatetime},#{createtime}))")
    int insert(KnowledgeChatMessage message);

    @Select("select id, session_id as sessionId, user_id as userId, sequence_no as sequenceNo, source_message_id as sourceMessageId, role, content, citations_json as citationsJson, " +
            "task_status as taskStatus, error_message as errorMessage, request_key as requestKey, request_json as requestJson, " +
            "retry_count as retryCount, status, createtime, updatetime " +
            "from knowledge_chat_message where session_id = #{sessionId} and status = 1 order by sequence_no asc")
    List<KnowledgeChatMessage> listBySessionId(@Param("sessionId") Long sessionId);

    @Select("select id, session_id as sessionId, user_id as userId, sequence_no as sequenceNo, source_message_id as sourceMessageId, role, content, citations_json as citationsJson, " +
            "task_status as taskStatus, error_message as errorMessage, request_key as requestKey, request_json as requestJson, " +
            "retry_count as retryCount, status, createtime, updatetime from knowledge_chat_message " +
            "where session_id = #{sessionId} and request_key = #{requestKey} and status = 1 limit 1")
    KnowledgeChatMessage getByRequestKey(@Param("sessionId") Long sessionId, @Param("requestKey") String requestKey);

    @Select("select id, session_id as sessionId, user_id as userId, sequence_no as sequenceNo, source_message_id as sourceMessageId, role, content, citations_json as citationsJson, " +
            "task_status as taskStatus, error_message as errorMessage, request_key as requestKey, request_json as requestJson, " +
            "retry_count as retryCount, status, createtime, updatetime from knowledge_chat_message " +
            "where id = #{messageId} and session_id = #{sessionId} and user_id = #{userId} and status = 1")
    KnowledgeChatMessage getOwned(@Param("messageId") Long messageId, @Param("sessionId") Long sessionId,
                                  @Param("userId") Long userId);

    @Select("select id, session_id as sessionId, user_id as userId, sequence_no as sequenceNo, source_message_id as sourceMessageId, role, content, citations_json as citationsJson, " +
            "task_status as taskStatus, error_message as errorMessage, request_key as requestKey, request_json as requestJson, " +
            "context_snapshot_json as contextSnapshotJson, context_hash as contextHash, context_version as contextVersion, context_token_count as contextTokenCount, retry_count as retryCount, status, createtime, updatetime from knowledge_chat_message " +
            "where id = #{messageId} and status = 1")
    KnowledgeChatMessage getTaskById(@Param("messageId") Long messageId);

    @Select("select id, session_id as sessionId, user_id as userId, sequence_no as sequenceNo, source_message_id as sourceMessageId, role, content, citations_json as citationsJson, " +
            "task_status as taskStatus, retry_count as retryCount, status, createtime, updatetime from knowledge_chat_message message " +
            "where message.session_id = #{sessionId} and message.user_id = #{userId} and message.status = 1 and message.sequence_no < #{beforeSequenceNo} " +
            "and ((message.role = 'user' and exists (select 1 from knowledge_chat_message answer where answer.source_message_id = message.id " +
            "and answer.role = 'assistant' and answer.task_status = 'SUCCESS' and answer.status = 1)) " +
            "or (message.role = 'assistant' and message.task_status = 'SUCCESS')) order by message.sequence_no asc")
    List<KnowledgeChatMessage> listContextCandidates(@Param("sessionId") Long sessionId, @Param("userId") Long userId,
                                                     @Param("beforeSequenceNo") Long beforeSequenceNo);

    @Update("update knowledge_chat_message set context_snapshot_json = #{snapshotJson}, context_hash = #{contextHash}, " +
            "context_version = #{contextVersion}, context_token_count = #{tokenCount}, updatetime = now() " +
            "where id = #{messageId} and task_status = 'RUNNING' and context_snapshot_json is null and status = 1")
    int saveContextSnapshot(@Param("messageId") Long messageId, @Param("snapshotJson") String snapshotJson,
                            @Param("contextHash") String contextHash, @Param("contextVersion") Integer contextVersion,
                            @Param("tokenCount") Integer tokenCount);

    @Update("update knowledge_chat_message set context_snapshot_json = #{snapshotJson}, context_hash = #{contextHash}, updatetime = now() " +
            "where id = #{messageId} and task_status = 'RUNNING' and status = 1 and context_snapshot_json is not null")
    int finalizeContextSnapshot(@Param("messageId") Long messageId, @Param("snapshotJson") String snapshotJson,
                                @Param("contextHash") String contextHash);

    @Update("update knowledge_chat_message set task_status = 'RUNNING', error_message = null, updatetime = now() " +
            "where id = #{messageId} and task_status = 'QUEUED' and status = 1")
    int claimQueued(@Param("messageId") Long messageId);

    @Update("update knowledge_chat_message set content = #{content}, citations_json = #{citationsJson}, " +
            "task_status = 'SUCCESS', error_message = null, updatetime = now() where id = #{messageId} and task_status = 'RUNNING' and status = 1")
    int markSuccess(@Param("messageId") Long messageId, @Param("content") String content,
                    @Param("citationsJson") String citationsJson);

    @Update("update knowledge_chat_message set content = '回答生成失败，请重试。', task_status = 'FAILED', " +
            "error_message = #{errorMessage}, updatetime = now() where id = #{messageId} and task_status = 'RUNNING' and status = 1")
    int markFailed(@Param("messageId") Long messageId, @Param("errorMessage") String errorMessage);

    @Update("update knowledge_chat_message set task_status = 'QUEUED', error_message = null, retry_count = retry_count + 1, " +
            "updatetime = now() where id = #{messageId} and task_status = 'FAILED' and status = 1")
    int retryFailed(@Param("messageId") Long messageId);

    @Update("update knowledge_chat_message set task_status = 'QUEUED', error_message = '进程中断后自动恢复', updatetime = now() " +
            "where task_status = 'RUNNING' and updatetime < #{cutoff} and status = 1")
    int requeueStale(@Param("cutoff") java.time.LocalDateTime cutoff);

    @Select("select id, session_id as sessionId, user_id as userId, sequence_no as sequenceNo, source_message_id as sourceMessageId, role, content, citations_json as citationsJson, " +
            "task_status as taskStatus, error_message as errorMessage, request_key as requestKey, request_json as requestJson, " +
            "retry_count as retryCount, status, createtime, updatetime from knowledge_chat_message " +
            "where task_status = 'QUEUED' and status = 1 order by id asc limit #{limit}")
    List<KnowledgeChatMessage> listQueued(@Param("limit") int limit);

    @Select("select count(1) from knowledge_chat_message where session_id = #{sessionId} and status = 1")
    Integer countBySessionId(@Param("sessionId") Long sessionId);

    @Update("update knowledge_chat_message set status = 0, context_snapshot_json = null, context_hash = null, " +
            "context_version = null, context_token_count = null where session_id = #{sessionId}")
    int disableBySessionId(@Param("sessionId") Long sessionId);

    @Select("select coalesce(sum(context_token_count),0) from knowledge_chat_message where user_id=#{userId} and task_status='SUCCESS' and status=1")
    Long sumContextTokens(@Param("userId") Long userId);
}
