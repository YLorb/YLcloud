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

    /**
     * 初始化 RagTaskExecutorService 对象。
     *
     * @param spaceRagService 空间 RAG 服务
     */
    public RagTaskExecutorService(@Lazy SpaceRagService spaceRagService) {
        this.spaceRagService = spaceRagService;
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
        spaceRagService.executeSpaceRagTask(taskId,spaceId,userId,true);
    }
}
