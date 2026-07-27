package com.ylcloud.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.Exception.ConflictException;
import com.ylcloud.VO.DataExportJobVO;
import com.ylcloud.VO.FileVO;
import com.ylcloud.async.task.DomainTaskPayload;
import com.ylcloud.async.task.TaskCreateCommand;
import com.ylcloud.async.task.UnifiedTaskCenterService;
import com.ylcloud.async.worker.TaskExecutionContext;
import com.ylcloud.entity.DataExportJob;
import com.ylcloud.entity.KnowledgeChatMessage;
import com.ylcloud.entity.KnowledgeChatSession;
import com.ylcloud.entity.UnifiedAsyncTask;
import com.ylcloud.entity.User;
import com.ylcloud.entity.UserMemoryItem;
import com.ylcloud.mapper.DataExportJobMapper;
import com.ylcloud.mapper.KnowledgeChatMessageMapper;
import com.ylcloud.mapper.KnowledgeChatSessionMapper;
import com.ylcloud.mapper.LoginMapper;
import com.ylcloud.mapper.FileInfoMapper;
import com.ylcloud.mapper.UserMemoryItemMapper;
import com.ylcloud.utils.MinioclientUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 数据导出服务：异步、限时下载的加密数据导出。
 * 导出不得包含 Key 明文、Secret、他人数据或内部敏感字段。
 * 每个导出任务生成独立 AES-256-GCM 密钥，密钥用服务端主密钥包裹后持久化，
 * 用户下载时可通过 decryptionKey 字段获取解密凭据。
 */
@Service
@Slf4j
public class DataExportService {
    private static final String TASK_DOMAIN = "maintenance";
    private static final String TASK_TYPE = "DATA_EXPORT";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int GCM_IV_LENGTH = 12;
    private static final int AES_KEY_SIZE = 256;
    private static final int MAX_CHAT_SESSIONS = 200;
    private static final int MAX_MEMORY_ITEMS = 2000;

    private final DataExportJobMapper jobMapper;
    private final UnifiedTaskCenterService taskCenter;
    private final MinioclientUtil minioUtil;
    private final ObjectMapper objectMapper;
    private final LoginMapper loginMapper;
    private final FileInfoMapper fileInfoMapper;
    private final KnowledgeChatSessionMapper sessionMapper;
    private final KnowledgeChatMessageMapper messageMapper;
    private final UserMemoryItemMapper memoryMapper;

    @Value("${ylcloud.export.download-expiry-hours:24}")
    private int downloadExpiryHours;

    @Value("${ylcloud.export.master-key:}")
    private String masterKeyBase64;

    public DataExportService(DataExportJobMapper jobMapper,
                             UnifiedTaskCenterService taskCenter,
                             MinioclientUtil minioUtil,
                             ObjectMapper objectMapper,
                             LoginMapper loginMapper,
                             FileInfoMapper fileInfoMapper,
                             KnowledgeChatSessionMapper sessionMapper,
                             KnowledgeChatMessageMapper messageMapper,
                             UserMemoryItemMapper memoryMapper) {
        this.jobMapper = jobMapper;
        this.taskCenter = taskCenter;
        this.minioUtil = minioUtil;
        this.objectMapper = objectMapper;
        this.loginMapper = loginMapper;
        this.fileInfoMapper = fileInfoMapper;
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.memoryMapper = memoryMapper;
    }

    /**
     * 请求数据导出。
     */
    @Transactional
    public DataExportJobVO requestExport(Long userId, String exportScope) {
        Long activeJobId = jobMapper.lockActiveByUserId(userId);
        if (activeJobId != null) {
            throw new ConflictException("已有导出任务正在进行中");
        }

        LocalDateTime now = LocalDateTime.now();
        String jobKey = "data-export:" + userId + ":" + System.currentTimeMillis();
        String scope = normalizeScope(exportScope);

        DataExportJob job = new DataExportJob();
        job.setUserId(userId);
        job.setJobKey(jobKey);
        job.setStatus("PENDING");
        job.setExportScope(scope);
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        jobMapper.insert(job);

        TaskCreateCommand command = new TaskCreateCommand(
                jobKey,
                TASK_DOMAIN,
                TASK_TYPE,
                new DomainTaskPayload(userId, null, Map.of("jobId", job.getId(), "scope", scope)),
                userId,
                null,
                jobKey,
                1L,
                null,
                3
        );

        UnifiedAsyncTask task = taskCenter.createTask(command);
        job.setAsyncTaskId(task.getId());
        jobMapper.update(job);

        log.info("Data export requested: userId={}, jobId={}, scope={}", userId, job.getId(), scope);
        return toVO(job);
    }

