package com.ylcloud.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.DTO.QuotaPolicyUpdateDTO;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.VO.QuotaUsageVO;
import com.ylcloud.entity.File;
import com.ylcloud.entity.QuotaAccount;
import com.ylcloud.entity.QuotaBlobReference;
import com.ylcloud.entity.QuotaPolicy;
import com.ylcloud.mapper.FileInfoMapper;
import com.ylcloud.mapper.QuotaMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class QuotaService {
    public static final ZoneId BILLING_ZONE = ZoneId.of("Asia/Shanghai");
    private static final QuotaPolicy FALLBACK = fallback();
    private final QuotaMapper mapper;
    private final FileInfoMapper files;
    private final ObjectMapper objectMapper;

    public QuotaPolicy policyForUser(Long userId) {
        QuotaPolicy value = mapper.getUserPolicy(userId);
        return value == null ? FALLBACK : value;
    }

    public QuotaPolicy groupPolicy(Long groupId) {
        if(groupId == null || mapper.groupExists(groupId) == 0) throw new BaseException(404,"用户组不存在");
        QuotaPolicy value = mapper.getPolicy(groupId);
        return value == null ? FALLBACK : value;
    }

    @Transactional
    public QuotaPolicy updateGroupPolicy(Long groupId,QuotaPolicyUpdateDTO dto) {
        if(mapper.groupExists(groupId) == 0) throw new BaseException(404,"用户组不存在");
        QuotaPolicy value = new QuotaPolicy();
        value.setGroupId(groupId); value.setStorageBytes(dto.getStorageBytes()); value.setMaxFileBytes(dto.getMaxFileBytes());
        value.setSpaceLimit(dto.getSpaceLimit()); value.setMonthlyApiCalls(dto.getMonthlyApiCalls());
        value.setMonthlyModelTokens(dto.getMonthlyModelTokens()); value.setMonthlyAgentTasks(dto.getMonthlyAgentTasks());
        value.setConcurrentAgentTasks(dto.getConcurrentAgentTasks());
        mapper.upsertPolicy(value,LocalDateTime.now());
        return mapper.getPolicy(groupId);
    }

    @Transactional
    public void requireSpaceCreation(Long userId) {
        QuotaPolicy policy = policyForUser(userId);
        long used = safe(mapper.activeOwnedTeamSpaces(userId));
        if(limited(policy.getSpaceLimit(),used,1)) throw quota("Space 数量");
    }

    @Transactional
    public void registerTeam(Long spaceId,Long ownerId) {
        mapper.ensureTeamAccount(spaceId,ownerId,LocalDateTime.now());
    }

    @Transactional
    public void transferTeam(Long spaceId,Long newOwnerId) {
        mapper.ensureTeamAccount(spaceId,newOwnerId,LocalDateTime.now());
    }

    public long storageLimitForUser(Long userId) {
        return safe(policyForUser(userId).getStorageBytes());
    }

    @Transactional
    public void requireUserStorage(Long userId,long additionalBytes) {
        if(additionalBytes <= 0) return;
        QuotaAccount account = requireAccount("USER",userId);
        mapper.lockAccount(account.getId());
        QuotaPolicy policy = policy(account);
        if(limited(policy.getStorageBytes(),safe(mapper.storageUsed(account.getId())),additionalBytes)) throw quota("存储");
    }

    public long additionalUserBytes(Long userId,String fileUuid,long fileSize) {
        requireFileSize(userId,fileSize);
        if(fileUuid == null) return Math.max(0,fileSize);
        File file = files.getFileByFileUuid(fileUuid,userId);
        String key = file == null ? "uuid:" + fileUuid : normalizeKey(file.getHash(),fileUuid);
        return mapper.blobOwner(key) == null ? Math.max(0,fileSize) : 0;
    }

    public void requireFileSize(Long userId,long bytes) {
        long limit = safe(policyForUser(userId).getMaxFileBytes());
        if(limit > 0 && bytes > limit) throw quota("单文件大小");
    }

    @Transactional
    public void requireTeamStorage(Long spaceId,String contentKey,long bytes) {
        QuotaAccount account = requireAccount("TEAM",spaceId);
        mapper.lockAccount(account.getId());
        if(mapper.blobOwner(normalizeKey(contentKey,null)) != null) return;
        QuotaPolicy policy = policy(account);
        long used = safe(mapper.storageUsed(account.getId()));
        if(limited(policy.getStorageBytes(),used,Math.max(0,bytes))) throw quota("TEAM 存储");
    }

    @Transactional
    public void recordUserFile(Long userFileId,Long userId,String fileUuid) {
        recordReference("USER_FILE",userFileId,"USER",userId,fileUuid);
    }

    @Transactional
    public void recordTeamFile(Long spaceFileId,Long spaceId,String fileUuid) {
        recordReference("SPACE_FILE",spaceFileId,"TEAM",spaceId,fileUuid);
    }

    @Transactional
    public void releaseReference(String type,Long referenceId) {
        QuotaBlobReference reference = mapper.lockReference(type,referenceId);
        if(reference == null || !Boolean.TRUE.equals(reference.getActive())) return;
        LocalDateTime now = LocalDateTime.now();
        if(mapper.deactivateReference(type,referenceId,now) == 1) {
            mapper.decrementBlob(reference.getContentKey(),now);
            mapper.reassignBlobOwner(reference.getContentKey(),now);
            mapper.deleteUnreferencedBlob(reference.getContentKey());
        }
    }

    @Transactional
    public void consumeApiCall(Long userId) {
        QuotaAccount account = requireAccount("USER",userId);
        QuotaPolicy policy = policy(account);
        LocalDate period = period(); LocalDateTime now = LocalDateTime.now();
        mapper.ensureUsage(account.getId(),period,now);
        if(mapper.consumeApi(account.getId(),period,1,safe(policy.getMonthlyApiCalls()),now) != 1) throw quota("月度 API 调用");
    }

    @Transactional
    public void consumeModelTokens(Long userId,long tokens) {
        if(tokens <= 0) return;
        QuotaAccount account = requireAccount("USER",userId);
        QuotaPolicy policy = policy(account);
        LocalDate period = period(); LocalDateTime now = LocalDateTime.now();
        mapper.ensureUsage(account.getId(),period,now);
        if(mapper.consumeTokens(account.getId(),period,tokens,safe(policy.getMonthlyModelTokens()),now) != 1) throw quota("月度模型 Token");
    }

    @Transactional
    public void reserveAgentTask(Long userId) {
        QuotaAccount account = requireAccount("USER",userId);
        QuotaPolicy policy = policy(account);
        LocalDate period = period(); LocalDateTime now = LocalDateTime.now();
        mapper.ensureUsage(account.getId(),period,now);
        if(mapper.reserveAgent(account.getId(),period,safe(policy.getMonthlyAgentTasks()),safe(policy.getConcurrentAgentTasks()),now) != 1)
            throw quota("Agent 任务或并发");
    }

    @Transactional
    public void releaseAgentTask(Long userId) {
        QuotaAccount account = requireAccount("USER",userId);
        mapper.releaseAgent(account.getId(),period(),LocalDateTime.now());
    }

    @Scheduled(fixedDelayString="${ylcloud.quota.concurrency-reconcile-ms:30000}")
    @Transactional
    public void reconcileConcurrency() {
        mapper.reconcileConcurrentUsage(period(),LocalDateTime.now());
    }

    public QuotaUsageVO userUsage(Long userId) { return usage(requireAccount("USER",userId)); }
    public QuotaUsageVO teamUsage(Long spaceId) { return usage(requireAccount("TEAM",spaceId)); }

    @Scheduled(cron="${ylcloud.quota.reconcile-cron:0 20 3 * * *}",zone="Asia/Shanghai")
    @Transactional
    public void reconcile() {
        LocalDateTime started = LocalDateTime.now();
        long differences = mapper.countLedgerDifferences();
        try {
            int concurrency = mapper.reconcileConcurrentUsage(period(),LocalDateTime.now());
            int orphaned = mapper.deleteOrphanLedgers();
            int repaired = mapper.repairLedgerCounts(LocalDateTime.now());
            int removed = mapper.deleteEmptyLedgers();
            mapper.insertReconcileEvidence("SUCCEEDED",differences,json(Map.of("rowsVisited",repaired,"orphanRemoved",orphaned,"emptyRemoved",removed,"concurrencyRows",concurrency)),started,LocalDateTime.now());
        } catch(Exception exception) {
            log.error("Quota ledger reconciliation failed",exception);
            mapper.insertReconcileEvidence("FAILED",differences,json(Map.of("error",safeMessage(exception))),started,LocalDateTime.now());
        }
    }

    private void recordReference(String referenceType,Long referenceId,String accountType,Long accountReference,String fileUuid) {
        if(referenceId == null || fileUuid == null) return;
        File file = files.getFileByFileUuid(fileUuid,accountReference);
        if(file == null) throw new BaseException("配额计量找不到物理文件");
        QuotaAccount account = requireAccount(accountType,accountReference);
        mapper.lockAccount(account.getId());
        String key = normalizeKey(file.getHash(),fileUuid);
        QuotaBlobReference existing = mapper.lockReference(referenceType,referenceId);
        if(existing != null && Boolean.TRUE.equals(existing.getActive())) return;
        if(mapper.blobOwner(key) == null) {
            QuotaPolicy policy = policy(account);
            if(limited(policy.getStorageBytes(),safe(mapper.storageUsed(account.getId())),safe(file.getSize()))) throw quota("存储");
            mapper.insertBlob(key,fileUuid,safe(file.getSize()),account.getId(),LocalDateTime.now());
        }
        LocalDateTime now = LocalDateTime.now();
        int changed = existing == null
                ? mapper.insertReference(referenceType,referenceId,account.getId(),key,fileUuid,safe(file.getSize()),now)
                : mapper.activateReference(referenceType,referenceId,account.getId(),key,fileUuid,safe(file.getSize()),now);
        if(changed == 1) mapper.incrementBlob(key,now);
    }

    private QuotaUsageVO usage(QuotaAccount account) {
        QuotaPolicy policy = policy(account); LocalDate period = period();
        Map<String,Object> values = mapper.usage(account.getId(),period);
        QuotaUsageVO vo = new QuotaUsageVO(); vo.setAccountType(account.getAccountType()); vo.setReferenceId(account.getReferenceId());
        vo.setPeriodStart(period); vo.setStorageBytes(safe(mapper.storageUsed(account.getId()))); vo.setStorageLimitBytes(safe(policy.getStorageBytes()));
        vo.setFileCount(safe(mapper.storageFiles(account.getId()))); vo.setSpaceCount("USER".equals(account.getAccountType()) ? safe(mapper.activeOwnedTeamSpaces(account.getReferenceId())) : 0L);
        vo.setSpaceLimit(safe(policy.getSpaceLimit())); vo.setApiCalls(number(values,"apiCalls")); vo.setApiCallLimit(safe(policy.getMonthlyApiCalls()));
        vo.setModelTokens(number(values,"modelTokens")); vo.setModelTokenLimit(safe(policy.getMonthlyModelTokens()));
        vo.setAgentTasks(number(values,"agentTasks")); vo.setAgentTaskLimit(safe(policy.getMonthlyAgentTasks()));
        vo.setConcurrentAgentTasks(number(values,"concurrentAgentTasks")); vo.setConcurrentAgentTaskLimit(safe(policy.getConcurrentAgentTasks()));
        return vo;
    }

    private QuotaAccount requireAccount(String type,Long referenceId) {
        LocalDateTime now = LocalDateTime.now();
        if("USER".equals(type)) mapper.ensureUserAccount(referenceId,now);
        QuotaAccount account = mapper.getAccount(type,referenceId);
        if(account == null) throw new BaseException("配额账户不存在");
        return account;
    }

    private QuotaPolicy policy(QuotaAccount account) {
        QuotaPolicy policy = account.getGroupId() == null ? null : mapper.getPolicy(account.getGroupId());
        return policy == null ? FALLBACK : policy;
    }

    private LocalDate period() { return LocalDate.now(BILLING_ZONE).withDayOfMonth(1); }
    private boolean limited(Long quota,long used,long additional) { return quota != null && quota > 0 && (additional > quota || used > quota-additional); }
    private BaseException quota(String name) { return new BaseException(429,name + "配额已用尽"); }
    private long safe(Long value) { return value == null ? 0 : Math.max(0,value); }
    private long number(Map<String,Object> values,String key) { Object value=values==null?null:values.get(key); return value instanceof Number number ? number.longValue() : 0; }
    private String normalizeKey(String hash,String uuid) { return hash == null || hash.isBlank() ? "uuid:" + uuid : hash; }
    private String safeMessage(Exception exception) { String value=exception.getMessage(); return value==null?exception.getClass().getSimpleName():value.substring(0,Math.min(500,value.length())); }
    private String json(Object value) { try { return objectMapper.writeValueAsString(value); } catch(Exception ignored) { return "{}"; } }
    private static QuotaPolicy fallback() { QuotaPolicy value=new QuotaPolicy(); value.setStorageBytes(1073741824L); value.setMaxFileBytes(20971520L); value.setSpaceLimit(10L); value.setMonthlyApiCalls(10000L); value.setMonthlyModelTokens(1000000L); value.setMonthlyAgentTasks(1000L); value.setConcurrentAgentTasks(3L); return value; }
}
