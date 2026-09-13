package com.ylcloud.mapper;

import com.ylcloud.entity.AccountDeletionJob;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AccountDeletionJobMapper {

    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    @Insert("insert into account_deletion_job(user_id, job_key, status, current_step, async_task_id, created_at, updated_at) " +
            "values(#{userId}, #{jobKey}, #{status}, #{currentStep}, #{asyncTaskId}, #{createdAt}, #{updatedAt})")
    int insert(AccountDeletionJob job);

    @Select("select id, user_id as userId, job_key as jobKey, status, current_step as currentStep, " +
            "step_result_json as stepResultJson, async_task_id as asyncTaskId, started_at as startedAt, " +
            "finished_at as finishedAt, last_error as lastError, retry_count as retryCount, " +
            "created_at as createdAt, updated_at as updatedAt " +
            "from account_deletion_job where id = #{id}")
    AccountDeletionJob getById(@Param("id") Long id);

    @Select("select id, user_id as userId, job_key as jobKey, status, current_step as currentStep, " +
            "step_result_json as stepResultJson, async_task_id as asyncTaskId, started_at as startedAt, " +
            "finished_at as finishedAt, last_error as lastError, retry_count as retryCount, " +
            "created_at as createdAt, updated_at as updatedAt " +
            "from account_deletion_job where user_id = #{userId}")
    AccountDeletionJob getByUserId(@Param("userId") Long userId);

    @Select("select id from account_deletion_job where user_id = #{userId} for update")
    Long lockByUserId(@Param("userId") Long userId);

    @Update("update account_deletion_job set status = #{status}, current_step = #{currentStep}, " +
            "step_result_json = #{stepResultJson}, async_task_id = #{asyncTaskId}, " +
            "started_at = #{startedAt}, finished_at = #{finishedAt}, " +
            "last_error = #{lastError}, retry_count = #{retryCount}, updated_at = #{updatedAt} " +
            "where id = #{id}")
    int update(AccountDeletionJob job);

    @Update("update account_deletion_job set status = 'RUNNING', current_step = #{step}, " +
            "started_at = coalesce(started_at, #{now}), updated_at = #{now} where id = #{id} and status in ('PENDING','FAILED')")
    int markRunning(@Param("id") Long id, @Param("step") String step, @Param("now") java.time.LocalDateTime now);

    @Update("update account_deletion_job set status = 'COMPLETED', current_step = 'DONE', " +
            "finished_at = #{now}, updated_at = #{now} where id = #{id}")
    int markCompleted(@Param("id") Long id, @Param("now") java.time.LocalDateTime now);

    @Update("update account_deletion_job set status = 'FAILED', last_error = #{error}, " +
            "retry_count = retry_count + 1, updated_at = #{now} where id = #{id}")
    int markFailed(@Param("id") Long id, @Param("error") String error, @Param("now") java.time.LocalDateTime now);
}
