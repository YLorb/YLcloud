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
    @Insert("insert into space_knowledge_pipeline_task(space_id, document_id, parent_task_id, task_type, task_status, stage, progress, total_count, success_count, failed_count, error_message, force_rebuild, terminal_stage, terminal_reason, incremental_action, incremental_detail, async_task_id, resource_version, created_by, started_time, finished_time, createtime, updatetime) " +
            "values(#{spaceId}, #{documentId}, #{parentTaskId}, #{taskType}, #{taskStatus}, #{stage}, #{progress}, #{totalCount}, #{successCount}, #{failedCount}, #{errorMessage}, #{forceRebuild}, #{terminalStage}, #{terminalReason}, #{incrementalAction}, #{incrementalDetail}, #{asyncTaskId}, coalesce(#{resourceVersion},1), #{createdBy}, #{startedTime}, #{finishedTime}, #{createtime}, #{updatetime})")
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

    @Update("update space_knowledge_pipeline_task set task_status = #{taskStatus}, stage = #{stage}, progress = #{progress}, " +
            "total_count = #{totalCount}, success_count = #{successCount}, failed_count = #{failedCount}, error_message = #{errorMessage}, " +
            "started_time = coalesce(#{startedTime}, started_time), finished_time = #{finishedTime}, updatetime = #{updateTime} " +
            "where id = #{id} and task_status = 'RUNNING'")
    int updateFlowIfRunning(@Param("id") Long id,
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

    @Update("update space_knowledge_pipeline_task set async_task_id=#{asyncTaskId},updatetime=#{now} " +
            "where id=#{id} and resource_version=#{version} and task_status in ('PENDING','RUNNING') " +
            "and (async_task_id is null or async_task_id=#{asyncTaskId})")
    int bindAsyncTask(@Param("id") Long id,@Param("version") long version,
                      @Param("asyncTaskId") Long asyncTaskId,@Param("now") LocalDateTime now);

    @Update("update space_knowledge_pipeline_task set task_status='RUNNING',started_time=coalesce(started_time,#{now})," +
            "finished_time=null,error_message=null,updatetime=#{now} where id=#{id} and resource_version=#{version} " +
            "and async_task_id=#{asyncTaskId} and task_status in ('PENDING','RUNNING')")
    int claimAsync(@Param("id") Long id,@Param("version") long version,
                   @Param("asyncTaskId") Long asyncTaskId,@Param("now") LocalDateTime now);

    @Update("update space_knowledge_pipeline_task set task_status=#{status},stage=#{stage},progress=100," +
            "total_count=#{total},success_count=#{success},failed_count=#{failed},error_message=#{error}," +
            "terminal_stage=#{stage},terminal_reason=#{reason},finished_time=#{now},updatetime=#{now} " +
            "where id=#{id} and resource_version=#{version} and async_task_id=#{asyncTaskId} and task_status='RUNNING'")
    int finishAsync(@Param("id") Long id,@Param("version") long version,@Param("asyncTaskId") Long asyncTaskId,
                    @Param("status") String status,@Param("stage") String stage,
                    @Param("total") int total,@Param("success") int success,@Param("failed") int failed,
                    @Param("error") String error,@Param("reason") String reason,@Param("now") LocalDateTime now);

    @Update("update space_knowledge_pipeline_task set task_status='CANCELED',stage='CANCELED',progress=100," +
            "error_message='Knowledge pipeline canceled',terminal_stage='CANCELED',terminal_reason='USER_CANCELED'," +
            "finished_time=#{now},updatetime=#{now} where id=#{id} and resource_version=#{version} " +
            "and async_task_id=#{asyncTaskId} and task_status in ('PENDING','RUNNING')")
    int cancelAsync(@Param("id") Long id,@Param("version") long version,
                    @Param("asyncTaskId") Long asyncTaskId,@Param("now") LocalDateTime now);

    @Update("update space_knowledge_pipeline_task set task_status='PENDING',stage='PENDING',progress=0," +
            "success_count=0,failed_count=0,error_message=null,terminal_stage=null,terminal_reason=null," +
            "started_time=null,finished_time=null,updatetime=#{now} where id=#{id} and resource_version=#{version} " +
            "and async_task_id=#{asyncTaskId} and task_status in ('FAILED','PARTIAL_SUCCESS')")
    int prepareAsyncRetry(@Param("id") Long id,@Param("version") long version,
                          @Param("asyncTaskId") Long asyncTaskId,@Param("now") LocalDateTime now);

    @Select("select id, space_id as spaceId, document_id as documentId, parent_task_id as parentTaskId, task_type as taskType, task_status as taskStatus, " +
            "stage, progress, total_count as totalCount, success_count as successCount, failed_count as failedCount, " +
            "error_message as errorMessage, force_rebuild as forceRebuild, terminal_stage as terminalStage, terminal_reason as terminalReason, " +
            "incremental_action as incrementalAction, incremental_detail as incrementalDetail, async_task_id as asyncTaskId, resource_version as resourceVersion, created_by as createdBy, started_time as startedTime, finished_time as finishedTime, createtime, updatetime " +
            "from space_knowledge_pipeline_task where space_id = #{spaceId} order by createtime desc limit #{limit}")
    List<SpaceKnowledgePipelineTask> listBySpaceId(@Param("spaceId") Long spaceId, @Param("limit") Integer limit);

    /**
     * 查询当前用户提交的知识流水线后台任务，可按 Space 过滤。
     */
    @Select("select id, space_id as spaceId, document_id as documentId, parent_task_id as parentTaskId, task_type as taskType, task_status as taskStatus, " +
            "stage, progress, total_count as totalCount, success_count as successCount, failed_count as failedCount, " +
            "error_message as errorMessage, force_rebuild as forceRebuild, terminal_stage as terminalStage, terminal_reason as terminalReason, " +
            "incremental_action as incrementalAction, incremental_detail as incrementalDetail, async_task_id as asyncTaskId, resource_version as resourceVersion, created_by as createdBy, started_time as startedTime, finished_time as finishedTime, createtime, updatetime " +
            "from space_knowledge_pipeline_task where created_by = #{userId} and (#{spaceId} is null or space_id = #{spaceId}) " +
            "order by createtime desc limit #{limit}")
    List<SpaceKnowledgePipelineTask> listByCreatedBy(@Param("userId") Long userId,
                                                      @Param("spaceId") Long spaceId,
                                                      @Param("limit") Integer limit);

    @Select("select id, space_id as spaceId, document_id as documentId, parent_task_id as parentTaskId, task_type as taskType, task_status as taskStatus, " +
            "stage, progress, total_count as totalCount, success_count as successCount, failed_count as failedCount, " +
            "error_message as errorMessage, force_rebuild as forceRebuild, terminal_stage as terminalStage, terminal_reason as terminalReason, " +
            "incremental_action as incrementalAction, incremental_detail as incrementalDetail, async_task_id as asyncTaskId, resource_version as resourceVersion, created_by as createdBy, started_time as startedTime, finished_time as finishedTime, createtime, updatetime " +
            "from space_knowledge_pipeline_task where id = #{id}")
    SpaceKnowledgePipelineTask getById(@Param("id") Long id);

    @Select("select id,space_id as spaceId,document_id as documentId,task_status as taskStatus," +
            "async_task_id as asyncTaskId,resource_version as resourceVersion from space_knowledge_pipeline_task " +
            "where id=#{id} for update")
    SpaceKnowledgePipelineTask getByIdForUpdate(@Param("id") Long id);

    @Select("select id,space_id as spaceId,document_id as documentId,parent_task_id as parentTaskId,task_type as taskType," +
            "task_status as taskStatus,async_task_id as asyncTaskId,resource_version as resourceVersion,created_by as createdBy " +
            "from space_knowledge_pipeline_task where parent_task_id=#{parentTaskId} and document_id=#{documentId} limit 1")
    SpaceKnowledgePipelineTask getByParentDocument(@Param("parentTaskId") Long parentTaskId,
                                                    @Param("documentId") Long documentId);

    @Select("select id, space_id as spaceId, document_id as documentId, task_type as taskType, task_status as taskStatus, " +
            "stage, progress, total_count as totalCount, success_count as successCount, failed_count as failedCount, " +
            "error_message as errorMessage, force_rebuild as forceRebuild, terminal_stage as terminalStage, terminal_reason as terminalReason, " +
            "incremental_action as incrementalAction, incremental_detail as incrementalDetail, async_task_id as asyncTaskId, resource_version as resourceVersion, created_by as createdBy, started_time as startedTime, finished_time as finishedTime, createtime, updatetime " +
            "from space_knowledge_pipeline_task where space_id = #{spaceId} and task_status in ('FAILED', 'PARTIAL_SUCCESS') order by createtime desc limit #{limit}")
    List<SpaceKnowledgePipelineTask> listFailedBySpaceId(@Param("spaceId") Long spaceId, @Param("limit") Integer limit);

    @Select("select id, space_id as spaceId, document_id as documentId, task_type as taskType, task_status as taskStatus, " +
            "stage, progress, total_count as totalCount, success_count as successCount, failed_count as failedCount, " +
            "error_message as errorMessage, force_rebuild as forceRebuild, terminal_stage as terminalStage, terminal_reason as terminalReason, " +
            "incremental_action as incrementalAction, incremental_detail as incrementalDetail, async_task_id as asyncTaskId, resource_version as resourceVersion, created_by as createdBy, started_time as startedTime, finished_time as finishedTime, createtime, updatetime " +
            "from space_knowledge_pipeline_task where space_id = #{spaceId} and document_id = #{documentId} " +
            "and task_type = 'PROFILE_DOCUMENT' and task_status in ('PENDING', 'RUNNING') order by createtime desc limit 1")
    SpaceKnowledgePipelineTask getActiveDocumentTask(@Param("spaceId") Long spaceId, @Param("documentId") Long documentId);

    @Select("select id, space_id as spaceId, document_id as documentId, task_type as taskType, task_status as taskStatus, " +
            "stage, progress, total_count as totalCount, success_count as successCount, failed_count as failedCount, " +
            "error_message as errorMessage, force_rebuild as forceRebuild, terminal_stage as terminalStage, terminal_reason as terminalReason, " +
            "incremental_action as incrementalAction, incremental_detail as incrementalDetail, async_task_id as asyncTaskId, resource_version as resourceVersion, created_by as createdBy, started_time as startedTime, finished_time as finishedTime, createtime, updatetime " +
            "from space_knowledge_pipeline_task where space_id = #{spaceId} and document_id is null " +
            "and task_type = 'PROFILE_SPACE' and task_status in ('PENDING', 'RUNNING') order by createtime desc limit 1")
    SpaceKnowledgePipelineTask getActiveSpaceTask(@Param("spaceId") Long spaceId);

    @Update("update space_knowledge_pipeline_task set task_status = 'RUNNING', started_time = #{startedTime}, " +
            "finished_time = null, updatetime = #{startedTime} where id = #{id} and task_status = 'PENDING'")
    int markRunningIfPending(@Param("id") Long id, @Param("startedTime") LocalDateTime startedTime);

    @Select("select id, space_id as spaceId, document_id as documentId, task_type as taskType, task_status as taskStatus, " +
            "stage, progress, total_count as totalCount, success_count as successCount, failed_count as failedCount, " +
            "error_message as errorMessage, force_rebuild as forceRebuild, terminal_stage as terminalStage, terminal_reason as terminalReason, " +
            "incremental_action as incrementalAction, incremental_detail as incrementalDetail, async_task_id as asyncTaskId, resource_version as resourceVersion, created_by as createdBy, started_time as startedTime, finished_time as finishedTime, createtime, updatetime " +
            "from space_knowledge_pipeline_task where task_status in ('PENDING', 'RUNNING') and updatetime <= #{cutoff} " +
            "order by updatetime asc limit #{limit}")
    List<SpaceKnowledgePipelineTask> listStaleActive(@Param("cutoff") LocalDateTime cutoff,
                                                     @Param("limit") Integer limit);

    @Update("update space_knowledge_pipeline_task set task_status = 'FAILED', stage = 'FAILED', progress = 100, " +
            "failed_count = greatest(failed_count, 1), error_message = #{errorMessage}, terminal_stage = 'FAILED', " +
            "terminal_reason = 'TASK_TIMEOUT', finished_time = #{finishedTime}, updatetime = #{finishedTime} " +
            "where id = #{id} and task_status in ('PENDING', 'RUNNING')")
    int failActive(@Param("id") Long id,
                   @Param("errorMessage") String errorMessage,
                   @Param("finishedTime") LocalDateTime finishedTime);
}
