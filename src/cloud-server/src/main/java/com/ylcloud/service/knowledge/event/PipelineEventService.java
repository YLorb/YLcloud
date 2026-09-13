package com.ylcloud.service.knowledge.event;

import com.ylcloud.constant.SpaceConstant;
import com.ylcloud.entity.SpaceKnowledgePipelineEvent;
import com.ylcloud.mapper.SpaceKnowledgePipelineEventMapper;
import com.ylcloud.service.knowledge.pipeline.KnowledgePipelineContext;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class PipelineEventService {
    private final SpaceKnowledgePipelineEventMapper eventMapper;

    public PipelineEventService(SpaceKnowledgePipelineEventMapper eventMapper) {
        this.eventMapper = eventMapper;
    }

    public void taskCreated(Long taskId, Long spaceId, Long documentId, String traceId) {
        append(taskId,spaceId,documentId,SpaceConstant.KNOWLEDGE_PIPELINE_TASK_INITIALIZE,
                SpaceConstant.KNOWLEDGE_EVENT_TASK_CREATED,SpaceConstant.KNOWLEDGE_EVENT_STATUS_SUCCEEDED,
                "Knowledge pipeline task created",null,null,null,null,null,traceId,1);
    }

    public <T> T executeStage(KnowledgePipelineContext context, String stage, String inputSummary, StageAction<T> action) {
        LocalDateTime started = LocalDateTime.now();
        append(context.getTaskId(),context.getSpaceId(),context.getDocumentId(),stage,
                SpaceConstant.KNOWLEDGE_EVENT_STAGE_STARTED,SpaceConstant.KNOWLEDGE_EVENT_STATUS_RUNNING,
                "Stage started",trim(inputSummary),null,null,null,null,context.getTraceId(),context.getAttemptNo());
        try {
            T result = action.run();
            long durationMs = Duration.between(started,LocalDateTime.now()).toMillis();
            append(context.getTaskId(),context.getSpaceId(),context.getDocumentId(),stage,
                    SpaceConstant.KNOWLEDGE_EVENT_STAGE_FINISHED,SpaceConstant.KNOWLEDGE_EVENT_STATUS_SUCCEEDED,
                    "Stage finished",trim(inputSummary),trim(String.valueOf(result)),null,null,durationMs,context.getTraceId(),context.getAttemptNo());
            return result;
        } catch (RuntimeException ex) {
            long durationMs = Duration.between(started,LocalDateTime.now()).toMillis();
            append(context.getTaskId(),context.getSpaceId(),context.getDocumentId(),stage,
                    SpaceConstant.KNOWLEDGE_EVENT_STAGE_FAILED,SpaceConstant.KNOWLEDGE_EVENT_STATUS_FAILED,
                    "Stage failed",trim(inputSummary),null,errorCode(ex),safeError(ex),durationMs,context.getTraceId(),context.getAttemptNo());
            throw ex;
        }
    }

    public void reviewRequired(KnowledgePipelineContext context, String message) {
        append(context.getTaskId(),context.getSpaceId(),context.getDocumentId(),SpaceConstant.KNOWLEDGE_PIPELINE_COMPLETE,
                SpaceConstant.KNOWLEDGE_EVENT_REVIEW_REQUIRED,SpaceConstant.KNOWLEDGE_EVENT_STATUS_SUCCEEDED,
                message,null,null,null,null,null,context.getTraceId(),context.getAttemptNo());
    }

    public void taskFinished(KnowledgePipelineContext context, String status, String message) {
        append(context.getTaskId(),context.getSpaceId(),context.getDocumentId(),SpaceConstant.KNOWLEDGE_PIPELINE_COMPLETE,
                SpaceConstant.KNOWLEDGE_EVENT_TASK_FINISHED,status,message,null,null,null,null,null,context.getTraceId(),context.getAttemptNo());
    }

    public List<SpaceKnowledgePipelineEvent> listByTaskId(Long taskId) {
        return eventMapper.listByTaskId(taskId);
    }

    private void append(Long taskId, Long spaceId, Long documentId, String stage, String eventType, String eventStatus,
                        String message, String inputSummary, String outputSummary, String errorCode, String errorMessage,
                        Long durationMs, String traceId, Integer attemptNo) {
        LocalDateTime now = LocalDateTime.now();
        SpaceKnowledgePipelineEvent event = new SpaceKnowledgePipelineEvent();
        event.setTaskId(taskId);
        event.setSpaceId(spaceId);
        event.setDocumentId(documentId);
        event.setStage(stage);
        event.setEventType(eventType);
        event.setEventStatus(eventStatus);
        event.setMessage(trim(message));
        event.setInputSummary(trim(inputSummary));
        event.setOutputSummary(trim(outputSummary));
        event.setErrorCode(errorCode);
        event.setErrorMessage(trim(errorMessage));
        event.setEventTime(now);
        event.setDurationMs(durationMs);
        event.setTraceId(traceId);
        event.setAttemptNo(attemptNo == null ? 1 : attemptNo);
        event.setCreatedAt(now);
        eventMapper.insert(event);
    }

    private String errorCode(RuntimeException ex) {
        if(ex.getMessage() != null && ex.getMessage().toLowerCase().contains("json")) {
            return "LLM_OUTPUT_INVALID_JSON";
        }
        return "PIPELINE_STAGE_FAILED";
    }

    private String safeError(Exception ex) {
        String message = ex.getMessage();
        if(message == null || message.isBlank()) {
            message = ex.getClass().getSimpleName();
        }
        return trim(message);
    }

    private String trim(String value) {
        if(value == null) {
            return null;
        }
        return value.length() > 900 ? value.substring(0,900) : value;
    }

    @FunctionalInterface
    public interface StageAction<T> {
        T run();
    }
}
