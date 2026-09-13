package com.ylcloud.service;

import com.ylcloud.async.mq.AsyncMqProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class KnowledgePipelineExecutorService {
    private final KnowledgePipelineService knowledgePipelineService;
    private AsyncMqProperties mqProperties;

    public KnowledgePipelineExecutorService(KnowledgePipelineService knowledgePipelineService) {
        this.knowledgePipelineService = knowledgePipelineService;
    }

    @Autowired(required = false)
    public void setMqProperties(AsyncMqProperties mqProperties) {
        this.mqProperties=mqProperties;
    }

    @Async("ragTaskExecutor")
    public void runDocumentTask(Long taskId) {
        if(mqKnowledgeEnabled()) return;
        knowledgePipelineService.executeTask(taskId);
    }

    @Async("ragTaskExecutor")
    public void runSpaceTask(Long taskId) {
        if(mqKnowledgeEnabled()) return;
        knowledgePipelineService.executeSpaceTask(taskId);
    }

    private boolean mqKnowledgeEnabled() {
        return mqProperties!=null && mqProperties.isEnabled() && mqProperties.isKnowledge();
    }
}
