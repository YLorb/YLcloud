package com.ylcloud.async.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.DTO.AsyncDemoCreateDTO;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.Exception.ConflictException;
import com.ylcloud.Exception.NotFoundException;
import com.ylcloud.async.TaskDispatchEnvelope;
import com.ylcloud.async.mq.AsyncMqProperties;
import com.ylcloud.async.mq.RabbitTaskTopology;
import com.ylcloud.entity.UnifiedAsyncTask;
import com.ylcloud.mapper.AccessControlMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class UnifiedTaskCenterService {
    private static final List<String> TERMINAL = List.of("SUCCESS","FAILED","CANCELED","SKIPPED_STALE");
    private final UnifiedAsyncTaskMapper mapper;
    private final TaskAuthorizationService authorizationService;
    private final AccessControlMapper auditMapper;
    private final AsyncMqProperties properties;
    private final ObjectMapper objectMapper;

    public UnifiedTaskCenterService(UnifiedAsyncTaskMapper mapper,
                                    TaskAuthorizationService authorizationService,
                                    AccessControlMapper auditMapper,
                                    AsyncMqProperties properties,
                                    ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.authorizationService = authorizationService;
        this.auditMapper = auditMapper;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public UnifiedAsyncTask createDemo(AsyncDemoCreateDTO dto, Long userId) {
        String rawKey = dto.getIdempotencyKey() == null || dto.getIdempotencyKey().isBlank()
                ? dto.getText() + "|" + dto.getDelayMs() + "|" + dto.getMode()
                : dto.getIdempotencyKey().trim();
        String digest = sha256(userId + "|" + rawKey);
        UnifiedAsyncTask task = new UnifiedAsyncTask();
        task.setTaskKey("async-demo:" + userId + ":" + digest);
        task.setTaskDomain("maintenance");
        task.setTaskType("ASYNC_DEMO");
        task.setPayloadJson(writeJson(dto));
        task.setCreatedBy(userId);
        task.setResourceKey("async-demo:" + userId + ":" + digest);
        task.setResourceVersion(1L);
        task.setStatus("PENDING_PUBLISH");
        task.setAttemptVersion(0);
        task.setNextTriggerType("INITIAL");
        task.setMaxAttempts(properties.getMaxAttempts());
        LocalDateTime now = LocalDateTime.now();
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        try {
            mapper.insertTask(task);
            enqueue(task,now);
            return task;
        } catch(DuplicateKeyException duplicate) {
            return mapper.getByTaskKey(task.getTaskKey());
        }
    }

    public UnifiedAsyncTask requireVisible(Long taskId, Long userId) {
        UnifiedAsyncTask task = mapper.getById(taskId);
        if(task == null) throw new NotFoundException("统一任务不存在");
        authorizationService.requireView(task,userId);
        return task;
    }

    @Transactional
    public UnifiedAsyncTask claim(TaskDispatchEnvelope envelope, String workerId, String leaseToken) {
        if(envelope.schemaVersion() != 1 || envelope.taskId() == null || envelope.expectedAttemptVersion() == null) {
            throw new FatalTaskException("不支持或不完整的任务消息协议");
        }
        LocalDateTime now = LocalDateTime.now();
        if(mapper.claimTask(envelope.taskId(),envelope.expectedAttemptVersion(),leaseToken,workerId,
                now.plusSeconds(properties.getLeaseSeconds()),now) != 1) return null;
        UnifiedAsyncTask claimed = mapper.getById(envelope.taskId());
        mapper.insertAttempt(claimed.getId(),claimed.getAttemptVersion(),claimed.getNextTriggerType(),
                workerId,leaseToken,null,null,now);
        if(mapper.latestResourceVersion(claimed.getResourceKey()) > claimed.getResourceVersion()) {
            finishStale(claimed,leaseToken,new StaleTaskException("资源版本已过期"));
            return null;
        }
        return claimed;
    }

    @Transactional
    public boolean heartbeat(UnifiedAsyncTask task, String leaseToken) {
        LocalDateTime now = LocalDateTime.now();
        int changed = mapper.heartbeat(task.getId(),task.getAttemptVersion(),leaseToken,
                now.plusSeconds(properties.getLeaseSeconds()),now);
        if(changed == 1) mapper.heartbeatAttempt(task.getId(),task.getAttemptVersion(),leaseToken,now);
        return changed == 1;
    }

    @Transactional
    public boolean complete(UnifiedAsyncTask task, String leaseToken, Object result) {
        LocalDateTime now = LocalDateTime.now();
        if(mapper.markSuccess(task.getId(),task.getAttemptVersion(),leaseToken,writeJson(result),
                expireAt(now),now) != 1) return false;
        mapper.finishAttempt(task.getId(),task.getAttemptVersion(),leaseToken,"SUCCESS",
                null,null,null,false,null,now);
        return true;
    }

    @Transactional
    public void fail(UnifiedAsyncTask task, String leaseToken, TaskFailure failure) {
        if(failure.stale()) {
            finishStale(task,leaseToken,new StaleTaskException(failure.message()));
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        boolean retry = failure.retryable() && task.getAttemptVersion() < task.getMaxAttempts();
        String status = retry ? "RETRY_WAIT" : "FAILED";
        LocalDateTime next = retry ? now.plusSeconds(properties.getRetrySeconds()) : null;
        if(mapper.markFailure(task.getId(),task.getAttemptVersion(),leaseToken,status,next,
                retry ? "AUTO_RETRY" : task.getNextTriggerType(),failure.type(),failure.code(),
                failure.message(),retry ? null : expireAt(now),now) != 1) return;
        mapper.finishAttempt(task.getId(),task.getAttemptVersion(),leaseToken,status,
                failure.type(),failure.code(),failure.message(),failure.retryable(),next,now);
        if(retry) {
            UnifiedAsyncTask updated = mapper.getById(task.getId());
            enqueue(updated,next);
        }
    }

    @Transactional
    public boolean checkpointCancel(UnifiedAsyncTask task, String leaseToken) {
        UnifiedAsyncTask latest = mapper.getById(task.getId());
        if(latest == null || latest.getCancelRequestedAt() == null) return false;
        LocalDateTime now = LocalDateTime.now();
        if(mapper.markRunningCanceled(task.getId(),task.getAttemptVersion(),leaseToken,expireAt(now),now) == 1) {
            mapper.finishAttempt(task.getId(),task.getAttemptVersion(),leaseToken,"CANCELED",
                    "CANCELED","USER_CANCELED",latest.getCancelReason(),false,null,now);
        }
        return true;
    }

    @Transactional
    public void retry(Long taskId, Long operatorId, String reason) {
        UnifiedAsyncTask task = mapper.getByIdForUpdate(taskId);
        if(task == null) throw new NotFoundException("统一任务不存在");
        authorizationService.requireOperate(task,operatorId);
        if(!"FAILED".equals(task.getStatus())) throw new ConflictException("只有 FAILED 任务可以人工重试");
        LocalDateTime now = LocalDateTime.now();
        if(mapper.manualRetry(taskId,now) != 1) throw new ConflictException("任务状态已变化");
        enqueue(mapper.getById(taskId),now);
        auditMapper.insertAudit(operatorId,"ASYNC_TASK",taskId,"MANUAL_RETRY",
                "{\"status\":\"FAILED\"}",writeJson(java.util.Map.of("status","RETRY_WAIT","reason",safe(reason))));
    }

    @Transactional
    public void cancel(Long taskId, Long operatorId, String reason) {
        UnifiedAsyncTask task = mapper.getByIdForUpdate(taskId);
        if(task == null) throw new NotFoundException("统一任务不存在");
        authorizationService.requireOperate(task,operatorId);
        if(TERMINAL.contains(task.getStatus())) throw new ConflictException("终态任务不能取消");
        LocalDateTime now = LocalDateTime.now();
        int changed = "RUNNING".equals(task.getStatus())
                ? mapper.requestRunningCancel(taskId,operatorId,safe(reason),now)
                : mapper.cancelWaiting(taskId,operatorId,safe(reason),expireAt(now),now);
        if(changed != 1) throw new ConflictException("任务状态已变化");
        auditMapper.insertAudit(operatorId,"ASYNC_TASK",taskId,"CANCEL",
                writeJson(java.util.Map.of("status",task.getStatus())),
                writeJson(java.util.Map.of("reason",safe(reason))));
    }

    @Scheduled(fixedDelayString = "${YLCLOUD_ASYNC_MQ_RECOVERY_DELAY_MS:10000}")
    public void recoverExpiredLeases() {
        if(!properties.isEnabled()) return;
        for(Long taskId : mapper.listExpiredTaskIds(LocalDateTime.now(),50)) recoverExpired(taskId);
    }

    @Transactional
    public void recoverExpired(Long taskId) {
        UnifiedAsyncTask task = mapper.getByIdForUpdate(taskId);
        LocalDateTime now = LocalDateTime.now();
        if(task == null || !"RUNNING".equals(task.getStatus()) || task.getLeaseUntil() == null
                || !task.getLeaseUntil().isBefore(now)) return;
        fail(task,task.getLeaseToken(),new TaskFailure(
                "TRANSIENT","LEASE_EXPIRED","Worker 租约已过期",true,false
        ));
    }

    @Scheduled(fixedDelayString = "${YLCLOUD_ASYNC_MQ_RETENTION_DELAY_MS:3600000}")
    @Transactional
    public void cleanExpired() {
        if(!properties.isEnabled()) return;
        LocalDateTime now = LocalDateTime.now();
        mapper.deleteExpiredInbox(now,200);
        mapper.deleteExpiredOutbox(now,200);
        mapper.deleteExpiredAttempts(now,200);
        mapper.deleteExpiredTasks(now,200);
    }

    private void finishStale(UnifiedAsyncTask task, String leaseToken, StaleTaskException error) {
        LocalDateTime now = LocalDateTime.now();
        String message = error.getMessage();
        if(mapper.markFailure(task.getId(),task.getAttemptVersion(),leaseToken,"SKIPPED_STALE",null,
                task.getNextTriggerType(),"STALE","RESOURCE_VERSION_STALE",message,expireAt(now),now) == 1) {
            mapper.finishAttempt(task.getId(),task.getAttemptVersion(),leaseToken,"SKIPPED_STALE",
                    "STALE","RESOURCE_VERSION_STALE",message,false,null,now);
        }
    }

    private void enqueue(UnifiedAsyncTask task, LocalDateTime dueAt) {
        String messageId = UUID.randomUUID().toString();
        TaskDispatchEnvelope envelope = new TaskDispatchEnvelope(
                1,messageId,"TASK_DISPATCH",task.getId(),task.getTaskDomain(),task.getTaskType(),
                task.getAttemptVersion(),task.getResourceKey(),task.getResourceVersion(),Instant.now(),
                UUID.randomUUID().toString()
        );
        mapper.insertOutbox(messageId,task.getId(),task.getAttemptVersion(),RabbitTaskTopology.TASK_EXCHANGE,
                routingKey(task.getTaskDomain()),writeJson(envelope),dueAt,LocalDateTime.now());
    }

    private String routingKey(String domain) {
        String normalized = "chat/workflow".equals(domain) ? "chat" : domain;
        if(!List.of("cleanup","maintenance","memory","chat","knowledge","rag").contains(normalized)) {
            throw new BaseException("不支持的任务域");
        }
        return "task." + normalized;
    }

    private LocalDateTime expireAt(LocalDateTime now) {
        return now.plusDays(properties.getRetentionDays());
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch(JsonProcessingException e) {
            throw new BaseException("任务数据序列化失败",e);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch(Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "未填写" : value.trim();
    }
}
