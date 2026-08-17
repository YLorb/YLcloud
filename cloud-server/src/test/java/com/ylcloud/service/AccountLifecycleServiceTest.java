package com.ylcloud.service;

import com.ylcloud.Exception.ConflictException;
import com.ylcloud.Exception.ForbiddenException;
import com.ylcloud.entity.AccountDeletionJob;
import com.ylcloud.entity.User;
import com.ylcloud.mapper.AccessControlMapper;
import com.ylcloud.mapper.AccountRecoveryLogMapper;
import com.ylcloud.mapper.UserLifecycleMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountLifecycleServiceTest {
    @Test
    void forcePurgeIsDisabledByDefaultConfiguration() {
        Fixture fixture = new Fixture(false);

        assertThrows(ForbiddenException.class,
                () -> fixture.service.forcePurgeTestAccount(1L, 9L, "e2e_course_run_1"));

        verify(fixture.deletionService, never()).submitDeletionJob(any());
    }

    @Test
    void forcePurgeRejectsOrdinaryUsername() {
        Fixture fixture = new Fixture(true);
        fixture.stubAdmin();
        User target = fixture.user(9L, "ordinary_user", "USER", "CANCELLED");
        when(fixture.lifecycleMapper.lockById(9L)).thenReturn(target);

        assertThrows(ForbiddenException.class,
                () -> fixture.service.forcePurgeTestAccount(1L, 9L, "ordinary_user"));

        verify(fixture.deletionService, never()).submitDeletionJob(any());
    }

    @Test
    void forcePurgeRejectsTestAccountThatStillOwnsTeam() {
        Fixture fixture = new Fixture(true);
        fixture.stubAdmin();
        User target = fixture.user(9L, "e2e_course_run_1", "USER", "CANCELLED");
        when(fixture.lifecycleMapper.lockById(9L)).thenReturn(target);
        when(fixture.lifecycleMapper.countOwnedTeams(9L)).thenReturn(1);

        assertThrows(ConflictException.class,
                () -> fixture.service.forcePurgeTestAccount(1L, 9L, "e2e_course_run_1"));

        verify(fixture.deletionService, never()).submitDeletionJob(any());
    }

    @Test
    void forcePurgeSubmitsExistingDeletionOrchestrationForExactCancelledTestUser() {
        Fixture fixture = new Fixture(true);
        fixture.stubAdmin();
        User target = fixture.user(9L, "e2e_course_run_1", "USER", "CANCELLED");
        User purging = fixture.user(9L, "e2e_course_run_1", "USER", "PURGING");
        when(fixture.lifecycleMapper.lockById(9L)).thenReturn(target);
        when(fixture.lifecycleMapper.countOwnedTeams(9L)).thenReturn(0);
        when(fixture.lifecycleMapper.markPurging(org.mockito.ArgumentMatchers.eq(9L), any(), any())).thenReturn(1);
        when(fixture.lifecycleMapper.getAccountStatus(9L)).thenReturn(purging);
        AccountDeletionJob job = new AccountDeletionJob();
        job.setId(77L);
        when(fixture.deletionService.submitDeletionJob(9L)).thenReturn(job);

        assertEquals("PURGING", fixture.service
                .forcePurgeTestAccount(1L, 9L, "e2e_course_run_1")
                .getAccountStatus());

        verify(fixture.deletionService).submitDeletionJob(9L);
    }

    private static class Fixture {
        final UserLifecycleMapper lifecycleMapper = mock(UserLifecycleMapper.class);
        final AccountRecoveryLogMapper recoveryMapper = mock(AccountRecoveryLogMapper.class);
        final AccessControlMapper auditMapper = mock(AccessControlMapper.class);
        final AccountDeletionOrchestrationService deletionService = mock(AccountDeletionOrchestrationService.class);
        final SecurityAuditService securityAudit = mock(SecurityAuditService.class);
        final AccountLifecycleService service;

        Fixture(boolean allowTestAccountPurge) {
            service = new AccountLifecycleService(lifecycleMapper, recoveryMapper, auditMapper,
                    deletionService, securityAudit, allowTestAccountPurge);
        }

        void stubAdmin() {
            when(lifecycleMapper.lockById(1L)).thenReturn(user(1L, "admin", "ADMIN", "ACTIVE"));
        }

        User user(Long id, String username, String role, String accountStatus) {
            User user = new User();
            user.setId(id);
            user.setUsername(username);
            user.setRole(role);
            user.setStatus("ACTIVE".equals(accountStatus) ? 1 : 0);
            user.setAccountStatus(accountStatus);
            user.setDeploymentOwner(false);
            return user;
        }
    }
}
