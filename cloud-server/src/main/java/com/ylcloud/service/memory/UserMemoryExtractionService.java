package com.ylcloud.service.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.ylcloud.async.mq.AsyncMqProperties;
import com.ylcloud.async.task.DomainTaskPayload;
import com.ylcloud.async.task.FatalTaskException;
import com.ylcloud.async.task.RetryableTaskException;
import com.ylcloud.async.task.StaleTaskException;
import com.ylcloud.async.task.TaskCreateCommand;
import com.ylcloud.async.task.UnifiedTaskCenterService;
import com.ylcloud.async.worker.StaleWorkerException;
import com.ylcloud.async.worker.TaskCanceledException;
import com.ylcloud.async.worker.TaskExecutionContext;
import com.ylcloud.config.RagProperties;
import com.ylcloud.entity.KnowledgeChatMessage;
import com.ylcloud.entity.UserMemoryExtractionTask;
import com.ylcloud.entity.UserMemoryItem;
import com.ylcloud.mapper.KnowledgeChatMessageMapper;
import com.ylcloud.mapper.UserMemoryExtractionTaskMapper;
import com.ylcloud.service.rag.RagGenerateRequest;
import com.ylcloud.service.rag.RagGenerateResponse;
import com.ylcloud.service.rag.RagModelClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

@Service
public class UserMemoryExtractionService {
    private static final Logger log = LoggerFactory.getLogger(UserMemoryExtractionService.class);
    private static final Pattern SENSITIVE = Pattern.compile("(?i)(password|passwd|secret|api[-_ ]?key|token|身份证|银行卡|密码|密钥|验证码|病历|诊断)");
    private static final String SYSTEM_PROMPT = "你是用户长期记忆抽取器。只能提取用户原文明确表达的稳定事实、偏好、约束或已确认决定；禁止根据助手回答推断。" +
            "忽略密码、令牌、密钥、身份证、银行卡、医疗等敏感信息。返回 JSON 数组，每项含 type、key、content、confidence、userConfirmed；无内容返回 []。";

    private final UserMemoryExtractionTaskMapper taskMapper;
    private final KnowledgeChatMessageMapper messageMapper;
    private final UserMemoryService memoryService;
    private final RagModelClient modelClient;
    private final RagProperties properties;
    private final ObjectMapper objectMapper;
    private final Executor executor;
    private AsyncMqProperties mqProperties;
    private UnifiedTaskCenterService taskCenter;

    public UserMemoryExtractionService(UserMemoryExtractionTaskMapper taskMapper, KnowledgeChatMessageMapper messageMapper,
                                       UserMemoryService memoryService, RagModelClient modelClient, RagProperties properties,
                                       ObjectMapper objectMapper, @Qualifier("ragTaskExecutor") Executor executor) {
        this.taskMapper = taskMapper; this.messageMapper = messageMapper; this.memoryService = memoryService;
        this.modelClient = modelClient; this.properties = properties; this.objectMapper = objectMapper; this.executor = executor;
    }

    @Transactional
    public void enqueue(KnowledgeChatMessage assistant) {
        if(assistant == null || assistant.getSourceMessageId() == null || !memoryService.enabled(assistant.getUserId())) return;
        LocalDateTime now = LocalDateTime.now();
        taskMapper.enqueue(assistant.getId(),assistant.getUserId(),assistant.getSessionId(),assistant.getSourceMessageId(),now);
        UserMemoryExtractionTask task = taskMapper.getByAssistantMessageId(assistant.getId());
        if(task == null) return;
        if(mqMemoryEnabled()) registerAsync(task);
        else executor.execute(() -> process(task.getId()));
    }

