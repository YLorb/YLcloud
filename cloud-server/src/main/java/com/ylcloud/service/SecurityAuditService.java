package com.ylcloud.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.DTO.AuditQueryDTO;
import com.ylcloud.VO.AuditRetentionConfigVO;
import com.ylcloud.VO.SecurityAuditEventVO;
import com.ylcloud.entity.AuditRetentionConfig;
import com.ylcloud.entity.SecurityAuditEvent;
import com.ylcloud.mapper.SecurityAuditMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * TASK-012: 统一安全审计服务。
 * 不可变审计事件模型，统一写入器，字段白名单和脱敏。
 * 审计写入异常不能绕过关键安全拒绝。
 */
@Service
@Slf4j
public class SecurityAuditService {
    private static final DateTimeFormatter ISO_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final int DEFAULT_RETENTION_DAYS = 7;
    private static final int CLEANUP_BATCH_SIZE = 500;

    // 敏感字段白名单：这些字段会被脱敏或移除
    private static final Set<String> SENSITIVE_FIELDS = Set.of(
            "password", "secret", "token", "apiKey", "api_key", "accessKey", "secretKey",
            "authorization", "cookie", "session", "credential", "privateKey", "private_key"
    );

    // 敏感值模式
    private static final Pattern SENSITIVE_PATTERN = Pattern.compile(
            "(?i)(password|secret|token|key|credential|authorization)[\"']?\\s*[:=]\\s*[\"']?[^\"',\\s}{]+",
            Pattern.CASE_INSENSITIVE
    );

    private final SecurityAuditMapper mapper;
    private final ObjectMapper objectMapper;

