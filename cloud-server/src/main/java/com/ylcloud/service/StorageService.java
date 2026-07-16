package com.ylcloud.service;

import com.ylcloud.VO.StorageQuotaVO;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.entity.User;
import com.ylcloud.mapper.FileInfoMapper;
import com.ylcloud.mapper.LoginMapper;
import org.springframework.stereotype.Service;

@Service
public class StorageService {
    public static final long DEFAULT_USER_TOTAL_BYTES = 1024L * 1024L * 1024L;
    public static final long DEFAULT_ADMIN_TOTAL_BYTES = 10L * 1024L * 1024L * 1024L;

    private final FileInfoMapper fileInfoMapper;
    private final LoginMapper loginMapper;
    private final SiteSettingService siteSettingService;

    public StorageService(FileInfoMapper fileInfoMapper, LoginMapper loginMapper, SiteSettingService siteSettingService) {
        this.fileInfoMapper = fileInfoMapper;
        this.loginMapper = loginMapper;
        this.siteSettingService = siteSettingService;
    }

    public StorageQuotaVO quota(Long userId) {
        long used = safeLong(fileInfoMapper.sumUserStorageBytes(userId));
        User user = loginMapper.getById(userId);
        long total = totalBytes(user);
        StorageQuotaVO vo = new StorageQuotaVO();
        vo.setUsedBytes(used);
        vo.setTotalBytes(total);
        vo.setAvailableBytes(Math.max(0,total - used));
        vo.setUsagePercent(total <= 0 ? 0.0 : Math.min(100.0,used * 100.0 / total));
        vo.setFileCount(fileInfoMapper.countUserStorageFiles(userId));
        vo.setPolicyName(isAdmin(user) ? "role-admin-quota" : "role-user-quota");
        return vo;
    }

    public void requireAvailable(Long userId, long additionalBytes) {
        if(additionalBytes <= 0) {
            return;
        }
        if(loginMapper.lockUserId(userId) == null) {
            throw new BaseException("用户不存在，无法校验存储配额");
        }
        long used = safeLong(fileInfoMapper.sumUserStorageBytes(userId));
        User user = loginMapper.getById(userId);
        long total = totalBytes(user);
        if(additionalBytes > total || used > total - additionalBytes) {
            throw new BaseException("存储空间不足，请删除文件或联系管理员调整配额");
        }
    }

    public long additionalBytes(Long userId, String fileUuid, long fileSize) {
        Integer activeReferences = fileUuid == null ? 0 : fileInfoMapper.countUserActiveByFileUuid(userId,fileUuid);
        if(activeReferences != null && activeReferences > 0) {
            return 0L;
        }
        return Math.max(0L,fileSize);
    }

    public long totalBytes(User user) {
        if(isAdmin(user)) {
            return siteSettingService.getLong(SiteSettingService.STORAGE_ADMIN_QUOTA_BYTES,DEFAULT_ADMIN_TOTAL_BYTES);
        }
        long legacyFallback = siteSettingService.getLong(
                SiteSettingService.STORAGE_DEFAULT_USER_QUOTA_BYTES,DEFAULT_USER_TOTAL_BYTES);
        return siteSettingService.getLong(SiteSettingService.STORAGE_USER_QUOTA_BYTES,legacyFallback);
    }

    private boolean isAdmin(User user) {
        return user != null && "ADMIN".equalsIgnoreCase(user.getRole());
    }

    private long safeLong(Long value) {
        return value == null ? 0L : value;
    }
}
