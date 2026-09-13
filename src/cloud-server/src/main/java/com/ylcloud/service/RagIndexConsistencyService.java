package com.ylcloud.service;

import com.ylcloud.entity.SpaceRagDocument;
import com.ylcloud.mapper.SpaceRagChunkRefMapper;
import com.ylcloud.mapper.SpaceRagDocumentMapper;
import com.ylcloud.service.rag.QdrantVectorStoreService;
import com.ylcloud.async.mq.AsyncMqProperties;
import com.ylcloud.async.task.DomainTaskPayload;
import com.ylcloud.async.task.StaleTaskException;
import com.ylcloud.async.task.TaskCreateCommand;
import com.ylcloud.async.task.UnifiedTaskCenterService;
import com.ylcloud.async.worker.TaskExecutionContext;
import com.ylcloud.entity.UnifiedAsyncTask;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MySQL 与 Qdrant 的持久化补偿和定期对账。
 */
@Service
public class RagIndexConsistencyService {
    private static final Logger log = LoggerFactory.getLogger(RagIndexConsistencyService.class);
    private static final int CLEANUP_BATCH_SIZE = 200;

    private final SpaceRagDocumentMapper documentMapper;
    private final SpaceRagChunkRefMapper chunkRefMapper;
    private final RagIndexTransactionService transactionService;
    private final QdrantVectorStoreService vectorStoreService;
    private final AtomicBoolean running = new AtomicBoolean();
    private AsyncMqProperties mqProperties;
    private UnifiedTaskCenterService taskCenter;

