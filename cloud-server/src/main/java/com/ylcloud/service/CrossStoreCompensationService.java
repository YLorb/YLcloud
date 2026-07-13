package com.ylcloud.service;

import com.ylcloud.utils.MinioclientUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 注册数据库事务之外的存储补偿动作。
 *
 * <p>MinIO/Qdrant 无法加入 MySQL 本地事务，因此外部写入必须使用预分配资源标识，
 * 并在数据库回滚后执行可重复的反向操作。需要可靠重试的删除由 outbox/任务表承担。</p>
 */
@Service
@Slf4j
public class CrossStoreCompensationService {
    private final MinioclientUtil minioclientUtil;

    public CrossStoreCompensationService(MinioclientUtil minioclientUtil) {
        this.minioclientUtil = minioclientUtil;
    }

    public void removeNewObjectOnRollback(String objectName) {
        registerRollback("MinIO object " + objectName, () -> minioclientUtil.removeObjectAllVersions(objectName));
    }

    public void removeNewVersionOnRollback(String objectName, String versionId) {
        registerRollback("MinIO version " + objectName + "/" + versionId,
                () -> {
                    minioclientUtil.removeObjectVersion(objectName,versionId);
                    return 1;
                });
    }

    private void registerRollback(String resource, ThrowingCleanup cleanup) {
        if(!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("跨存储补偿必须在数据库事务中注册");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if(status != STATUS_ROLLED_BACK) {
                    return;
                }
                try {
                    cleanup.run();
                } catch (Exception ex) {
                    // 这里仅是最后防线；正常业务应以 outbox 处理长期重试。
                    log.error("跨存储回滚补偿失败: {}",resource,ex);
                }
            }
        });
    }

    @FunctionalInterface
    private interface ThrowingCleanup {
        int run() throws Exception;
    }
}
