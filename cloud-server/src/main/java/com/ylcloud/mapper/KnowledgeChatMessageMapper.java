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
    String SELECT_COLUMNS = "id, session_id as sessionId, user_id as userId, sequence_no as sequenceNo, " +
            "source_message_id as sourceMessageId, role, content, citations_json as citationsJson, " +
            "task_status as taskStatus, error_message as errorMessage, request_key as requestKey, request_json as requestJson, " +
            "context_snapshot_json as contextSnapshotJson, context_hash as contextHash, context_version as contextVersion, " +
            "context_token_count as contextTokenCount, retry_count as retryCount, " +
            "workflow_run_id as workflowRunId, workflow_execution_id as workflowExecutionId, " +
            "workflow_execution_epoch as workflowExecutionEpoch, workflow_status as workflowStatus, " +
            "workflow_degraded as workflowDegraded, workflow_result_hash as workflowResultHash, " +
            "workflow_snapshot_hash as workflowSnapshotHash, workflow_result_json as workflowResultJson, " +
            "generation_status as generationStatus, status, createtime, updatetime";
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    @Insert("insert into knowledge_chat_message(session_id, user_id, sequence_no, source_message_id, role, content, citations_json, task_status, " +
            "error_message, request_key, request_json, context_snapshot_json, context_hash, context_version, context_token_count, retry_count, status, createtime, updatetime) " +
            "values(#{sessionId}, #{userId}, #{sequenceNo}, #{sourceMessageId}, #{role}, #{content}, #{citationsJson}, #{taskStatus}, " +
            "#{errorMessage}, #{requestKey}, #{requestJson}, #{contextSnapshotJson}, #{contextHash}, #{contextVersion}, #{contextTokenCount}, coalesce(#{retryCount},0), #{status}, #{createtime}, " +
            "coalesce(#{updatetime},#{createtime}))")
    int insert(KnowledgeChatMessage message);

    @Select("select " + SELECT_COLUMNS + " " +
            "from knowledge_chat_message where session_id = #{sessionId} and status = 1 order by sequence_no asc")
    List<KnowledgeChatMessage> listBySessionId(@Param("sessionId") Long sessionId);

    @Select("select " + SELECT_COLUMNS + " from knowledge_chat_message " +
            "where session_id = #{sessionId} and request_key = #{requestKey} and status = 1 limit 1")
    KnowledgeChatMessage getByRequestKey(@Param("sessionId") Long sessionId, @Param("requestKey") String requestKey);

    @Select("select " + SELECT_COLUMNS + " from knowledge_chat_message " +
            "where id = #{messageId} and session_id = #{sessionId} and user_id = #{userId} and status = 1")
    KnowledgeChatMessage getOwned(@Param("messageId") Long messageId, @Param("sessionId") Long sessionId,
                                  @Param("userId") Long userId);

    @Select("select " + SELECT_COLUMNS + " from knowledge_chat_message " +
            "where id = #{messageId} and status = 1")
    KnowledgeChatMessage getTaskById(@Param("messageId") Long messageId);

    @Select("select " + SELECT_COLUMNS + " from knowledge_chat_message message " +
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

    @Select("select " + SELECT_COLUMNS + " from knowledge_chat_message " +
            "where task_status = 'QUEUED' and status = 1 order by id asc limit #{limit}")
    List<KnowledgeChatMessage> listQueued(@Param("limit") int limit);

    @Update("update knowledge_chat_message set workflow_run_id=#{runId}, workflow_execution_id=#{executionId}, " +
            "workflow_execution_epoch=#{epoch}, workflow_status='QUEUED', workflow_degraded=0, " +
            "generation_status=null, error_message=null, updatetime=now() " +
            "where id=#{messageId} and workflow_run_id is null and task_status='QUEUED' and status=1")
    int bindWorkflowRun(@Param("messageId") Long messageId, @Param("runId") String runId,
                        @Param("executionId") String executionId, @Param("epoch") Integer epoch);

    @Update("update knowledge_chat_message set content='Workflow 暂时不可用，请重试。', task_status='FAILED', " +
            "error_message=#{errorMessage}, updatetime=now() where id=#{messageId} and workflow_run_id is null " +
            "and task_status='QUEUED' and status=1")
    int markWorkflowSubmissionFailed(@Param("messageId") Long messageId,
                                     @Param("errorMessage") String errorMessage);

    @Update("update knowledge_chat_message set workflow_status=#{workflowStatus}, task_status=#{taskStatus}, " +
            "workflow_degraded=#{degraded}, error_message=null, updatetime=now() " +
            "where id=#{messageId} and workflow_execution_id=#{executionId} " +
            "and workflow_execution_epoch=#{epoch} and status=1 " +
            "and field(workflow_status,'QUEUED','PLANNING','VALIDATING','RUNNING') > 0 " +
            "and field(workflow_status,'QUEUED','PLANNING','VALIDATING','RUNNING') " +
            "<= field(#{workflowStatus},'QUEUED','PLANNING','VALIDATING','RUNNING')")
    int updateWorkflowProgress(@Param("messageId") Long messageId,
                               @Param("executionId") String executionId,
                               @Param("epoch") Integer epoch,
                               @Param("workflowStatus") String workflowStatus,
                               @Param("taskStatus") String taskStatus,
                               @Param("degraded") boolean degraded);

    @Update("update knowledge_chat_message set workflow_execution_id=#{executionId}, " +
            "workflow_execution_epoch=#{epoch}, workflow_status='QUEUED', workflow_degraded=0, " +
            "workflow_result_hash=null, workflow_snapshot_hash=null, workflow_result_json=null, " +
            "generation_status=null, task_status='QUEUED', error_message=null, updatetime=now() " +
            "where id=#{messageId} and workflow_run_id=#{runId} " +
            "and #{epoch} > workflow_execution_epoch and task_status='QUEUED' and status=1")
    int bindWorkflowRetry(@Param("messageId") Long messageId, @Param("runId") String runId,
                          @Param("executionId") String executionId, @Param("epoch") Integer epoch);

    @Update("update knowledge_chat_message set workflow_status=#{workflowStatus}, workflow_degraded=#{degraded}, " +
            "workflow_result_hash=#{resultHash}, workflow_snapshot_hash=#{snapshotHash}, " +
            "workflow_result_json=#{resultJson}, generation_status='PENDING', task_status='RUNNING', " +
            "error_message=null, updatetime=now() where id=#{messageId} " +
            "and workflow_run_id=#{runId} and workflow_execution_id=#{executionId} " +
            "and workflow_execution_epoch=#{epoch} " +
            "and workflow_status in ('QUEUED','PLANNING','VALIDATING','RUNNING') and status=1")
    int queueWorkflowGeneration(@Param("messageId") Long messageId, @Param("runId") String runId,
                                @Param("executionId") String executionId, @Param("epoch") Integer epoch,
                                @Param("workflowStatus") String workflowStatus,
                                @Param("degraded") boolean degraded,
                                @Param("resultHash") String resultHash,
                                @Param("snapshotHash") String snapshotHash,
                                @Param("resultJson") String resultJson);

    @Update("update knowledge_chat_message set generation_status='RUNNING', task_status='RUNNING', updatetime=now() " +
            "where id=#{messageId} and workflow_execution_epoch=#{epoch} " +
            "and generation_status='PENDING' and workflow_status in ('SUCCEEDED','DEGRADED') and status=1")
    int claimWorkflowGeneration(@Param("messageId") Long messageId, @Param("epoch") Integer epoch);

    @Update("update knowledge_chat_message set content=#{content}, citations_json='[]', task_status='SUCCESS', " +
            "generation_status='SUCCESS', error_message=null, updatetime=now() where id=#{messageId} " +
            "and workflow_execution_epoch=#{epoch} and generation_status='RUNNING' and status=1")
    int markWorkflowGenerated(@Param("messageId") Long messageId, @Param("epoch") Integer epoch,
                              @Param("content") String content);

    @Update("update knowledge_chat_message set content='回答生成失败，请重试。', task_status='FAILED', " +
            "generation_status='FAILED', error_message=#{errorMessage}, updatetime=now() where id=#{messageId} " +
            "and workflow_execution_epoch=#{epoch} and generation_status='RUNNING' and status=1")
    int markWorkflowGenerationFailed(@Param("messageId") Long messageId, @Param("epoch") Integer epoch,
                                     @Param("errorMessage") String errorMessage);

    @Update("update knowledge_chat_message set generation_status='PENDING', task_status='RUNNING', " +
            "error_message='Java 生成进程中断后自动恢复', updatetime=now() where generation_status='RUNNING' " +
            "and workflow_status in ('SUCCEEDED','DEGRADED') and updatetime < #{cutoff} and status=1")
    int requeueStaleWorkflowGeneration(@Param("cutoff") java.time.LocalDateTime cutoff);

    @Update("update knowledge_chat_message set task_status='FAILED', generation_status='NOT_REQUIRED', " +
            "workflow_status=#{workflowStatus}, workflow_degraded=0, " +
            "content='Workflow 执行失败，请重试。', error_message=#{errorMessage}, updatetime=now() " +
            "where id=#{messageId} and workflow_execution_id=#{executionId} " +
            "and workflow_execution_epoch=#{epoch} and status=1")
    int markWorkflowFailed(@Param("messageId") Long messageId,
                           @Param("executionId") String executionId,
                           @Param("epoch") Integer epoch,
                           @Param("workflowStatus") String workflowStatus,
                           @Param("errorMessage") String errorMessage);

    @Update("update knowledge_chat_message set task_status='QUEUED', generation_status=case " +
            "when workflow_status in ('SUCCEEDED','DEGRADED') then 'PENDING' else null end, " +
            "error_message=null, retry_count=retry_count+1, updatetime=now() " +
            "where id=#{messageId} and task_status='FAILED' and workflow_run_id is not null and status=1")
    int prepareWorkflowRetry(@Param("messageId") Long messageId);

    @Update("update knowledge_chat_message set task_status='FAILED', error_message=#{errorMessage}, updatetime=now() " +
            "where id=#{messageId} and workflow_execution_epoch=#{epoch} and task_status='QUEUED' and status=1")
    int markWorkflowRetrySubmissionFailed(@Param("messageId") Long messageId,
                                          @Param("epoch") Integer epoch,
                                          @Param("errorMessage") String errorMessage);

    @Select("select " + SELECT_COLUMNS + " from knowledge_chat_message where workflow_run_id is not null " +
            "and status=1 and (workflow_status in ('QUEUED','PLANNING','VALIDATING','RUNNING') " +
            "or generation_status='PENDING') order by updatetime asc limit #{limit}")
    List<KnowledgeChatMessage> listWorkflowReconcilable(@Param("limit") int limit);

    @Select("select " + SELECT_COLUMNS + " from knowledge_chat_message where workflow_run_id is null " +
            "and role='assistant' and task_status='QUEUED' and status=1 order by id asc limit #{limit}")
    List<KnowledgeChatMessage> listWorkflowUnaccepted(@Param("limit") int limit);

    @Select("select count(1) from knowledge_chat_message where session_id = #{sessionId} and status = 1")
    Integer countBySessionId(@Param("sessionId") Long sessionId);

    @Update("update knowledge_chat_message set status = 0, context_snapshot_json = null, context_hash = null, " +
            "context_version = null, context_token_count = null where session_id = #{sessionId}")
    int disableBySessionId(@Param("sessionId") Long sessionId);

    @Select("select coalesce(sum(context_token_count),0) from knowledge_chat_message where user_id=#{userId} and task_status='SUCCESS' and status=1")
    Long sumContextTokens(@Param("userId") Long userId);
}
