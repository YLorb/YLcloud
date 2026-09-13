package com.ylcloud.mapper;

import com.ylcloud.entity.QuotaAccount;
import com.ylcloud.entity.QuotaBlobReference;
import com.ylcloud.entity.QuotaPolicy;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Delete;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

@Mapper
public interface QuotaMapper {
    String POLICY_COLUMNS = "group_id as groupId,storage_bytes as storageBytes,max_file_bytes as maxFileBytes," +
            "space_limit as spaceLimit,monthly_api_calls as monthlyApiCalls,monthly_model_tokens as monthlyModelTokens," +
            "monthly_agent_tasks as monthlyAgentTasks,concurrent_agent_tasks as concurrentAgentTasks";
    String QUALIFIED_POLICY_COLUMNS = "qp.group_id as groupId,qp.storage_bytes as storageBytes,qp.max_file_bytes as maxFileBytes," +
            "qp.space_limit as spaceLimit,qp.monthly_api_calls as monthlyApiCalls,qp.monthly_model_tokens as monthlyModelTokens," +
            "qp.monthly_agent_tasks as monthlyAgentTasks,qp.concurrent_agent_tasks as concurrentAgentTasks";
    String ACCOUNT_COLUMNS = "account_id as id,account_type as accountType,reference_id as referenceId,group_id as groupId";

    @Select("select " + POLICY_COLUMNS + " from quota_policy where group_id=#{groupId}")
    QuotaPolicy getPolicy(@Param("groupId") Long groupId);

    @Select("select count(*) from permission_group where group_id=#{groupId}")
    int groupExists(@Param("groupId") Long groupId);

    @Select("select " + QUALIFIED_POLICY_COLUMNS + " from user_permission_group upg " +
            "join quota_policy qp on qp.group_id=upg.group_id where upg.user_id=#{userId}")
    QuotaPolicy getUserPolicy(@Param("userId") Long userId);

    @Insert("insert into quota_policy(group_id,storage_bytes,max_file_bytes,space_limit,monthly_api_calls," +
            "monthly_model_tokens,monthly_agent_tasks,concurrent_agent_tasks,updatetime) values(" +
            "#{policy.groupId},#{policy.storageBytes},#{policy.maxFileBytes},#{policy.spaceLimit},#{policy.monthlyApiCalls},#{policy.monthlyModelTokens}," +
            "#{policy.monthlyAgentTasks},#{policy.concurrentAgentTasks},#{now}) on duplicate key update " +
            "storage_bytes=values(storage_bytes),max_file_bytes=values(max_file_bytes),space_limit=values(space_limit)," +
            "monthly_api_calls=values(monthly_api_calls),monthly_model_tokens=values(monthly_model_tokens)," +
            "monthly_agent_tasks=values(monthly_agent_tasks),concurrent_agent_tasks=values(concurrent_agent_tasks),updatetime=values(updatetime)")
    int upsertPolicy(@Param("policy") QuotaPolicy policy,@Param("now") LocalDateTime now);

    @Insert("insert into quota_account(account_type,reference_id,group_id,createtime,updatetime) " +
            "select 'USER',#{userId},upg.group_id,#{now},#{now} from users u left join user_permission_group upg on upg.user_id=u.user_id " +
            "where u.user_id=#{userId} on duplicate key update group_id=values(group_id),updatetime=values(updatetime)")
    int ensureUserAccount(@Param("userId") Long userId,@Param("now") LocalDateTime now);

    @Insert("insert into quota_account(account_type,reference_id,group_id,createtime,updatetime) " +
            "select 'TEAM',#{spaceId},upg.group_id,#{now},#{now} from users u left join user_permission_group upg on upg.user_id=u.user_id " +
            "where u.user_id=#{ownerId} " +
            "on duplicate key update group_id=values(group_id),updatetime=values(updatetime)")
    int ensureTeamAccount(@Param("spaceId") Long spaceId,@Param("ownerId") Long ownerId,@Param("now") LocalDateTime now);

    @Select("select " + ACCOUNT_COLUMNS + " from quota_account where account_type=#{type} and reference_id=#{referenceId}")
    QuotaAccount getAccount(@Param("type") String type,@Param("referenceId") Long referenceId);

