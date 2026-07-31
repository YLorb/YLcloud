from __future__ import annotations

import json
import os
import time
from dataclasses import dataclass
from types import MappingProxyType
from typing import Any, Literal, Mapping
from urllib.parse import urlsplit
from uuid import UUID

import httpx
from pydantic_core import PydanticSerializationError

from mini_agent_flow.contracts.models import (
    ConfirmationGrant,
    ToolInvokeRequest,
    ToolInvokeResponse,
)
from mini_agent_flow.security.service_jwt import (
    TOOL_GATEWAY_AUDIENCE,
    WorkflowServiceTokenIssuer,
)


@dataclass(frozen=True, slots=True)
class GatewayToolDescriptor:
    """Java Tool Gateway 的最小可信目录；调用方不能覆盖风险等级或 scope。"""

    risk_level: Literal["READ_ONLY", "WRITE", "HIGH"]
    required_scope: str


TOOL_GATEWAY_CATALOG: Mapping[str, GatewayToolDescriptor] = MappingProxyType(
    {
        "conversation.verify_context": GatewayToolDescriptor(
            "READ_ONLY", "tool.conversation.read"
        ),
        "knowledge.list_accessible_spaces": GatewayToolDescriptor(
            "READ_ONLY", "tool.knowledge.read"
        ),
        "knowledge.search": GatewayToolDescriptor(
            "READ_ONLY", "tool.knowledge.search"
        ),
        "knowledge.load_chunks": GatewayToolDescriptor(
            "READ_ONLY", "tool.knowledge.read"
        ),
        "knowledge.file.list": GatewayToolDescriptor(
            "READ_ONLY", "tool.knowledge.read"
        ),
        "knowledge.file.create_folder": GatewayToolDescriptor(
            "WRITE", "tool.knowledge.write"
        ),
        "knowledge.file.delete": GatewayToolDescriptor(
            "HIGH", "tool.knowledge.delete"
        ),
        "memory.search": GatewayToolDescriptor("READ_ONLY", "tool.memory.read"),
        "memory.load_versions": GatewayToolDescriptor(
            "READ_ONLY", "tool.memory.read"
        ),
        "memory.enqueue_conflict_cleanup": GatewayToolDescriptor(
            "WRITE", "tool.memory.write"
        ),
        "memory.save": GatewayToolDescriptor("WRITE", "tool.memory.write"),
        "memory.update": GatewayToolDescriptor("WRITE", "tool.memory.write"),
        "memory.delete": GatewayToolDescriptor("HIGH", "tool.memory.delete"),
        "memory.clear": GatewayToolDescriptor("HIGH", "tool.memory.delete"),
        "web.search": GatewayToolDescriptor("READ_ONLY", "tool.web.search"),
        "smtp.send_email": GatewayToolDescriptor("HIGH", "tool.smtp.send"),
        "caldav.create_event": GatewayToolDescriptor("HIGH", "tool.caldav.write"),
    }
)


@dataclass(frozen=True, slots=True)
class ToolGatewaySettings:
    """Tool Gateway 出站资源限制；URL 只能是固定服务根地址。"""

    base_url: str
    timeout_seconds: float = 10.0
    max_request_bytes: int = 1_048_576
    max_response_bytes: int = 1_048_576
    max_attempts: int = 3
    retry_base_seconds: float = 0.1

    def __post_init__(self) -> None:
        parsed = urlsplit(self.base_url)
        if (
            parsed.scheme not in {"http", "https"}
            or not parsed.hostname
            or parsed.username is not None
            or parsed.password is not None
            or parsed.query
            or parsed.fragment
            or parsed.path not in {"", "/"}
        ):
            raise ValueError("tool gateway base_url must be an HTTP(S) service root")
        if self.timeout_seconds <= 0:
            raise ValueError("tool gateway timeout must be positive")
        if self.max_request_bytes < 1 or self.max_response_bytes < 1:
            raise ValueError("tool gateway request/response limits must be positive")
        if not 1 <= self.max_attempts <= 5:
            raise ValueError("tool gateway attempts must be between 1 and 5")
        if self.retry_base_seconds < 0 or self.retry_base_seconds > 5:
            raise ValueError("tool gateway retry delay must be between 0 and 5 seconds")

    @classmethod
    def from_env(cls) -> "ToolGatewaySettings | None":
        base_url = os.getenv("WORKFLOW_TOOL_GATEWAY_URL", "").strip()
        if not base_url:
            return None
        return cls(
            base_url=base_url,
            timeout_seconds=float(
                os.getenv("WORKFLOW_TOOL_GATEWAY_TIMEOUT_SECONDS", "10")
            ),
            max_request_bytes=int(
                os.getenv("WORKFLOW_TOOL_GATEWAY_MAX_REQUEST_BYTES", "1048576")
            ),
            max_response_bytes=int(
                os.getenv("WORKFLOW_TOOL_GATEWAY_MAX_RESPONSE_BYTES", "1048576")
            ),
            max_attempts=int(os.getenv("WORKFLOW_TOOL_GATEWAY_MAX_ATTEMPTS", "3")),
            retry_base_seconds=float(
                os.getenv("WORKFLOW_TOOL_GATEWAY_RETRY_BASE_SECONDS", "0.1")
            ),
        )


