from __future__ import annotations

import json
import os
import urllib.error
import urllib.request
from dataclasses import dataclass
from typing import Any
from uuid import uuid4

from mini_agent_flow.tools.spec import ToolSpec


class SandboxClientError(RuntimeError):
    def __init__(self, message: str, *, span: dict[str, Any] | None = None) -> None:
        super().__init__(message)
        self.span = span or {}
class SandboxTimeoutError(SandboxClientError): pass
class SandboxCancelledError(SandboxClientError): pass


@dataclass(frozen=True)
class SandboxInvocationResult:
    result: Any
    span: dict[str, Any]


class SandboxClient:
    """Calls the isolated control plane and never falls back to local execution."""

    def __init__(self, base_url: str, *, subject_id: str = "workflow-service", token: str = "") -> None:
        self.base_url = base_url.rstrip("/")
        self.subject_id = subject_id
        self.token = token

    @classmethod
    def from_env(cls) -> "SandboxClient | None":
        if os.getenv("MINI_AGENT_FLOW_SANDBOX_ENABLED", "false").lower() not in {"1", "true", "yes"}:
            return None
        token = os.getenv("MINI_AGENT_FLOW_SANDBOX_TOKEN", "")
        token_file = os.getenv("MINI_AGENT_FLOW_SANDBOX_TOKEN_FILE", "")
        if not token and token_file:
            try:
                with open(token_file, encoding="utf-8") as handle:
                    token = handle.read().strip()
            except OSError:
                token = ""
        if not token:
            raise SandboxClientError("sandbox service token is not configured")
        return cls(os.getenv("MINI_AGENT_FLOW_SANDBOX_BASE_URL", "http://sandbox-service:8004"), token=token)

    def invoke(self, spec: ToolSpec, arguments: dict[str, Any], *, timeout_seconds: float,
               idempotency_key: str, trace_id: str, parent_span_id: str) -> SandboxInvocationResult:
        if spec.sandbox is None:
            raise SandboxClientError("sandbox tool descriptor is missing")
        payload = {"contract_version": "1.0", "invocation_id": uuid4().hex,
                   "idempotency_key": idempotency_key, "tool_name": spec.name,
                   "tool_version": spec.sandbox.tool_version, "arguments": arguments,
                   "parent_trace": {"trace_id": trace_id, "span_id": parent_span_id},
                   "subject_id": self.subject_id,
                   "timeout_seconds": min(max(1, int(timeout_seconds)), 600)}
        request = urllib.request.Request(f"{self.base_url}/internal/v1/invocations",
            data=json.dumps(payload).encode(), headers={"Content-Type": "application/json", "Authorization": f"Bearer {self.token}"}, method="POST")
        try:
            with urllib.request.urlopen(request, timeout=timeout_seconds + 5) as response:
                body = json.loads(response.read().decode())
        except TimeoutError as exc:
            raise SandboxTimeoutError("sandbox service timed out") from exc
        except (urllib.error.URLError, json.JSONDecodeError) as exc:
            raise SandboxClientError("sandbox service is unavailable") from exc
        failure_span = body.get("span") or {}
        if body.get("status") == "CANCELLED": raise SandboxCancelledError("sandbox invocation was cancelled", span=failure_span)
        if body.get("status") == "TIMED_OUT": raise SandboxTimeoutError("sandbox invocation timed out", span=failure_span)
        if body.get("status") != "SUCCEEDED": raise SandboxClientError(body.get("error_message") or "sandbox invocation failed", span=failure_span)
        return SandboxInvocationResult(body.get("result"), body.get("span") or {})
