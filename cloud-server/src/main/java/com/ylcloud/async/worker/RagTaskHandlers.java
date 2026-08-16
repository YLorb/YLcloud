package com.ylcloud.async.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.async.task.DomainTaskPayload;
import com.ylcloud.entity.UnifiedAsyncTask;
import com.ylcloud.service.SpaceRagService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RagTaskHandlers {
    @Bean
    TaskHandler ragIndexFileTaskHandler(ObjectMapper json,SpaceRagService service) {
        return fileHandler("RAG_INDEX_FILE",json,service);
    }

    @Bean
    TaskHandler ragRebuildFileTaskHandler(ObjectMapper json,SpaceRagService service) {
        return fileHandler("RAG_REBUILD_FILE",json,service);
    }

    @Bean
    TaskHandler ragDeleteFileTaskHandler(ObjectMapper json,SpaceRagService service) {
        return handler("RAG_DELETE_FILE",json,(task,id,context) -> service.executeDeleteAsync(
                id,task.getId(),version(task),finalAttempt(task),context));
    }

    @Bean
    TaskHandler ragSpaceFanoutTaskHandler(ObjectMapper json,SpaceRagService service) {
        return handler("RAG_SPACE_FANOUT",json,(task,id,context) -> service.executeSpaceAsync(
                id,task.getId(),version(task),finalAttempt(task),context));
    }

    private TaskHandler fileHandler(String type,ObjectMapper json,SpaceRagService service) {
        return handler(type,json,(task,id,context) -> service.executeFileAsync(
                id,task.getId(),version(task),task.getAttemptVersion(),finalAttempt(task),context,type));
    }

    private TaskHandler handler(String type,ObjectMapper json,Execution execution) {
        return new TaskHandler() {
            @Override public String taskType() { return type; }

            @Override
            public Object execute(UnifiedAsyncTask task,TaskExecutionContext context) throws Exception {
                DomainTaskPayload payload=json.readValue(task.getPayloadJson(),DomainTaskPayload.class);
                if(payload.resourceId()==null) throw new IllegalArgumentException("RAG domain task ID is required");
                return execution.execute(task,payload.resourceId(),context);
            }
        };
    }

    private static long version(UnifiedAsyncTask task) {
        return task.getResourceVersion()==null ? 1L : task.getResourceVersion();
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
