package com.ylcloud.workflow.tool;

import org.apache.ibatis.annotations.*;

@Mapper
public interface WorkflowConfirmationMapper {
    @Select("select grant_id as grantId,user_id as userId,tool_name as toolName,parameter_hash as parameterHash," +
            "grant_mode as grantMode,similarity_scope_json as similarityScopeJson,expires_at as expiresAt," +
            "revoked,consumed_invocation_id as consumedInvocationId from workflow_confirmation_grant " +
            "where grant_id=#{grantId} for update")
    WorkflowConfirmationRecord getForUpdate(@Param("grantId") String grantId);

    @Insert("insert into workflow_confirmation_grant(grant_id,user_id,tool_name,parameter_hash,grant_mode," +
            "similarity_scope_json,expires_at,revoked,createtime,updatetime) values(#{grantId},#{userId}," +
            "#{toolName},#{parameterHash},#{grantMode},#{similarityScopeJson},#{expiresAt},0,now(),now())")
    int insert(WorkflowConfirmationRecord record);

    @Update("update workflow_confirmation_grant set consumed_invocation_id=#{invocationId},updatetime=now() " +
            "where grant_id=#{grantId} and consumed_invocation_id is null and revoked=0 and expires_at>now()")
    int consumeOnce(@Param("grantId") String grantId, @Param("invocationId") String invocationId);

    @Update("update workflow_confirmation_grant set revoked=1,updatetime=now() where grant_id=#{grantId} " +
            "and user_id=#{userId} and revoked=0")
    int revoke(@Param("grantId") String grantId, @Param("userId") Long userId);
}
