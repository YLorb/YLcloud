from __future__ import annotations

from datetime import datetime, timedelta, timezone
from typing import Any
from uuid import UUID

import httpx
import pytest
from pydantic import ValidationError

from mini_agent_flow.contracts.models import ConfirmationGrant
from mini_agent_flow.security.service_jwt import (
    HmacJwtVerifier,
    ServiceJwtSettings,
    WorkflowServiceTokenIssuer,
)
from mini_agent_flow.tools.ylcloud_gateway import (
    JavaToolGatewayClient,
    ToolGatewayClientError,
    ToolGatewaySettings,
    ToolInvocationBinding,
)


SECRET = "active-service-secret-32-bytes-minimum-0001"
RUN_ID = UUID("11111111-1111-4111-8111-111111111111")
EXECUTION_ID = UUID("22222222-2222-4222-8222-222222222222")
INVOCATION_ID = UUID("33333333-3333-4333-8333-333333333333")


def _binding() -> ToolInvocationBinding:
    return ToolInvocationBinding(
        run_id=RUN_ID,
        execution_id=EXECUTION_ID,
        node_id="search_1",
        invocation_id=INVOCATION_ID,
        user_id=7,
        session_id=8,
    )


def _response(result: dict[str, Any] | None = None) -> dict[str, Any]:
    return {
        "contractVersion": "1.0",
        "invocationId": str(INVOCATION_ID),
        "status": "SUCCEEDED",
        "result": result or {"results": []},
        "error": None,
    }


def _client(handler: Any, **overrides: Any) -> JavaToolGatewayClient:
    settings = ToolGatewaySettings(
        base_url="http://ylcloud-server:8080",
        retry_base_seconds=0,
        **overrides,
    )
    return JavaToolGatewayClient(
        settings,
        WorkflowServiceTokenIssuer(SECRET),
        transport=httpx.MockTransport(handler),
    )


def test_invocation_uses_fixed_endpoint_minimal_scope_and_all_bindings() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert str(request.url) == "http://ylcloud-server:8080/internal/v1/tools/invoke"
        body = __import__("json").loads(request.content)
        assert body["riskLevel"] == "READ_ONLY"
        assert body["arguments"] == {"query": "hello"}
        verifier = HmacJwtVerifier(
            ServiceJwtSettings(
                issuer="ylcloud-workflow",
                subject="ylcloud-workflow",
                audience="ylcloud-tool-gateway",
                active_secret=SECRET,
            )
        )
        identity = verifier.authenticate(
            request.headers["Authorization"], ["tool.web.search"]
        )
        assert identity.scopes == frozenset({"tool.web.search"})
        assert (
            identity.user_id,
            identity.session_id,
            identity.run_id,
            identity.execution_id,
            identity.node_id,
            identity.invocation_id,
        ) == (7, 8, str(RUN_ID), str(EXECUTION_ID), "search_1", str(INVOCATION_ID))
        return httpx.Response(200, json=_response())

    result = _client(handler).invoke(_binding(), "web.search", {"query": "hello"})

    assert result.status == "SUCCEEDED"
    assert result.invocation_id == INVOCATION_ID


def test_unknown_tool_is_denied_before_network_and_risk_cannot_be_overridden() -> None:
    called = False

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal called
        called = True
        return httpx.Response(500)

    with pytest.raises(ToolGatewayClientError) as rejected:
        _client(handler).invoke(_binding(), "java.reflect.anything", {})

    assert rejected.value.code == "TOOL_NOT_REGISTERED"
    assert called is False


def test_high_risk_tool_requires_matching_confirmation() -> None:
    client = _client(lambda request: httpx.Response(200, json=_response()))
    with pytest.raises(ValidationError, match="high-risk"):
        client.invoke(
            _binding(),
            "smtp.send_email",
            {"to": "user@example.com", "subject": "s", "body": "b"},
        )

    now = datetime.now(timezone.utc)
    grant = ConfirmationGrant(
        mode="ALLOW_ONCE",
        grant_id=UUID("44444444-4444-4444-8444-444444444444"),
        user_id=99,
        tool_name="smtp.send_email",
        parameter_hash="a" * 64,
        issued_at=now,
        expires_at=now + timedelta(minutes=1),
    )
    with pytest.raises(ValidationError, match="confirmation user"):
        client.invoke(
            _binding(),
            "smtp.send_email",
            {"to": "user@example.com", "subject": "s", "body": "b"},
            confirmation=grant,
        )


def test_transport_retry_reuses_exact_invocation_and_request_body() -> None:
    attempts: list[bytes] = []

    def handler(request: httpx.Request) -> httpx.Response:
        attempts.append(request.content)
        if len(attempts) == 1:
            raise httpx.ConnectError("temporary", request=request)
        return httpx.Response(200, json=_response({"accepted": True}))

    result = _client(handler).invoke(
        _binding(),
        "web.search",
        {"query": "retry safely"},
    )

    assert result.result == {"accepted": True}
    assert len(attempts) == 2
    assert attempts[0] == attempts[1]
    assert str(INVOCATION_ID).encode() in attempts[0]


@pytest.mark.parametrize(
    ("response", "expected_code"),
    [
        (httpx.Response(302, headers={"Location": "http://attacker.invalid"}), "TOOL_GATEWAY_REJECTED"),
        (httpx.Response(200, text="not-json"), "INVALID_GATEWAY_RESPONSE"),
        (httpx.Response(200, json={"unexpected": True}), "INVALID_GATEWAY_RESPONSE"),
    ],
)
def test_redirect_or_invalid_contract_response_is_rejected(
    response: httpx.Response, expected_code: str
) -> None:
    with pytest.raises(ToolGatewayClientError) as rejected:
        _client(lambda request: response, max_attempts=1).invoke(
            _binding(), "web.search", {"query": "safe"}
        )

    assert rejected.value.code == expected_code
    assert "attacker" not in str(rejected.value)


def test_response_size_and_base_url_are_bounded() -> None:
    with pytest.raises(ValueError, match="service root"):
        ToolGatewaySettings(base_url="http://user:pass@ylcloud-server/private?next=x")

    oversized = httpx.Response(
        200,
        content=b"{" + b"x" * 128 + b"}",
        headers={"Content-Type": "application/json"},
    )
    with pytest.raises(ToolGatewayClientError) as rejected:
        _client(lambda request: oversized, max_response_bytes=64).invoke(
            _binding(), "web.search", {"query": "safe"}
        )
    assert rejected.value.code == "GATEWAY_RESPONSE_TOO_LARGE"

    with pytest.raises(ToolGatewayClientError) as rejected:
        _client(lambda request: httpx.Response(200, json=_response()), max_request_bytes=128).invoke(
            _binding(), "web.search", {"query": "x" * 256}
        )
    assert rejected.value.code == "GATEWAY_REQUEST_TOO_LARGE"

    with pytest.raises(ToolGatewayClientError) as rejected:
        _client(lambda request: httpx.Response(200, json=_response())).invoke(
            _binding(), "web.search", {"query": object()}
        )
    assert rejected.value.code == "INVALID_TOOL_ARGUMENTS"


def test_exhausted_transient_failure_is_retryable_and_sanitized() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(503, text="secret downstream details")

    with pytest.raises(ToolGatewayClientError) as rejected:
        _client(handler, max_attempts=2).invoke(
            _binding(), "web.search", {"query": "private question"}
        )

    assert rejected.value.code == "TOOL_GATEWAY_UNAVAILABLE"
    assert rejected.value.retryable is True
    assert "secret" not in str(rejected.value)
    assert "private" not in str(rejected.value)
