package com.ylcloud.workflow.contract;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * YLcloud 与 Workflow 服务共享的 1.0 契约 DTO。
 *
 * <p>JSON Schema 位于仓库根目录 {@code schemas/}，是唯一权威来源。本类保持手写 DTO，
 * 由双端契约测试校验字段、枚举与共享样例一致，避免生成代码污染业务提交。</p>
 */
public final class WorkflowContracts {

    public static final String CONTRACT_VERSION = "1.0";
    private static final String SHA_256_PATTERN = "^[a-f0-9]{64}$";

    private WorkflowContracts() {
    }

    public enum WorkflowRunStatus {
        QUEUED,
        PLANNING,
        VALIDATING,
        RUNNING,
        SUCCEEDED,
        DEGRADED,
        FAILED,
        TIMED_OUT,
        CANCELLED,
        ABANDONED;

        public boolean isTerminal() {
            return switch (this) {
                case SUCCEEDED, DEGRADED, FAILED, TIMED_OUT, CANCELLED, ABANDONED -> true;
                default -> false;
            };
        }
    }

    public enum JavaMessageStatus {
        QUEUED,
        RUNNING,
        SUCCESS,
        FAILED
    }

    public enum WorkflowType {
        ASSISTANT
    }

    public enum WorkflowVersion {
        @com.fasterxml.jackson.annotation.JsonProperty("1.0")
        V1,
        @com.fasterxml.jackson.annotation.JsonProperty("2.0")
        V2
    }

    public enum RiskLevel {
        READ_ONLY,
        WRITE,
        HIGH
    }

    public enum IntentType {
        GENERAL_CHAT,
        KNOWLEDGE_QA,
        EXTERNAL_ACTION,
        MEMORY_OPERATION,
        MIXED
    }

    public enum ContractErrorCode {
        INVALID_REQUEST,
        UNSUPPORTED_VERSION,
        UNAUTHORIZED,
        FORBIDDEN,
        NOT_FOUND,
        CONFLICT,
        BUDGET_EXCEEDED,
        TOOL_CONFIRMATION_REQUIRED,
        TIMED_OUT,
        INTERNAL_ERROR
    }

    public record StatusMapping(
            JavaMessageStatus javaStatus,
            WorkflowRunStatus workflowStatus,
            boolean degraded
    ) {
        public StatusMapping {
            Objects.requireNonNull(javaStatus, "javaStatus");
            Objects.requireNonNull(workflowStatus, "workflowStatus");
            JavaMessageStatus expectedStatus = switch (workflowStatus) {
                case QUEUED -> JavaMessageStatus.QUEUED;
                case PLANNING, VALIDATING, RUNNING -> JavaMessageStatus.RUNNING;
                case SUCCEEDED, DEGRADED -> JavaMessageStatus.SUCCESS;
                case FAILED, TIMED_OUT, CANCELLED, ABANDONED -> JavaMessageStatus.FAILED;
            };
            boolean expectedDegraded = workflowStatus == WorkflowRunStatus.DEGRADED;
            if (javaStatus != expectedStatus || degraded != expectedDegraded) {
                throw new IllegalArgumentException("inconsistent Java/Workflow status mapping");
            }
        }
    }

    /** 将 Workflow 二级状态转换为 Java/前端一级状态。 */
    public static StatusMapping mapJavaStatus(WorkflowRunStatus status) {
        Objects.requireNonNull(status, "status");
        return switch (status) {
            case QUEUED -> new StatusMapping(JavaMessageStatus.QUEUED, status, false);
            case PLANNING, VALIDATING, RUNNING -> new StatusMapping(JavaMessageStatus.RUNNING, status, false);
            case SUCCEEDED -> new StatusMapping(JavaMessageStatus.SUCCESS, status, false);
            case DEGRADED -> new StatusMapping(JavaMessageStatus.SUCCESS, status, true);
            case FAILED, TIMED_OUT, CANCELLED, ABANDONED ->
                    new StatusMapping(JavaMessageStatus.FAILED, status, false);
        };
    }

    public record ContractError(
            @NotBlank String contractVersion,
            @NotNull ContractErrorCode code,
            @NotBlank @Size(max = 512) String message,
            boolean retryable,
            @Size(max = 32) Map<String, Object> details
    ) {
        public ContractError {
            requireContractVersion(contractVersion);
            if (details != null) {
                details = Map.copyOf(details);
            }
        }
    }

