package com.ylcloud.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.Exception.ConflictException;
import com.ylcloud.VO.DataExportJobVO;
import com.ylcloud.async.task.DomainTaskPayload;
import com.ylcloud.async.task.TaskCreateCommand;
import com.ylcloud.async.task.UnifiedTaskCenterService;
import com.ylcloud.async.worker.TaskExecutionContext;
import com.ylcloud.entity.DataExportJob;
import com.ylcloud.entity.UnifiedAsyncTask;
import com.ylcloud.mapper.DataExportJobMapper;
import com.ylcloud.utils.MinioclientUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 数据导出服务：异步、限时下载的加密数据导出。
 * 导出不得包含 Key 明文、Secret、他人数据或内部敏感字段。
 */
@Service
@Slf4j
public class DataExportService {
    private static final String TASK_DOMAIN = "maintenance";
    private static final String TASK_TYPE = "DATA_EXPORT";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int GCM_IV_LENGTH = 12;

    private final DataExportJobMapper jobMapper;
    private final UnifiedTaskCenterService taskCenter;
    private final MinioclientUtil minioUtil;
    private final ObjectMapper objectMapper;

    @Value("${ylcloud.export.download-expiry-hours:24}")
    private int downloadExpiryHours;

    public DataExportService(DataExportJobMapper jobMapper,
                             UnifiedTaskCenterService taskCenter,
                             MinioclientUtil minioUtil,
                             ObjectMapper objectMapper) {
        this.jobMapper = jobMapper;
        this.taskCenter = taskCenter;
        this.minioUtil = minioUtil;
        this.objectMapper = objectMapper;
    }

    /**
     * 请求数据导出。
     */
    @Transactional
    public DataExportJobVO requestExport(Long userId, String exportScope) {
        // 检查是否有进行中的导出
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

        // 创建统一异步任务
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
            // 标记为运行中
            if (jobMapper.markRunning(jobId, LocalDateTime.now()) != 1) {
                throw new BaseException("导出任务状态已变化");
            }

            context.checkpoint();

            // 收集导出数据
            Map<String, Object> exportData = collectExportData(userId, scope);

            context.checkpoint();

            // 生成加密密钥
            SecretKey secretKey = generateAesKey();
            String keyId = "export-key-" + jobId + "-" + System.currentTimeMillis();

            // 打包并加密
            byte[] zipData = packageAsZip(exportData);
            byte[] encryptedData = encryptAesGcm(zipData, secretKey);

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

            // 标记完成
            jobMapper.markCompleted(jobId, objectName, (long) encryptedData.length, fileHash,
                    downloadUrl, expiresAt, LocalDateTime.now());

            log.info("Data export completed: userId={}, jobId={}, size={}", userId, jobId, encryptedData.length);

            return Map.of(
                    "exportedScope", scope,
                    "fileSize", encryptedData.length,
                    "downloadExpiresAt", expiresAt.toString()
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
     * 获取单个导出任务详情。
     */
    public DataExportJobVO getExport(Long userId, Long jobId) {
        DataExportJob job = jobMapper.getById(jobId);
        if (job == null || !job.getUserId().equals(userId)) {
            throw new BaseException("导出任务不存在");
        }
        return toVO(job);
    }

    private Map<String, Object> collectExportData(Long userId, String scope) {
        Map<String, Object> data = new HashMap<>();
        data.put("exportedAt", LocalDateTime.now().toString());
        data.put("userId", userId);
        data.put("scope", scope);

        // 根据 scope 收集数据
        // 注意：不导出 Key 明文、Secret、他人数据或内部敏感字段
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
        // 收集用户基本信息（不含密码等敏感字段）
        Map<String, Object> profile = new HashMap<>();
        profile.put("note", "Profile data export placeholder");
        return profile;
    }

    private List<Map<String, Object>> collectFiles(Long userId) {
        // 收集用户文件元数据（不含实际内容，内容通过 MinIO 单独导出）
        return List.of(Map.of("note", "Files metadata export placeholder"));
    }

    private List<Map<String, Object>> collectChatHistory(Long userId) {
        // 收集聊天历史
        return List.of(Map.of("note", "Chat history export placeholder"));
    }

    private List<Map<String, Object>> collectMemory(Long userId) {
        // 收集用户记忆
        return List.of(Map.of("note", "Memory export placeholder"));
    }

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
        keyGen.init(256);
        return keyGen.generateKey();
    }

    private byte[] encryptAesGcm(byte[] plaintext, SecretKey key) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        byte[] iv = new byte[GCM_IV_LENGTH];
        new SecureRandom().nextBytes(iv);
        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.ENCRYPT_MODE, key, spec);
        byte[] ciphertext = cipher.doFinal(plaintext);

        // IV + ciphertext
        byte[] result = new byte[iv.length + ciphertext.length];
        System.arraycopy(iv, 0, result, 0, iv.length);
        System.arraycopy(ciphertext, 0, result, iv.length, ciphertext.length);
        return result;
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
