package com.ylcloud.service;

import com.ylcloud.DTO.UserFileDTO;
import com.ylcloud.entity.CrossStoreOperation;
import com.ylcloud.entity.FileVersion;
import com.ylcloud.entity.SpaceFile;
import com.ylcloud.mapper.FileInfoMapper;
import com.ylcloud.mapper.FileVersionMapper;
import com.ylcloud.mapper.SpaceFileMapper;
import com.ylcloud.utils.MinioclientUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** 对进程中断留下的跨存储 RUNNING 操作进行数据库对账或外部存储补偿。 */
@Service
@Slf4j
public class CrossStoreRecoveryService {
    private final CrossStoreOperationService operationService;
    private final FileInfoMapper fileInfoMapper;
    private final SpaceFileMapper spaceFileMapper;
    private final FileVersionMapper fileVersionMapper;
    private final MinioclientUtil minioclientUtil;

    public CrossStoreRecoveryService(CrossStoreOperationService operationService,
                                     FileInfoMapper fileInfoMapper,
                                     SpaceFileMapper spaceFileMapper,
                                     FileVersionMapper fileVersionMapper,
                                     MinioclientUtil minioclientUtil) {
        this.operationService = operationService;
        this.fileInfoMapper = fileInfoMapper;
        this.spaceFileMapper = spaceFileMapper;
        this.fileVersionMapper = fileVersionMapper;
        this.minioclientUtil = minioclientUtil;
    }

    @Scheduled(fixedDelayString = "${ylcloud.cross-store.recovery-delay-ms:60000}",
            initialDelayString = "${ylcloud.cross-store.recovery-initial-delay-ms:60000}")
    public void reconcileStaleOperations() {
        for(CrossStoreOperation operation : operationService.listStaleRunning(100)) {
            reconcile(operation);
        }
    }

    private void reconcile(CrossStoreOperation operation) {
        try {
            if(resultExists(operation)) {
                operationService.markSuccess(operation.getOperationKey(),operation.getResultRef());
                log.info("跨存储操作已按数据库结果对账成功: {}",operation.getOperationKey());
                return;
            }
            compensateExternalWrite(operation);
            operationService.markFailed(operation.getOperationKey(),"Recovered stale operation and compensated external write");
        } catch (Exception ex) {
            log.warn("跨存储操作恢复失败: {}",operation.getOperationKey(),ex);
        }
    }

    private boolean resultExists(CrossStoreOperation operation) {
        if(operation.getResultRef() == null || !operation.getResultRef().matches("\\d+")) {
            return false;
        }
        Long resultId = Long.valueOf(operation.getResultRef());
        return switch(operation.getOperationType()) {
            case "PERSONAL_UPLOAD", "EMPTY_FILE" -> {
                UserFileDTO node = fileInfoMapper.getByFileIdAny(resultId);
                yield node != null;
            }
            case "SPACE_UPLOAD", "SPACE_GENERATED" -> {
                SpaceFile node = spaceFileMapper.getByIdAny(resultId);
                yield node != null;
            }
            case "VERSION_UPLOAD", "VERSION_RESTORE" -> {
                FileVersion version = fileVersionMapper.getByIdAndFileUuid(resultId,operation.getResourceId());
                yield version != null;
            }
            default -> false;
        };
    }

    private void compensateExternalWrite(CrossStoreOperation operation) throws Exception {
        if("VERSION_UPLOAD".equals(operation.getOperationType())
                || "VERSION_RESTORE".equals(operation.getOperationType())) {
            if(operation.getExternalRef() != null && !operation.getExternalRef().isBlank()) {
                minioclientUtil.removeObjectVersion(operation.getResourceId(),operation.getExternalRef());
            } else if(fileInfoMapper.getFileInfo(operation.getResourceId(),0L) == null) {
                // A restore-as-copy can crash after creating the reserved object but before
                // its version id is persisted. The reserved UUID is not referenced yet, so
                // deleting every version is safe and makes the retry converge.
                minioclientUtil.removeObjectAllVersions(operation.getResourceId());
            }
            return;
        }
        if(("PERSONAL_UPLOAD".equals(operation.getOperationType())
                || "EMPTY_FILE".equals(operation.getOperationType())
                || "SPACE_UPLOAD".equals(operation.getOperationType())
                || "SPACE_GENERATED".equals(operation.getOperationType()))
                && operation.getResourceId() != null
                && fileInfoMapper.getFileInfo(operation.getResourceId(),0L) == null) {
            minioclientUtil.removeObjectAllVersions(operation.getResourceId());
        }
    }
}