    @Select("select " + ACCOUNT_COLUMNS + " from quota_account where account_id=#{accountId} for update")
    QuotaAccount lockAccount(@Param("accountId") Long accountId);

    @Select("select coalesce(sum(size_bytes),0) from quota_blob_ledger where owner_account_id=#{accountId}")
    Long storageUsed(@Param("accountId") Long accountId);

    @Select("select count(*) from quota_blob_ledger where owner_account_id=#{accountId}")
    Long storageFiles(@Param("accountId") Long accountId);

    @Select("select count(*) from spaces where owner_id=#{userId} and type='TEAM' and status=1 and lifecycle_state='ACTIVE'")
    Long activeOwnedTeamSpaces(@Param("userId") Long userId);

    @Select("select owner_account_id from quota_blob_ledger where content_key=#{contentKey}")
    Long blobOwner(@Param("contentKey") String contentKey);

    @Insert("insert ignore into quota_blob_ledger(content_key,file_uuid,size_bytes,owner_account_id,reference_count,createtime,updatetime) " +
            "values(#{contentKey},#{fileUuid},#{sizeBytes},#{accountId},0,#{now},#{now})")
    int insertBlob(@Param("contentKey") String contentKey,@Param("fileUuid") String fileUuid,
                   @Param("sizeBytes") long sizeBytes,@Param("accountId") Long accountId,@Param("now") LocalDateTime now);

    @Select("select reference_type as referenceType,reference_id as referenceId,account_id as accountId," +
            "content_key as contentKey,file_uuid as fileUuid,size_bytes as sizeBytes,active " +
            "from quota_blob_reference where reference_type=#{type} and reference_id=#{id} for update")
    QuotaBlobReference lockReference(@Param("type") String type,@Param("id") Long id);

    @Insert("insert into quota_blob_reference(reference_type,reference_id,account_id,content_key,file_uuid,size_bytes,active,createtime,updatetime) " +
            "values(#{type},#{id},#{accountId},#{contentKey},#{fileUuid},#{sizeBytes},1,#{now},#{now})")
    int insertReference(@Param("type") String type,@Param("id") Long id,@Param("accountId") Long accountId,
                        @Param("contentKey") String contentKey,@Param("fileUuid") String fileUuid,
                        @Param("sizeBytes") long sizeBytes,@Param("now") LocalDateTime now);

    @Update("update quota_blob_reference set active=1,account_id=#{accountId},content_key=#{contentKey},file_uuid=#{fileUuid}," +
            "size_bytes=#{sizeBytes},updatetime=#{now} where reference_type=#{type} and reference_id=#{id} and active=0")
    int activateReference(@Param("type") String type,@Param("id") Long id,@Param("accountId") Long accountId,
                          @Param("contentKey") String contentKey,@Param("fileUuid") String fileUuid,
                          @Param("sizeBytes") long sizeBytes,@Param("now") LocalDateTime now);

    @Update("update quota_blob_ledger set reference_count=reference_count+1,updatetime=#{now} where content_key=#{contentKey}")
    int incrementBlob(@Param("contentKey") String contentKey,@Param("now") LocalDateTime now);

    @Update("update quota_blob_reference set active=0,updatetime=#{now} where reference_type=#{type} and reference_id=#{id} and active=1")
    int deactivateReference(@Param("type") String type,@Param("id") Long id,@Param("now") LocalDateTime now);

    @Update("update quota_blob_ledger set reference_count=reference_count-1,updatetime=#{now} " +
            "where content_key=#{contentKey} and reference_count>0")
    int decrementBlob(@Param("contentKey") String contentKey,@Param("now") LocalDateTime now);

    @Update("update quota_blob_ledger ledger set owner_account_id=(select min(ref.account_id) from quota_blob_reference ref " +
            "where ref.content_key=ledger.content_key and ref.active=1),updatetime=#{now} where ledger.content_key=#{contentKey} " +
            "and ledger.reference_count>0")
    int reassignBlobOwner(@Param("contentKey") String contentKey,@Param("now") LocalDateTime now);

    @Delete("delete from quota_blob_ledger where content_key=#{contentKey} and reference_count=0")
    int deleteUnreferencedBlob(@Param("contentKey") String contentKey);

    @Insert("insert ignore into quota_usage_period(account_id,period_start,updatetime) values(#{accountId},#{period},#{now})")
    int ensureUsage(@Param("accountId") Long accountId,@Param("period") LocalDate period,@Param("now") LocalDateTime now);