    /**
     * 执行导出（由异步任务处理器调用）。
     */
    public Map<String, Object> executeExport(Long jobId, Long asyncTaskId, TaskExecutionContext context) throws Exception {
        DataExportJob job = jobMapper.getById(jobId);
        if (job == null) throw new BaseException("导出任务不存在");
        if ("COMPLETED".equals(job.getStatus())) return Map.of("alreadyCompleted", true);
        if (!asyncTaskId.equals(job.getAsyncTaskId())) {
            throw new BaseException("任务版本不匹配");
        }

        Long userId = job.getUserId();
        String scope = job.getExportScope();

        try {
            if (jobMapper.markRunning(jobId, LocalDateTime.now()) != 1) {
                throw new BaseException("导出任务状态已变化");
            }

            context.checkpoint();

            // 收集导出数据
            Map<String, Object> exportData = collectExportData(userId, scope);

            context.checkpoint();

            // 生成导出密钥对：数据密钥 + IV，然后用主密钥包裹数据密钥
            SecretKey dataKey = generateAesKey();
            byte[] iv = generateIv();
            String keyId = "ek-" + jobId + "-" + System.currentTimeMillis();

            // 打包并加密
            byte[] zipData = packageAsZip(exportData);
            byte[] encryptedData = encryptAesGcm(zipData, dataKey, iv);

            // 用主密钥包裹数据密钥，持久化到数据库
            SecretKey masterKey = resolveMasterKey();
            String wrappedKeyBase64 = wrapKey(dataKey, masterKey);
            String ivBase64 = Base64.getEncoder().encodeToString(iv);

            job.setEncryptionKeyId(keyId);
            job.setEncryptedKey(wrappedKeyBase64);
            job.setEncryptedIv(ivBase64);
            jobMapper.update(job);

            context.checkpoint();

            // 上传到 MinIO
            String objectName = "exports/" + userId + "/" + job.getJobKey() + ".enc";
            minioUtil.putObject(new java.io.ByteArrayInputStream(encryptedData),
                    encryptedData.length, "application/octet-stream", objectName);

            // 计算哈希
            String fileHash = sha256Hex(encryptedData);

            // 生成下载链接（限时）
            LocalDateTime expiresAt = LocalDateTime.now().plusHours(downloadExpiryHours);
            String downloadUrl = minioUtil.getPresignedObjectUrl(objectName, downloadExpiryHours * 3600);

            // 解密凭据：数据密钥明文 + IV，base64 编码后供用户解密使用
            String decryptionKey = Base64.getEncoder().encodeToString(dataKey.getEncoded());

            // 标记完成
            jobMapper.markCompleted(jobId, objectName, (long) encryptedData.length, fileHash,
                    downloadUrl, expiresAt, LocalDateTime.now());

            log.info("Data export completed: userId={}, jobId={}, size={}", userId, jobId, encryptedData.length);

            return Map.of(
                    "exportedScope", scope,
                    "fileSize", encryptedData.length,
                    "downloadExpiresAt", expiresAt.toString(),
                    "decryptionKey", decryptionKey
            );

        } catch (Exception e) {
            log.error("Data export failed: userId={}, jobId={}", userId, jobId, e);
            jobMapper.markFailed(jobId, truncate(e.getMessage()), LocalDateTime.now());
            throw e;
        }
    }

    /**
     * 获取用户的导出任务列表。
     */
    public List<DataExportJobVO> listExports(Long userId, int limit) {
        return jobMapper.listByUserId(userId, Math.min(limit, 50)).stream()
                .map(this::toVO)
                .toList();
    }

    /**
     * 获取单个导出任务详情（含解密凭据）。
     */
    public DataExportJobVO getExport(Long userId, Long jobId) {
        DataExportJob job = jobMapper.getById(jobId);
        if (job == null || !job.getUserId().equals(userId)) {
            throw new BaseException("导出任务不存在");
        }
        DataExportJobVO vo = toVO(job);
        if ("COMPLETED".equals(job.getStatus()) && job.getEncryptedKey() != null) {
            vo.setDecryptionKey(unwrapToBase64(job.getEncryptedKey()));
        }
        return vo;
    }

    // ─── 数据收集 ───────────────────────────────────────────────

