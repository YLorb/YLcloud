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

    String TASK_COLUMNS = "id, space_id as spaceId, space_file_id as spaceFileId, document_id as documentId, task_type as taskType, " +
            "task_status as taskStatus, total_count as totalCount, success_count as successCount, failed_count as failedCount, " +
            "error_message as errorMessage, created_by as createdBy, started_time as startedTime, finished_time as finishedTime, createtime, updatetime";

    /**
     * 新增 insert 相关逻辑。
     * @return 影响行数
     */
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    @Insert("insert into space_rag_task(space_id, space_file_id, document_id, task_type, task_status, total_count, success_count, failed_count, error_message, created_by, started_time, finished_time, createtime, updatetime) " +
            "values(#{spaceId}, #{spaceFileId}, #{documentId}, #{taskType}, #{taskStatus}, #{totalCount}, #{successCount}, #{failedCount}, #{errorMessage}, #{createdBy}, #{startedTime}, #{finishedTime}, #{createtime}, #{updatetime})")
    int insert(SpaceRagTask task);

    /**
     * 查询 getById 相关逻辑。
     * @return 处理结果
     */
    @Select("select " + TASK_COLUMNS + " from space_rag_task where id = #{id}")
    SpaceRagTask getById(@Param("id") Long id);

    /**
     * 查询 listBySpaceId 相关逻辑。
     * @return 列表结果
     */
    @Select("select " + TASK_COLUMNS + " from space_rag_task where space_id = #{spaceId} order by createtime desc")
    List<SpaceRagTask> listBySpaceId(@Param("spaceId") Long spaceId);

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
}
