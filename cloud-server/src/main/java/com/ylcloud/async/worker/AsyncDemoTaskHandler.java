package com.ylcloud.async.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.DTO.AsyncDemoCreateDTO;
import com.ylcloud.async.task.FatalTaskException;
import com.ylcloud.async.task.RetryableTaskException;
import com.ylcloud.entity.UnifiedAsyncTask;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class AsyncDemoTaskHandler implements TaskHandler {
    private final ObjectMapper objectMapper;

    public AsyncDemoTaskHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String taskType() {
        return "ASYNC_DEMO";
    }

    @Override
    public Object execute(UnifiedAsyncTask task, TaskExecutionContext context) throws Exception {
        AsyncDemoCreateDTO payload = objectMapper.readValue(task.getPayloadJson(),AsyncDemoCreateDTO.class);
        int delay = payload.getDelayMs() == null ? 0 : payload.getDelayMs();
        int elapsed = 0;
        while(elapsed < delay) {
            int slice = Math.min(1000,delay - elapsed);
            Thread.sleep(slice);
            elapsed += slice;
            context.checkpoint();
        }
        return switch(payload.getMode()) {
            case "RETRYABLE" -> throw new RetryableTaskException("demo temporary failure token=secret-value");
            case "FATAL" -> throw new FatalTaskException("demo fatal validation failure");
            case "TIMEOUT" -> throw new RetryableTaskException("demo timeout");
            default -> Map.of("echo",payload.getText(),"delayMs",delay,"attempt",task.getAttemptVersion());
        };
    }
}
