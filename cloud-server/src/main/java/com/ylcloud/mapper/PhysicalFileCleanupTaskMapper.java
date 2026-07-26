package com.ylcloud.mapper;

import com.ylcloud.entity.PhysicalFileCleanupTask;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface PhysicalFileCleanupTaskMapper {
    @Insert("insert into physical_file_cleanup_task(file_uuid, task_status, retry_count, error_message, createtime, updatetime) " +
            "values(#{fileUuid}, 'PENDING', 0, null, #{now}, #{now}) " +
            "on duplicate key update task_status = if(task_status = 'SUCCESS', task_status, 'PENDING'), updatetime = values(updatetime)")
    int enqueue(@Param("fileUuid") String fileUuid, @Param("now") LocalDateTime now);

    String COLUMNS = "id,file_uuid as fileUuid,task_status as taskStatus,retry_count as retryCount," +
            "error_message as errorMessage,async_task_id as asyncTaskId,resource_version as resourceVersion,createtime,updatetime";

    @Select("select " + COLUMNS + " from physical_file_cleanup_task where file_uuid = #{fileUuid}")
    PhysicalFileCleanupTask getByFileUuid(@Param("fileUuid") String fileUuid);

    @Select("select " + COLUMNS + " from physical_file_cleanup_task where id=#{id}")
    PhysicalFileCleanupTask getById(@Param("id") Long id);

    @Select("select " + COLUMNS + " from physical_file_cleanup_task " +
            "where task_status in ('PENDING','FAILED') order by updatetime asc limit #{limit}")
    List<PhysicalFileCleanupTask> listPending(@Param("limit") Integer limit);

    @Update("update physical_file_cleanup_task set task_status = 'RUNNING', updatetime = #{now} " +
            "where id = #{id} and task_status in ('PENDING','FAILED')")
    int markRunning(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Update("update physical_file_cleanup_task set task_status = 'SUCCESS', error_message = null, updatetime = #{now} where id = #{id}")
    int markSuccess(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Update("update physical_file_cleanup_task set task_status = 'FAILED', retry_count = retry_count + 1, " +
            "error_message = #{errorMessage}, updatetime = #{now} where id = #{id}")
    int markFailed(@Param("id") Long id, @Param("errorMessage") String errorMessage, @Param("now") LocalDateTime now);

    @Update("update physical_file_cleanup_task set task_status = 'FAILED', " +
            "error_message = 'Application stopped while cleanup was running', updatetime = #{now} where task_status = 'RUNNING'")
    int recoverInterrupted(@Param("now") LocalDateTime now);

    @Update("update physical_file_cleanup_task set async_task_id=#{asyncTaskId},updatetime=#{now} " +
            "where id=#{id} and resource_version=#{version} and task_status in ('PENDING','FAILED') " +
            "and (async_task_id is null or async_task_id=#{asyncTaskId})")
    int bindAsyncTask(@Param("id") Long id,@Param("version") Long version,
                      @Param("asyncTaskId") Long asyncTaskId,@Param("now") LocalDateTime now);

    @Update("update physical_file_cleanup_task set task_status='SUCCESS',error_message=null,updatetime=#{now} " +
            "where id=#{id} and async_task_id=#{asyncTaskId} and task_status='RUNNING'")
    int markAsyncSuccess(@Param("id") Long id,@Param("asyncTaskId") Long asyncTaskId,@Param("now") LocalDateTime now);

    @Update("update physical_file_cleanup_task set task_status='FAILED',retry_count=retry_count+1," +
            "error_message=#{error},updatetime=#{now} where id=#{id} and async_task_id=#{asyncTaskId} and task_status='RUNNING'")
    int markAsyncFailed(@Param("id") Long id,@Param("asyncTaskId") Long asyncTaskId,
                        @Param("error") String error,@Param("now") LocalDateTime now);
}
