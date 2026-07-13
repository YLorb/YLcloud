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

    @Select("select id, file_uuid as fileUuid, task_status as taskStatus, retry_count as retryCount, " +
            "error_message as errorMessage, createtime, updatetime from physical_file_cleanup_task where file_uuid = #{fileUuid}")
    PhysicalFileCleanupTask getByFileUuid(@Param("fileUuid") String fileUuid);

    @Select("select id, file_uuid as fileUuid, task_status as taskStatus, retry_count as retryCount, " +
            "error_message as errorMessage, createtime, updatetime from physical_file_cleanup_task " +
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
}