    public void process(Long taskId) {
        UserMemoryExtractionTask existing = taskMapper.getById(taskId);
        if(existing != null && hasActiveAsyncTask(existing.getAsyncTaskId())) return;
        if(taskMapper.claim(taskId,LocalDateTime.now()) == 0) return;
        UserMemoryExtractionTask task = taskMapper.getById(taskId);
        try {
            if(!memoryService.enabled(task.getUserId())) { taskMapper.succeed(taskId,LocalDateTime.now()); return; }
            KnowledgeChatMessage source = messageMapper.getOwned(task.getSourceMessageId(),task.getSessionId(),task.getUserId());
            if(source == null || !"user".equals(source.getRole())) throw new IllegalStateException("Memory source user message is missing");
            for(UserMemoryCandidate candidate : extract(source.getContent())) {
                UserMemoryItem item = memoryService.accept(task.getUserId(),task.getSessionId(),source.getId(),source.getContent(),candidate);
                if(item != null && "PENDING".equals(item.getEmbeddingStatus()) && !memoryService.usesUnifiedTasks()) {
                    memoryService.processIndex(item.getId());
                }
            }
            taskMapper.succeed(taskId,LocalDateTime.now());
        } catch(Exception ex) {
            taskMapper.fail(taskId,error(ex),LocalDateTime.now().plusSeconds(backoff(task)),LocalDateTime.now());
            log.warn("User memory extraction failed: taskId={}, type={}",taskId,ex.getClass().getSimpleName());
        }
    }

    public Object executeAsync(Long taskId,Long asyncTaskId,long version,TaskExecutionContext context) {
        UserMemoryExtractionTask task = taskMapper.getById(taskId);
        if(task == null) return java.util.Map.of("alreadyAbsent",true);
        if(!asyncTaskId.equals(task.getAsyncTaskId()) || task.getResourceVersion() == null
                || task.getResourceVersion() != version) throw new StaleTaskException("记忆抽取任务版本已变化");
        if(taskMapper.claimAsync(taskId,asyncTaskId,version,LocalDateTime.now()) == 0) {
            throw new StaleTaskException("记忆抽取任务状态已变化");
        }
        try {
            if(!memoryService.enabled(task.getUserId())) throw new StaleTaskException("用户记忆功能已关闭");
            KnowledgeChatMessage source=requireSource(task);
            String original=source.getContent();
            context.checkpoint();
            List<UserMemoryCandidate> candidates;
            try {
                candidates=extract(original);
            } catch(JsonProcessingException malformed) {
                throw new FatalTaskException("用户记忆模型响应格式无效");
            } catch(Exception transientFailure) {
                throw new RetryableTaskException("用户记忆模型暂时不可用");
            }
            context.checkpoint();
            KnowledgeChatMessage latest=requireSource(task);
            if(!java.util.Objects.equals(original,latest.getContent())) throw new StaleTaskException("记忆来源内容已变化");
            int created=0;
            for(UserMemoryCandidate candidate:candidates) {
                context.checkpoint();
                UserMemoryItem item=memoryService.acceptFromTask(task.getUserId(),task.getSessionId(),latest.getId(),
                        latest.getContent(),candidate,asyncTaskId);
                if(item != null) created++;
            }
            context.checkpoint();
            if(taskMapper.succeedAsync(taskId,asyncTaskId,version,LocalDateTime.now()) != 1) {
                throw new StaleTaskException("记忆抽取结果被版本栅栏拒绝");
            }
            return java.util.Map.of("candidateCount",created);
        } catch(TaskCanceledException canceled) {
            taskMapper.skipAsync(taskId,asyncTaskId,version,"Unified task canceled",LocalDateTime.now());
            throw canceled;
        } catch(StaleTaskException stale) {
            taskMapper.skipAsync(taskId,asyncTaskId,version,"Memory source is stale",LocalDateTime.now());
            throw stale;
        } catch(StaleWorkerException staleWorker) {
            throw staleWorker;
        } catch(RuntimeException failure) {
            taskMapper.failAsync(taskId,asyncTaskId,version,safeFailure(failure),LocalDateTime.now());
            throw failure;
        }
    }

