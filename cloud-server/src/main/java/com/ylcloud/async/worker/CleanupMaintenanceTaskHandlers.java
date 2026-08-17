package com.ylcloud.async.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.async.task.DomainTaskPayload;
import com.ylcloud.entity.UnifiedAsyncTask;
import com.ylcloud.service.CrossStoreRecoveryService;
import com.ylcloud.service.MultipartUploadCleanupService;
import com.ylcloud.service.PhysicalFileCleanupService;
import com.ylcloud.service.RagIndexConsistencyService;
import com.ylcloud.service.SpaceFileService;
import com.ylcloud.service.SpaceFileImportBatchService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CleanupMaintenanceTaskHandlers {
    @Bean
    TaskHandler physicalFileCleanupTaskHandler(ObjectMapper json,PhysicalFileCleanupService service) {
        return handler("PHYSICAL_FILE_CLEANUP",json,(task,id,context) -> service.executeAsync(id,task.getId(),context));
    }

    @Bean
    TaskHandler multipartExpiredCleanupTaskHandler(ObjectMapper json,MultipartUploadCleanupService service) {
        return handler("MULTIPART_EXPIRED_CLEANUP",json,
                (task,id,context) -> service.executeAsync(id,task.getId(),true,context));
    }

    @Bean
    TaskHandler multipartMergedCleanupTaskHandler(ObjectMapper json,MultipartUploadCleanupService service) {
        return handler("MULTIPART_MERGED_CLEANUP",json,
                (task,id,context) -> service.executeAsync(id,task.getId(),false,context));
    }

    @Bean
    TaskHandler crossStoreRecoveryTaskHandler(ObjectMapper json,CrossStoreRecoveryService service) {
        return handler("CROSS_STORE_RECOVERY",json,
                (task,id,context) -> service.executeAsync(id,task.getId(),task.getResourceVersion(),context));
    }

    @Bean
    TaskHandler ragConsistencyCleanupTaskHandler(ObjectMapper json,RagIndexConsistencyService service) {
        return handler("RAG_CONSISTENCY_CLEANUP",json,
                (task,id,context) -> service.executeCleanupAsync(id,task.getId(),task.getResourceVersion(),context));
    }

    @Bean
    TaskHandler ragConsistencyValidateTaskHandler(ObjectMapper json,RagIndexConsistencyService service) {
        return handler("RAG_CONSISTENCY_VALIDATE",json,
                (task,id,context) -> service.executeValidationAsync(id,task.getId(),task.getResourceVersion(),context));
    }

    @Bean
    TaskHandler spaceFileDeleteTaskHandler(ObjectMapper json, SpaceFileService service) {
        return handler("SPACE_FILE_DELETE",json,(task,id,context) -> service.executeDeleteBatch(id));
    }

    @Bean
    TaskHandler spaceFileImportTaskHandler(ObjectMapper json, SpaceFileImportBatchService service) {
        return handler("SPACE_FILE_IMPORT",json,(task,id,context) -> service.executeBatch(id));
    }

    private TaskHandler handler(String taskType,ObjectMapper json,IdExecution execution) {
        return new TaskHandler() {
            @Override public String taskType() { return taskType; }

            @Override
            public Object execute(UnifiedAsyncTask task,TaskExecutionContext context) throws Exception {
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