    public SecurityAuditService(SecurityAuditMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 记录审计事件（统一写入器）。
     * 审计写入失败不应阻断业务流程，但必须记录错误日志。
     */
    public void record(AuditEventBuilder builder) {
        try {
            SecurityAuditEvent event = builder.build();
            // 脱敏处理
            sanitize(event);
            mapper.insert(event);
        } catch (Exception e) {
            // 审计写入失败不阻断业务，但必须记录
            log.error("Failed to write audit event: type={}, action={}, subject={}",
                    builder.eventType, builder.action, builder.subjectId, e);
        }
    }

    /**
     * 记录安全关键事件（永久保留）。
     */
    public void recordCritical(AuditEventBuilder builder) {
        builder.retentionPolicy("PERMANENT");
        record(builder);
    }

    /**
     * 记录成功事件。
     */
    public void recordSuccess(String eventType, String action, Long subjectId, String subjectName,
                              String targetType, String targetId, String targetName, Map<String, Object> detail) {
        record(new AuditEventBuilder()
                .eventType(eventType)
                .action(action)
                .subject(subjectId, subjectName)
                .target(targetType, targetId, targetName)
                .result("SUCCESS")
                .detail(detail));
    }

    /**
     * 记录失败事件。
     */
    public void recordFailure(String eventType, String action, Long subjectId, String subjectName,
                              String targetType, String targetId, String targetName,
                              String errorMessage, Map<String, Object> detail) {
        record(new AuditEventBuilder()
                .eventType(eventType)
                .action(action)
                .subject(subjectId, subjectName)
                .target(targetType, targetId, targetName)
                .result("FAILURE")
                .errorMessage(errorMessage)
                .detail(detail));
    }

    /**
     * 记录拒绝事件。
     */
    public void recordDenied(String eventType, String action, Long subjectId, String subjectName,
                             String targetType, String targetId, String targetName, String reason) {
        record(new AuditEventBuilder()
                .eventType(eventType)
                .action(action)
                .subject(subjectId, subjectName)
                .target(targetType, targetId, targetName)
                .result("DENIED")
                .errorMessage(reason));
    }

    /**
     * 查询审计事件。
     */
    public List<SecurityAuditEventVO> query(AuditQueryDTO dto) {
        int limit = dto.getLimit() == null || dto.getLimit() <= 0 ? 100 : Math.min(dto.getLimit(), 1000);
        LocalDateTime from = parseTime(dto.getFrom(), LocalDateTime.now().minusDays(7));
        LocalDateTime to = parseTime(dto.getTo(), LocalDateTime.now().plusDays(1));

        List<SecurityAuditEvent> events;
        if (dto.getTraceId() != null && !dto.getTraceId().isBlank()) {
            events = mapper.listByTrace(dto.getTraceId());
        } else if (dto.getSubjectId() != null) {
            events = mapper.listBySubject(dto.getSubjectId(), from, limit);
        } else if (dto.getTargetType() != null && dto.getTargetId() != null) {
            events = mapper.listByTarget(dto.getTargetType(), dto.getTargetId(), limit);
        } else if (dto.getEventType() != null) {
            events = mapper.listByTypeAndTime(dto.getEventType(), from, to, limit);
        } else {
            events = mapper.listByTypeAndTime("SECURITY", from, to, limit);
        }

        return events.stream().map(this::toVO).toList();
    }

    /**
     * 获取保留配置。
     */
    public List<AuditRetentionConfigVO> listRetentionConfigs() {
        return mapper.listRetentionConfigs().stream().map(this::toConfigVO).toList();
    }

    /**
     * 更新保留配置（仅 ADMIN）。
     */
    @Transactional
    public AuditRetentionConfigVO updateRetentionConfig(String configKey, Integer retentionDays,
                                                         Boolean permanent, String description) {
        AuditRetentionConfig existing = mapper.getRetentionConfig(configKey);
        if (existing == null) {
            throw new IllegalArgumentException("保留配置不存在: " + configKey);
        }

        // 永久保留配置不能被改为非永久
        if (Boolean.TRUE.equals(existing.getPermanent()) && !Boolean.TRUE.equals(permanent)) {
            throw new IllegalArgumentException("永久保留配置不能降级为非永久");
        }

        mapper.updateRetentionConfig(configKey, retentionDays, permanent, description, LocalDateTime.now());
        return toConfigVO(mapper.getRetentionConfig(configKey));
    }

    /**
     * 定时清理过期审计事件。
     * 仅清理 STANDARD 保留策略的事件，PERMANENT 事件永不清理。
     */
    @Scheduled(cron = "${ylcloud.audit.cleanup-cron:0 0 3 * * ?}")
    @Transactional
    public void cleanupExpiredEvents() {
        try {
            AuditRetentionConfig defaultConfig = mapper.getRetentionConfig("DEFAULT");
            int retentionDays = defaultConfig != null && defaultConfig.getRetentionDays() != null
                    ? defaultConfig.getRetentionDays() : DEFAULT_RETENTION_DAYS;

            LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
            int totalDeleted = 0;
            int deleted;

            do {
                deleted = mapper.cleanupExpired(cutoff, CLEANUP_BATCH_SIZE);
                totalDeleted += deleted;
            } while (deleted == CLEANUP_BATCH_SIZE);

            if (totalDeleted > 0) {
                log.info("Audit cleanup completed: {} events deleted (cutoff={})", totalDeleted, cutoff);
            }
        } catch (Exception e) {
            log.error("Audit cleanup failed", e);
        }
    }

    /**
     * 获取审计统计。
     */
    public Map<String, Object> getStats() {
        return Map.of(
                "permanentEvents", mapper.countPermanentEvents(),
                "recentEvents", mapper.countRecentEvents(LocalDateTime.now().minusDays(7))
        );
    }

    private void sanitize(SecurityAuditEvent event) {
        if (event.getDetailJson() != null) {
            event.setDetailJson(sanitizeJson(event.getDetailJson()));
        }
        if (event.getErrorMessage() != null) {
            event.setErrorMessage(sanitizeText(event.getErrorMessage()));
        }
        if (event.getUserAgent() != null && event.getUserAgent().length() > 500) {
            event.setUserAgent(event.getUserAgent().substring(0, 500));
        }
    }

    private String sanitizeJson(String json) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = objectMapper.readValue(json, Map.class);
            sanitizeMap(map);
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            // 如果解析失败，使用正则脱敏
            return sanitizeText(json);
        }
    }

    private void sanitizeMap(Map<String, Object> map) {
        for (String key : map.keySet()) {
            String lowerKey = key.toLowerCase();
            if (SENSITIVE_FIELDS.stream().anyMatch(lowerKey::contains)) {
                map.put(key, "[REDACTED]");
            } else if (map.get(key) instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> nested = (Map<String, Object>) map.get(key);
                sanitizeMap(nested);
            }
        }
    }

    private String sanitizeText(String text) {
        if (text == null) return null;
        return SENSITIVE_PATTERN.matcher(text).replaceAll("$1=[REDACTED]");
    }

    private LocalDateTime parseTime(String time, LocalDateTime defaultValue) {
        if (time == null || time.isBlank()) return defaultValue;
        try {
            return LocalDateTime.parse(time, ISO_FORMAT);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private SecurityAuditEventVO toVO(SecurityAuditEvent event) {
        SecurityAuditEventVO vo = new SecurityAuditEventVO();
        vo.setEventId(event.getEventId());
        vo.setEventType(event.getEventType());
        vo.setSubjectType(event.getSubjectType());
        vo.setSubjectId(event.getSubjectId());
        vo.setSubjectName(event.getSubjectName());
        vo.setTargetType(event.getTargetType());
        vo.setTargetId(event.getTargetId());
        vo.setTargetName(event.getTargetName());
        vo.setAction(event.getAction());
        vo.setResult(event.getResult());
        vo.setTraceId(event.getTraceId());
        vo.setIpAddress(event.getIpAddress());
        vo.setDetailJson(event.getDetailJson());
        vo.setErrorMessage(event.getErrorMessage());
        vo.setRetentionPolicy(event.getRetentionPolicy());
        vo.setOccurredAt(event.getOccurredAt());
        return vo;
    }

    private AuditRetentionConfigVO toConfigVO(AuditRetentionConfig config) {
        AuditRetentionConfigVO vo = new AuditRetentionConfigVO();
        vo.setConfigKey(config.getConfigKey());
        vo.setRetentionDays(config.getRetentionDays());
        vo.setPermanent(config.getPermanent());
        vo.setDescription(config.getDescription());
        vo.setUpdatedAt(config.getUpdatedAt());
        return vo;
    }

    /**
     * 审计事件构建器。
     */
    public static class AuditEventBuilder {
        private String eventType;
        private String subjectType = "USER";
        private Long subjectId;
        private String subjectName;
        private String targetType;
        private String targetId;
        private String targetName;
        private String action;
        private String result = "SUCCESS";
        private String traceId;
        private String requestId;
        private String ipAddress;
        private String userAgent;
        private Map<String, Object> detail;
        private String errorMessage;
        private String retentionPolicy = "STANDARD";

        public AuditEventBuilder eventType(String eventType) {
            this.eventType = eventType;
            return this;
        }

        public AuditEventBuilder subject(Long subjectId, String subjectName) {
            this.subjectId = subjectId;
            this.subjectName = subjectName;
            return this;
        }

        public AuditEventBuilder subjectType(String subjectType) {
            this.subjectType = subjectType;
            return this;
        }

        public AuditEventBuilder target(String targetType, String targetId, String targetName) {
            this.targetType = targetType;
            this.targetId = targetId;
            this.targetName = targetName;
            return this;
        }

        public AuditEventBuilder action(String action) {
            this.action = action;
            return this;
        }

        public AuditEventBuilder result(String result) {
            this.result = result;
            return this;
        }

        public AuditEventBuilder traceId(String traceId) {
            this.traceId = traceId;
            return this;
        }

        public AuditEventBuilder requestId(String requestId) {
            this.requestId = requestId;
            return this;
        }

        public AuditEventBuilder ipAddress(String ipAddress) {
            this.ipAddress = ipAddress;
            return this;
        }

        public AuditEventBuilder userAgent(String userAgent) {
            this.userAgent = userAgent;
            return this;
        }

        public AuditEventBuilder detail(Map<String, Object> detail) {
            this.detail = detail;
            return this;
        }

        public AuditEventBuilder errorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
            return this;
        }

        public AuditEventBuilder retentionPolicy(String retentionPolicy) {
            this.retentionPolicy = retentionPolicy;
            return this;
        }

        public SecurityAuditEvent build() {
            SecurityAuditEvent event = new SecurityAuditEvent();
            event.setEventKey(UUID.randomUUID().toString());
            event.setEventType(eventType);
            event.setSubjectType(subjectType);
            event.setSubjectId(subjectId);
            event.setSubjectName(subjectName);
            event.setTargetType(targetType);
            event.setTargetId(targetId);
            event.setTargetName(targetName);
            event.setAction(action);
            event.setResult(result);
            event.setTraceId(traceId);
            event.setRequestId(requestId);
            event.setIpAddress(ipAddress);
            event.setUserAgent(userAgent);
            event.setErrorMessage(errorMessage);
            event.setRetentionPolicy(retentionPolicy);
            event.setOccurredAt(LocalDateTime.now());
            event.setCreatedAt(LocalDateTime.now());

            if (detail != null && !detail.isEmpty()) {
                try {
                    event.setDetailJson(new ObjectMapper().writeValueAsString(detail));
                } catch (Exception e) {
                    event.setDetailJson("{}");
                }
            }

            return event;
        }
    }
}
