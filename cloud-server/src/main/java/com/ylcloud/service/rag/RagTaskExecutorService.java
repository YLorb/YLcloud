package com.ylcloud.service.rag;

import com.ylcloud.service.SpaceRagService;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Async boundary for RAG indexing tasks.
 */
@Service
public class RagTaskExecutorService {
    private final SpaceRagService spaceRagService;

    public RagTaskExecutorService(@Lazy SpaceRagService spaceRagService) {
        this.spaceRagService = spaceRagService;
    }

    @Async("ragTaskExecutor")
    public void runFileTask(Long taskId, Long documentId, Long userId) {
        spaceRagService.executeFileRagTask(taskId,documentId,userId);
    }

    @Async("ragTaskExecutor")
    public void runSpaceTask(Long taskId, Long spaceId, Long userId) {
        spaceRagService.executeSpaceRagTask(taskId,spaceId,userId);
    }
}
