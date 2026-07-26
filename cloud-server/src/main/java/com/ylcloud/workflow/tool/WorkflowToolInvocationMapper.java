package com.ylcloud.workflow.tool;

import org.apache.ibatis.annotations.*;

@Mapper
public interface WorkflowToolInvocationMapper {
    @Select("select invocation_id as invocationId,run_id as runId,execution_id as executionId,node_id as nodeId," +
            "user_id as userId,api_key_id as apiKeyId,session_id as sessionId,tool_name as toolName,risk_level as riskLevel," +
            "arguments_hash as argumentsHash,invocation_status as invocationStatus,response_json as responseJson," +
            "error_code as errorCode from workflow_tool_invocation where invocation_id=#{invocationId}")
    WorkflowToolInvocationRecord get(@Param("invocationId") String invocationId);

    @Insert("insert into workflow_tool_invocation(invocation_id,run_id,execution_id,node_id,user_id,api_key_id,session_id," +
            "tool_name,risk_level,arguments_hash,invocation_status,createtime,updatetime) values(" +
            "#{invocationId},#{runId},#{executionId},#{nodeId},#{userId},#{apiKeyId},#{sessionId},#{toolName},#{riskLevel}," +
            "#{argumentsHash},'RUNNING',now(),now())")
    int insert(WorkflowToolInvocationRecord record);

    @Update("update workflow_tool_invocation set invocation_status='COMPLETED',response_json=#{responseJson}," +
            "error_code=#{errorCode},updatetime=now() where invocation_id=#{invocationId} and invocation_status='RUNNING'")
    int complete(@Param("invocationId") String invocationId, @Param("responseJson") String responseJson,
                 @Param("errorCode") String errorCode);
}
