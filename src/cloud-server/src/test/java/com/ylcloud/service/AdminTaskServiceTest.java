package com.ylcloud.service;

import com.ylcloud.Exception.BaseException;
import com.ylcloud.Exception.ConflictException;
import com.ylcloud.mapper.AdminTaskMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AdminTaskServiceTest {
    private final AdminTaskMapper mapper = mock(AdminTaskMapper.class);
    private final SecurityAuditService audit = mock(SecurityAuditService.class);
    private final AdminTaskService service = new AdminTaskService(mapper, audit);

    @Test void validatesPaginationBeforeQuery() {
        assertThrows(BaseException.class, () -> service.page(false, null, null, null, 0, 20));
        assertThrows(BaseException.class, () -> service.page(false, null, null, null, 1, 101));
        verifyNoInteractions(mapper);
    }
    @Test void passesServerSideFiltersAndUsesLongOffset() {
        service.page(true, " FAILED ", " TYPE ", 7L, Integer.MAX_VALUE, 100);
        verify(mapper).page(true, "FAILED", "TYPE", 7L, 100, 214748364600L);
        verify(mapper).count(true, "FAILED", "TYPE", 7L);
    }
    @Test void archivesOnlyWhenDatabaseConditionalUpdateSucceeds() {
        when(mapper.archive(8L, 7L)).thenReturn(1);
        service.archive(8L, 7L);
        verify(audit).recordCritical(any(SecurityAuditService.AuditEventBuilder.class));
    }
    @Test void rejectsRunningMissingOrAlreadyArchivedTask() {
        when(mapper.archive(8L, 7L)).thenReturn(0);
        assertThrows(ConflictException.class, () -> service.archive(8L, 7L));
        verifyNoInteractions(audit);
    }
}
