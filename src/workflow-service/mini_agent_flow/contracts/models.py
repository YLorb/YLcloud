from __future__ import annotations

from enum import Enum
from typing import Annotated, Any, Literal
from uuid import UUID

from pydantic import AwareDatetime, BaseModel, ConfigDict, Field, model_validator


def _to_camel(value: str) -> str:
    head, *tail = value.split("_")
    return head + "".join(part.capitalize() for part in tail)


class ContractModel(BaseModel):
    """跨仓库 DTO 公共约束：驼峰 JSON、拒绝未知字段，避免契约静默漂移。"""

    model_config = ConfigDict(
        alias_generator=_to_camel,
        populate_by_name=True,
        extra="forbid",
    )


PositiveId = Annotated[int, Field(ge=1)]
Sha256 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]


class WorkflowRunStatus(str, Enum):
    QUEUED = "QUEUED"
    PLANNING = "PLANNING"
    VALIDATING = "VALIDATING"
    RUNNING = "RUNNING"
    SUCCEEDED = "SUCCEEDED"
    DEGRADED = "DEGRADED"
    FAILED = "FAILED"
    TIMED_OUT = "TIMED_OUT"
    CANCELLED = "CANCELLED"
    ABANDONED = "ABANDONED"


class JavaMessageStatus(str, Enum):
    QUEUED = "QUEUED"
    RUNNING = "RUNNING"
    SUCCESS = "SUCCESS"
    FAILED = "FAILED"


TERMINAL_STATUSES = {
    WorkflowRunStatus.SUCCEEDED,
    WorkflowRunStatus.DEGRADED,
    WorkflowRunStatus.FAILED,
    WorkflowRunStatus.TIMED_OUT,
    WorkflowRunStatus.CANCELLED,
    WorkflowRunStatus.ABANDONED,
}


class StatusMapping(ContractModel):
    java_status: JavaMessageStatus
    workflow_status: WorkflowRunStatus
    degraded: bool

    @model_validator(mode="after")
    def validate_mapping(self) -> "StatusMapping":
        if self.workflow_status is WorkflowRunStatus.QUEUED:
            expected = (JavaMessageStatus.QUEUED, False)
        elif self.workflow_status in {
            WorkflowRunStatus.PLANNING,
            WorkflowRunStatus.VALIDATING,
            WorkflowRunStatus.RUNNING,
        }:
            expected = (JavaMessageStatus.RUNNING, False)
        elif self.workflow_status is WorkflowRunStatus.SUCCEEDED:
            expected = (JavaMessageStatus.SUCCESS, False)
        elif self.workflow_status is WorkflowRunStatus.DEGRADED:
            expected = (JavaMessageStatus.SUCCESS, True)
        else:
            expected = (JavaMessageStatus.FAILED, False)
        if (self.java_status, self.degraded) != expected:
            raise ValueError("inconsistent Java/Workflow status mapping")
        return self


def map_java_status(status: WorkflowRunStatus) -> StatusMapping:
    """将 Workflow 二级状态映射为前端一级状态，并单独保留降级标记。"""

    if status is WorkflowRunStatus.QUEUED:
        return StatusMapping(java_status=JavaMessageStatus.QUEUED, workflow_status=status, degraded=False)
    if status in {
        WorkflowRunStatus.PLANNING,
        WorkflowRunStatus.VALIDATING,
        WorkflowRunStatus.RUNNING,
    }:
        return StatusMapping(java_status=JavaMessageStatus.RUNNING, workflow_status=status, degraded=False)
    if status is WorkflowRunStatus.SUCCEEDED:
        return StatusMapping(java_status=JavaMessageStatus.SUCCESS, workflow_status=status, degraded=False)
    if status is WorkflowRunStatus.DEGRADED:
        return StatusMapping(java_status=JavaMessageStatus.SUCCESS, workflow_status=status, degraded=True)
    return StatusMapping(java_status=JavaMessageStatus.FAILED, workflow_status=status, degraded=False)


class IntentType(str, Enum):
    GENERAL_CHAT = "GENERAL_CHAT"
    KNOWLEDGE_QA = "KNOWLEDGE_QA"
    EXTERNAL_ACTION = "EXTERNAL_ACTION"
    MEMORY_OPERATION = "MEMORY_OPERATION"
    MIXED = "MIXED"


class ContractErrorCode(str, Enum):
    INVALID_REQUEST = "INVALID_REQUEST"
    UNSUPPORTED_VERSION = "UNSUPPORTED_VERSION"
    UNAUTHORIZED = "UNAUTHORIZED"
    FORBIDDEN = "FORBIDDEN"
    NOT_FOUND = "NOT_FOUND"
    CONFLICT = "CONFLICT"
    BUDGET_EXCEEDED = "BUDGET_EXCEEDED"
    TOOL_CONFIRMATION_REQUIRED = "TOOL_CONFIRMATION_REQUIRED"
    TIMED_OUT = "TIMED_OUT"
    INTERNAL_ERROR = "INTERNAL_ERROR"


