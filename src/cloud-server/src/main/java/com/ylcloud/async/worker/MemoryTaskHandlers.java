package com.ylcloud.async.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.async.task.DomainTaskPayload;
import com.ylcloud.entity.UnifiedAsyncTask;
import com.ylcloud.service.memory.UserMemoryExtractionService;
import com.ylcloud.service.memory.UserMemoryService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MemoryTaskHandlers {
    @Bean
    TaskHandler memoryExtractTaskHandler(ObjectMapper json,UserMemoryExtractionService service) {
        return handler("MEMORY_EXTRACT",json,(task,id,context) ->
                service.executeAsync(id,task.getId(),task.getResourceVersion(),context));
    }

    @Bean
    TaskHandler memoryProfileBuildTaskHandler(ObjectMapper json,UserMemoryService service) {
        return handler("MEMORY_PROFILE_BUILD",json,(task,id,context) ->
                service.executeProfileAsync(id,task.getId(),task.getResourceVersion(),context));
    }

    @Bean
    TaskHandler memoryVectorIndexTaskHandler(ObjectMapper json,UserMemoryService service) {
        return handler("MEMORY_VECTOR_INDEX",json,(task,id,context) ->
                service.executeIndexAsync(id,task.getId(),task.getResourceVersion(),context));
    }

    @Bean
    TaskHandler memoryVectorDeleteTaskHandler(ObjectMapper json,UserMemoryService service) {
        return handler("MEMORY_VECTOR_DELETE",json,(task,id,context) ->
                service.executeDeleteAsync(id,task.getId(),task.getResourceVersion(),context));
    }

    private TaskHandler handler(String type,ObjectMapper json,IdExecution execution) {
        return new TaskHandler() {
            @Override public String taskType() { return type; }
            @Override public Object execute(UnifiedAsyncTask task,TaskExecutionContext context) throws Exception {
                DomainTaskPayload payload=json.readValue(task.getPayloadJson(),DomainTaskPayload.class);
                if(payload.resourceId()==null) throw new IllegalArgumentException("任务资源 ID 缺失");
                return execution.execute(task,payload.resourceId(),context);
            }
        };
    }

    @FunctionalInterface
    private interface IdExecution {
        Object execute(UnifiedAsyncTask task,Long id,TaskExecutionContext context) throws Exception;
    }
}
