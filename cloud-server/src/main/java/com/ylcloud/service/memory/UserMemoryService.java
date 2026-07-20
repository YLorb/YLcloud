package com.ylcloud.service.memory;

import com.ylcloud.config.RagProperties;
import com.ylcloud.entity.UserMemoryItem;
import com.ylcloud.mapper.UserMemoryItemMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Locale;

@Service
public class UserMemoryService {
    private static final Logger log = LoggerFactory.getLogger(UserMemoryService.class);
    private final UserMemoryItemMapper mapper;
    private final UserMemoryVectorStoreService vectorStore;
    private final RagProperties properties;

    public UserMemoryService(UserMemoryItemMapper mapper, UserMemoryVectorStoreService vectorStore, RagProperties properties) {
        this.mapper = mapper;
        this.vectorStore = vectorStore;
        this.properties = properties;
    }

    public UserMemoryItem accept(Long userId, Long sessionId, Long sourceMessageId, String sourceText, UserMemoryCandidate candidate) {
        return acceptInternal(userId,sessionId,sourceMessageId,sourceText,candidate,true);
    }

    public UserMemoryItem acceptManual(Long userId, Long sessionId, Long sourceMessageId, String sourceText, UserMemoryCandidate candidate) {
        return acceptInternal(userId,sessionId,sourceMessageId,sourceText,candidate,false);
    }

    private UserMemoryItem acceptInternal(Long userId, Long sessionId, Long sourceMessageId, String sourceText,
                                          UserMemoryCandidate candidate, boolean requireEnabled) {
        if((requireEnabled && !enabled(userId)) || candidate == null || candidate.content() == null || candidate.content().isBlank()) return null;
        String key = normalize(candidate.key() == null || candidate.key().isBlank() ? candidate.content() : candidate.key(),190);
        String sourceHash = sha256(sourceText == null ? "" : sourceText);
        UserMemoryItem existing = mapper.findIdempotent(userId,key,sourceHash);
        if(existing != null) return existing;
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
        int retention = properties.getMemory().getRetentionDays() == null ? 365 : properties.getMemory().getRetentionDays();
        item.setExpiresAt(retention <= 0 ? null : now.plusDays(retention));
        item.setVersion(active == null || active.getVersion() == null ? 1 : active.getVersion() + 1);
        item.setMemoryStatus("CANDIDATE"); item.setEmbeddingStatus("PENDING");
        item.setSupersedesId(active == null ? null : active.getId()); item.setStatus(1); item.setCreatetime(now); item.setUpdatetime(now);
        mapper.insert(item);
        return item;
    }

    public void processIndex(Long id) {
        LocalDateTime now = LocalDateTime.now();
        if(mapper.claimIndex(id,now) == 0) return;
        UserMemoryItem item = mapper.getById(id);
        try {
            String pointId = vectorStore.upsert(item);
            if(mapper.activate(id,pointId,LocalDateTime.now()) == 0) throw new IllegalStateException("User memory activation CAS failed");
            if(item.getSupersedesId() != null && mapper.supersede(item.getSupersedesId(),LocalDateTime.now()) > 0) processDelete(item.getSupersedesId());
        } catch(Exception ex) {
            mapper.failIndex(id,error(ex),nextRetry(item.getRetryCount()),LocalDateTime.now());
            log.warn("User memory indexing failed: memoryId={}",id,ex);
        }
    }

    public void deleteSourceSession(Long userId, Long sessionId) {
        mapper.deleteBySourceSession(userId,sessionId,LocalDateTime.now());
    }

    public void forget(Long userId, Long id) {
        if(mapper.forget(id,userId,LocalDateTime.now()) > 0) processDelete(id);
    }

    public void clear(Long userId) {
        mapper.clear(userId,LocalDateTime.now());
        mapper.listDeletePending(500).stream().filter(item -> userId.equals(item.getUserId())).forEach(item -> processDelete(item.getId()));
    }

    public boolean enabled(Long userId) {
        return Boolean.TRUE.equals(properties.getMemory().getEnabled()) && !Boolean.FALSE.equals(mapper.isEnabled(userId));
    }

    @Scheduled(fixedDelayString = "${ylcloud.memory.recovery-delay-ms:30000}", initialDelayString = "${ylcloud.memory.recovery-initial-delay-ms:20000}")
    public void reconcile() {
        mapper.expire(LocalDateTime.now());
        mapper.listPendingIndex(100,maxRetries()).forEach(item -> processIndex(item.getId()));
        mapper.listDeletePending(100).forEach(item -> processDelete(item.getId()));
    }

    private void processDelete(Long id) {
        UserMemoryItem item = mapper.getById(id);
        if(item == null || !"DELETE_PENDING".equals(item.getEmbeddingStatus())) return;
        try {
            vectorStore.delete(item);
            mapper.markDeleted(id,LocalDateTime.now());
        } catch(Exception ex) {
            mapper.failDelete(id,error(ex),nextRetry(item.getRetryCount()),LocalDateTime.now());
        }
    }

    private LocalDateTime nextRetry(Integer retries) {
        int attempt = Math.min(10,(retries == null ? 0 : retries) + 1);
        return LocalDateTime.now().plusSeconds(Math.min(3600,1L << attempt));
    }
    private int maxRetries() { Integer value = properties.getMemory().getMaxRetries(); return value == null || value <= 0 ? 8 : value; }
    private String normalize(String value, int limit) { String result = value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+"," "); return result.substring(0,Math.min(limit,result.length())); }
    private String normalizeType(String type) { String value = type == null ? "FACT" : type.trim().toUpperCase(Locale.ROOT); return value.matches("FACT|PREFERENCE|CONSTRAINT|DECISION") ? value : "FACT"; }
    private String error(Exception ex) { String value = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(); return value.substring(0,Math.min(1000,value.length())); }
    private String sha256(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch(Exception ex) { throw new IllegalStateException(ex); } }
}
