from __future__ import annotations

from enum import Enum
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)


class InvocationStatus(str, Enum):
    QUEUED = "QUEUED"
    PREPARING = "PREPARING"
    RUNNING = "RUNNING"
    COLLECTING = "COLLECTING"
    SUCCEEDED = "SUCCEEDED"
    FAILED = "FAILED"
    CANCELLED = "CANCELLED"
    TIMED_OUT = "TIMED_OUT"
    RESOURCE_EXCEEDED = "RESOURCE_EXCEEDED"
    SECURITY_REJECTED = "SECURITY_REJECTED"


class ParentTrace(StrictModel):
    trace_id: str = Field(min_length=16, max_length=64, pattern=r"^[a-fA-F0-9]+$")
    span_id: str = Field(min_length=16, max_length=32, pattern=r"^[a-fA-F0-9]+$")


class InvocationRequest(StrictModel):
    contract_version: Literal["1.0"] = "1.0"
    invocation_id: str = Field(min_length=8, max_length=64, pattern=r"^[A-Za-z0-9_-]+$")
    idempotency_key: str = Field(min_length=8, max_length=128, pattern=r"^[A-Za-z0-9_.:-]+$")
    tool_name: str = Field(pattern=r"^[a-z][a-z0-9_.-]{1,127}$")
    tool_version: str = Field(min_length=1, max_length=64, pattern=r"^[A-Za-z0-9_.-]+$")
    arguments: dict[str, Any]
    parent_trace: ParentTrace
    subject_id: str = Field(min_length=1, max_length=128)
    timeout_seconds: int | None = Field(default=None, ge=1, le=600)


class SandboxSpan(StrictModel):
    span_id: str
    parent_span_id: str
    trace_id: str
    invocation_id: str
    subject_id: str
    tool_name: str
    tool_version: str
    image_digest: str
    status: InvocationStatus
    network_policy: Literal["none"]
    resource_limits: dict[str, int | float]
    resource_usage: dict[str, int | float]
    started_at: str
    ended_at: str
    duration_ms: float
    exit_code: int | None = None
    termination_reason: str | None = None
    input_digest: str
    output_digest: str | None = None
    result_committed: bool = False


class InvocationResponse(StrictModel):
    contract_version: Literal["1.0"] = "1.0"
    invocation_id: str
    status: InvocationStatus
    result: Any | None = None
    error_code: str | None = None
    error_message: str | None = None
    span: SandboxSpan

