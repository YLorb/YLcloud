package com.ylcloud.service;

import com.ylcloud.utils.MinioclientUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.mockito.Mockito.*;

class CrossStoreCompensationServiceTest {
    private final MinioclientUtil minio = mock(MinioclientUtil.class);
    private final CrossStoreCompensationService service = new CrossStoreCompensationService(minio);

    @AfterEach
    void cleanupSynchronization() {
        if(TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void removesOnlyTheCreatedVersionAfterRollback() throws Exception {
        TransactionSynchronizationManager.initSynchronization();
        service.removeNewVersionOnRollback("file","version-2");

        TransactionSynchronizationManager.getSynchronizations().forEach(
                sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(minio).removeObjectVersion("file","version-2");
        verify(minio,never()).removeObjectAllVersions(anyString());
    }

    @Test
    void keepsExternalWriteAfterCommit() throws Exception {
        TransactionSynchronizationManager.initSynchronization();
        service.removeNewObjectOnRollback("file");

        TransactionSynchronizationManager.getSynchronizations().forEach(
                sync -> sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));

        verifyNoInteractions(minio);
    }
}
