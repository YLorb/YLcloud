package com.ylcloud.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.DTO.UserFileDTO;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.entity.SpaceFile;
import com.ylcloud.mapper.SpaceFileLifecycleMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Service
public class SpaceFileLifecycleService {
    public static final String INDEX_REQUESTED = "INDEX_REQUESTED";
    public static final String REMOVAL_REQUESTED = "REMOVAL_REQUESTED";
    private final SpaceFileLifecycleMapper mapper;
    private final ObjectMapper objectMapper;

    public SpaceFileLifecycleService(SpaceFileLifecycleMapper mapper,ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public long fileAdded(SpaceFile file,Long userId) {
        if(file == null || file.getDir() == 1) return 0;
        LocalDateTime now = LocalDateTime.now();
        if(mapper.beginIndex(file.getSpaceId(),file.getId(),now) != 1) {
            throw new BaseException("空间文件知识生命周期初始化失败");
        }
        SpaceFile current = mapper.getAny(file.getSpaceId(),file.getId());
        mapper.addReference(file.getFileUuid(),file.getId(),userId,file.getSpaceId(),now);
        insertEvent(current,INDEX_REQUESTED,now);
        return current.getKnowledgeVersion();
    }

    @Transactional
    public long fileRemovalStarted(SpaceFile file) {
        LocalDateTime now = LocalDateTime.now();
        if(mapper.beginRemoval(file.getSpaceId(),file.getId(),now) != 1) {
            throw new BaseException("空间文件移除生命周期初始化失败");
        }
        SpaceFile current = mapper.getAny(file.getSpaceId(),file.getId());
        mapper.supersedeIndexEvents(file.getId(),current.getKnowledgeVersion(),now);
        mapper.releaseReference(file.getId(),now);
        insertEvent(current,REMOVAL_REQUESTED,now);
        return current.getKnowledgeVersion();
    }

    @Transactional
    public void indexing(Long spaceId,Long fileId) {
        LocalDateTime now = LocalDateTime.now();
        SpaceFile current = mapper.getAny(spaceId,fileId);
        if(current != null && ("READY".equals(current.getKnowledgeState()) || "FAILED".equals(current.getKnowledgeState()))) {
            mapper.beginIndex(spaceId,fileId,now);
            current = mapper.getAny(spaceId,fileId);
            insertEvent(current,INDEX_REQUESTED,now);
        }
        mapper.markIndexing(spaceId,fileId,now);
    }

    public void indexSucceeded(Long spaceId,Long fileId) {
        SpaceFile current = mapper.getAny(spaceId,fileId);
        if(current == null || mapper.markReady(spaceId,fileId,LocalDateTime.now()) != 1) return;
        mapper.completeEvent(fileId,INDEX_REQUESTED,current.getKnowledgeVersion(),LocalDateTime.now());
    }

    public void indexFailed(Long spaceId,Long fileId,String error) {
        SpaceFile current = mapper.getAny(spaceId,fileId);
        if(current == null) return;
        String safe = safe(error);
        if(mapper.markFailed(spaceId,fileId,safe,LocalDateTime.now()) == 1) {
            mapper.failEvent(fileId,INDEX_REQUESTED,current.getKnowledgeVersion(),safe,LocalDateTime.now());
        }
    }

    public void removalSucceeded(Long spaceId,Long fileId) {
        SpaceFile current = mapper.getAny(spaceId,fileId);
        if(current == null || mapper.markRemoved(spaceId,fileId,LocalDateTime.now()) != 1) return;
        mapper.completeEvent(fileId,REMOVAL_REQUESTED,current.getKnowledgeVersion(),LocalDateTime.now());
    }

    public void removalFailed(Long spaceId,Long fileId,String error) {
        SpaceFile current = mapper.getAny(spaceId,fileId);
        if(current != null) {
            String safe = safe(error);
            mapper.markRemovalFailed(spaceId,fileId,safe,LocalDateTime.now());
            mapper.failEvent(fileId,REMOVAL_REQUESTED,current.getKnowledgeVersion(),safe,LocalDateTime.now());
        }
    }

    public int activeReferences(String fileUuid) {
        return mapper.countActiveReferences(fileUuid);
    }

    public void personalFileAdded(UserFileDTO file) {
        if(file == null || file.getDir() == 1 || file.getFileUuid() == null) return;
        mapper.addUserReference(file.getFileUuid(),file.getId(),file.getUserId(),LocalDateTime.now());
    }

    public void personalFileRemoved(UserFileDTO file) {
        if(file == null || file.getDir() == 1) return;
        mapper.releaseUserReference(file.getId(),LocalDateTime.now());
    }

    private void insertEvent(SpaceFile file,String type,LocalDateTime now) {
        String key = type + ":" + file.getId() + ":" + file.getKnowledgeVersion();
        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                    "spaceId",file.getSpaceId(),"spaceFileId",file.getId(),"fileUuid",file.getFileUuid(),
                    "resourceVersion",file.getKnowledgeVersion()
            ));
            mapper.insertEvent(UUID.randomUUID().toString(),key,type,file.getSpaceId(),file.getId(),
                    file.getFileUuid(),file.getKnowledgeVersion(),payload,now);
        } catch(JsonProcessingException e) {
            throw new BaseException("空间文件生命周期事件序列化失败",e);
        }
    }

    private String safe(String error) {
        if(error == null || error.isBlank()) return "未知错误";
        String value = error.replaceAll("(?i)(token|password|secret|key)\\s*[=:]\\s*\\S+","$1=***");
        return value.length() > 1000 ? value.substring(0,1000) : value;
    }
}
