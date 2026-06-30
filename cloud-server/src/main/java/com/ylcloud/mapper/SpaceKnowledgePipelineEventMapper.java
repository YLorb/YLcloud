package com.ylcloud.mapper;

import com.ylcloud.entity.SpaceKnowledgePipelineEvent;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SpaceKnowledgePipelineEventMapper {
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    @Insert("insert into space_knowledge_pipeline_event(task_id, space_id, document_id, stage, event_type, event_status, message, input_summary, output_summary, error_code, error_message, event_time, duration_ms, trace_id, attempt_no, created_at) " +
            "values(#{taskId}, #{spaceId}, #{documentId}, #{stage}, #{eventType}, #{eventStatus}, #{message}, #{inputSummary}, #{outputSummary}, #{errorCode}, #{errorMessage}, #{eventTime}, #{durationMs}, #{traceId}, #{attemptNo}, #{createdAt})")
    int insert(SpaceKnowledgePipelineEvent event);

    @Select("select id, task_id as taskId, space_id as spaceId, document_id as documentId, stage, event_type as eventType, event_status as eventStatus, " +
            "message, input_summary as inputSummary, output_summary as outputSummary, error_code as errorCode, error_message as errorMessage, " +
            "event_time as eventTime, duration_ms as durationMs, trace_id as traceId, attempt_no as attemptNo, created_at as createdAt " +
            "from space_knowledge_pipeline_event where task_id = #{taskId} order by id asc")
    List<SpaceKnowledgePipelineEvent> listByTaskId(@Param("taskId") Long taskId);
}
