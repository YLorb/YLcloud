package com.ylcloud.service;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class KnowledgePipelineExecutorService {
    private final KnowledgePipelineService knowledgePipelineService;

    public KnowledgePipelineExecutorService(KnowledgePipelineService knowledgePipelineService) {
        this.knowledgePipelineService = knowledgePipelineService;
    }

    @Async("ragTaskExecutor")
    public void runDocumentTask(Long taskId) {
        knowledgePipelineService.executeTask(taskId);
    }

    @Async("ragTaskExecutor")
    public void runSpaceTask(Long taskId) {
        knowledgePipelineService.executeSpaceTask(taskId);
    }
}