    private Map<String, Object> collectExportData(Long userId, String scope) {
        Map<String, Object> data = new HashMap<>();
        data.put("exportedAt", LocalDateTime.now().toString());
        data.put("userId", userId);
        data.put("scope", scope);

        switch (scope) {
            case "FULL":
                data.put("profile", collectProfile(userId));
                data.put("files", collectFiles(userId));
                data.put("chatHistory", collectChatHistory(userId));
                data.put("memory", collectMemory(userId));
                break;
            case "PERSONAL_FILES":
                data.put("files", collectFiles(userId));
                break;
            case "CHAT_HISTORY":
                data.put("chatHistory", collectChatHistory(userId));
                break;
            case "MEMORY":
                data.put("memory", collectMemory(userId));
                break;
            default:
                data.put("profile", collectProfile(userId));
        }

        return data;
    }

    private Map<String, Object> collectProfile(Long userId) {
        User user = loginMapper.getById(userId);
        Map<String, Object> profile = new HashMap<>();
        if (user != null) {
            profile.put("username", user.getUsername());
            profile.put("nickname", user.getNickname());
            profile.put("email", user.getEmail());
            profile.put("avatar", user.getAvatar());
            profile.put("role", user.getRole());
            profile.put("accountStatus", user.getAccountStatus());
            profile.put("createTime", user.getCreateTime() != null ? user.getCreateTime().toString() : null);
        }
        return profile;
    }

