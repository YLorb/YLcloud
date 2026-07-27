package com.ylcloud.async.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.async.task.DomainTaskPayload;
import com.ylcloud.entity.UnifiedAsyncTask;
import com.ylcloud.service.KnowledgeChatQueryService;
import com.ylcloud.workflow.client.WorkflowMessageLifecycleService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatWorkflowTaskHandlers {
    @Bean
    TaskHandler chatQueryTaskHandler(ObjectMapper json,KnowledgeChatQueryService service) {
        return handler("CHAT_QUERY",json,(task,id,context) -> service.executeAsync(
                id,task.getId(),task.getResourceVersion(),finalAttempt(task),context));
    }

    @Bean
    TaskHandler chatWorkflowRunTaskHandler(ObjectMapper json,WorkflowMessageLifecycleService service) {
        return handler("CHAT_WORKFLOW_RUN",json,(task,id,context) -> service.executeAsync(
                id,task.getId(),task.getResourceVersion(),false,finalAttempt(task),context));
    }

    @Bean
    TaskHandler chatWorkflowRetryTaskHandler(ObjectMapper json,WorkflowMessageLifecycleService service) {
        return handler("CHAT_WORKFLOW_RETRY",json,(task,id,context) -> service.executeAsync(
                id,task.getId(),task.getResourceVersion(),true,finalAttempt(task),context));
    }

    private TaskHandler handler(String type,ObjectMapper json,Execution execution) {
        return new TaskHandler() {
            @Override public String taskType() { return type; }
            @Override public Object execute(UnifiedAsyncTask task,TaskExecutionContext context) throws Exception {
                DomainTaskPayload payload=json.readValue(task.getPayloadJson(),DomainTaskPayload.class);
                if(payload.resourceId()==null) throw new IllegalArgumentException("Chat message ID is required");
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
