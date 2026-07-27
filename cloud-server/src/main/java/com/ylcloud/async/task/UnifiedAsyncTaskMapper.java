package com.ylcloud.async.task;

import com.ylcloud.entity.MqOutboxRecord;
import com.ylcloud.entity.UnifiedAsyncTask;
import com.ylcloud.entity.UnifiedTaskAttempt;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface UnifiedAsyncTaskMapper {
    @Options(useGeneratedKeys = true,keyProperty = "id")
    @Insert("insert into async_task(task_key,task_domain,task_type,payload_json,created_by,space_id,parent_task_id,resource_key,resource_version,status,attempt_version,next_trigger_type,max_attempts,created_at,updated_at) " +
            "values(#{taskKey},#{taskDomain},#{taskType},#{payloadJson},#{createdBy},#{spaceId},#{parentTaskId},#{resourceKey},#{resourceVersion},#{status},#{attemptVersion},#{nextTriggerType},#{maxAttempts},#{createdAt},#{updatedAt})")
    int insertTask(UnifiedAsyncTask task);

    @Select("select * from async_task where task_key=#{taskKey}")
    UnifiedAsyncTask getByTaskKey(@Param("taskKey") String taskKey);

    @Select("select * from async_task where id=#{taskId}")
    UnifiedAsyncTask getById(@Param("taskId") Long taskId);

    @Select("select * from async_task where id=#{taskId} for update")
    UnifiedAsyncTask getByIdForUpdate(@Param("taskId") Long taskId);

    @Select("select coalesce(max(resource_version),0) from async_task where resource_key=#{resourceKey}")
    long latestResourceVersion(@Param("resourceKey") String resourceKey);

    @Insert("insert into mq_outbox(message_id,task_id,expected_attempt_version,exchange_name,routing_key,payload_json,headers_json,status,publish_attempts,next_publish_at,created_at,updated_at) " +
            "values(#{messageId},#{taskId},#{expectedVersion},#{exchangeName},#{routingKey},#{payloadJson},'{}','PENDING',0,#{nextPublishAt},#{now},#{now})")
    int insertOutbox(@Param("messageId") String messageId,
                     @Param("taskId") Long taskId,
                     @Param("expectedVersion") Integer expectedVersion,
                     @Param("exchangeName") String exchangeName,
                     @Param("routingKey") String routingKey,
                     @Param("payloadJson") String payloadJson,
                     @Param("nextPublishAt") LocalDateTime nextPublishAt,
                     @Param("now") LocalDateTime now);

    @Select("select id from mq_outbox where (status='PENDING' or (status='PUBLISHING' and lease_until < #{now})) and next_publish_at <= #{now} order by id limit #{limit}")
    List<Long> listPublishableIds(@Param("now") LocalDateTime now,@Param("limit") int limit);

    @Update("update mq_outbox set status='PUBLISHING',lease_token=#{token},lease_owner=#{owner},lease_until=#{until},publish_attempts=publish_attempts+1,updated_at=#{now} " +
            "where id=#{id} and (status='PENDING' or (status='PUBLISHING' and lease_until < #{now}))")
    int claimOutbox(@Param("id") Long id,@Param("token") String token,@Param("owner") String owner,
                    @Param("until") LocalDateTime until,@Param("now") LocalDateTime now);

    @Select("select * from mq_outbox where id=#{id} and status='PUBLISHING' and lease_token=#{token}")
    MqOutboxRecord getClaimedOutbox(@Param("id") Long id,@Param("token") String token);

    @Update("update mq_outbox set status='SENT',confirmed_at=#{now},lease_token=null,lease_owner=null,lease_until=null,last_error=null,updated_at=#{now} " +
            "where id=#{id} and status='PUBLISHING' and lease_token=#{token}")
    int markOutboxSent(@Param("id") Long id,@Param("token") String token,@Param("now") LocalDateTime now);

    @Update("update async_task set status='PENDING',updated_at=#{now},row_version=row_version+1 " +
            "where id=#{taskId} and status='PENDING_PUBLISH' and attempt_version=0 and cancel_requested_at is null")
    int prepareInitialPublish(@Param("taskId") Long taskId,@Param("now") LocalDateTime now);

    @Update("update async_task set status='PENDING_PUBLISH',updated_at=#{now},row_version=row_version+1 " +
            "where id=#{taskId} and status='PENDING' and attempt_version=0 and cancel_requested_at is null")
    int revertInitialPublish(@Param("taskId") Long taskId,@Param("now") LocalDateTime now);

    @Update("update mq_outbox set status='PENDING',next_publish_at=#{nextAt},lease_token=null,lease_owner=null,lease_until=null,last_error=#{error},updated_at=#{now} " +
            "where id=#{id} and status='PUBLISHING' and lease_token=#{token}")
    int releaseOutbox(@Param("id") Long id,@Param("token") String token,@Param("nextAt") LocalDateTime nextAt,
                      @Param("error") String error,@Param("now") LocalDateTime now);

    @Insert("insert ignore into mq_inbox(message_id,task_id,expected_attempt_version,consumer_name,status,received_at) " +
            "values(#{messageId},#{taskId},#{expectedVersion},#{consumer},'RECEIVED',#{now})")
    int insertInbox(@Param("messageId") String messageId,@Param("taskId") Long taskId,
                    @Param("expectedVersion") Integer expectedVersion,@Param("consumer") String consumer,
                    @Param("now") LocalDateTime now);

    @Update("update mq_inbox set status=#{status},processed_at=#{now},result_summary=#{summary} " +
            "where message_id=#{messageId} and status='RECEIVED'")
    int finishInbox(@Param("messageId") String messageId,@Param("status") String status,
                    @Param("summary") String summary,@Param("now") LocalDateTime now);

    @Update("update async_task set status='RUNNING',attempt_version=attempt_version+1,lease_token=#{leaseToken},lease_owner=#{workerId},lease_until=#{leaseUntil},last_heartbeat_at=#{now},started_at=coalesce(started_at,#{now}),updated_at=#{now},row_version=row_version+1 " +
            "where id=#{taskId} and attempt_version=#{expectedVersion} and cancel_requested_at is null and " +
            "(status='PENDING' or (status='RETRY_WAIT' and next_retry_at <= #{now}))")
    int claimTask(@Param("taskId") Long taskId,@Param("expectedVersion") Integer expectedVersion,
                  @Param("leaseToken") String leaseToken,@Param("workerId") String workerId,
                  @Param("leaseUntil") LocalDateTime leaseUntil,@Param("now") LocalDateTime now);

    @Insert("insert into async_task_attempt(task_id,attempt_version,trigger_type,worker_id,lease_token,status,started_at,last_heartbeat_at,operator_id,operator_remark) " +
            "values(#{taskId},#{attemptVersion},#{triggerType},#{workerId},#{leaseToken},'RUNNING',#{now},#{now},#{operatorId},#{operatorRemark})")
    int insertAttempt(@Param("taskId") Long taskId,@Param("attemptVersion") Integer attemptVersion,
                      @Param("triggerType") String triggerType,@Param("workerId") String workerId,
                      @Param("leaseToken") String leaseToken,@Param("operatorId") Long operatorId,
                      @Param("operatorRemark") String operatorRemark,@Param("now") LocalDateTime now);

    @Update("update async_task set lease_until=#{leaseUntil},last_heartbeat_at=#{now},updated_at=#{now} " +
            "where id=#{taskId} and status='RUNNING' and attempt_version=#{attemptVersion} and lease_token=#{leaseToken} and cancel_requested_at is null")
    int heartbeat(@Param("taskId") Long taskId,@Param("attemptVersion") Integer attemptVersion,
                  @Param("leaseToken") String leaseToken,@Param("leaseUntil") LocalDateTime leaseUntil,
                  @Param("now") LocalDateTime now);

    @Update("update async_task_attempt set last_heartbeat_at=#{now} where task_id=#{taskId} and attempt_version=#{attemptVersion} and status='RUNNING' and lease_token=#{leaseToken}")
    int heartbeatAttempt(@Param("taskId") Long taskId,@Param("attemptVersion") Integer attemptVersion,
                         @Param("leaseToken") String leaseToken,@Param("now") LocalDateTime now);

    @Update("update async_task set status='SUCCESS',result_json=#{resultJson},lease_token=null,lease_owner=null,lease_until=null,last_heartbeat_at=#{now},finished_at=#{now},updated_at=#{now},expire_at=#{expireAt},row_version=row_version+1 " +
            "where id=#{taskId} and status='RUNNING' and attempt_version=#{attemptVersion} and lease_token=#{leaseToken} and cancel_requested_at is null")
    int markSuccess(@Param("taskId") Long taskId,@Param("attemptVersion") Integer attemptVersion,
                    @Param("leaseToken") String leaseToken,@Param("resultJson") String resultJson,
                    @Param("expireAt") LocalDateTime expireAt,@Param("now") LocalDateTime now);

    @Update("update async_task set status=#{status},next_retry_at=#{nextRetryAt},next_trigger_type=#{nextTrigger},lease_token=null,lease_owner=null,lease_until=null,last_error_type=#{failureType},last_error_code=#{failureCode},last_error_message=#{failureMessage},finished_at=case when #{status}='FAILED' then #{now} else null end,updated_at=#{now},expire_at=#{expireAt},row_version=row_version+1 " +
            "where id=#{taskId} and status='RUNNING' and attempt_version=#{attemptVersion} and lease_token=#{leaseToken}")
    int markFailure(@Param("taskId") Long taskId,@Param("attemptVersion") Integer attemptVersion,
                    @Param("leaseToken") String leaseToken,@Param("status") String status,
                    @Param("nextRetryAt") LocalDateTime nextRetryAt,@Param("nextTrigger") String nextTrigger,
                    @Param("failureType") String failureType,@Param("failureCode") String failureCode,
                    @Param("failureMessage") String failureMessage,@Param("expireAt") LocalDateTime expireAt,
                    @Param("now") LocalDateTime now);

    @Update("update async_task_attempt set status=#{status},finished_at=#{now},failure_type=#{failureType},failure_code=#{failureCode},failure_message=#{failureMessage},retryable=#{retryable},next_retry_at=#{nextRetryAt},duration_ms=timestampdiff(microsecond,started_at,#{now}) div 1000 " +
            "where task_id=#{taskId} and attempt_version=#{attemptVersion} and status='RUNNING' and lease_token=#{leaseToken}")
    int finishAttempt(@Param("taskId") Long taskId,@Param("attemptVersion") Integer attemptVersion,
                      @Param("leaseToken") String leaseToken,@Param("status") String status,
                      @Param("failureType") String failureType,@Param("failureCode") String failureCode,
                      @Param("failureMessage") String failureMessage,@Param("retryable") Boolean retryable,
                      @Param("nextRetryAt") LocalDateTime nextRetryAt,@Param("now") LocalDateTime now);

    @Update("update async_task set status='CANCELED',cancel_requested_at=#{now},cancel_requested_by=#{operatorId},cancel_reason=#{reason},finished_at=#{now},updated_at=#{now},expire_at=#{expireAt},row_version=row_version+1 " +
            "where id=#{taskId} and status in ('PENDING_PUBLISH','PENDING','RETRY_WAIT')")
    int cancelWaiting(@Param("taskId") Long taskId,@Param("operatorId") Long operatorId,@Param("reason") String reason,
                      @Param("expireAt") LocalDateTime expireAt,@Param("now") LocalDateTime now);

    @Update("update async_task set cancel_requested_at=#{now},cancel_requested_by=#{operatorId},cancel_reason=#{reason},updated_at=#{now},row_version=row_version+1 where id=#{taskId} and status='RUNNING' and cancel_requested_at is null")
    int requestRunningCancel(@Param("taskId") Long taskId,@Param("operatorId") Long operatorId,
                             @Param("reason") String reason,@Param("now") LocalDateTime now);

    @Update("update async_task set status='CANCELED',lease_token=null,lease_owner=null,lease_until=null,finished_at=#{now},updated_at=#{now},expire_at=#{expireAt},row_version=row_version+1 " +
            "where id=#{taskId} and status='RUNNING' and attempt_version=#{attemptVersion} and lease_token=#{leaseToken} and cancel_requested_at is not null")
    int markRunningCanceled(@Param("taskId") Long taskId,@Param("attemptVersion") Integer attemptVersion,
                            @Param("leaseToken") String leaseToken,@Param("expireAt") LocalDateTime expireAt,
                            @Param("now") LocalDateTime now);

    @Update("update async_task set status='RETRY_WAIT',next_retry_at=#{now},next_trigger_type='MANUAL_RETRY',last_error_type=null,last_error_code=null,last_error_message=null,finished_at=null,expire_at=null,updated_at=#{now},row_version=row_version+1 where id=#{taskId} and status='FAILED'")
    int manualRetry(@Param("taskId") Long taskId,@Param("now") LocalDateTime now);

    @Select("select * from async_task_attempt where task_id=#{taskId} order by attempt_version")
    List<UnifiedTaskAttempt> listAttempts(@Param("taskId") Long taskId);

    @Select("select id from async_task where status='RUNNING' and lease_until < #{now} order by lease_until limit #{limit}")
    List<Long> listExpiredTaskIds(@Param("now") LocalDateTime now,@Param("limit") int limit);

    @Delete("delete from mq_inbox where task_id in (select id from async_task where expire_at < #{now}) limit #{limit}")
    int deleteExpiredInbox(@Param("now") LocalDateTime now,@Param("limit") int limit);

    @Delete("delete from mq_outbox where status='SENT' and task_id in (select id from async_task where expire_at < #{now}) limit #{limit}")
    int deleteExpiredOutbox(@Param("now") LocalDateTime now,@Param("limit") int limit);

    @Delete("delete from async_task_attempt where task_id in (select id from async_task where expire_at < #{now}) limit #{limit}")
    int deleteExpiredAttempts(@Param("now") LocalDateTime now,@Param("limit") int limit);

    @Delete("delete from async_task where expire_at < #{now} limit #{limit}")
    int deleteExpiredTasks(@Param("now") LocalDateTime now,@Param("limit") int limit);
}
