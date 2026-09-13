package com.ylcloud.service;

import com.ylcloud.Exception.ConflictException;
import com.ylcloud.entity.CrossStoreOperation;
import com.ylcloud.mapper.CrossStoreOperationMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.function.Supplier;

@Service
public class CrossStoreOperationService {
    public static final String SUCCESS = "SUCCESS";
    private final CrossStoreOperationMapper mapper;
    private final long leaseSeconds;

    public CrossStoreOperationService(CrossStoreOperationMapper mapper,
                                      @Value("${ylcloud.cross-store.lease-seconds:300}") long leaseSeconds) {
        this.mapper = mapper;
        this.leaseSeconds = Math.max(30,leaseSeconds);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CrossStoreOperation claim(String operationKey, String operationType, String payloadHash, String resourceId) {
        LocalDateTime now = LocalDateTime.now();
        mapper.insertIfAbsent(operationKey,operationType,payloadHash,resourceId,now);
        CrossStoreOperation operation = mapper.get(operationKey);
        if(operation == null || !operationType.equals(operation.getOperationType())
                || !payloadHash.equals(operation.getPayloadHash())) {
            throw new ConflictException("幂等操作键与请求内容不匹配");
        }
        if(SUCCESS.equals(operation.getOperationStatus())) {
            return operation;
        }
        if(mapper.claim(operationKey,now.plusSeconds(leaseSeconds),now) == 0) {
            throw new ConflictException("相同操作正在执行，请稍后查询结果");
        }
        return mapper.get(operationKey);
    }

    public CrossStoreOperation get(String operationKey) {
        return mapper.get(operationKey);
    }

    public CrossStoreOperation getById(Long id) { return mapper.getById(id); }

    public int bindRecoveryTask(Long id,Integer attempt,Long asyncTaskId,LocalDateTime now) {
        return mapper.bindRecoveryTask(id,attempt,asyncTaskId,now);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordResultCandidate(String operationKey, String resultRef) {
        mapper.recordResultCandidate(operationKey,resultRef,LocalDateTime.now());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordExternalRef(String operationKey, String externalRef) {
        mapper.recordExternalRef(operationKey,externalRef,LocalDateTime.now());
    }

    public java.util.List<CrossStoreOperation> listStaleRunning(int limit) {
        return mapper.listStaleRunning(LocalDateTime.now(),limit);
    }

    public void completeAfterCommit(String operationKey, String resultRef) {
        completeAfterCommit(operationKey,() -> resultRef);
    }

    public void completeAfterCommit(String operationKey, Supplier<String> resultRefSupplier) {
        if(!TransactionSynchronizationManager.isSynchronizationActive()) {
            markSuccess(operationKey,resultRefSupplier.get());
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                markSuccess(operationKey,resultRefSupplier.get());
            }

            @Override
            public void afterCompletion(int status) {
                if(status != STATUS_COMMITTED) {
                    markFailed(operationKey,"Database transaction rolled back");
                }
            }
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSuccess(String operationKey, String resultRef) {
        mapper.markSuccess(operationKey,resultRef,LocalDateTime.now());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(String operationKey, String errorMessage) {
        mapper.markFailed(operationKey,truncate(errorMessage),LocalDateTime.now());
    }

    public static String key(String namespace, Object... parts) {
        StringBuilder source = new StringBuilder(namespace);
        for(Object part : parts) {
            source.append('\n').append(part == null ? "" : part);
        }
        return namespace + ":" + sha256(source.toString());
    }

    public static String payloadHash(Object... parts) {
        StringBuilder source = new StringBuilder();
        for(Object part : parts) {
            source.append(part == null ? "" : part).append('\n');
        }
        return sha256(source.toString());
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to calculate operation key",ex);
        }
    }

    private String truncate(String value) {
        if(value == null || value.isBlank()) return "Cross-store operation failed";
        return value.length() <= 1000 ? value : value.substring(0,1000);
    }
}
