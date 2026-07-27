package com.ylcloud.mapper;

import com.ylcloud.entity.SpaceRagTask;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 空间 RAG 索引任务 Mapper。
 */
@Mapper
public interface SpaceRagTaskMapper {

    String TASK_COLUMNS = "id, space_id as spaceId, space_file_id as spaceFileId, document_id as documentId, parent_task_id as parentTaskId, task_type as taskType, " +
            "task_status as taskStatus, total_count as totalCount, success_count as successCount, failed_count as failedCount, " +
            "error_message as errorMessage, async_task_id as asyncTaskId, resource_version as resourceVersion, clear_vectors as clearVectors, fanout_cursor as fanoutCursor, " +
            "created_by as createdBy, started_time as startedTime, finished_time as finishedTime, createtime, updatetime";

    /**
     * 新增 insert 相关逻辑。
     * @return 影响行数
     */
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    @Insert("insert ignore into space_rag_task(space_id, space_file_id, document_id, parent_task_id, task_type, task_status, total_count, success_count, failed_count, error_message, async_task_id, resource_version, clear_vectors, fanout_cursor, created_by, started_time, finished_time, createtime, updatetime) " +
            "values(#{spaceId}, #{spaceFileId}, #{documentId}, #{parentTaskId}, #{taskType}, #{taskStatus}, #{totalCount}, #{successCount}, #{failedCount}, #{errorMessage}, #{asyncTaskId}, coalesce(#{resourceVersion},1), #{clearVectors}, coalesce(#{fanoutCursor},0), #{createdBy}, #{startedTime}, #{finishedTime}, #{createtime}, #{updatetime})")
    int insert(SpaceRagTask task);

    /**
     * 查询 getById 相关逻辑。
     * @return 处理结果
     */
    @Select("select " + TASK_COLUMNS + " from space_rag_task where id = #{id}")
    SpaceRagTask getById(@Param("id") Long id);

    @Select("select " + TASK_COLUMNS + " from space_rag_task where id=#{id} for update")
    SpaceRagTask getByIdForUpdate(@Param("id") Long id);

    @Select("select " + TASK_COLUMNS + " from space_rag_task where parent_task_id=#{parentId} and space_file_id=#{spaceFileId} and task_type=#{taskType} limit 1")
    SpaceRagTask getByParentFileType(@Param("parentId") Long parentId,@Param("spaceFileId") Long spaceFileId,
                                     @Param("taskType") String taskType);

    @Select("select " + TASK_COLUMNS + " from space_rag_task where parent_task_id=#{parentId} order by id")
    List<SpaceRagTask> listByParent(@Param("parentId") Long parentId);

    @Update("update space_rag_task set async_task_id=#{asyncTaskId},updatetime=#{now} where id=#{id} and resource_version=#{version} " +
            "and task_status in ('PENDING','RUNNING') and (async_task_id is null or async_task_id=#{asyncTaskId})")
    int bindAsyncTask(@Param("id") Long id,@Param("version") long version,@Param("asyncTaskId") Long asyncTaskId,
                      @Param("now") LocalDateTime now);

    @Update("update space_rag_task set task_status='RUNNING',error_message=null,started_time=coalesce(started_time,#{now})," +
            "finished_time=null,updatetime=#{now} where id=#{id} and resource_version=#{version} and async_task_id=#{asyncTaskId} " +
            "and task_status in ('PENDING','RUNNING')")
    int claimAsync(@Param("id") Long id,@Param("version") long version,@Param("asyncTaskId") Long asyncTaskId,
                   @Param("now") LocalDateTime now);

    @Update("update space_rag_task set task_status=#{status},total_count=#{total},success_count=#{success},failed_count=#{failed}," +
            "error_message=#{error},finished_time=#{now},updatetime=#{now} where id=#{id} and resource_version=#{version} " +
            "and async_task_id=#{asyncTaskId} and task_status='RUNNING'")
    int finishAsync(@Param("id") Long id,@Param("version") long version,@Param("asyncTaskId") Long asyncTaskId,
                    @Param("status") String status,@Param("total") int total,@Param("success") int success,
                    @Param("failed") int failed,@Param("error") String error,@Param("now") LocalDateTime now);

