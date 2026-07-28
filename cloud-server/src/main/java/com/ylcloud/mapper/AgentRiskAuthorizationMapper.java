package com.ylcloud.mapper;

import com.ylcloud.entity.AgentRiskAuthorization;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AgentRiskAuthorizationMapper {
    String COLUMNS = "authorization_id as id,user_id as userId,api_key_id as apiKeyId," +
            "authorization_mode as authorizationMode,authorization_status as authorizationStatus," +
            "expires_at as expiresAt,consumed_invocation_id as consumedInvocationId,consumed_at as consumedAt," +
            "revoked_by as revokedBy,revoked_at as revokedAt,risk_acknowledged as riskAcknowledged," +
            "createtime as createTime,updatetime as updateTime";

    @Select("select " + COLUMNS + " from agent_risk_authorization where user_id=#{userId} " +
            "and ((#{apiKeyId} is null and api_key_id is null) or api_key_id=#{apiKeyId}) " +
            "and authorization_status='ACTIVE' for update")
    AgentRiskAuthorization lockActive(@Param("userId") Long userId,@Param("apiKeyId") Long apiKeyId);

    @Select("select " + COLUMNS + " from agent_risk_authorization where authorization_id=#{id} for update")
    AgentRiskAuthorization lockById(@Param("id") Long id);

    @Insert("insert into agent_risk_authorization(user_id,api_key_id,authorization_mode,authorization_status," +
            "expires_at,risk_acknowledged,createtime,updatetime) values(#{userId},#{apiKeyId}," +
            "#{authorizationMode},#{authorizationStatus},#{expiresAt},#{riskAcknowledged},#{createTime},#{updateTime})")
    @Options(useGeneratedKeys = true,keyProperty = "id",keyColumn = "authorization_id")
    int insert(AgentRiskAuthorization authorization);

    @Update("update agent_risk_authorization set authorization_status=#{status},revoked_by=#{operatorId}," +
            "revoked_at=#{now},updatetime=#{now} where authorization_id=#{id} and authorization_status='ACTIVE'")
    int revoke(@Param("id") Long id,@Param("status") String status,@Param("operatorId") Long operatorId,
               @Param("now") LocalDateTime now);

    @Update("update agent_risk_authorization set authorization_status='CONSUMED'," +
            "consumed_invocation_id=#{invocationId},consumed_at=#{now},updatetime=#{now} " +
            "where authorization_id=#{id} and authorization_status='ACTIVE' and authorization_mode='ALLOW_ONCE' " +
            "and (expires_at is null or expires_at>#{now}) and consumed_invocation_id is null")
    int consumeOnce(@Param("id") Long id,@Param("invocationId") String invocationId,
                    @Param("now") LocalDateTime now);

    @Select("select " + COLUMNS + " from agent_risk_authorization where user_id=#{userId} " +
            "order by createtime desc,authorization_id desc")
    List<AgentRiskAuthorization> listByUser(@Param("userId") Long userId);

    @Select("select count(1) from agent_risk_authorization where user_id=#{userId} and api_key_id=#{apiKeyId} " +
            "and authorization_status='ACTIVE' and (expires_at is null or expires_at>now())")
    int countActiveForApiKey(@Param("userId") Long userId,@Param("apiKeyId") Long apiKeyId);
}
