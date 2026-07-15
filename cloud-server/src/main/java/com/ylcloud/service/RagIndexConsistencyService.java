package com.ylcloud.service;

import com.ylcloud.entity.SpaceRagDocument;
import com.ylcloud.mapper.SpaceRagChunkRefMapper;
import com.ylcloud.mapper.SpaceRagDocumentMapper;
import com.ylcloud.service.rag.QdrantVectorStoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

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
    public void reconcile() {
        if(!running.compareAndSet(false,true)) {
            return;
        }
        try {
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

    private void cleanupPendingDocuments() {
        for(SpaceRagDocument document : documentMapper.listCleanupPending(CLEANUP_BATCH_SIZE)) {
            cleanupDocument(document);
        }
    }

    private boolean cleanupDocument(SpaceRagDocument document) {
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

    private void validateActiveDocuments() {
        for(SpaceRagDocument document : documentMapper.listActiveVectorDocuments()) {
            int activeRefs = chunkRefMapper.countActiveByDocumentId(document.getId());
            if(activeRefs <= 0 || document.getChunkCount() == null || activeRefs != document.getChunkCount()) {
                invalidateAndCleanup(document,"RAG database reference count mismatch");
                continue;
            }
            try {
                int vectorCount = vectorStoreService.countByDocumentStrict(document.getSpaceId(),document.getId());
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
}
