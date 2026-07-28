from __future__ import annotations

from enum import Enum
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class IntentCode(str, Enum):
    GENERAL_QA = "GENERAL_QA"
    EXPLAIN_CONCEPT = "EXPLAIN_CONCEPT"
    HOW_TO = "HOW_TO"
    COMPARE = "COMPARE"
    SUMMARIZE = "SUMMARIZE"
    ANALYZE = "ANALYZE"
    CONTENT_GENERATION = "CONTENT_GENERATION"
    CONTENT_REWRITE = "CONTENT_REWRITE"
    CONTENT_EXPAND = "CONTENT_EXPAND"
    CONTENT_SHORTEN = "CONTENT_SHORTEN"
    TRANSLATION = "TRANSLATION"
    FORMAT_CONVERSION = "FORMAT_CONVERSION"
    CODE_GENERATION = "CODE_GENERATION"
    CODE_REVIEW = "CODE_REVIEW"
    CODE_MODIFICATION = "CODE_MODIFICATION"
    MEMORY_LOOKUP = "MEMORY_LOOKUP"
    MEMORY_SAVE = "MEMORY_SAVE"
    MEMORY_UPDATE = "MEMORY_UPDATE"
    MEMORY_DELETE = "MEMORY_DELETE"
    MEMORY_CLEAR = "MEMORY_CLEAR"
    MEMORY_CONFIRM = "MEMORY_CONFIRM"
    MEMORY_CORRECTION = "MEMORY_CORRECTION"
    CONTINUE_TASK = "CONTINUE_TASK"
    RESUME_PROJECT = "RESUME_PROJECT"
    MODIFY_PREVIOUS_RESULT = "MODIFY_PREVIOUS_RESULT"
    RETRY = "RETRY"
    REFRESH_RETRY = "REFRESH_RETRY"
    ROLLBACK = "ROLLBACK"
    TASK_STATUS_QUERY = "TASK_STATUS_QUERY"
    TOOL_EXECUTION = "TOOL_EXECUTION"
    WEB_SEARCH = "WEB_SEARCH"
    FILE_SEARCH = "FILE_SEARCH"
    FILE_READ = "FILE_READ"
    EMAIL_DRAFT = "EMAIL_DRAFT"
    EMAIL_SEND = "EMAIL_SEND"
    CALENDAR_QUERY = "CALENDAR_QUERY"
    CALENDAR_CREATE = "CALENDAR_CREATE"
    REMINDER_CREATE = "REMINDER_CREATE"
    EXTERNAL_ACTION = "EXTERNAL_ACTION"
    CONFIRMATION = "CONFIRMATION"
    CANCEL_ACTION = "CANCEL_ACTION"
    CORRECTION = "CORRECTION"
    HELP = "HELP"
    UNKNOWN = "UNKNOWN"
    ACCOUNT_OPERATION = "ACCOUNT_OPERATION"
    PERMISSION_CHANGE = "PERMISSION_CHANGE"
    DATA_EXPORT = "DATA_EXPORT"
    DATA_DELETE = "DATA_DELETE"


class ShortTermMessage(StrictModel):
    role: Literal["USER", "ASSISTANT", "SYSTEM"]
    content: str = Field(max_length=32_000)


class IntentPlanRequest(StrictModel):
    contractVersion: Literal["1.0"]
    schemaVersion: Literal["intent-plan/1.0"]
    promptVersion: Literal["intent-plan-prompt/1.0"]
    question: str = Field(min_length=1, max_length=32_000)
    shortTermContext: list[ShortTermMessage] = Field(max_length=50)
    attempt: int = Field(ge=1, le=10)
    confidenceThreshold: Literal[0.7]


class IntentTask(StrictModel):
    taskId: str = Field(pattern=r"^[A-Za-z_][A-Za-z0-9_-]{0,63}$")
    role: Literal["PRIMARY", "SUBTASK"]
    intent: IntentCode
    instruction: str = Field(min_length=1, max_length=8_000)
    dependencies: list[str] = Field(max_length=5)
    confidence: float = Field(ge=0, le=1)
    contextSufficiency: Literal["SUFFICIENT", "PARTIAL", "INSUFFICIENT", "UNKNOWN"]
    relevance: Literal["REQUIRED", "OPTIONAL", "IRRELEVANT"]
    uncertainty: Literal["NONE", "ROUTING", "SECURITY", "IRRELEVANT_SUBTASK"]
    disposition: Literal["ACTIVE", "SKIPPED_UNCERTAIN"]


class IntentPlan(StrictModel):
    primaryTaskId: str = Field(pattern=r"^[A-Za-z_][A-Za-z0-9_-]{0,63}$")
    complexity: Literal["SIMPLE", "COMPLEX"]
    tasks: list[IntentTask] = Field(min_length=1, max_length=6)

    @model_validator(mode="after")
    def validate_graph(self) -> "IntentPlan":
        ids = [task.taskId for task in self.tasks]
        if len(ids) != len(set(ids)):
            raise ValueError("task ids must be unique")
        known = set(ids)
        primary = [task for task in self.tasks if task.role == "PRIMARY"]
        if len(primary) != 1 or primary[0].taskId != self.primaryTaskId:
            raise ValueError("plan must contain exactly one matching primary task")
        if primary[0].relevance != "REQUIRED":
            raise ValueError("primary task relevance must be REQUIRED")
        if len(self.tasks) > 1 and self.complexity != "COMPLEX":
            raise ValueError("plan with subtasks must be COMPLEX")
        for task in self.tasks:
            if len(task.dependencies) != len(set(task.dependencies)):
                raise ValueError("task dependencies must be unique")
            if task.taskId in task.dependencies or not set(task.dependencies) <= known:
                raise ValueError("task dependency is invalid")
        visiting: set[str] = set()
        visited: set[str] = set()
        dependencies = {task.taskId: task.dependencies for task in self.tasks}

        def visit(task_id: str) -> None:
            if task_id in visiting:
                raise ValueError("task graph must be acyclic")
            if task_id in visited:
                return
            visiting.add(task_id)
            for dependency in dependencies[task_id]:
                visit(dependency)
            visiting.remove(task_id)
            visited.add(task_id)

        for task_id in ids:
            visit(task_id)
        return self


class IntentPlanResponse(StrictModel):
    contractVersion: Literal["1.0"]
    schemaVersion: Literal["intent-plan/1.0"]
    promptVersion: Literal["intent-plan-prompt/1.0"]
    modelVersion: str = Field(min_length=1, max_length=128)
    plan: IntentPlan