    List<UserMemoryCandidate> extract(String userText) throws Exception {
        RagGenerateRequest request = new RagGenerateRequest();
        request.setModel(properties.getModelService().getChatModel()); request.setSystemPrompt(SYSTEM_PROMPT);
        request.setPrompt("用户原文：\n" + userText); request.setMaxTokens(700); request.setTemperature(0.0);
        RagGenerateResponse response = modelClient.generate(request);
        String json = stripFence(response == null ? null : response.getText());
        if(json == null || json.isBlank()) return List.of();
        JsonNode root = objectMapper.readTree(json);
        if(!root.isArray()) throw new IllegalStateException("Memory extraction response must be a JSON array");
        List<UserMemoryCandidate> result = new ArrayList<>();
        for(JsonNode node : root) {
            String content = text(node,"content"); String key = text(node,"key");
            double confidence = node.path("confidence").asDouble(0);
            if(content.isBlank() || key.isBlank() || confidence < 0.65 || SENSITIVE.matcher(content + " " + key).find()) continue;
            result.add(new UserMemoryCandidate(text(node,"type").toUpperCase(Locale.ROOT),key,content,confidence,node.path("userConfirmed").asBoolean(false)));
            if(result.size() == 5) break;
        }
        return result;
    }

    @Scheduled(fixedDelayString = "${ylcloud.memory.extraction-delay-ms:30000}", initialDelayString = "${ylcloud.memory.extraction-initial-delay-ms:20000}")
    public void recover() {
        if(mqMemoryEnabled()) {
            taskMapper.recoverUnboundInterrupted(LocalDateTime.now().minusMinutes(10),LocalDateTime.now());
            taskMapper.listPending(100,maxRetries()).forEach(this::registerAsync);
            return;
        }
        taskMapper.recoverInterrupted(LocalDateTime.now().minusMinutes(10),LocalDateTime.now());
        taskMapper.listPending(100,maxRetries()).forEach(task -> executor.execute(() -> process(task.getId())));
    }

    private KnowledgeChatMessage requireSource(UserMemoryExtractionTask task) {
        KnowledgeChatMessage source=messageMapper.getOwned(task.getSourceMessageId(),task.getSessionId(),task.getUserId());
        if(source == null || !"user".equals(source.getRole())) throw new StaleTaskException("记忆来源已删除或不可用");
        return source;
    }

    private void registerAsync(UserMemoryExtractionTask extraction) {
        long version=extraction.getResourceVersion()==null?1:extraction.getResourceVersion();
        com.ylcloud.entity.UnifiedAsyncTask task=taskCenter.createTask(new TaskCreateCommand(
                "memory-extract:"+extraction.getAssistantMessageId()+":"+version,"memory","MEMORY_EXTRACT",
                new DomainTaskPayload(extraction.getId()),extraction.getUserId(),null,
                "user-memory:"+extraction.getUserId()+":"+extraction.getSourceMessageId(),version));
        taskMapper.bindAsyncTask(extraction.getId(),version,task.getId(),LocalDateTime.now());
    }

    @Autowired(required=false)
    public void setAsyncTaskInfrastructure(AsyncMqProperties properties,UnifiedTaskCenterService taskCenter) {
        this.mqProperties=properties;
        this.taskCenter=taskCenter;
    }

    private boolean mqMemoryEnabled() {
        return mqProperties != null && taskCenter != null && mqProperties.isEnabled() && mqProperties.isMemory();
    }

    private boolean hasActiveAsyncTask(Long taskId) {
        return taskCenter != null && taskCenter.isActive(taskId);
    }

    private int maxRetries() {
        Integer configured=properties.getMemory().getMaxRetries();
        return configured==null||configured<=0?8:configured;
    }

    private String stripFence(String value) { if(value == null) return null; String text = value.trim(); if(text.startsWith("```")) { int first = text.indexOf('\n'); int last = text.lastIndexOf("```"); if(first >= 0 && last > first) text = text.substring(first + 1,last).trim(); } return text; }
    private String text(JsonNode node, String field) { return node.path(field).asText("").trim(); }
    private long backoff(UserMemoryExtractionTask task) { int attempt = Math.min(10,(task.getRetryCount() == null ? 0 : task.getRetryCount()) + 1); return Math.min(3600,1L << attempt); }
    private String error(Exception ex) { return safeFailure(ex); }
    private String safeFailure(Throwable failure) {
        if(failure instanceof StaleTaskException) return "Memory source is stale";
        if(failure instanceof FatalTaskException) return "Memory model response is invalid";
        return "Memory processing is temporarily unavailable";
    }
}