    private List<Map<String, Object>> collectFiles(Long userId) {
        List<FileVO> files = fileInfoMapper.listFileByUserId(userId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (FileVO f : files) {
            Map<String, Object> item = new HashMap<>();
            item.put("fileId", f.getFileId());
            item.put("fileUuid", f.getFileUuid());
            item.put("name", f.getName());
            item.put("type", f.getType());
            item.put("size", f.getSize());
            item.put("path", f.getPath());
            item.put("dir", f.isDir());
            item.put("createTime", f.getCreateTime() != null ? f.getCreateTime().toString() : null);
            item.put("updateTime", f.getUpdateTime() != null ? f.getUpdateTime().toString() : null);
            result.add(item);
        }
        return result;
    }

    private List<Map<String, Object>> collectChatHistory(Long userId) {
        List<KnowledgeChatSession> sessions = sessionMapper.listByUser(userId, null, MAX_CHAT_SESSIONS);
        List<Map<String, Object>> result = new ArrayList<>();
        for (KnowledgeChatSession session : sessions) {
            Map<String, Object> sessionData = new HashMap<>();
            sessionData.put("sessionId", session.getId());
            sessionData.put("title", session.getTitle());
            sessionData.put("scopeMode", session.getScopeMode());
            sessionData.put("createtime", session.getCreatetime() != null ? session.getCreatetime().toString() : null);
            sessionData.put("updatetime", session.getUpdatetime() != null ? session.getUpdatetime().toString() : null);

            List<KnowledgeChatMessage> messages = messageMapper.listBySessionId(session.getId());
            List<Map<String, Object>> msgList = new ArrayList<>();
            for (KnowledgeChatMessage msg : messages) {
                Map<String, Object> msgData = new HashMap<>();
                msgData.put("sequenceNo", msg.getSequenceNo());
                msgData.put("role", msg.getRole());
                msgData.put("content", msg.getContent());
                msgData.put("taskStatus", msg.getTaskStatus());
                msgData.put("createtime", msg.getCreatetime() != null ? msg.getCreatetime().toString() : null);
                msgList.add(msgData);
            }
            sessionData.put("messages", msgList);
            result.add(sessionData);
        }
        return result;
    }

    private List<Map<String, Object>> collectMemory(Long userId) {
        List<UserMemoryItem> items = memoryMapper.listManaged(userId, null, null, MAX_MEMORY_ITEMS);
        List<Map<String, Object>> result = new ArrayList<>();
        for (UserMemoryItem item : items) {
            Map<String, Object> m = new HashMap<>();
            m.put("memoryType", item.getMemoryType());
            m.put("content", item.getContent());
            m.put("normalizedKey", item.getNormalizedKey());
            m.put("confidence", item.getConfidence());
            m.put("pinned", item.getPinned());
            m.put("memoryStatus", item.getMemoryStatus());
            m.put("createtime", item.getCreatetime() != null ? item.getCreatetime().toString() : null);
            result.add(m);
        }
        return result;
    }

    // ─── 加密工具 ───────────────────────────────────────────────

    private byte[] packageAsZip(Map<String, Object> data) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("export.json"));
            zos.write(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(data));
            zos.closeEntry();
        }
        return baos.toByteArray();
    }

    private SecretKey generateAesKey() throws Exception {
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(AES_KEY_SIZE);
        return keyGen.generateKey();
    }

    private byte[] generateIv() {
        byte[] iv = new byte[GCM_IV_LENGTH];
        new SecureRandom().nextBytes(iv);
        return iv;
    }

    private byte[] encryptAesGcm(byte[] plaintext, SecretKey key, byte[] iv) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.ENCRYPT_MODE, key, spec);
        byte[] ciphertext = cipher.doFinal(plaintext);

        byte[] result = new byte[iv.length + ciphertext.length];
        System.arraycopy(iv, 0, result, 0, iv.length);
        System.arraycopy(ciphertext, 0, result, iv.length, ciphertext.length);
        return result;
    }

    /**
     * 解析服务端主密钥。优先使用配置的 base64 编码密钥，否则生成临时密钥并警告。
     * 生产环境必须通过环境变量 YLCLOUD_EXPORT_MASTER_KEY 配置固定的主密钥。
     */
    private SecretKey resolveMasterKey() {
        if (masterKeyBase64 != null && !masterKeyBase64.isBlank()) {
            byte[] keyBytes = Base64.getDecoder().decode(masterKeyBase64);
            if (keyBytes.length == 32) {
                return new SecretKeySpec(keyBytes, "AES");
            }
            log.warn("Configured master key is not 32 bytes, generating temporary key");
        }
        log.error("!!! No ylcloud.export.master-key configured — data export keys will NOT survive restarts. " +
                "Set YLCLOUD_EXPORT_MASTER_KEY env var with a base64-encoded 256-bit AES key.");
        try {
            return generateAesKey();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate fallback master key", e);
        }
    }

    /**
     * 用主密钥 AES-wrap 包裹数据密钥。使用 AES-GCM 加密数据密钥明文，
     * 返回 base64(IV || ciphertext) 格式。
     */
    private String wrapKey(SecretKey dataKey, SecretKey masterKey) {
        try {
            byte[] wrapIv = generateIv();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, wrapIv);
            cipher.init(Cipher.ENCRYPT_MODE, masterKey, spec);
            byte[] wrapped = cipher.doFinal(dataKey.getEncoded());

            byte[] combined = new byte[wrapIv.length + wrapped.length];
            System.arraycopy(wrapIv, 0, combined, 0, wrapIv.length);
            System.arraycopy(wrapped, 0, combined, wrapIv.length, wrapped.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new RuntimeException("Failed to wrap data key", e);
        }
    }

    /**
     * 解包密钥并返回 base64 编码的数据密钥明文，供用户解密导出文件。
     */
    private String unwrapToBase64(String wrappedBase64) {
        try {
            SecretKey masterKey = resolveMasterKey();
            byte[] combined = Base64.getDecoder().decode(wrappedBase64);

            byte[] wrapIv = new byte[GCM_IV_LENGTH];
            byte[] wrapped = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, 0, wrapIv, 0, GCM_IV_LENGTH);
            System.arraycopy(combined, GCM_IV_LENGTH, wrapped, 0, wrapped.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, wrapIv);
            cipher.init(Cipher.DECRYPT_MODE, masterKey, spec);
            byte[] unwrapped = cipher.doFinal(wrapped);

            return Base64.getEncoder().encodeToString(unwrapped);
        } catch (Exception e) {
            log.error("Failed to unwrap data key", e);
            return null;
        }
    }

    private String sha256Hex(byte[] data) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            return java.util.HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ─── 工具方法 ───────────────────────────────────────────────

    private String normalizeScope(String scope) {
        if (scope == null || scope.isBlank()) return "FULL";
        return switch (scope.toUpperCase()) {
            case "FULL", "PERSONAL_FILES", "SPACE_DATA", "CHAT_HISTORY", "MEMORY" -> scope.toUpperCase();
            default -> "FULL";
        };
    }

    private DataExportJobVO toVO(DataExportJob job) {
        DataExportJobVO vo = new DataExportJobVO();
        vo.setId(job.getId());
        vo.setStatus(job.getStatus());
        vo.setExportScope(job.getExportScope());
        vo.setFileSizeBytes(job.getFileSizeBytes());
        vo.setDownloadUrl(job.getDownloadUrl());
        vo.setDownloadExpiresAt(job.getDownloadExpiresAt());
        vo.setCreatedAt(job.getCreatedAt());
        vo.setFinishedAt(job.getFinishedAt());
        return vo;
    }

    private String truncate(String message) {
        if (message == null) return null;
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }
}
