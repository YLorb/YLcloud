package com.ylcloud.service.rag;

import com.ylcloud.async.mq.AsyncMqProperties;
import com.ylcloud.service.SpaceRagService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Async boundary for RAG indexing tasks.
 */
@Service
public class RagTaskExecutorService {
    private final SpaceRagService spaceRagService;
    private AsyncMqProperties mqProperties;

    /**
     * 初始化 RagTaskExecutorService 对象。
     *
     * @param spaceRagService 空间 RAG 服务
     */
    public RagTaskExecutorService(@Lazy SpaceRagService spaceRagService) {
        this.spaceRagService = spaceRagService;
    }

    @Autowired(required = false)
    public void setMqProperties(AsyncMqProperties mqProperties) {
        this.mqProperties=mqProperties;
    }

    private boolean mqEnabled() {
        return mqProperties!=null && mqProperties.isEnabled() && mqProperties.isRag();
    }

    /**
     * 运行 runFileTask 相关逻辑。
     *
     * @param taskId 任务 ID
     * @param documentId 文档 ID
     * @param userId 用户 ID
     */
    @Async("ragTaskExecutor")
    public void runFileTask(Long taskId, Long documentId, Long userId) {
        if(mqEnabled()) return;
        spaceRagService.executeFileRagTask(taskId,documentId,userId);
    }

    /**
     * 运行 runSpaceTask 相关逻辑。
     *
     * @param taskId 任务 ID
     * @param spaceId 空间 ID
     * @param userId 用户 ID
     */
    @Async("ragTaskExecutor")
    public void runSpaceTask(Long taskId, Long spaceId, Long userId) {
        if(mqEnabled()) return;
        spaceRagService.executeSpaceRagTask(taskId,spaceId,userId);
    }

    /**
     * 运行 runSpaceRepairTask 相关逻辑。
     *
     * @param taskId 任务 ID
     * @param spaceId 空间 ID
     * @param userId 用户 ID
     */
    @Async("ragTaskExecutor")
    public void runSpaceRepairTask(Long taskId, Long spaceId, Long userId) {
        if(mqEnabled()) return;
        spaceRagService.executeSpaceRagTask(taskId,spaceId,userId,true);
    }

    @Async("ragTaskExecutor")
    public void runDeleteFileTask(Long taskId, Long spaceId, Long spaceFileId) {
        if(mqEnabled()) return;
        spaceRagService.executeDeleteFileRagTask(taskId,spaceId,spaceFileId);
    }
}
