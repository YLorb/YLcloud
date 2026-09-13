package com.ylcloud.service.memory;

import com.ylcloud.config.RagProperties;
import com.ylcloud.async.mq.AsyncMqProperties;
import com.ylcloud.async.task.DomainTaskPayload;
import com.ylcloud.async.task.RetryableTaskException;
import com.ylcloud.async.task.StaleTaskException;
import com.ylcloud.async.task.TaskCreateCommand;
import com.ylcloud.async.task.UnifiedTaskCenterService;
import com.ylcloud.async.worker.StaleWorkerException;
import com.ylcloud.async.worker.TaskCanceledException;
import com.ylcloud.async.worker.TaskExecutionContext;
import com.ylcloud.entity.UserMemoryItem;
import com.ylcloud.mapper.UserMemoryItemMapper;
import com.ylcloud.mapper.UserMemoryExtractionTaskMapper;
import com.ylcloud.mapper.UserLifecycleMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class UserMemoryService {
    private static final Logger log = LoggerFactory.getLogger(UserMemoryService.class);
    private final UserMemoryItemMapper mapper;
    private final UserMemoryVectorStoreService vectorStore;
    private final RagProperties properties;
    private AsyncMqProperties mqProperties;
    private UnifiedTaskCenterService taskCenter;
    private UserMemoryExtractionTaskMapper extractionTaskMapper;
    private UserLifecycleMapper userLifecycleMapper;

    public UserMemoryService(UserMemoryItemMapper mapper, UserMemoryVectorStoreService vectorStore, RagProperties properties) {
        this.mapper = mapper;
        this.vectorStore = vectorStore;
        this.properties = properties;
    }

    @Autowired(required = false)
    public void setDeletionFences(UserMemoryExtractionTaskMapper extractionTaskMapper,
                                  UserLifecycleMapper userLifecycleMapper) {
        this.extractionTaskMapper = extractionTaskMapper;
        this.userLifecycleMapper = userLifecycleMapper;
    }

    @Transactional
    public UserMemoryItem accept(Long userId, Long sessionId, Long sourceMessageId, String sourceText, UserMemoryCandidate candidate) {
        return acceptInternal(userId,sessionId,sourceMessageId,sourceText,candidate,true,null);
    }

    @Transactional
    public UserMemoryItem acceptManual(Long userId, Long sessionId, Long sourceMessageId, String sourceText, UserMemoryCandidate candidate) {
        return acceptInternal(userId,sessionId,sourceMessageId,sourceText,candidate,false,null);
    }

    @Transactional
    public UserMemoryItem acceptFromTask(Long userId,Long sessionId,Long sourceMessageId,String sourceText,
                                         UserMemoryCandidate candidate,Long originAsyncTaskId) {
        return acceptInternal(userId,sessionId,sourceMessageId,sourceText,candidate,true,originAsyncTaskId);
    }

    private UserMemoryItem acceptInternal(Long userId, Long sessionId, Long sourceMessageId, String sourceText,
                                          UserMemoryCandidate candidate, boolean requireEnabled,Long originAsyncTaskId) {
        if(userLifecycleMapper != null) {
            com.ylcloud.entity.User user = userLifecycleMapper.getAccountStatus(userId);
            if(user == null || !"ACTIVE".equals(user.getAccountStatus())) return null;
        }
        if((requireEnabled && !enabled(userId)) || candidate == null || candidate.content() == null || candidate.content().isBlank()) return null;
        String key = normalize(candidate.key() == null || candidate.key().isBlank() ? candidate.content() : candidate.key(),190);
        String sourceHash = sha256(sourceText == null ? "" : sourceText);
        UserMemoryItem existing = mapper.findIdempotent(userId,key,sourceHash);
        if(existing != null) return afterAccept(existing);
        String content = candidate.content().trim();
        UserMemoryItem active = mapper.findActiveByKey(userId,key);
        if(active != null && sha256(content).equals(active.getContentHash())) return active;
        LocalDateTime now = LocalDateTime.now();
        UserMemoryItem item = new UserMemoryItem();
        item.setUserId(userId); item.setSourceSessionId(sessionId); item.setSourceMessageId(sourceMessageId);
        item.setMemoryType(normalizeType(candidate.type())); item.setContent(content.substring(0,Math.min(2000,content.length())));
        item.setNormalizedKey(key); item.setContentHash(sha256(item.getContent())); item.setSourceHash(sourceHash);
        item.setConfidence(BigDecimal.valueOf(Math.max(0,Math.min(1,candidate.confidence()))));
        item.setUserConfirmed(candidate.userConfirmed()); item.setPinned(false);
        item.setExpiresAt(null);
        item.setVersion(active == null || active.getVersion() == null ? 1 : active.getVersion() + 1);
        item.setMemoryStatus("CANDIDATE"); item.setEmbeddingStatus("PENDING");
        item.setSupersedesId(active == null ? null : active.getId()); item.setStatus(1); item.setCreatetime(now); item.setUpdatetime(now);
        item.setOriginAsyncTaskId(originAsyncTaskId);
        item.setAsyncVersion((long)item.getVersion());
        mapper.insert(item);
        return afterAccept(item);
    }

    public void processIndex(Long id) {
        UserMemoryItem existing=mapper.getById(id);
        if(existing != null && (hasActiveAsyncTask(existing.getProfileAsyncTaskId())
                || hasActiveAsyncTask(existing.getVectorAsyncTaskId()))) return;
        LocalDateTime now = LocalDateTime.now();
        if(mapper.claimIndex(id,now) == 0) return;
        UserMemoryItem item = mapper.getById(id);
        try {
            String pointId = vectorStore.upsert(item);
            if(mapper.activate(id,pointId,LocalDateTime.now()) == 0) throw new IllegalStateException("User memory activation CAS failed");
            if(item.getSupersedesId() != null && mapper.supersede(item.getSupersedesId(),LocalDateTime.now()) > 0) processDelete(item.getSupersedesId());
        } catch(Exception ex) {
            mapper.failIndex(id,error(ex),nextRetry(item.getRetryCount()),LocalDateTime.now());
            log.warn("User memory indexing failed: memoryId={}, type={}",id,ex.getClass().getSimpleName());
        }
    }

    @Transactional
    public Map<String,Object> executeProfileAsync(Long id,Long asyncTaskId,long version,TaskExecutionContext context) {
        UserMemoryItem item=requireCurrent(id,version);
        if(!asyncTaskId.equals(item.getProfileAsyncTaskId())) throw new StaleTaskException("记忆画像任务已被替代");
        if(!"CANDIDATE".equals(item.getMemoryStatus())
                || !("PENDING".equals(item.getEmbeddingStatus()) || "FAILED_RETRYABLE".equals(item.getEmbeddingStatus()))) {
            throw new StaleTaskException("记忆画像状态已变化");
        }
        requireOriginSucceeded(item);
        context.checkpoint();
        com.ylcloud.entity.UnifiedAsyncTask vectorTask=taskCenter.createTask(new TaskCreateCommand(
                "memory-vector-index:"+id+":"+version,"memory","MEMORY_VECTOR_INDEX",
                new DomainTaskPayload(id),item.getUserId(),null,resourceKey(item),version));
        if(mapper.bindVectorTask(id,version,asyncTaskId,vectorTask.getId(),LocalDateTime.now()) != 1) {
            throw new StaleTaskException("记忆向量任务绑定被版本栅栏拒绝");
        }
        return Map.of("vectorTaskId",vectorTask.getId());
    }

    public Map<String,Object> executeIndexAsync(Long id,Long asyncTaskId,long version,TaskExecutionContext context) {
        UserMemoryItem item=requireCurrent(id,version);
        if(!asyncTaskId.equals(item.getVectorAsyncTaskId())) throw new StaleTaskException("记忆向量任务已被替代");
        requireOriginSucceeded(item);
        if(mapper.claimIndexAsync(id,version,asyncTaskId,LocalDateTime.now()) == 0) {
            throw new StaleTaskException("记忆向量状态已变化");
        }
        item=mapper.getById(id);
        try {
            context.checkpoint();
            String pointId=vectorStore.upsert(item);
            context.checkpoint();
            if(mapper.activateAsync(id,version,asyncTaskId,pointId,LocalDateTime.now()) != 1) {
                throw new StaleTaskException("记忆向量结果被版本栅栏拒绝");
            }
            if(item.getSupersedesId() != null && mapper.supersede(item.getSupersedesId(),LocalDateTime.now()) > 0) {
                UserMemoryItem superseded=mapper.getById(item.getSupersedesId());
                if(superseded != null) registerDelete(superseded);
            }
            return Map.of("pointId",pointId);
        } catch(TaskCanceledException canceled) {
            mapper.cancelIndexAsync(id,version,asyncTaskId,LocalDateTime.now());
            throw canceled;
        } catch(StaleWorkerException | StaleTaskException control) {
            throw control;
        } catch(Exception failure) {
            mapper.failIndexAsync(id,version,asyncTaskId,"Memory vector service unavailable",LocalDateTime.now());
            throw new RetryableTaskException("用户记忆向量服务暂时不可用");
        }
    }

    public Map<String,Object> executeDeleteAsync(Long id,Long asyncTaskId,long version,TaskExecutionContext context) {
        UserMemoryItem item=mapper.getById(id);
        if(item == null || "DELETED".equals(item.getEmbeddingStatus())) return Map.of("alreadyAbsent",true);
        if(item.getAsyncVersion()==null || item.getAsyncVersion()!=version
                || !asyncTaskId.equals(item.getVectorAsyncTaskId()) || !"DELETE_PENDING".equals(item.getEmbeddingStatus())) {
            throw new StaleTaskException("记忆删除任务版本已变化");
        }
        try {
            context.checkpoint();
            vectorStore.delete(item);
            context.checkpoint();
            if(mapper.markDeletedAsync(id,version,asyncTaskId,LocalDateTime.now()) != 1) {
                throw new StaleTaskException("记忆删除结果被版本栅栏拒绝");
            }
            return Map.of("deleted",true);
        } catch(TaskCanceledException | StaleWorkerException | StaleTaskException control) {
            throw control;
        } catch(Exception failure) {
            mapper.failDeleteAsync(id,version,asyncTaskId,"Memory vector service unavailable",LocalDateTime.now());
            throw new RetryableTaskException("用户记忆向量服务暂时不可用");
        }
    }

    @Transactional
    public void forget(Long userId, Long id) {
        if(mapper.forget(id,userId,LocalDateTime.now()) > 0) {
            UserMemoryItem item=mapper.getById(id);
            if(usesUnifiedTasks()) registerDelete(item); else processDelete(id);
        }
    }

    @Transactional
    public void clear(Long userId) {
        if(extractionTaskMapper != null) extractionTaskMapper.cancelByUserId(userId,LocalDateTime.now());
        mapper.clear(userId,LocalDateTime.now());
        long afterId = 0L;
        while (true) {
            List<UserMemoryItem> batch = mapper.listDeletePendingByUserAfter(userId, afterId, 500);
            if (batch.isEmpty()) break;
            for (UserMemoryItem item : batch) {
                if(usesUnifiedTasks()) registerDelete(item); else processDelete(item.getId());
                afterId = item.getId();
            }
        }
    }

    public int countDeletePending(Long userId) {
        return mapper.countDeletePendingByUser(userId);
    }

    @Transactional
    public int redactDeleted(Long userId) {
        return mapper.redactDeletedByUser(userId, LocalDateTime.now());
    }

    public boolean enabled(Long userId) {
        return Boolean.TRUE.equals(properties.getMemory().getEnabled()) && !Boolean.FALSE.equals(mapper.isEnabled(userId));
    }

    @Scheduled(fixedDelayString = "${ylcloud.memory.recovery-delay-ms:30000}", initialDelayString = "${ylcloud.memory.recovery-initial-delay-ms:20000}")
    public void reconcile() {
        if(usesUnifiedTasks()) {
            mapper.listPendingIndex(100,maxRetries()).forEach(this::registerProfile);
            mapper.listDeletePending(100).forEach(this::registerDelete);
            return;
        }
        mapper.listPendingIndex(100,maxRetries()).forEach(item -> processIndex(item.getId()));
        mapper.listDeletePending(100).forEach(item -> processDelete(item.getId()));
    }

    private void processDelete(Long id) {
        UserMemoryItem item = mapper.getById(id);
        if(item == null || !"DELETE_PENDING".equals(item.getEmbeddingStatus())) return;
        if(hasActiveAsyncTask(item.getVectorAsyncTaskId())) return;
        try {
            vectorStore.delete(item);
            mapper.markDeleted(id,LocalDateTime.now());
        } catch(Exception ex) {
            mapper.failDelete(id,error(ex),nextRetry(item.getRetryCount()),LocalDateTime.now());
        }
    }

    public boolean usesUnifiedTasks() {
        return mqProperties != null && taskCenter != null && mqProperties.isEnabled() && mqProperties.isMemory();
    }

    private UserMemoryItem afterAccept(UserMemoryItem item) {
        if(usesUnifiedTasks() && item != null && "CANDIDATE".equals(item.getMemoryStatus())
                && ("PENDING".equals(item.getEmbeddingStatus()) || "FAILED_RETRYABLE".equals(item.getEmbeddingStatus()))) {
            registerProfile(item);
        }
        return item;
    }

    private void registerProfile(UserMemoryItem item) {
        if(item==null) return;
        long version=item.getAsyncVersion()==null?Math.max(1,item.getVersion()==null?1:item.getVersion()):item.getAsyncVersion();
        com.ylcloud.entity.UnifiedAsyncTask task=taskCenter.createTask(new TaskCreateCommand(
                "memory-profile:"+item.getId()+":"+version,"memory","MEMORY_PROFILE_BUILD",
                new DomainTaskPayload(item.getId()),item.getUserId(),null,resourceKey(item),version));
        mapper.bindProfileTask(item.getId(),version,task.getId(),LocalDateTime.now());
    }

    private void registerDelete(UserMemoryItem item) {
        if(item==null || !usesUnifiedTasks()) return;
        long version=item.getAsyncVersion()==null?1:item.getAsyncVersion();
        com.ylcloud.entity.UnifiedAsyncTask task=taskCenter.createTask(new TaskCreateCommand(
                "memory-vector-delete:"+item.getId()+":"+version,"memory","MEMORY_VECTOR_DELETE",
                new DomainTaskPayload(item.getId()),item.getUserId(),null,resourceKey(item),version));
        mapper.bindDeleteTask(item.getId(),version,task.getId(),LocalDateTime.now());
    }

    private UserMemoryItem requireCurrent(Long id,long version) {
        UserMemoryItem item=mapper.getById(id);
        if(item==null || item.getAsyncVersion()==null || item.getAsyncVersion()!=version || item.getStatus()==null || item.getStatus()!=1) {
            throw new StaleTaskException("用户记忆资源版本已变化");
        }
        return item;
    }

    private void requireOriginSucceeded(UserMemoryItem item) {
        if(item.getOriginAsyncTaskId()==null) return;
        String status=taskCenter.status(item.getOriginAsyncTaskId());
        if("SUCCESS".equals(status)) return;
        if(status==null || java.util.List.of("FAILED","CANCELED","SKIPPED_STALE").contains(status)) {
            throw new StaleTaskException("记忆来源任务未成功");
        }
        throw new RetryableTaskException("记忆来源任务尚未完成");
    }

    private String resourceKey(UserMemoryItem item) {
        return "user-memory:"+item.getUserId()+":"+sha256(item.getNormalizedKey()==null?String.valueOf(item.getId()):item.getNormalizedKey());
    }

    private boolean hasActiveAsyncTask(Long taskId) {
        return taskCenter != null && taskCenter.isActive(taskId);
    }

    @Autowired(required=false)
    public void setAsyncTaskInfrastructure(AsyncMqProperties properties,UnifiedTaskCenterService taskCenter) {
        this.mqProperties=properties;
        this.taskCenter=taskCenter;
    }

    private LocalDateTime nextRetry(Integer retries) {
        int attempt = Math.min(10,(retries == null ? 0 : retries) + 1);
        return LocalDateTime.now().plusSeconds(Math.min(3600,1L << attempt));
    }
    private int maxRetries() { Integer value = properties.getMemory().getMaxRetries(); return value == null || value <= 0 ? 8 : value; }
    private String normalize(String value, int limit) { String result = value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+"," "); return result.substring(0,Math.min(limit,result.length())); }
    private String normalizeType(String type) { String value = type == null ? "FACT" : type.trim().toUpperCase(Locale.ROOT); return value.matches("FACT|PREFERENCE|CONSTRAINT|DECISION") ? value : "FACT"; }
    private String error(Exception ex) { return "Memory vector service unavailable"; }
    private String sha256(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch(Exception ex) { throw new IllegalStateException(ex); } }
}
