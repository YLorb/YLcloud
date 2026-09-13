from __future__ import annotations

from enum import Enum
from typing import Literal

from pydantic import Field, model_validator

from mini_agent_flow.contracts.models import ContractModel, ShortTermMessage


_INTENT_CODES = (
    "GENERAL_QA", "EXPLAIN_CONCEPT", "HOW_TO", "COMPARE", "SUMMARIZE", "ANALYZE",
    "CONTENT_GENERATION", "CONTENT_REWRITE", "CONTENT_EXPAND", "CONTENT_SHORTEN",
    "TRANSLATION", "FORMAT_CONVERSION", "CODE_GENERATION", "CODE_REVIEW", "CODE_MODIFICATION",
    "MEMORY_LOOKUP", "MEMORY_SAVE", "MEMORY_UPDATE", "MEMORY_DELETE", "MEMORY_CLEAR",
    "MEMORY_CONFIRM", "MEMORY_CORRECTION", "CONTINUE_TASK", "RESUME_PROJECT",
    "MODIFY_PREVIOUS_RESULT", "RETRY", "REFRESH_RETRY", "ROLLBACK", "TASK_STATUS_QUERY",
    "TOOL_EXECUTION", "WEB_SEARCH", "FILE_SEARCH", "FILE_READ", "EMAIL_DRAFT", "EMAIL_SEND",
    "CALENDAR_QUERY", "CALENDAR_CREATE", "REMINDER_CREATE", "EXTERNAL_ACTION",
    "CONFIRMATION", "CANCEL_ACTION", "CORRECTION", "HELP", "UNKNOWN", "ACCOUNT_OPERATION",
    "PERMISSION_CHANGE", "DATA_EXPORT", "DATA_DELETE",
)
IntentCode = Enum("IntentCode", {value: value for value in _INTENT_CODES}, type=str)


class IntentPlanRequest(ContractModel):
    contract_version: Literal["1.0"]
    schema_version: Literal["intent-plan/1.0"]
    prompt_version: Literal["intent-plan-prompt/1.0"]
    question: str = Field(min_length=1, max_length=32_000)
    short_term_context: list[ShortTermMessage] = Field(max_length=50)
    attempt: int = Field(ge=1, le=10)
    confidence_threshold: Literal[0.7]


class IntentTask(ContractModel):
    task_id: str = Field(pattern=r"^[A-Za-z_][A-Za-z0-9_-]{0,63}$")
    role: Literal["PRIMARY", "SUBTASK"]
    intent: IntentCode
    instruction: str = Field(min_length=1, max_length=8_000)
    dependencies: list[str] = Field(max_length=5)
    confidence: float = Field(ge=0, le=1)
    context_sufficiency: Literal["SUFFICIENT", "PARTIAL", "INSUFFICIENT", "UNKNOWN"]
    relevance: Literal["REQUIRED", "OPTIONAL", "IRRELEVANT"]
    uncertainty: Literal["NONE", "ROUTING", "SECURITY", "IRRELEVANT_SUBTASK"]
    disposition: Literal["ACTIVE", "SKIPPED_UNCERTAIN"]


class StructuredIntentPlan(ContractModel):
    primary_task_id: str = Field(pattern=r"^[A-Za-z_][A-Za-z0-9_-]{0,63}$")
    complexity: Literal["SIMPLE", "COMPLEX"]
    tasks: list[IntentTask] = Field(min_length=1, max_length=6)

    @model_validator(mode="after")
    def validate_references(self) -> "StructuredIntentPlan":
        ids = [task.task_id for task in self.tasks]
        if len(ids) != len(set(ids)):
            raise ValueError("task ids must be unique")
        primary = [task for task in self.tasks if task.role == "PRIMARY"]
        if len(primary) != 1 or primary[0].task_id != self.primary_task_id:
            raise ValueError("plan must contain exactly one matching primary task")
        if primary[0].relevance != "REQUIRED":
            raise ValueError("primary task relevance must be REQUIRED")
        if len(self.tasks) > 1 and self.complexity != "COMPLEX":
            raise ValueError("plan with subtasks must be COMPLEX")
        known = set(ids)
        for task in self.tasks:
            if len(task.dependencies) != len(set(task.dependencies)):
                raise ValueError("task dependencies must be unique")
            if task.task_id in task.dependencies or not set(task.dependencies) <= known:
                raise ValueError("task dependency is invalid")
        return self


class IntentPlanResponse(ContractModel):
    contract_version: Literal["1.0"]
    schema_version: Literal["intent-plan/1.0"]
    prompt_version: Literal["intent-plan-prompt/1.0"]
    model_version: str = Field(min_length=1, max_length=128)
    plan: StructuredIntentPlan