    public RagIndexConsistencyService(SpaceRagDocumentMapper documentMapper,
                                      SpaceRagChunkRefMapper chunkRefMapper,
                                      RagIndexTransactionService transactionService,
                                      QdrantVectorStoreService vectorStoreService) {
        this.documentMapper = documentMapper;
        this.chunkRefMapper = chunkRefMapper;
        this.transactionService = transactionService;
        this.vectorStoreService = vectorStoreService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOnStartup() {
        reconcile();
    }

    @Scheduled(fixedDelayString = "${ylcloud.rag.index.reconcile-delay-ms:300000}",
            initialDelayString = "${ylcloud.rag.index.reconcile-initial-delay-ms:30000}")
    @Transactional
    public void reconcile() {
        if(!running.compareAndSet(false,true)) {
            return;
        }
        try {
            if(mqMaintenanceEnabled()) {
                documentMapper.listCleanupPending(CLEANUP_BATCH_SIZE).forEach(document -> enqueue(document,"RAG_CONSISTENCY_CLEANUP"));
                documentMapper.listActiveVectorDocuments().forEach(document -> enqueue(document,"RAG_CONSISTENCY_VALIDATE"));
                return;
            }
            cleanupPendingDocuments();
            validateActiveDocuments();
        } finally {
            running.set(false);
        }
    }

    public boolean cleanupDocumentIfPending(SpaceRagDocument document) {
        if(document == null || !"CLEANUP_PENDING".equals(document.getVectorState())) {
            return true;
        }
        return cleanupDocument(document);
    }

    public Map<String,Object> executeCleanupAsync(Long documentId,Long asyncTaskId,long version,
                                                  TaskExecutionContext context) {
        SpaceRagDocument document = requireCurrent(documentId,asyncTaskId,version);
        if("CLEAN".equals(document.getVectorState())) return Map.of("alreadyClean",true);
        if(!"CLEANUP_PENDING".equals(document.getVectorState())) throw new StaleTaskException("RAG 清理状态已变化");
        cleanupStrict(document,context);
        return Map.of("cleaned",true);
    }

    public Map<String,Object> executeValidationAsync(Long documentId,Long asyncTaskId,long version,
                                                     TaskExecutionContext context) {
        SpaceRagDocument document = requireCurrent(documentId,asyncTaskId,version);
        if(document.getStatus() == null || document.getStatus() != 1
                || !"SUCCESS".equals(document.getIndexStatus()) || !"ACTIVE".equals(document.getVectorState())) {
            throw new StaleTaskException("RAG 文档已不再是活动索引");
        }
        context.checkpoint();
        int activeRefs = chunkRefMapper.countActiveByDocumentId(document.getId());
        if(activeRefs <= 0 || document.getChunkCount() == null || activeRefs != document.getChunkCount()) {
            isolateAndCleanupStrict(document,"RAG database reference count mismatch",context);
            return Map.of("isolated",true,"reason","reference-count");
        }
        int vectorCount = vectorStoreService.countByFileUuidStrict(document.getFileUuid());
        if(vectorCount != activeRefs) {
            isolateAndCleanupStrict(document,"RAG Qdrant vector count mismatch: expected " + activeRefs + ", got " + vectorCount,context);
            return Map.of("isolated",true,"reason","vector-count");
        }
        context.checkpoint();
        if(documentMapper.completeConsistencyValidation(documentId,version,asyncTaskId) != 1) {
            throw new StaleTaskException("RAG 对账结果被版本栅栏拒绝");
        }
        return Map.of("validatedVectors",vectorCount);
    }

    private void cleanupPendingDocuments() {
        for(SpaceRagDocument document : documentMapper.listCleanupPending(CLEANUP_BATCH_SIZE)) {
            cleanupDocument(document);
        }
    }

    private boolean cleanupDocument(SpaceRagDocument document) {
        if(hasActiveAsyncTask(document.getConsistencyAsyncTaskId())) return false;
        if(!transactionService.claimCleanup(document.getId())) {
            return false;
        }
        try {
            vectorStoreService.deleteBySpaceFileStrict(document.getSpaceId(),document.getSpaceFileId());
            transactionService.completeCleanup(document.getId());
            return true;
        } catch (Throwable ex) {
            transactionService.releaseCleanup(document.getId());
            log.error("RAG vector compensation failed: documentId={}, spaceId={}, spaceFileId={}",
                    document.getId(),document.getSpaceId(),document.getSpaceFileId(),ex);
            return false;
        }
    }

    private void cleanupStrict(SpaceRagDocument document,TaskExecutionContext context) {
        if(!transactionService.claimCleanup(document.getId())) throw new StaleTaskException("RAG 清理已被其他执行领取");
        try {
            context.checkpoint();
            vectorStoreService.deleteBySpaceFileStrict(document.getSpaceId(),document.getSpaceFileId());
            context.checkpoint();
            transactionService.completeCleanup(document.getId());
        } catch(RuntimeException exception) {
            transactionService.releaseCleanup(document.getId());
            throw exception;
        }
    }

    private void isolateAndCleanupStrict(SpaceRagDocument document,String reason,TaskExecutionContext context) {
        if(!transactionService.failActiveIndex(document.getId(),reason)) throw new StaleTaskException("RAG 索引状态已变化");
        SpaceRagDocument failed = documentMapper.getAnyById(document.getId());
        if(failed != null) cleanupStrict(failed,context);
    }

    private void validateActiveDocuments() {
        for(SpaceRagDocument document : documentMapper.listActiveVectorDocuments()) {
            if(hasActiveAsyncTask(document.getConsistencyAsyncTaskId())) continue;
            int activeRefs = chunkRefMapper.countActiveByDocumentId(document.getId());
            if(activeRefs <= 0 || document.getChunkCount() == null || activeRefs != document.getChunkCount()) {
                invalidateAndCleanup(document,"RAG database reference count mismatch");
                continue;
            }
            try {
                int vectorCount = vectorStoreService.countByFileUuidStrict(document.getFileUuid());
                if(vectorCount != activeRefs) {
                    invalidateAndCleanup(document,"RAG Qdrant vector count mismatch: expected " + activeRefs + ", got " + vectorCount);
                }
            } catch (Throwable ex) {
                // Qdrant 暂时不可用时不破坏一个已验证过的索引；下一轮继续对账。
                log.warn("RAG vector reconciliation deferred: documentId={}, spaceId={}",
                        document.getId(),document.getSpaceId(),ex);
            }
        }
    }

    private void invalidateAndCleanup(SpaceRagDocument document, String reason) {
        if(!transactionService.failActiveIndex(document.getId(),reason)) {
            return;
        }
        SpaceRagDocument failed = documentMapper.getAnyById(document.getId());
        if(failed != null) {
            cleanupDocument(failed);
        }
        log.error("RAG cross-store inconsistency detected and isolated: documentId={}, reason={}",
                document.getId(),reason);
    }

    private SpaceRagDocument requireCurrent(Long documentId,Long asyncTaskId,long version) {
        SpaceRagDocument document = documentMapper.getAnyById(documentId);
        if(document == null) throw new StaleTaskException("RAG 文档已删除");
        long current = document.getConsistencyVersion() == null ? 1 : document.getConsistencyVersion();
        if(current != version || !asyncTaskId.equals(document.getConsistencyAsyncTaskId())) {
            throw new StaleTaskException("RAG 文档资源版本已变化");
        }
        return document;
    }

    private void enqueue(SpaceRagDocument document,String taskType) {
        long version = document.getConsistencyVersion() == null ? 1 : document.getConsistencyVersion();
        UnifiedAsyncTask task = taskCenter.createTask(new TaskCreateCommand(
                "rag-consistency:" + taskType + ":" + document.getId() + ":" + version,
                "maintenance",taskType,new DomainTaskPayload(document.getId()),document.getCreatedBy(),document.getSpaceId(),
                "rag-document:" + document.getId(),version));
        documentMapper.bindConsistencyTask(document.getId(),version,task.getId());
    }

    @Autowired(required=false)
    public void setAsyncTaskInfrastructure(AsyncMqProperties mqProperties,UnifiedTaskCenterService taskCenter) {
        this.mqProperties=mqProperties;
        this.taskCenter=taskCenter;
    }

    private boolean mqMaintenanceEnabled() {
        return mqProperties != null && taskCenter != null && mqProperties.isEnabled() && mqProperties.isMaintenance();
    }

    private boolean hasActiveAsyncTask(Long taskId) {
        return taskCenter != null && taskCenter.isActive(taskId);
    }
}