    public record IntentTask(
            @NotBlank @Pattern(regexp = "^[A-Za-z_][A-Za-z0-9_-]{0,63}$") String taskId,
            @NotNull IntentType intent,
            @NotBlank @Size(max = 8_000) String instruction,
            @NotNull @Size(max = 5) List<@NotBlank String> dependencies
    ) {
        public IntentTask {
            dependencies = List.copyOf(dependencies);
            requireUnique(dependencies, "dependencies");
            if (dependencies.contains(taskId)) {
                throw new IllegalArgumentException("intent task cannot depend on itself");
            }
        }
    }

    public record IntentPlan(
            @NotBlank String planVersion,
            @NotNull IntentType primaryIntent,
            @NotNull @Size(min = 1, max = 6) List<@Valid IntentTask> tasks
    ) {
        public IntentPlan {
            if (!CONTRACT_VERSION.equals(planVersion)) {
                throw new IllegalArgumentException("unsupported planVersion: " + planVersion);
            }
            tasks = List.copyOf(tasks);
            Set<String> taskIds = tasks.stream()
                    .map(IntentTask::taskId)
                    .collect(java.util.stream.Collectors.toSet());
            if (taskIds.size() != tasks.size()) {
                throw new IllegalArgumentException("intent task ids must be unique");
            }
            for (IntentTask task : tasks) {
                if (!taskIds.containsAll(task.dependencies())) {
                    throw new IllegalArgumentException("intent task dependency is unknown");
                }
            }
        }
    }

    public record ShortTermMessage(
            @NotNull Role role,
            @NotNull @Size(max = 32_000) String content
    ) {
        public enum Role {
            USER,
            ASSISTANT,
            SYSTEM
        }
    }

    public record RunBudget(
            @Min(1) @Max(6) int maxBusinessNodes,
            @Min(1) @Max(3) int maxParallelNodes,
            @Min(1) @Max(12) int maxModelCalls,
            @Min(1_000) @Max(300_000) int maxRuntimeMs
    ) {
        public RunBudget {
            if (maxParallelNodes > maxBusinessNodes) {
                throw new IllegalArgumentException("parallel node budget cannot exceed business node budget");
            }
        }
    }

    public record KnowledgeScopeDecision(
            boolean explicitSelection,
            @NotNull @Size(max = 32) List<@Positive Long> selectedSpaceIds,
            @NotNull @Size(max = 2) List<@Positive Long> autoAddedSpaceIds
    ) {
        public KnowledgeScopeDecision {
            selectedSpaceIds = List.copyOf(selectedSpaceIds);
            autoAddedSpaceIds = List.copyOf(autoAddedSpaceIds);
            requireUnique(selectedSpaceIds, "selectedSpaceIds");
            requireUnique(autoAddedSpaceIds, "autoAddedSpaceIds");
            Set<Long> overlap = new HashSet<>(selectedSpaceIds);
            overlap.retainAll(autoAddedSpaceIds);
            if (!overlap.isEmpty()) {
                throw new IllegalArgumentException("selected and auto-added spaces cannot overlap");
            }
            if (explicitSelection && !autoAddedSpaceIds.isEmpty()) {
                throw new IllegalArgumentException("explicit knowledge selection cannot auto-add spaces");
            }
        }
    }

    public record WorkflowRunCreateRequest(
            @NotBlank String contractVersion,
            @NotNull WorkflowType workflowType,
            @NotNull WorkflowVersion workflowVersion,
            @Positive long userId,
            @Positive long sessionId,
            @Positive long sourceMessageId,
            @Positive long assistantMessageId,
            @NotBlank @Size(max = 32_000) String question,
            @NotNull @Size(max = 50) List<@Valid ShortTermMessage> shortTermContext,
            @NotNull @Size(max = 64) Map<String, Object> permissionScope,
            @Valid KnowledgeScopeDecision knowledgeScope,
            @NotBlank @Size(max = 128) String configVersion,
            @NotBlank @Pattern(regexp = SHA_256_PATTERN) String requestContextHash,
            @NotNull @Valid RunBudget budget
    ) {
        public WorkflowRunCreateRequest {
            requireContractVersion(contractVersion);
            shortTermContext = List.copyOf(shortTermContext);
            permissionScope = Map.copyOf(permissionScope);
        }
    }

