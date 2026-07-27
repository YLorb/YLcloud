package com.ylcloud.mapper;

import com.ylcloud.entity.AuditRetentionConfig;
import com.ylcloud.entity.SecurityAuditEvent;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface SecurityAuditMapper {
    String COLUMNS = "event_id as eventId, event_key as eventKey, event_type as eventType, " +
            "subject_type as subjectType, subject_id as subjectId, subject_name as subjectName, " +
            "target_type as targetType, target_id as targetId, target_name as targetName, " +
            "action, result, trace_id as traceId, request_id as requestId, " +
            "ip_address as ipAddress, user_agent as userAgent, detail_json as detailJson, " +
            "error_message as errorMessage, retention_policy as retentionPolicy, " +
            "occurred_at as occurredAt, created_at as createdAt";

    @Insert("insert into security_audit_event(event_key, event_type, subject_type, subject_id, subject_name, " +
            "target_type, target_id, target_name, action, result, trace_id, request_id, ip_address, user_agent, " +
            "detail_json, error_message, retention_policy, occurred_at, created_at) " +
            "values(#{eventKey}, #{eventType}, #{subjectType}, #{subjectId}, #{subjectName}, " +
            "#{targetType}, #{targetId}, #{targetName}, #{action}, #{result}, #{traceId}, #{requestId}, " +
            "#{ipAddress}, #{userAgent}, #{detailJson}, #{errorMessage}, #{retentionPolicy}, #{occurredAt}, #{createdAt})")
    int insert(SecurityAuditEvent event);

    @Select("select " + COLUMNS + " from security_audit_event where event_id = #{eventId}")
    SecurityAuditEvent getById(@Param("eventId") Long eventId);

    @Select("select " + COLUMNS + " from security_audit_event where event_key = #{eventKey}")
    SecurityAuditEvent getByKey(@Param("eventKey") String eventKey);

    @Select("select " + COLUMNS + " from security_audit_event " +
            "where event_type = #{eventType} and occurred_at >= #{from} and occurred_at < #{to} " +
            "order by occurred_at desc limit #{limit}")
    List<SecurityAuditEvent> listByTypeAndTime(@Param("eventType") String eventType,
                                               @Param("from") LocalDateTime from,
                                               @Param("to") LocalDateTime to,
                                               @Param("limit") int limit);

    @Select("select " + COLUMNS + " from security_audit_event " +
            "where subject_id = #{subjectId} and occurred_at >= #{from} " +
            "order by occurred_at desc limit #{limit}")
    List<SecurityAuditEvent> listBySubject(@Param("subjectId") Long subjectId,
                                           @Param("from") LocalDateTime from,
                                           @Param("limit") int limit);

    @Select("select " + COLUMNS + " from security_audit_event " +
            "where target_type = #{targetType} and target_id = #{targetId} " +
            "order by occurred_at desc limit #{limit}")
    List<SecurityAuditEvent> listByTarget(@Param("targetType") String targetType,
                                          @Param("targetId") String targetId,
                                          @Param("limit") int limit);

    @Select("select " + COLUMNS + " from security_audit_event " +
            "where trace_id = #{traceId} order by occurred_at asc")
    List<SecurityAuditEvent> listByTrace(@Param("traceId") String traceId);

    // Retention cleanup: only delete STANDARD retention events older than retention period
    // PERMANENT events are never deleted
    @Update("delete from security_audit_event " +
            "where retention_policy = 'STANDARD' and occurred_at < #{cutoff} " +
            "limit #{limit}")
    int cleanupExpired(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    @Select("select config_key as configKey, retention_days as retentionDays, permanent, " +
            "description, updated_at as updatedAt from audit_retention_config")
    List<AuditRetentionConfig> listRetentionConfigs();

    @Select("select config_key as configKey, retention_days as retentionDays, permanent, " +
            "description, updated_at as updatedAt from audit_retention_config where config_key = #{configKey}")
    AuditRetentionConfig getRetentionConfig(@Param("configKey") String configKey);

    @Update("update audit_retention_config set retention_days = #{retentionDays}, permanent = #{permanent}, " +
            "description = #{description}, updated_at = #{updatedAt} where config_key = #{configKey}")
    int updateRetentionConfig(@Param("configKey") String configKey,
                              @Param("retentionDays") Integer retentionDays,
                              @Param("permanent") Boolean permanent,
                              @Param("description") String description,
                              @Param("updatedAt") LocalDateTime updatedAt);

    @Select("select count(1) from security_audit_event where retention_policy = 'PERMANENT'")
    int countPermanentEvents();

    @Select("select count(1) from security_audit_event where occurred_at >= #{from}")
    int countRecentEvents(@Param("from") LocalDateTime from);
}
