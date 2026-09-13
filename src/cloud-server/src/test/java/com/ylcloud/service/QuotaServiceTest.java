package com.ylcloud.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.DTO.QuotaPolicyUpdateDTO;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.entity.File;
import com.ylcloud.entity.QuotaAccount;
import com.ylcloud.entity.QuotaBlobReference;
import com.ylcloud.entity.QuotaPolicy;
import com.ylcloud.mapper.FileInfoMapper;
import com.ylcloud.mapper.QuotaMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QuotaServiceTest {
    private final QuotaMapper mapper=mock(QuotaMapper.class);
    private final FileInfoMapper files=mock(FileInfoMapper.class);
    private final QuotaService service=new QuotaService(mapper,files,new ObjectMapper());
    private final QuotaAccount account=account();
    private final QuotaPolicy policy=policy();

    @BeforeEach
    void setup() {
        when(mapper.getAccount("USER",7L)).thenReturn(account);
        when(mapper.getAccount("TEAM",9L)).thenReturn(account);
        when(mapper.getPolicy(2L)).thenReturn(policy);
        when(mapper.ensureUsage(anyLong(),any(),any())).thenReturn(1);
    }

    @Test
    void apiCounterRejectsAtomicallyAtMonthlyLimit() {
        when(mapper.consumeApi(eq(1L),any(),eq(1L),eq(100L),any())).thenReturn(0);

        BaseException error=assertThrows(BaseException.class,()->service.consumeApiCall(7L));

        assertEquals(429,error.getStatusCode());
    }

    @Test
    void agentReservationUsesShanghaiNaturalMonthAndSingleAtomicUpdate() {
        when(mapper.reserveAgent(eq(1L),any(),eq(20L),eq(3L),any())).thenReturn(1);

        service.reserveAgentTask(7L);

        ArgumentCaptor<LocalDate> period=ArgumentCaptor.forClass(LocalDate.class);
        verify(mapper).reserveAgent(eq(1L),period.capture(),eq(20L),eq(3L),any());
        assertEquals(1,period.getValue().getDayOfMonth());
    }

    @Test
    void globallyKnownBlobAddsReferenceWithoutChargingAgain() {
        File file=physical();
        when(files.getFileByFileUuid("file-1",7L)).thenReturn(file);
        when(mapper.blobOwner("sha256")).thenReturn(88L);
        when(mapper.insertReference(eq("USER_FILE"),eq(11L),eq(1L),eq("sha256"),eq("file-1"),eq(20L),any())).thenReturn(1);

        service.recordUserFile(11L,7L,"file-1");

        verify(mapper,never()).insertBlob(any(),any(),anyLong(),anyLong(),any());
        verify(mapper).incrementBlob(eq("sha256"),any());
    }

    @Test
    void globallyKnownPhysicalFileNeedsNoAdditionalUserBytes() {
        when(files.getFileByFileUuid("file-1",7L)).thenReturn(physical());
        when(mapper.blobOwner("sha256")).thenReturn(88L);

        assertEquals(0L,service.additionalUserBytes(7L,"file-1",20L));
    }

    @Test
    void newBlobIsConservativelyRejectedWhenAccountWouldOverflow() {
        when(mapper.getAccount("TEAM",9L)).thenReturn(account);
        when(mapper.blobOwner("new-hash")).thenReturn(null);
        when(mapper.storageUsed(1L)).thenReturn(95L);
        policy.setStorageBytes(100L);

        BaseException error=assertThrows(BaseException.class,()->service.requireTeamStorage(9L,"new-hash",20L));

        assertEquals(429,error.getStatusCode());
    }

    @Test
    void releasingReferenceReassignsOwnershipAndRemovesLastBlob() {
        QuotaBlobReference reference=new QuotaBlobReference();
        reference.setActive(true); reference.setContentKey("sha256");
        when(mapper.lockReference("SPACE_FILE",11L)).thenReturn(reference);
        when(mapper.deactivateReference(eq("SPACE_FILE"),eq(11L),any())).thenReturn(1);

        service.releaseReference("SPACE_FILE",11L);

        verify(mapper).decrementBlob(eq("sha256"),any());
        verify(mapper).reassignBlobOwner(eq("sha256"),any());
        verify(mapper).deleteUnreferencedBlob("sha256");
    }

    @Test
    void groupPolicyUpdatePersistsEveryDimension() {
        when(mapper.groupExists(2L)).thenReturn(1);
        when(mapper.upsertPolicy(any(),any())).thenReturn(1);
        QuotaPolicyUpdateDTO dto=new QuotaPolicyUpdateDTO();
        dto.setStorageBytes(1L); dto.setMaxFileBytes(2L); dto.setSpaceLimit(3L); dto.setMonthlyApiCalls(4L);
        dto.setMonthlyModelTokens(5L); dto.setMonthlyAgentTasks(6L); dto.setConcurrentAgentTasks(7L);

        service.updateGroupPolicy(2L,dto);

        ArgumentCaptor<QuotaPolicy> saved=ArgumentCaptor.forClass(QuotaPolicy.class);
        verify(mapper).upsertPolicy(saved.capture(),any());
        assertEquals(7L,saved.getValue().getConcurrentAgentTasks());
    }

    @Test
    void reconciliationRecordsRepairEvidence() {
        when(mapper.countLedgerDifferences()).thenReturn(2L);
        when(mapper.deleteOrphanLedgers()).thenReturn(1);
        when(mapper.repairLedgerCounts(any())).thenReturn(3);

        service.reconcile();

        verify(mapper).insertReconcileEvidence(eq("SUCCEEDED"),eq(2L),any(),any(),any());
    }

    private QuotaAccount account() { QuotaAccount value=new QuotaAccount(); value.setId(1L); value.setAccountType("USER"); value.setReferenceId(7L); value.setGroupId(2L); return value; }
    private QuotaPolicy policy() { QuotaPolicy value=new QuotaPolicy(); value.setStorageBytes(1000L); value.setMaxFileBytes(50L); value.setSpaceLimit(5L); value.setMonthlyApiCalls(100L); value.setMonthlyModelTokens(1000L); value.setMonthlyAgentTasks(20L); value.setConcurrentAgentTasks(3L); return value; }
    private File physical() { File value=new File(); value.setFileUuid("file-1"); value.setHash("sha256"); value.setSize(20L); return value; }
}