class ContractError(ContractModel):
    contract_version: Literal["1.0"]
    code: ContractErrorCode
    message: str = Field(min_length=1, max_length=512)
    retryable: bool
    details: dict[str, Any] | None = Field(default=None, max_length=32)


class IntentTask(ContractModel):
    task_id: str = Field(pattern=r"^[A-Za-z_][A-Za-z0-9_-]{0,63}$")
    intent: IntentType
    instruction: str = Field(min_length=1, max_length=8_000)
    dependencies: list[str] = Field(max_length=5)


class IntentPlan(ContractModel):
    plan_version: Literal["1.0"]
    primary_intent: IntentType
    tasks: list[IntentTask] = Field(min_length=1, max_length=6)

    @model_validator(mode="after")
    def validate_task_references(self) -> "IntentPlan":
        task_ids = [task.task_id for task in self.tasks]
        if len(task_ids) != len(set(task_ids)):
            raise ValueError("intent task ids must be unique")
        known = set(task_ids)
        for task in self.tasks:
            if task.task_id in task.dependencies:
                raise ValueError("intent task cannot depend on itself")
            if not set(task.dependencies).issubset(known):
                raise ValueError("intent task dependency is unknown")
        return self


class ShortTermMessage(ContractModel):
    role: Literal["USER", "ASSISTANT", "SYSTEM"]
    content: str = Field(max_length=32_000)


class RunBudget(ContractModel):
    max_business_nodes: int = Field(ge=1, le=6)
    max_parallel_nodes: int = Field(ge=1, le=3)
    max_model_calls: int = Field(ge=1, le=12)
    max_runtime_ms: int = Field(ge=1_000, le=300_000)

    @model_validator(mode="after")
    def validate_parallel_budget(self) -> "RunBudget":
        if self.max_parallel_nodes > self.max_business_nodes:
            raise ValueError("parallel node budget cannot exceed business node budget")
        return self


class KnowledgeScopeDecision(ContractModel):
    explicit_selection: bool
    selected_space_ids: list[PositiveId] = Field(max_length=32)
    auto_added_space_ids: list[PositiveId] = Field(max_length=2)

    @model_validator(mode="after")
    def validate_closed_explicit_scope(self) -> "KnowledgeScopeDecision":
        if self.explicit_selection and self.auto_added_space_ids:
            raise ValueError("explicit knowledge selection cannot auto-add spaces")
        if len(set(self.selected_space_ids)) != len(self.selected_space_ids):
            raise ValueError("selected space ids must be unique")
        if len(set(self.auto_added_space_ids)) != len(self.auto_added_space_ids):
            raise ValueError("auto-added space ids must be unique")
        if set(self.selected_space_ids) & set(self.auto_added_space_ids):
            raise ValueError("selected and auto-added spaces cannot overlap")
        return self


class WorkflowRunCreateRequest(ContractModel):
    contract_version: Literal["1.0"]
    workflow_type: Literal["ASSISTANT"]
    workflow_version: Literal["1.0", "2.0"]
    user_id: PositiveId
    session_id: PositiveId
    source_message_id: PositiveId
    assistant_message_id: PositiveId
    question: str = Field(min_length=1, max_length=32_000)
    short_term_context: list[ShortTermMessage] = Field(max_length=50)
    permission_scope: dict[str, Any] = Field(max_length=64)
    knowledge_scope: KnowledgeScopeDecision | None = None
    config_version: str = Field(min_length=1, max_length=128)
    request_context_hash: Sha256
    budget: RunBudget


class WorkflowRunAccepted(ContractModel):
    contract_version: Literal["1.0"]
    run_id: UUID
    execution_id: UUID
    execution_epoch: int = Field(ge=1)
    status: Literal["QUEUED"]
    accepted_at: AwareDatetime


class MemoryReference(ContractModel):
    memory_id: PositiveId
    version: int = Field(ge=1)
    hash: Sha256


class KnowledgeReference(ContractModel):
    space_id: PositiveId
    document_id: PositiveId
    chunk_id: str = Field(min_length=1, max_length=128)
    hash: Sha256


class SnapshotDraft(ContractModel):
    snapshot_version: Literal[2]
    request_context_hash: Sha256
    snapshot_hash: Sha256
    queries: list[Annotated[str, Field(min_length=1, max_length=4_000)]] = Field(
        max_length=18
    )
    memory_references: list[MemoryReference] = Field(max_length=100)
    knowledge_references: list[KnowledgeReference] = Field(max_length=100)
    injectable_context: str = Field(max_length=64_000)


class RetrievalCandidateTrace(ContractModel):
    source_type: Literal["MEMORY", "KNOWLEDGE"]
    source_id: str = Field(min_length=1, max_length=128)
    version: int = Field(ge=1)
    hash: Sha256
    query: str = Field(min_length=1, max_length=4_000)
    score: float
    rank: int = Field(ge=1)
    selected: bool
    elimination_reason: str | None = Field(default=None, max_length=256)


class RetrievalTrace(ContractModel):
    trace_version: Literal[1]
    candidates: list[RetrievalCandidateTrace] = Field(max_length=300)