    public record WorkflowRunAccepted(
            @NotBlank String contractVersion,
            @NotNull UUID runId,
            @NotNull UUID executionId,
            @Min(1) int executionEpoch,
            @NotNull WorkflowRunStatus status,
            @NotNull Instant acceptedAt
    ) {
        public WorkflowRunAccepted {
            requireContractVersion(contractVersion);
            if (status != WorkflowRunStatus.QUEUED) {
                throw new IllegalArgumentException("accepted run status must be QUEUED");
            }
        }
    }

    public record MemoryReference(
            @Positive long memoryId,
            @Min(1) int version,
            @NotBlank @Pattern(regexp = SHA_256_PATTERN) String hash
    ) {
    }

    public record KnowledgeReference(
            @Positive long spaceId,
            @Positive long documentId,
            @NotBlank @Size(max = 128) String chunkId,
            @NotBlank @Pattern(regexp = SHA_256_PATTERN) String hash
    ) {
    }

    public record SnapshotDraft(
            int snapshotVersion,
            @NotBlank @Pattern(regexp = SHA_256_PATTERN) String requestContextHash,
            @NotBlank @Pattern(regexp = SHA_256_PATTERN) String snapshotHash,
            @NotNull @Size(max = 18) List<@NotBlank @Size(max = 4_000) String> queries,
            @NotNull @Size(max = 100) List<@Valid MemoryReference> memoryReferences,
            @NotNull @Size(max = 100) List<@Valid KnowledgeReference> knowledgeReferences,
            @NotNull @Size(max = 64_000) String injectableContext
    ) {
        public SnapshotDraft {
            if (snapshotVersion != 2) {
                throw new IllegalArgumentException("snapshotVersion must be 2");
            }
            queries = List.copyOf(queries);
            memoryReferences = List.copyOf(memoryReferences);
            knowledgeReferences = List.copyOf(knowledgeReferences);
        }
    }

    public record RetrievalCandidateTrace(
            @NotNull SourceType sourceType,
            @NotBlank @Size(max = 128) String sourceId,
            @Min(1) int version,
            @NotBlank @Pattern(regexp = SHA_256_PATTERN) String hash,
            @NotBlank @Size(max = 4_000) String query,
            double score,
            @Min(1) int rank,
            boolean selected,
            @Size(max = 256) String eliminationReason
    ) {
        public enum SourceType {
            MEMORY,
            KNOWLEDGE
        }
    }

    public record RetrievalTrace(
            int traceVersion,
            @NotNull @Size(max = 300) List<@Valid RetrievalCandidateTrace> candidates
    ) {
        public RetrievalTrace {
            if (traceVersion != 1) {
                throw new IllegalArgumentException("traceVersion must be 1");
            }
            candidates = List.copyOf(candidates);
        }
    }

    public record WorkflowResult(
            @NotBlank String contractVersion,
            @NotNull UUID runId,
            @NotNull UUID executionId,
            @Min(1) int executionEpoch,
            @NotNull WorkflowRunStatus status,
            @NotBlank @Pattern(regexp = SHA_256_PATTERN) String requestContextHash,
            @NotBlank @Pattern(regexp = SHA_256_PATTERN) String snapshotHash,
            @NotBlank @Pattern(regexp = SHA_256_PATTERN) String resultHash,
            @NotNull Map<String, Object> structuredResult,
            @NotNull @Valid SnapshotDraft snapshotDraft,
            @NotNull @Valid RetrievalTrace retrievalTrace,
            @NotNull @Size(max = 32) List<@Positive Long> usedKnowledgeSpaces,
            @Size(max = 512) String degradedReason
    ) {
        public WorkflowResult {
            requireContractVersion(contractVersion);
            if (!status.isTerminal()) {
                throw new IllegalArgumentException("workflow result status must be terminal");
            }
            if (!Objects.equals(requestContextHash, snapshotDraft.requestContextHash())) {
                throw new IllegalArgumentException("requestContextHash must match snapshot draft");
            }
            if (!Objects.equals(snapshotHash, snapshotDraft.snapshotHash())) {
                throw new IllegalArgumentException("snapshotHash must match snapshot draft");
            }
            structuredResult = Map.copyOf(structuredResult);
            usedKnowledgeSpaces = List.copyOf(usedKnowledgeSpaces);
            requireUnique(usedKnowledgeSpaces, "usedKnowledgeSpaces");
        }
    }

