package com.ylcloud.service;

import com.ylcloud.VO.StorageQuotaVO;
import com.ylcloud.mapper.FileInfoMapper;
import org.springframework.stereotype.Service;

@Service
public class StorageService {
    private static final long DEFAULT_TOTAL_BYTES = 10L * 1024L * 1024L * 1024L;

    private final FileInfoMapper fileInfoMapper;

    public StorageService(FileInfoMapper fileInfoMapper) {
        this.fileInfoMapper = fileInfoMapper;
    }

    public StorageQuotaVO quota(Long userId) {
        long used = safeLong(fileInfoMapper.sumUserStorageBytes(userId));
        long total = DEFAULT_TOTAL_BYTES;
        StorageQuotaVO vo = new StorageQuotaVO();
        vo.setUsedBytes(used);
        vo.setTotalBytes(total);
        vo.setAvailableBytes(Math.max(0,total - used));
        vo.setUsagePercent(total <= 0 ? 0.0 : Math.min(100.0,used * 100.0 / total));
        vo.setFileCount(fileInfoMapper.countUserStorageFiles(userId));
        vo.setPolicyName("default-10gb");
        return vo;
    }

    private long safeLong(Long value) {
        return value == null ? 0L : value;
    }
}