@dataclass(frozen=True, slots=True)
class ToolInvocationBinding:
    """一次调用的完整资源身份；字段同时进入请求正文和短期 Service JWT。"""

    run_id: UUID
    execution_id: UUID
    node_id: str
    invocation_id: UUID
    user_id: int
    session_id: int


class ToolGatewayClientError(RuntimeError):
    """不会携带 Token、参数正文或下游响应正文的安全错误。"""

    def __init__(self, code: str, message: str, *, retryable: bool) -> None:
        super().__init__(message)
        self.code = code
        self.retryable = retryable


@dataclass(frozen=True, slots=True)
class _GatewayHttpResponse:
    status_code: int
    content_type: str
    body: bytes


class JavaToolGatewayClient:
    """以固定端点、固定目录和全资源绑定 JWT 调用 Java Internal Tool Gateway。"""

    _RETRYABLE_STATUSES = frozenset({500, 502, 503, 504})

    def __init__(
        self,
        settings: ToolGatewaySettings,
        token_issuer: WorkflowServiceTokenIssuer,
        *,
        transport: httpx.BaseTransport | None = None,
    ) -> None:
        self.settings = settings
        self.token_issuer = token_issuer
        self.transport = transport
        self.endpoint = f"{settings.base_url.rstrip('/')}/internal/v1/tools/invoke"

    def invoke(
        self,
        binding: ToolInvocationBinding,
        tool_name: str,
        arguments: dict[str, Any],
        *,
        confirmation: ConfirmationGrant | None = None,
    ) -> ToolInvokeResponse:
        descriptor = TOOL_GATEWAY_CATALOG.get(tool_name)
        if descriptor is None:
            raise ToolGatewayClientError(
                "TOOL_NOT_REGISTERED",
                "tool is not registered for the Java gateway",
                retryable=False,
            )
        request = ToolInvokeRequest(
            contract_version="1.0",
            run_id=binding.run_id,
            execution_id=binding.execution_id,
            node_id=binding.node_id,
            invocation_id=binding.invocation_id,
            user_id=binding.user_id,
            session_id=binding.session_id,
            tool_name=tool_name,
            risk_level=descriptor.risk_level,
            arguments=arguments,
            confirmation=confirmation,
        )
        token = self.token_issuer.issue(
            TOOL_GATEWAY_AUDIENCE,
            [descriptor.required_scope],
            {
                "userId": binding.user_id,
                "sessionId": binding.session_id,
                "runId": str(binding.run_id),
                "executionId": str(binding.execution_id),
                "nodeId": binding.node_id,
                "invocationId": str(binding.invocation_id),
            },
        )
        try:
            payload = request.model_dump(mode="json", by_alias=True)
            request_body = json.dumps(
                payload, separators=(",", ":"), ensure_ascii=False
            ).encode("utf-8")
        except (PydanticSerializationError, TypeError, ValueError) as exc:
            raise ToolGatewayClientError(
                "INVALID_TOOL_ARGUMENTS",
                "tool arguments are not JSON serializable",
                retryable=False,
            ) from exc
        if len(request_body) > self.settings.max_request_bytes:
            raise ToolGatewayClientError(
                "GATEWAY_REQUEST_TOO_LARGE",
                "tool gateway request exceeded the configured limit",
                retryable=False,
            )
        response = self._post_with_retry(request_body, token)
        try:
            parsed = ToolInvokeResponse.model_validate(response)
        except Exception as exc:
            raise ToolGatewayClientError(
                "INVALID_GATEWAY_RESPONSE",
                "tool gateway returned an invalid contract response",
                retryable=False,
            ) from exc
        if parsed.invocation_id != binding.invocation_id:
            raise ToolGatewayClientError(
                "INVOCATION_BINDING_MISMATCH",
                "tool gateway response does not match this invocation",
                retryable=False,
            )
        return parsed

    def _post_with_retry(self, request_body: bytes, token: str) -> dict[str, Any]:
        last_error: ToolGatewayClientError | None = None
        for attempt in range(self.settings.max_attempts):
            try:
                response = self._post_once(request_body, token)
            except httpx.TransportError as exc:
                last_error = ToolGatewayClientError(
                    "TOOL_GATEWAY_UNAVAILABLE",
                    "tool gateway transport failed",
                    retryable=True,
                )
                if attempt + 1 >= self.settings.max_attempts:
                    raise last_error from exc
                self._backoff(attempt)
                continue
            if response.status_code in self._RETRYABLE_STATUSES:
                last_error = ToolGatewayClientError(
                    "TOOL_GATEWAY_UNAVAILABLE",
                    "tool gateway is temporarily unavailable",
                    retryable=True,
                )
                if attempt + 1 >= self.settings.max_attempts:
                    raise last_error
                self._backoff(attempt)
                continue
            if response.status_code != 200:
                raise ToolGatewayClientError(
                    "TOOL_GATEWAY_REJECTED",
                    f"tool gateway rejected the request with HTTP {response.status_code}",
                    retryable=False,
                )
            return self._decode_response(response)
        assert last_error is not None
        raise last_error

    def _post_once(
        self, request_body: bytes, token: str
    ) -> _GatewayHttpResponse:
        # 禁止跟随重定向，避免 Bearer Token 被转发到非预期主机。
        with httpx.Client(
            timeout=self.settings.timeout_seconds,
            follow_redirects=False,
            transport=self.transport,
        ) as client:
            with client.stream(
                "POST",
                self.endpoint,
                content=request_body,
                headers={
                    "Authorization": f"Bearer {token}",
                    "Accept": "application/json",
                    "Content-Type": "application/json",
                },
            ) as response:
                if response.status_code != 200:
                    # 非成功响应不需要读取正文，避免下游错误页占用无界内存。
                    return _GatewayHttpResponse(
                        response.status_code,
                        response.headers.get("content-type", ""),
                        b"",
                    )
                declared_length = response.headers.get("content-length")
                if declared_length is not None:
                    try:
                        if int(declared_length) > self.settings.max_response_bytes:
                            raise self._too_large()
                    except ValueError:
                        # 非法 Content-Length 不作为可信边界，仍由实际流量计数限制。
                        pass
                parts: list[bytes] = []
                received = 0
                for chunk in response.iter_bytes():
                    received += len(chunk)
                    if received > self.settings.max_response_bytes:
                        raise self._too_large()
                    parts.append(chunk)
                return _GatewayHttpResponse(
                    response.status_code,
                    response.headers.get("content-type", ""),
                    b"".join(parts),
                )

    def _decode_response(self, response: _GatewayHttpResponse) -> dict[str, Any]:
        content_type = response.content_type.split(";", 1)[0].strip().lower()
        if content_type != "application/json":
            raise ToolGatewayClientError(
                "INVALID_GATEWAY_RESPONSE",
                "tool gateway response must be JSON",
                retryable=False,
            )
        body = response.body
        try:
            value = json.loads(body)
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise ToolGatewayClientError(
                "INVALID_GATEWAY_RESPONSE",
                "tool gateway returned malformed JSON",
                retryable=False,
            ) from exc
        if not isinstance(value, dict):
            raise ToolGatewayClientError(
                "INVALID_GATEWAY_RESPONSE",
                "tool gateway response must be a JSON object",
                retryable=False,
            )
        return value

    @staticmethod
    def _too_large() -> ToolGatewayClientError:
        return ToolGatewayClientError(
            "GATEWAY_RESPONSE_TOO_LARGE",
            "tool gateway response exceeded the configured limit",
            retryable=False,
        )

    def _backoff(self, attempt: int) -> None:
        delay = min(self.settings.retry_base_seconds * (2**attempt), 1.0)
        if delay:
            time.sleep(delay)
