package com.ylcloud.async.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.async.task.DomainTaskPayload;
import com.ylcloud.entity.UnifiedAsyncTask;
import com.ylcloud.service.KnowledgePipelineService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class KnowledgePipelineTaskHandlers {
    @Bean
    TaskHandler knowledgeProfileDocumentTaskHandler(ObjectMapper json, KnowledgePipelineService service) {
        return handler("KNOWLEDGE_PROFILE_DOCUMENT",json,(task,id,context) -> service.executeDocumentAsync(
                id,task.getId(),task.getResourceVersion(),task.getAttemptVersion(),finalAttempt(task),context));
    }

    @Bean
    TaskHandler knowledgeProfileSpaceFanoutTaskHandler(ObjectMapper json, KnowledgePipelineService service) {
        return handler("KNOWLEDGE_PROFILE_SPACE_FANOUT",json,(task,id,context) -> service.executeSpaceAsync(
                id,task.getId(),task.getResourceVersion(),task.getAttemptVersion(),finalAttempt(task),context));
    }

    private TaskHandler handler(String type,ObjectMapper json,Execution execution) {
        return new TaskHandler() {
            @Override public String taskType() { return type; }

            @Override
            public Object execute(UnifiedAsyncTask task,TaskExecutionContext context) throws Exception {
                DomainTaskPayload payload=json.readValue(task.getPayloadJson(),DomainTaskPayload.class);
                if(payload.resourceId()==null) throw new IllegalArgumentException("Knowledge pipeline task ID is required");
                return execution.execute(task,payload.resourceId(),context);
            }
        };
    }

    private static boolean finalAttempt(UnifiedAsyncTask task) {
        return task.getAttemptVersion()!=null && task.getMaxAttempts()!=null
                && task.getAttemptVersion()>=task.getMaxAttempts();
    }

    @FunctionalInterface
    private interface Execution {
        Object execute(UnifiedAsyncTask task,Long id,TaskExecutionContext context) throws Exception;
    }
}
