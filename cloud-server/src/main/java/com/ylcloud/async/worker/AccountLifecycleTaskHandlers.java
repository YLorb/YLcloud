package com.ylcloud.async.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.async.task.DomainTaskPayload;
import com.ylcloud.entity.UnifiedAsyncTask;
import com.ylcloud.service.AccountDeletionOrchestrationService;
import com.ylcloud.service.DataExportService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * TASK-010: 账号删除和数据导出任务处理器。
 */
@Configuration
public class AccountLifecycleTaskHandlers {

    @Bean
    TaskHandler accountDeletionTaskHandler(ObjectMapper json, AccountDeletionOrchestrationService service) {
        return new TaskHandler() {
            @Override
            public String taskType() {
                return "ACCOUNT_DELETION";
            }

            @Override
            public Object execute(UnifiedAsyncTask task, TaskExecutionContext context) throws Exception {
                DomainTaskPayload payload = json.readValue(task.getPayloadJson(), DomainTaskPayload.class);
                if (payload.resourceId() == null) throw new IllegalArgumentException("任务资源 ID 缺失");
                Long jobId = payload.metadata() != null ? ((Number) payload.metadata().get("jobId")).longValue() : null;
                if (jobId == null) throw new IllegalArgumentException("删除任务 ID 缺失");
                return service.executeDeletion(jobId, task.getId(), context);
            }
        };
    }

    @Bean
    TaskHandler dataExportTaskHandler(ObjectMapper json, DataExportService service) {
        return new TaskHandler() {
            @Override
            public String taskType() {
                return "DATA_EXPORT";
            }

            @Override
            public Object execute(UnifiedAsyncTask task, TaskExecutionContext context) throws Exception {
                DomainTaskPayload payload = json.readValue(task.getPayloadJson(), DomainTaskPayload.class);
                if (payload.resourceId() == null) throw new IllegalArgumentException("任务资源 ID 缺失");
                Long jobId = payload.metadata() != null ? ((Number) payload.metadata().get("jobId")).longValue() : null;
                if (jobId == null) throw new IllegalArgumentException("导出任务 ID 缺失");
                return service.executeExport(jobId, task.getId(), context);
            }
        };
    }
}
