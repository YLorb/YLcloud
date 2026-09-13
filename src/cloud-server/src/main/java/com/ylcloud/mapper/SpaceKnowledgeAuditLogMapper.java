package com.ylcloud.mapper;

import com.ylcloud.entity.SpaceKnowledgeAuditLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;

@Mapper
public interface SpaceKnowledgeAuditLogMapper {
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    @Insert("insert into space_knowledge_audit_log(space_id, operator_id, action, resource_type, resource_id, before_snapshot, after_snapshot, ip_address, user_agent, created_time) " +
            "values(#{spaceId}, #{operatorId}, #{action}, #{resourceType}, #{resourceId}, #{beforeSnapshot}, #{afterSnapshot}, #{ipAddress}, #{userAgent}, #{createdTime})")
    int insert(SpaceKnowledgeAuditLog auditLog);
}