class WorkflowResult(ContractModel):
    """Workflow 结构化结果；刻意不包含 finalAnswer，最终回答只能由 Java 生成。"""

    contract_version: Literal["1.0"]
    run_id: UUID
    execution_id: UUID
    execution_epoch: int = Field(ge=1)
    status: WorkflowRunStatus
    request_context_hash: Sha256
    snapshot_hash: Sha256
    result_hash: Sha256
    structured_result: dict[str, Any]
    snapshot_draft: SnapshotDraft
    retrieval_trace: RetrievalTrace
    used_knowledge_spaces: list[PositiveId] = Field(max_length=32)
    degraded_reason: str | None = Field(default=None, max_length=512)

    @model_validator(mode="after")
    def validate_terminal_snapshot(self) -> "WorkflowResult":
        if self.status not in TERMINAL_STATUSES:
            raise ValueError("workflow result status must be terminal")
        if self.request_context_hash != self.snapshot_draft.request_context_hash:
            raise ValueError("requestContextHash must match snapshot draft")
        if self.snapshot_hash != self.snapshot_draft.snapshot_hash:
            raise ValueError("snapshotHash must match snapshot draft")
        if len(set(self.used_knowledge_spaces)) != len(self.used_knowledge_spaces):
            raise ValueError("used knowledge spaces must be unique")
        return self


class WorkflowTerminalCallback(ContractModel):
    contract_version: Literal["1.0"]
    delivery_id: UUID
    run_id: UUID
    execution_id: UUID
    execution_epoch: int = Field(ge=1)
    status: WorkflowRunStatus
    result_hash: Sha256
    completed_at: AwareDatetime

    @model_validator(mode="after")
    def validate_terminal_status(self) -> "WorkflowTerminalCallback":
        if self.status not in TERMINAL_STATUSES:
            raise ValueError("callback status must be terminal")
        return self


class WorkflowDeliveryAck(ContractModel):
    contract_version: Literal["1.0"]
    delivery_id: UUID
    accepted: Literal[True]
    duplicate: bool
    acknowledged_at: AwareDatetime


class ConfirmationGrant(ContractModel):
    mode: Literal["ALLOW_ONCE", "ALLOW_SIMILAR"]
    grant_id: UUID
    user_id: PositiveId
    tool_name: str = Field(pattern=r"^[a-z][a-z0-9_.-]{1,127}$")
    parameter_hash: Sha256
    similarity_scope: dict[str, Any] | None = Field(default=None, max_length=32)
    issued_at: AwareDatetime
    expires_at: AwareDatetime

    @model_validator(mode="after")
    def validate_grant_window(self) -> "ConfirmationGrant":
        if self.expires_at <= self.issued_at:
            raise ValueError("confirmation grant must expire after issue time")
        if self.mode == "ALLOW_ONCE" and self.similarity_scope is not None:
            raise ValueError("ALLOW_ONCE cannot define similarity scope")
        return self


class ToolInvokeRequest(ContractModel):
    contract_version: Literal["1.0"]
    run_id: UUID
    execution_id: UUID
    node_id: str = Field(pattern=r"^[A-Za-z_][A-Za-z0-9_-]{0,63}$")
    invocation_id: UUID
    user_id: PositiveId
    session_id: PositiveId
    tool_name: str = Field(pattern=r"^[a-z][a-z0-9_.-]{1,127}$")
    risk_level: Literal["READ_ONLY", "WRITE", "HIGH"]
    arguments: dict[str, Any]
    confirmation: ConfirmationGrant | None = None

    @model_validator(mode="after")
    def validate_high_risk_confirmation(self) -> "ToolInvokeRequest":
        if self.risk_level == "HIGH" and self.confirmation is None:
            raise ValueError("high-risk tool requires confirmation")
        if self.confirmation is not None:
            if self.confirmation.user_id != self.user_id:
                raise ValueError("confirmation user does not match invocation")
            if self.confirmation.tool_name != self.tool_name:
                raise ValueError("confirmation tool does not match invocation")
        return self


class ToolInvokeResponse(ContractModel):
    contract_version: Literal["1.0"]
    invocation_id: UUID
    status: Literal["SUCCEEDED", "FAILED"]
    result: dict[str, Any] | None = None
    error: ContractError | None = None

    @model_validator(mode="after")
    def validate_outcome(self) -> "ToolInvokeResponse":
        if self.status == "SUCCEEDED" and self.result is None:
            raise ValueError("successful tool invocation requires result")
        if self.status == "SUCCEEDED" and self.error is not None:
            raise ValueError("successful tool invocation cannot include error")
        if self.status == "FAILED" and self.error is None:
            raise ValueError("failed tool invocation requires error")
        if self.status == "FAILED" and self.result is not None:
            raise ValueError("failed tool invocation cannot include result")
        return self


class SessionDeletionOutboxEvent(ContractModel):
    contract_version: Literal["1.0"]
    event_id: UUID
    user_id: PositiveId
    session_id: PositiveId
    deleted_at: AwareDatetime


class JavaGenerationRetry(ContractModel):
    contract_version: Literal["1.0"]
    assistant_message_id: PositiveId
    run_id: UUID
    execution_id: UUID
    snapshot_hash: Sha256
    retry_java_generation_only: Literal[True]
