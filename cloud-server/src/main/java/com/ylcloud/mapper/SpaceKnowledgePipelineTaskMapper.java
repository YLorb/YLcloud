package com.ylcloud.mapper;

import com.ylcloud.entity.SpaceKnowledgePipelineTask;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface SpaceKnowledgePipelineTaskMapper {
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    @Insert("insert into space_knowledge_pipeline_task(space_id, document_id, task_type, task_status, stage, progress, total_count, success_count, failed_count, error_message, force_rebuild, terminal_stage, terminal_reason, incremental_action, incremental_detail, created_by, started_time, finished_time, createtime, updatetime) " +
            "values(#{spaceId}, #{documentId}, #{taskType}, #{taskStatus}, #{stage}, #{progress}, #{totalCount}, #{successCount}, #{failedCount}, #{errorMessage}, #{forceRebuild}, #{terminalStage}, #{terminalReason}, #{incrementalAction}, #{incrementalDetail}, #{createdBy}, #{startedTime}, #{finishedTime}, #{createtime}, #{updatetime})")
    int insert(SpaceKnowledgePipelineTask task);

    @Update("update space_knowledge_pipeline_task set task_status = #{taskStatus}, error_message = #{errorMessage}, " +
            "started_time = #{startedTime}, finished_time = #{finishedTime}, updatetime = #{updateTime} where id = #{id}")
    int updateResult(@Param("id") Long id,
                     @Param("taskStatus") String taskStatus,
                     @Param("errorMessage") String errorMessage,
                     @Param("startedTime") LocalDateTime startedTime,
                     @Param("finishedTime") LocalDateTime finishedTime,
                     @Param("updateTime") LocalDateTime updateTime);

    @Update("update space_knowledge_pipeline_task set task_status = #{taskStatus}, stage = #{stage}, progress = #{progress}, " +
            "total_count = #{totalCount}, success_count = #{successCount}, failed_count = #{failedCount}, error_message = #{errorMessage}, " +
            "started_time = #{startedTime}, finished_time = #{finishedTime}, updatetime = #{updateTime} where id = #{id}")
    int updateFlow(@Param("id") Long id,
                   @Param("taskStatus") String taskStatus,
                   @Param("stage") String stage,
                   @Param("progress") Integer progress,
                   @Param("totalCount") Integer totalCount,
                   @Param("successCount") Integer successCount,
                   @Param("failedCount") Integer failedCount,
                   @Param("errorMessage") String errorMessage,
                   @Param("startedTime") LocalDateTime startedTime,
                   @Param("finishedTime") LocalDateTime finishedTime,
                   @Param("updateTime") LocalDateTime updateTime);

    @Update("update space_knowledge_pipeline_task set terminal_stage = #{terminalStage}, terminal_reason = #{terminalReason}, " +
            "incremental_action = #{incrementalAction}, incremental_detail = #{incrementalDetail}, updatetime = #{updateTime} where id = #{id}")
    int updateIncremental(@Param("id") Long id,
                          @Param("terminalStage") String terminalStage,
                          @Param("terminalReason") String terminalReason,
                          @Param("incrementalAction") String incrementalAction,
                          @Param("incrementalDetail") String incrementalDetail,
                          @Param("updateTime") LocalDateTime updateTime);

    @Select("select id, space_id as spaceId, document_id as documentId, task_type as taskType, task_status as taskStatus, " +
            "stage, progress, total_count as totalCount, success_count as successCount, failed_count as failedCount, " +
            "error_message as errorMessage, force_rebuild as forceRebuild, terminal_stage as terminalStage, terminal_reason as terminalReason, " +
            "incremental_action as incrementalAction, incremental_detail as incrementalDetail, created_by as createdBy, started_time as startedTime, finished_time as finishedTime, createtime, updatetime " +
            "from space_knowledge_pipeline_task where space_id = #{spaceId} order by createtime desc limit #{limit}")
    List<SpaceKnowledgePipelineTask> listBySpaceId(@Param("spaceId") Long spaceId, @Param("limit") Integer limit);

    /**
     * 查询当前用户提交的知识流水线后台任务，可按 Space 过滤。
     */
    @Select("select id, space_id as spaceId, document_id as documentId, task_type as taskType, task_status as taskStatus, " +
            "stage, progress, total_count as totalCount, success_count as successCount, failed_count as failedCount, " +
            "error_message as errorMessage, force_rebuild as forceRebuild, terminal_stage as terminalStage, terminal_reason as terminalReason, " +
            "incremental_action as incrementalAction, incremental_detail as incrementalDetail, created_by as createdBy, started_time as startedTime, finished_time as finishedTime, createtime, updatetime " +
            "from space_knowledge_pipeline_task where created_by = #{userId} and (#{spaceId} is null or space_id = #{spaceId}) " +
            "order by createtime desc limit #{limit}")
    List<SpaceKnowledgePipelineTask> listByCreatedBy(@Param("userId") Long userId,
                                                      @Param("spaceId") Long spaceId,
                                                      @Param("limit") Integer limit);

    @Select("select id, space_id as spaceId, document_id as documentId, task_type as taskType, task_status as taskStatus, " +
            "stage, progress, total_count as totalCount, success_count as successCount, failed_count as failedCount, " +
            "error_message as errorMessage, force_rebuild as forceRebuild, terminal_stage as terminalStage, terminal_reason as terminalReason, " +
            "incremental_action as incrementalAction, incremental_detail as incrementalDetail, created_by as createdBy, started_time as startedTime, finished_time as finishedTime, createtime, updatetime " +
            "from space_knowledge_pipeline_task where id = #{id}")
    SpaceKnowledgePipelineTask getById(@Param("id") Long id);

    @Select("select id, space_id as spaceId, document_id as documentId, task_type as taskType, task_status as taskStatus, " +
            "stage, progress, total_count as totalCount, success_count as successCount, failed_count as failedCount, " +
            "error_message as errorMessage, force_rebuild as forceRebuild, terminal_stage as terminalStage, terminal_reason as terminalReason, " +
            "incremental_action as incrementalAction, incremental_detail as incrementalDetail, created_by as createdBy, started_time as startedTime, finished_time as finishedTime, createtime, updatetime " +
            "from space_knowledge_pipeline_task where space_id = #{spaceId} and task_status in ('FAILED', 'PARTIAL_SUCCESS') order by createtime desc limit #{limit}")
    List<SpaceKnowledgePipelineTask> listFailedBySpaceId(@Param("spaceId") Long spaceId, @Param("limit") Integer limit);

    @Select("select id, space_id as spaceId, document_id as documentId, task_type as taskType, task_status as taskStatus, " +
            "stage, progress, total_count as totalCount, success_count as successCount, failed_count as failedCount, " +
            "error_message as errorMessage, force_rebuild as forceRebuild, terminal_stage as terminalStage, terminal_reason as terminalReason, " +
            "incremental_action as incrementalAction, incremental_detail as incrementalDetail, created_by as createdBy, started_time as startedTime, finished_time as finishedTime, createtime, updatetime " +
            "from space_knowledge_pipeline_task where space_id = #{spaceId} and document_id = #{documentId} " +
            "and task_type = 'PROFILE_DOCUMENT' and task_status in ('PENDING', 'RUNNING') order by createtime desc limit 1")
    SpaceKnowledgePipelineTask getActiveDocumentTask(@Param("spaceId") Long spaceId, @Param("documentId") Long documentId);
}
