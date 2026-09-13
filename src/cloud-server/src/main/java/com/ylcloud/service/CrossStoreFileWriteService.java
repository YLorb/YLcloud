package com.ylcloud.service;

import com.ylcloud.utils.MinioclientUtil;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;

/**
 * MySQL 事务内的 MinIO 写入口。所有新对象/新版本写入都在这里注册回滚补偿。
 */
@Service
public class CrossStoreFileWriteService {
    private final MinioclientUtil minioclientUtil;
    private final CrossStoreCompensationService compensationService;

    public CrossStoreFileWriteService(MinioclientUtil minioclientUtil,
                                      CrossStoreCompensationService compensationService) {
        this.minioclientUtil = minioclientUtil;
        this.compensationService = compensationService;
    }

    public void putNewObject(MultipartFile file, String objectName) throws Exception {
        minioclientUtil.putObject(file,objectName);
        compensationService.removeNewObjectOnRollback(objectName);
    }

    public void putNewObject(InputStream inputStream,
                             long size,
                             String contentType,
                             String objectName) throws Exception {
        minioclientUtil.putObject(inputStream,size,contentType,objectName);
        compensationService.removeNewObjectOnRollback(objectName);
    }

    public void putNewEmptyObject(String objectName) throws Exception {
        minioclientUtil.putEmptyObject(objectName);
        compensationService.removeNewObjectOnRollback(objectName);
    }

    public String putNewVersion(MultipartFile file, String objectName) throws Exception {
        String versionId = minioclientUtil.putObjectAndReturnVersionId(file,objectName);
        if(versionId != null && !versionId.isBlank()) {
            compensationService.removeNewVersionOnRollback(objectName,versionId);
        }
        return versionId;
    }

    public String restoreAsNewVersion(String objectName, String sourceVersionId) throws Exception {
        String versionId = minioclientUtil.restoreObjectVersion(objectName,sourceVersionId);
        if(versionId != null && !versionId.isBlank()) {
            compensationService.removeNewVersionOnRollback(objectName,versionId);
        }
        return versionId;
    }

    public String copyVersionToNewObject(String sourceObjectName,
                                         String sourceVersionId,
                                         String targetObjectName) throws Exception {
        String versionId = minioclientUtil.copyObjectVersion(sourceObjectName,sourceVersionId,targetObjectName);
        if(versionId != null && !versionId.isBlank()) {
            compensationService.removeNewVersionOnRollback(targetObjectName,versionId);
        }
        return versionId;
    }
}
