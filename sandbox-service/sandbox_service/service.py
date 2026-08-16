from __future__ import annotations

import hashlib
import json
from datetime import datetime, timezone
from time import perf_counter
from uuid import uuid4

from jsonschema import Draft202012Validator

from sandbox_service.catalog import ToolCatalog
from sandbox_service.models import InvocationRequest, InvocationResponse, InvocationStatus, SandboxSpan
from sandbox_service.runtime import RuntimeExecutionError, RuntimeResourceError, RuntimeTimeoutError, SandboxRuntime


class SandboxService:
    def __init__(self, catalog: ToolCatalog, runtime: SandboxRuntime) -> None:
        self.catalog = catalog
        self.runtime = runtime
        self._responses: dict[str, tuple[str, InvocationResponse]] = {}
        self._active_subjects: dict[str, str] = {}

    def invoke(self, request: InvocationRequest) -> InvocationResponse:
        stable_payload = request.model_dump(mode="json")
        stable_payload.pop("invocation_id", None)
        payload_digest = self._digest(stable_payload)
        cached = self._responses.get(request.idempotency_key)
        if cached:
            if cached[0] != payload_digest:
                raise ValueError("idempotency key was reused with a different request")
            return cached[1]
        tool = self.catalog.get(request.tool_name, request.tool_version)
        errors = sorted(Draft202012Validator(tool.input_schema).iter_errors(request.arguments), key=lambda e: list(e.path))
        if errors:
            raise ValueError(f"sandbox tool input does not match schema: {errors[0].message}")
        started_at = datetime.now(timezone.utc)
        started_perf = perf_counter()
        timeout = min(request.timeout_seconds or tool.limits.timeout_seconds, tool.limits.timeout_seconds)
        self._active_subjects[request.invocation_id] = request.subject_id
        try:
            runtime_result = self.runtime.execute(request.invocation_id, tool, request.arguments, timeout)
            output_errors = list(Draft202012Validator(tool.output_schema).iter_errors(runtime_result.result))
            if output_errors:
                raise RuntimeExecutionError("sandbox tool returned an invalid result")
        except RuntimeExecutionError as exc:
            if isinstance(exc, RuntimeTimeoutError):
                status, code = InvocationStatus.TIMED_OUT, "SANDBOX_TIMED_OUT"
            elif isinstance(exc, RuntimeResourceError):
                status, code = InvocationStatus.RESOURCE_EXCEEDED, "SANDBOX_RESOURCE_EXCEEDED"
            else:
                status, code = InvocationStatus.FAILED, "SANDBOX_TOOL_FAILED"
            response = self._failure_response(request, tool, started_at, started_perf, status, code, exc)
            self._responses[request.idempotency_key] = (payload_digest, response)
            return response
        finally:
            self._active_subjects.pop(request.invocation_id, None)
        ended_at = datetime.now(timezone.utc)
        span = SandboxSpan(
            span_id=uuid4().hex[:16], parent_span_id=request.parent_trace.span_id,
            trace_id=request.parent_trace.trace_id, invocation_id=request.invocation_id,
            subject_id=request.subject_id, tool_name=tool.name, tool_version=tool.version,
            image_digest=self._image_digest(tool.image), status=InvocationStatus.SUCCEEDED,
            network_policy="none",
            resource_limits={"cpus": tool.limits.cpus, "memoryBytes": tool.limits.memory_bytes,
                             "pids": tool.limits.pids, "tmpfsBytes": tool.limits.tmpfs_bytes,
                             "timeoutSeconds": timeout},
            resource_usage=runtime_result.resource_usage,
            started_at=started_at.isoformat(), ended_at=ended_at.isoformat(),
            duration_ms=(perf_counter() - started_perf) * 1000, exit_code=runtime_result.exit_code,
            input_digest=self._digest(request.arguments), output_digest=self._digest(runtime_result.result),
            result_committed=True,
        )
        response = InvocationResponse(invocation_id=request.invocation_id, status=InvocationStatus.SUCCEEDED,
                                      result=runtime_result.result, span=span)
        self._responses[request.idempotency_key] = (payload_digest, response)
        return response

    def _failure_response(self, request: InvocationRequest, tool: object, started_at: datetime,
                          started_perf: float, status: InvocationStatus, code: str,
                          error: RuntimeExecutionError) -> InvocationResponse:
        limits = tool.limits  # type: ignore[attr-defined]
        span = SandboxSpan(
            span_id=uuid4().hex[:16], parent_span_id=request.parent_trace.span_id,
            trace_id=request.parent_trace.trace_id, invocation_id=request.invocation_id,
            subject_id=request.subject_id, tool_name=tool.name, tool_version=tool.version,  # type: ignore[attr-defined]
            image_digest=self._image_digest(tool.image), status=status, network_policy="none",  # type: ignore[attr-defined]
            resource_limits={"cpus": limits.cpus, "memoryBytes": limits.memory_bytes,
                             "pids": limits.pids, "tmpfsBytes": limits.tmpfs_bytes,
                             "timeoutSeconds": min(request.timeout_seconds or limits.timeout_seconds, limits.timeout_seconds)},
            resource_usage={}, started_at=started_at.isoformat(), ended_at=datetime.now(timezone.utc).isoformat(),
            duration_ms=(perf_counter() - started_perf) * 1000, exit_code=error.exit_code,
            termination_reason=code, input_digest=self._digest(request.arguments), result_committed=False,
        )
        return InvocationResponse(invocation_id=request.invocation_id, status=status, error_code=code,
                                  error_message="sandbox execution failed", span=span)

    def get(self, invocation_id: str, subject_id: str) -> InvocationResponse:
        for _, response in self._responses.values():
            if response.invocation_id == invocation_id and response.span.subject_id == subject_id:
                return response
        raise KeyError("sandbox invocation was not found")

    def list_for_subject(self, subject_id: str) -> list[InvocationResponse]:
        return [response for _, response in self._responses.values() if response.span.subject_id == subject_id]

    def cancel(self, invocation_id: str, subject_id: str) -> None:
        cached = next((response for _, response in self._responses.values()
                       if response.invocation_id == invocation_id), None)
        owner = cached.span.subject_id if cached is not None else self._active_subjects.get(invocation_id)
        if owner != subject_id:
            raise KeyError("sandbox invocation was not found")
        self.runtime.cancel(invocation_id)

    @staticmethod
    def _digest(value: object) -> str:
        raw = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
        return hashlib.sha256(raw).hexdigest()

    @staticmethod
    def _image_digest(image: str) -> str:
        return image.split("@", 1)[1] if "@" in image else image