    public record WorkflowTerminalCallback(
            @NotBlank String contractVersion,
            @NotNull UUID deliveryId,
            @NotNull UUID runId,
            @NotNull UUID executionId,
            @Min(1) int executionEpoch,
            @NotNull WorkflowRunStatus status,
            @NotBlank @Pattern(regexp = SHA_256_PATTERN) String resultHash,
            @NotNull Instant completedAt
    ) {
        public WorkflowTerminalCallback {
            requireContractVersion(contractVersion);
            if (!status.isTerminal()) {
                throw new IllegalArgumentException("callback status must be terminal");
            }
        }
    }

    public record WorkflowDeliveryAck(
            @NotBlank String contractVersion,
            @NotNull UUID deliveryId,
            boolean accepted,
            boolean duplicate,
            @NotNull Instant acknowledgedAt
    ) {
        public WorkflowDeliveryAck {
            requireContractVersion(contractVersion);
            if (!accepted) {
                throw new IllegalArgumentException("delivery ACK must be accepted");
            }
        }
    }

    public record ToolInvokeRequest(
            @NotBlank String contractVersion,
            @NotNull UUID runId,
            @NotNull UUID executionId,
            @NotBlank @Pattern(regexp = "^[A-Za-z_][A-Za-z0-9_-]{0,63}$") String nodeId,
            @NotNull UUID invocationId,
            @Positive long userId,
            @Positive Long apiKeyId,
            @Positive long sessionId,
            @NotBlank @Pattern(regexp = "^[a-z][a-z0-9_.-]{1,127}$") String toolName,
            @NotNull RiskLevel riskLevel,
            @NotNull Map<String, Object> arguments
    ) {
        public ToolInvokeRequest {
            requireContractVersion(contractVersion);
            arguments = Map.copyOf(arguments);
        }
    }

    public record ToolInvokeResponse(
            @NotBlank String contractVersion,
            @NotNull UUID invocationId,
            @NotNull ToolInvokeStatus status,
            Map<String, Object> result,
            @Valid ContractError error
    ) {
        public enum ToolInvokeStatus {
            SUCCEEDED,
            FAILED
        }

        public ToolInvokeResponse {
            requireContractVersion(contractVersion);
            if (status == ToolInvokeStatus.SUCCEEDED && result == null) {
                throw new IllegalArgumentException("successful tool invocation requires result");
            }
            if (status == ToolInvokeStatus.SUCCEEDED && error != null) {
                throw new IllegalArgumentException("successful tool invocation cannot include error");
            }
            if (status == ToolInvokeStatus.FAILED && error == null) {
                throw new IllegalArgumentException("failed tool invocation requires error");
            }
            if (status == ToolInvokeStatus.FAILED && result != null) {
                throw new IllegalArgumentException("failed tool invocation cannot include result");
            }
            if (result != null) {
                result = Map.copyOf(result);
            }
        }
    }

    public record SessionDeletionOutboxEvent(
            @NotBlank String contractVersion,
            @NotNull UUID eventId,
            @Positive long userId,
            @Positive long sessionId,
            @NotNull Instant deletedAt
    ) {
        public SessionDeletionOutboxEvent {
            requireContractVersion(contractVersion);
        }
    }

    public record JavaGenerationRetry(
            @NotBlank String contractVersion,
            @Positive long assistantMessageId,
            @NotNull UUID runId,
            @NotNull UUID executionId,
            @NotBlank @Pattern(regexp = SHA_256_PATTERN) String snapshotHash,
            boolean retryJavaGenerationOnly
    ) {
        public JavaGenerationRetry {
            requireContractVersion(contractVersion);
            if (!retryJavaGenerationOnly) {
                throw new IllegalArgumentException("retryJavaGenerationOnly must be true");
            }
        }
    }

    private static void requireContractVersion(String version) {
        if (!CONTRACT_VERSION.equals(version)) {
            throw new IllegalArgumentException("unsupported contractVersion: " + version);
        }
    }

    private static void requireUnique(List<?> values, String field) {
        Set<?> unique = new HashSet<>(values);
        if (unique.size() != values.size()) {
            throw new IllegalArgumentException(field + " must contain unique values");
        }
    }
}
