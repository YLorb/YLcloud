package com.ylcloud.mapper;

import com.ylcloud.entity.UserMemoryExtractionTask;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface UserMemoryExtractionTaskMapper {
    String COLUMNS = "id, assistant_message_id as assistantMessageId, user_id as userId, session_id as sessionId, " +
            "source_message_id as sourceMessageId, task_status as taskStatus, retry_count as retryCount, next_retry_time as nextRetryTime, " +
            "error_message as errorMessage, createtime, updatetime";

    @Insert("insert ignore into user_memory_extraction_task(assistant_message_id,user_id,session_id,source_message_id,task_status,retry_count,createtime,updatetime) " +
            "values(#{assistantMessageId},#{userId},#{sessionId},#{sourceMessageId},'PENDING',0,#{now},#{now})")
    int enqueue(@Param("assistantMessageId") Long assistantMessageId, @Param("userId") Long userId,
                @Param("sessionId") Long sessionId, @Param("sourceMessageId") Long sourceMessageId, @Param("now") LocalDateTime now);

    @Select("select " + COLUMNS + " from user_memory_extraction_task where id=#{id}")
    UserMemoryExtractionTask getById(@Param("id") Long id);

    @Select("select " + COLUMNS + " from user_memory_extraction_task where assistant_message_id=#{assistantMessageId}")
    UserMemoryExtractionTask getByAssistantMessageId(@Param("assistantMessageId") Long assistantMessageId);

    @Select("select " + COLUMNS + " from user_memory_extraction_task where task_status in ('PENDING','FAILED_RETRYABLE') " +
            "and retry_count < #{maxRetries} and (next_retry_time is null or next_retry_time <= now()) order by id limit #{limit}")
    List<UserMemoryExtractionTask> listPending(@Param("limit") int limit, @Param("maxRetries") int maxRetries);

    @Update("update user_memory_extraction_task set task_status='RUNNING',error_message=null,updatetime=#{now} " +
            "where id=#{id} and task_status in ('PENDING','FAILED_RETRYABLE')")
    int claim(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Update("update user_memory_extraction_task set task_status='SUCCESS',error_message=null,next_retry_time=null,updatetime=#{now} where id=#{id} and task_status='RUNNING'")
    int succeed(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Update("update user_memory_extraction_task set task_status='FAILED_RETRYABLE',retry_count=retry_count+1,error_message=#{error}," +
            "next_retry_time=#{nextRetry},updatetime=#{now} where id=#{id} and task_status='RUNNING'")
    int fail(@Param("id") Long id, @Param("error") String error, @Param("nextRetry") LocalDateTime nextRetry, @Param("now") LocalDateTime now);

    @Update("update user_memory_extraction_task set task_status='FAILED_RETRYABLE',error_message='Application stopped during extraction',updatetime=#{now} " +
            "where task_status='RUNNING' and updatetime < #{cutoff}")
    int recoverInterrupted(@Param("cutoff") LocalDateTime cutoff, @Param("now") LocalDateTime now);
}