    @Update("update space_rag_task set task_status='CANCELED',error_message='RAG task canceled',finished_time=#{now},updatetime=#{now} " +
            "where id=#{id} and resource_version=#{version} and async_task_id=#{asyncTaskId} and task_status in ('PENDING','RUNNING')")
    int cancelAsync(@Param("id") Long id,@Param("version") long version,@Param("asyncTaskId") Long asyncTaskId,
                    @Param("now") LocalDateTime now);

    @Update("update space_rag_task set task_status='SKIPPED',error_message=#{reason},finished_time=#{now},updatetime=#{now} " +
            "where id=#{id} and resource_version=#{version} and async_task_id=#{asyncTaskId} and task_status in ('PENDING','RUNNING')")
    int skipAsync(@Param("id") Long id,@Param("version") long version,@Param("asyncTaskId") Long asyncTaskId,
                  @Param("reason") String reason,@Param("now") LocalDateTime now);

    @Update("update space_rag_task set task_status='SKIPPED',error_message=#{reason},finished_time=#{now},updatetime=#{now} " +
            "where space_id=#{spaceId} and space_file_id=#{spaceFileId} and task_type in ('INDEX_FILE','REBUILD_FILE') " +
            "and task_status in ('PENDING','RUNNING')")
    int skipActiveFileIndexTasks(@Param("spaceId") Long spaceId,@Param("spaceFileId") Long spaceFileId,
                                 @Param("reason") String reason,@Param("now") LocalDateTime now);

    @Update("update space_rag_task set task_status='PENDING',success_count=0,failed_count=0,error_message=null," +
            "started_time=null,finished_time=null,updatetime=#{now} where id=#{id} and resource_version=#{version} " +
            "and async_task_id=#{asyncTaskId} and task_status='FAILED'")
    int prepareAsyncRetry(@Param("id") Long id,@Param("version") long version,@Param("asyncTaskId") Long asyncTaskId,
                          @Param("now") LocalDateTime now);

    @Update("update space_rag_task set fanout_cursor=#{cursor},total_count=#{total},updatetime=#{now} " +
            "where id=#{id} and resource_version=#{version} and async_task_id=#{asyncTaskId} and task_status='RUNNING'")
    int advanceFanout(@Param("id") Long id,@Param("version") long version,@Param("asyncTaskId") Long asyncTaskId,
                      @Param("cursor") long cursor,@Param("total") int total,@Param("now") LocalDateTime now);

    /**
     * 查询 listBySpaceId 相关逻辑。
     * @return 列表结果
     */
    @Select("select " + TASK_COLUMNS + " from space_rag_task where space_id = #{spaceId} order by createtime desc")
    List<SpaceRagTask> listBySpaceId(@Param("spaceId") Long spaceId);

    /**
     * 查询当前用户提交的真实后台 RAG 任务，可按 Space 过滤。
     */
    @Select("select " + TASK_COLUMNS + " from space_rag_task " +
            "where created_by = #{userId} and (#{spaceId} is null or space_id = #{spaceId}) " +
            "order by createtime desc limit #{limit}")
    List<SpaceRagTask> listByCreatedBy(@Param("userId") Long userId,
                                       @Param("spaceId") Long spaceId,
                                       @Param("limit") Integer limit);

    /**
     * 查找 findRunningIndexTaskBySpace 相关逻辑。
     * @return 处理结果
     */
    @Select("select " + TASK_COLUMNS + " from space_rag_task " +
            "where space_id = #{spaceId} and task_type in ('INDEX_FILE','REBUILD_FILE','REBUILD_SPACE') " +
            "and task_status in ('PENDING','RUNNING') order by createtime desc limit 1")
    SpaceRagTask findRunningIndexTaskBySpace(@Param("spaceId") Long spaceId);

    /**
     * 查找 findRunningFileTask 相关逻辑。
     * @return 处理结果
     */
    @Select("select " + TASK_COLUMNS + " from space_rag_task " +
            "where space_id = #{spaceId} and space_file_id = #{spaceFileId} and task_type in ('INDEX_FILE','REBUILD_FILE') " +
            "and task_status in ('PENDING','RUNNING') order by createtime desc limit 1")
    SpaceRagTask findRunningFileTask(@Param("spaceId") Long spaceId,
                                     @Param("spaceFileId") Long spaceFileId);

    /**
     * 查找 findRunningSpaceTask 相关逻辑。
     * @return 处理结果
     */
    @Select("select " + TASK_COLUMNS + " from space_rag_task " +
            "where space_id = #{spaceId} and task_type = #{taskType} and task_status in ('PENDING','RUNNING') " +
            "order by createtime desc limit 1")
    SpaceRagTask findRunningSpaceTask(@Param("spaceId") Long spaceId,
                                      @Param("taskType") String taskType);