    @Update("update quota_usage_period set api_calls=api_calls+#{amount},updatetime=#{now} where account_id=#{accountId} " +
            "and period_start=#{period} and (#{quota}=0 or api_calls+#{amount}<=#{quota})")
    int consumeApi(@Param("accountId") Long accountId,@Param("period") LocalDate period,@Param("amount") long amount,
                   @Param("quota") long quota,@Param("now") LocalDateTime now);

    @Update("update quota_usage_period set model_tokens=model_tokens+#{amount},updatetime=#{now} where account_id=#{accountId} " +
            "and period_start=#{period} and (#{quota}=0 or model_tokens+#{amount}<=#{quota})")
    int consumeTokens(@Param("accountId") Long accountId,@Param("period") LocalDate period,@Param("amount") long amount,
                      @Param("quota") long quota,@Param("now") LocalDateTime now);

    @Update("update quota_usage_period set agent_tasks=agent_tasks+1,concurrent_agent_tasks=concurrent_agent_tasks+1,updatetime=#{now} " +
            "where account_id=#{accountId} and period_start=#{period} and (#{monthly}=0 or agent_tasks<#{monthly}) " +
            "and (#{concurrent}=0 or concurrent_agent_tasks<#{concurrent})")
    int reserveAgent(@Param("accountId") Long accountId,@Param("period") LocalDate period,@Param("monthly") long monthly,
                     @Param("concurrent") long concurrent,@Param("now") LocalDateTime now);

    @Update("update quota_usage_period set concurrent_agent_tasks=greatest(0,concurrent_agent_tasks-1),updatetime=#{now} " +
            "where account_id=#{accountId} and period_start=#{period}")
    int releaseAgent(@Param("accountId") Long accountId,@Param("period") LocalDate period,@Param("now") LocalDateTime now);

    @Update("update quota_usage_period qu join quota_account qa on qa.account_id=qu.account_id " +
            "set qu.concurrent_agent_tasks=(select count(*) from knowledge_chat_message kcm " +
            "where kcm.user_id=qa.reference_id and kcm.task_status in ('QUEUED','RUNNING')),qu.updatetime=#{now} " +
            "where qu.period_start=#{period} and qa.account_type='USER'")
    int reconcileConcurrentUsage(@Param("period") LocalDate period,@Param("now") LocalDateTime now);

    @Select("select api_calls as apiCalls,model_tokens as modelTokens,agent_tasks as agentTasks," +
            "concurrent_agent_tasks as concurrentAgentTasks from quota_usage_period where account_id=#{accountId} and period_start=#{period}")
    Map<String,Object> usage(@Param("accountId") Long accountId,@Param("period") LocalDate period);

    @Select("select count(*) from quota_blob_ledger ledger where ledger.reference_count<>(select count(*) from quota_blob_reference ref " +
            "where ref.content_key=ledger.content_key and ref.active=1)")
    long countLedgerDifferences();

    @Update("update quota_blob_ledger ledger set reference_count=(select count(*) from quota_blob_reference ref " +
            "where ref.content_key=ledger.content_key and ref.active=1),owner_account_id=(select min(ref.account_id) " +
            "from quota_blob_reference ref where ref.content_key=ledger.content_key and ref.active=1),updatetime=#{now} " +
            "where exists(select 1 from quota_blob_reference active_ref where active_ref.content_key=ledger.content_key and active_ref.active=1)")
    int repairLedgerCounts(@Param("now") LocalDateTime now);

    @Delete("delete from quota_blob_ledger ledger where not exists(select 1 from quota_blob_reference ref " +
            "where ref.content_key=ledger.content_key and ref.active=1)")
    int deleteOrphanLedgers();

    @Delete("delete from quota_blob_ledger where reference_count=0")
    int deleteEmptyLedgers();

    @Insert("insert into quota_reconcile_run(run_status,difference_count,detail_json,started_at,completed_at) " +
            "values(#{status},#{differences},#{detail},#{started},#{completed})")
    int insertReconcileEvidence(@Param("status") String status,@Param("differences") long differences,
                                @Param("detail") String detail,@Param("started") LocalDateTime started,
                                @Param("completed") LocalDateTime completed);
}
