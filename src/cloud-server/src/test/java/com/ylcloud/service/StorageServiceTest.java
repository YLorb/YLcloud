package com.ylcloud.service;

import com.ylcloud.Exception.BaseException;
import com.ylcloud.entity.User;
import com.ylcloud.mapper.FileInfoMapper;
import com.ylcloud.mapper.LoginMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StorageServiceTest {
    private final FileInfoMapper fileInfoMapper = mock(FileInfoMapper.class);
    private final LoginMapper loginMapper = mock(LoginMapper.class);
    private final SiteSettingService settings = mock(SiteSettingService.class);
    private final StorageService service = new StorageService(fileInfoMapper,loginMapper,settings);

    @Test
    void quotaUsesUserRoleConfiguredLimit() {
        when(loginMapper.getById(7L)).thenReturn(user("USER"));
        when(settings.getLong(SiteSettingService.STORAGE_DEFAULT_USER_QUOTA_BYTES,StorageService.DEFAULT_USER_TOTAL_BYTES)).thenReturn(StorageService.DEFAULT_USER_TOTAL_BYTES);
        when(settings.getLong(SiteSettingService.STORAGE_USER_QUOTA_BYTES,StorageService.DEFAULT_USER_TOTAL_BYTES)).thenReturn(2_000L);
        when(fileInfoMapper.sumUserStorageBytes(7L)).thenReturn(500L);
        when(fileInfoMapper.countUserStorageFiles(7L)).thenReturn(2);

        var quota = service.quota(7L);

        assertEquals(2_000L,quota.getTotalBytes());
        assertEquals(1_500L,quota.getAvailableBytes());
        assertEquals(25.0,quota.getUsagePercent());
    }

    @Test
    void quotaUsesAdminRoleConfiguredLimit() {
        when(loginMapper.getById(7L)).thenReturn(user("ADMIN"));
        when(settings.getLong(SiteSettingService.STORAGE_ADMIN_QUOTA_BYTES,StorageService.DEFAULT_ADMIN_TOTAL_BYTES)).thenReturn(10_000L);
        when(fileInfoMapper.sumUserStorageBytes(7L)).thenReturn(1_000L);

        var quota = service.quota(7L);

        assertEquals(10_000L,quota.getTotalBytes());
        assertEquals("role-admin-quota",quota.getPolicyName());
    }

    @Test
    void rejectsWriteThatWouldExceedQuota() {
        when(loginMapper.lockUserId(7L)).thenReturn(7L);
        when(loginMapper.getById(7L)).thenReturn(user("USER"));
        when(settings.getLong(SiteSettingService.STORAGE_DEFAULT_USER_QUOTA_BYTES,StorageService.DEFAULT_USER_TOTAL_BYTES)).thenReturn(1_000L);
        when(settings.getLong(SiteSettingService.STORAGE_USER_QUOTA_BYTES,1_000L)).thenReturn(1_000L);
        when(fileInfoMapper.sumUserStorageBytes(7L)).thenReturn(900L);

        assertThrows(BaseException.class,() -> service.requireAvailable(7L,101L));
    }

    @Test
    void deduplicatedReferenceHasNoAdditionalUsage() {
        when(fileInfoMapper.countUserActiveByFileUuid(7L,"file-1")).thenReturn(1);
        assertEquals(0L,service.additionalBytes(7L,"file-1",500L));
    }

    private User user(String role) {
        User user = new User();
        user.setRole(role);
        return user;
    }
}