    @Select("select " + TASK_COLUMNS + " from space_rag_task where space_id = #{spaceId} " +
            "and (#{spaceFileId} is null or space_file_id = #{spaceFileId}) and task_type = #{taskType} " +
            "and task_status in ('PENDING','RUNNING') order by createtime desc limit 1")
    SpaceRagTask findActiveTask(@Param("spaceId") Long spaceId,
                                @Param("spaceFileId") Long spaceFileId,
                                @Param("taskType") String taskType);

    /**
     * 查询 listFailedBySpace 相关逻辑。
     * @return 列表结果
     */
    @Select("select " + TASK_COLUMNS + " from space_rag_task " +
            "where space_id = #{spaceId} and task_status = 'FAILED' order by createtime desc limit #{limit}")
    List<SpaceRagTask> listFailedBySpace(@Param("spaceId") Long spaceId, @Param("limit") Integer limit);

    /**
     * 更新 updateResult 相关逻辑。
     * @return 影响行数
     */
    @Update("update space_rag_task set task_status = #{taskStatus}, error_message = #{errorMessage}, " +
            "started_time = #{startedTime}, finished_time = #{finishedTime}, updatetime = #{updateTime} where id = #{id}")
    int updateResult(@Param("id") Long id,
                     @Param("taskStatus") String taskStatus,
                     @Param("errorMessage") String errorMessage,
                     @Param("startedTime") LocalDateTime startedTime,
                     @Param("finishedTime") LocalDateTime finishedTime,
                     @Param("updateTime") LocalDateTime updateTime);

    /**
     * 更新 updateProgress 相关逻辑。
     * @return 影响行数
     */
    @Update("update space_rag_task set total_count = #{totalCount}, success_count = #{successCount}, failed_count = #{failedCount}, updatetime = #{updateTime} where id = #{id}")
    int updateProgress(@Param("id") Long id,
                       @Param("totalCount") Integer totalCount,
                       @Param("successCount") Integer successCount,
                       @Param("failedCount") Integer failedCount,
                       @Param("updateTime") LocalDateTime updateTime);

    /**
     * 查询超过执行时限的索引任务。
     * @return 列表结果
     */
    @Select("<script>" +
            "select " + TASK_COLUMNS + " from space_rag_task " +
            "where task_type in ('INDEX_FILE','REBUILD_FILE','REBUILD_SPACE','DELETE_FILE','DELETE_SPACE') " +
            "and task_status in ('PENDING','RUNNING') " +
            "and coalesce(started_time, updatetime, createtime) <![CDATA[ <= ]]> #{cutoff} " +
            "<if test='spaceId != null'>and space_id = #{spaceId} </if>" +
            "order by createtime asc" +
            "</script>")
    List<SpaceRagTask> listStaleIndexTasks(@Param("spaceId") Long spaceId,
                                           @Param("cutoff") LocalDateTime cutoff);

    /**
     * 仅当任务仍在等待或运行时标记为失败。
     * @return 影响行数
     */
    @Update("update space_rag_task set task_status = 'FAILED', error_message = #{errorMessage}, " +
            "finished_time = #{finishedTime}, updatetime = #{updateTime} " +
            "where id = #{id} and task_status in ('PENDING','RUNNING')")
    int failActiveTask(@Param("id") Long id,
                       @Param("errorMessage") String errorMessage,
                       @Param("finishedTime") LocalDateTime finishedTime,
                       @Param("updateTime") LocalDateTime updateTime);

    @Update("update space_rag_task set task_status = 'RUNNING', error_message = null, started_time = #{startedTime}, " +
            "finished_time = null, updatetime = #{startedTime} where id = #{id} and task_status = 'PENDING'")
    int markRunningIfPending(@Param("id") Long id, @Param("startedTime") LocalDateTime startedTime);

    @Update("update space_rag_task set task_status = #{taskStatus}, error_message = #{errorMessage}, " +
            "finished_time = #{finishedTime}, updatetime = #{finishedTime} " +
            "where id = #{id} and task_status = 'RUNNING'")
    int finishIfRunning(@Param("id") Long id,
                        @Param("taskStatus") String taskStatus,
                        @Param("errorMessage") String errorMessage,
                        @Param("finishedTime") LocalDateTime finishedTime);

}
