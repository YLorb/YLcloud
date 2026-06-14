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

    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    @Insert("insert into space_rag_task(space_id, space_file_id, document_id, task_type, task_status, error_message, created_by, started_time, finished_time, createtime, updatetime) " +
            "values(#{spaceId}, #{spaceFileId}, #{documentId}, #{taskType}, #{taskStatus}, #{errorMessage}, #{createdBy}, #{startedTime}, #{finishedTime}, #{createtime}, #{updatetime})")
    int insert(SpaceRagTask task);

    @Select("select id, space_id as spaceId, space_file_id as spaceFileId, document_id as documentId, task_type as taskType, " +
            "task_status as taskStatus, error_message as errorMessage, created_by as createdBy, started_time as startedTime, " +
            "finished_time as finishedTime, createtime, updatetime from space_rag_task where space_id = #{spaceId} order by createtime desc")
    List<SpaceRagTask> listBySpaceId(@Param("spaceId") Long spaceId);

    @Select("select id, space_id as spaceId, space_file_id as spaceFileId, document_id as documentId, task_type as taskType, " +
            "task_status as taskStatus, error_message as errorMessage, created_by as createdBy, started_time as startedTime, " +
            "finished_time as finishedTime, createtime, updatetime from space_rag_task " +
            "where space_id = #{spaceId} and task_type in ('INDEX_FILE','REBUILD_FILE','REBUILD_SPACE') " +
            "and task_status in ('PENDING','RUNNING') order by createtime desc limit 1")
    SpaceRagTask findRunningIndexTaskBySpace(@Param("spaceId") Long spaceId);

    @Select("select id, space_id as spaceId, space_file_id as spaceFileId, document_id as documentId, task_type as taskType, " +
            "task_status as taskStatus, error_message as errorMessage, created_by as createdBy, started_time as startedTime, " +
            "finished_time as finishedTime, createtime, updatetime from space_rag_task " +
            "where space_id = #{spaceId} and space_file_id = #{spaceFileId} and task_type in ('INDEX_FILE','REBUILD_FILE') " +
            "and task_status in ('PENDING','RUNNING') order by createtime desc limit 1")
    SpaceRagTask findRunningFileTask(@Param("spaceId") Long spaceId,
                                     @Param("spaceFileId") Long spaceFileId);

    @Select("select id, space_id as spaceId, space_file_id as spaceFileId, document_id as documentId, task_type as taskType, " +
            "task_status as taskStatus, error_message as errorMessage, created_by as createdBy, started_time as startedTime, " +
            "finished_time as finishedTime, createtime, updatetime from space_rag_task " +
            "where space_id = #{spaceId} and task_type = #{taskType} and task_status in ('PENDING','RUNNING') " +
            "order by createtime desc limit 1")
    SpaceRagTask findRunningSpaceTask(@Param("spaceId") Long spaceId,
                                      @Param("taskType") String taskType);

    @Update("update space_rag_task set task_status = #{taskStatus}, error_message = #{errorMessage}, " +
            "started_time = #{startedTime}, finished_time = #{finishedTime}, updatetime = #{updateTime} where id = #{id}")
    int updateResult(@Param("id") Long id,
                     @Param("taskStatus") String taskStatus,
                     @Param("errorMessage") String errorMessage,
                     @Param("startedTime") LocalDateTime startedTime,
                     @Param("finishedTime") LocalDateTime finishedTime,
                     @Param("updateTime") LocalDateTime updateTime);
}
