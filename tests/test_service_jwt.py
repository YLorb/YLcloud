from __future__ import annotations

import time
import base64
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from uuid import UUID

import pytest
from fastapi.testclient import TestClient
from jsonschema import Draft202012Validator, FormatChecker

from mini_agent_flow.contracts.models import (
    WorkflowResult,
    WorkflowRunAccepted,
    WorkflowRunCreateRequest,
)
from mini_agent_flow.security.service_jwt import (
    HmacJwtIssuer,
    HmacJwtVerifier,
    ServiceAuthError,
    ServiceJwtSettings,
    WorkflowServiceTokenIssuer,
)
from mini_agent_flow.service.api import create_app
from mini_agent_flow.service.run_service import RequestMetadata, RunApplicationService


ACTIVE = "active-service-secret-32-bytes-minimum-0001"
PREVIOUS = "previous-service-secret-32-bytes-minimum-01"
RUN_ID = UUID("11111111-1111-4111-8111-111111111111")
EXECUTION_ID = UUID("22222222-2222-4222-8222-222222222222")


def _settings(
    *,
    secret: str = ACTIVE,
    previous: str | None = None,
    previous_until: int | None = None,
    audience: str = "ylcloud-workflow",
) -> ServiceJwtSettings:
    return ServiceJwtSettings(
        issuer="ylcloud-app",
        subject="ylcloud-app",
        audience=audience,
        active_secret=secret,
        previous_secret=previous,
        previous_valid_until_epoch_seconds=previous_until,
        clock_skew_seconds=0,
    )


def _payload() -> dict[str, Any]:
    return {
        "contractVersion": "1.0",
        "workflowType": "ASSISTANT",
        "workflowVersion": "2.0",
        "userId": 101,
        "sessionId": 201,
        "sourceMessageId": 301,
        "assistantMessageId": 302,
        "question": "鉴权测试",
        "shortTermContext": [],
        "permissionScope": {},
        "configVersion": "v1",
        "requestContextHash": "a" * 64,
        "budget": {
            "maxBusinessNodes": 2,
            "maxParallelNodes": 1,
            "maxModelCalls": 2,
            "maxRuntimeMs": 5000,
        },
    }


class StubRunService(RunApplicationService):
    async def is_ready(self) -> bool:
        return True

    async def create_run(
        self, request: WorkflowRunCreateRequest, metadata: RequestMetadata
    ) -> WorkflowRunAccepted:
        return WorkflowRunAccepted(
            contract_version="1.0",
            run_id=RUN_ID,
            execution_id=EXECUTION_ID,
            execution_epoch=1,
            status="QUEUED",
            accepted_at=datetime.now(timezone.utc),
        )

    async def get_run(self, run_id: str) -> dict[str, Any]:
        return {"runId": run_id, "status": "QUEUED"}

    async def get_result(self, run_id: str) -> WorkflowResult:
        raise AssertionError("not used")

    async def cancel_run(self, run_id: str) -> dict[str, Any]:
        return {"runId": run_id, "status": "CANCELLED"}

    async def retry_run(self, run_id: str) -> WorkflowRunAccepted:
        raise AssertionError("not used")


def test_active_and_previous_secrets_support_bounded_rotation() -> None:
    verifier = HmacJwtVerifier(
        _settings(previous=PREVIOUS, previous_until=int(time.time()) + 60)
    )
    active = HmacJwtIssuer(_settings()).issue(["workflow.run.create"])
    old = HmacJwtIssuer(_settings(secret=PREVIOUS)).issue(["workflow.run.create"])
    assert verifier.authenticate(f"Bearer {active}", ["workflow.run.create"]).subject == "ylcloud-app"
    assert verifier.authenticate(f"Bearer {old}", ["workflow.run.create"]).subject == "ylcloud-app"

    without_overlap = HmacJwtVerifier(_settings())
    with pytest.raises(ServiceAuthError) as rejected:
        without_overlap.authenticate(f"Bearer {old}", ["workflow.run.create"])
    assert rejected.value.status_code == 401
    expired_overlap = HmacJwtVerifier(
        _settings(previous=PREVIOUS, previous_until=int(time.time()) - 1)
    )
    with pytest.raises(ServiceAuthError):
        expired_overlap.authenticate(f"Bearer {old}", ["workflow.run.create"])


def test_issued_claims_match_authoritative_schema() -> None:
    token = HmacJwtIssuer(_settings()).issue(
        ["workflow.run.create"],
        bindings={"userId": 101, "sessionId": 201, "messageId": 302},
    )
    payload = token.split(".")[1]
    claims = json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))
    schema = json.loads(
        (Path(__file__).resolve().parents[1] / "schemas" / "service-jwt-claims.schema.json").read_text(
            encoding="utf-8"
        )
    )
    Draft202012Validator(schema, format_checker=FormatChecker()).validate(claims)

    outbound = WorkflowServiceTokenIssuer(ACTIVE).issue(
        "ylcloud-model-service",
        ["model.embed"],
        {"runId": str(RUN_ID)},
    )
    outbound_payload = outbound.split(".")[1]
    outbound_claims = json.loads(
        base64.urlsafe_b64decode(outbound_payload + "=" * (-len(outbound_payload) % 4))
    )
    Draft202012Validator(schema, format_checker=FormatChecker()).validate(outbound_claims)
    with pytest.raises(ValueError, match="audience"):
        WorkflowServiceTokenIssuer(ACTIVE).issue("user-browser", ["model.embed"], {})
    with pytest.raises(ValueError, match="binding"):
        HmacJwtIssuer(_settings()).issue(
            ["workflow.run.create"], bindings={"aud": "ylcloud-model-service"}
        )


@pytest.mark.parametrize("case", ["expired", "wrong_audience", "tampered", "over_scope"])
def test_invalid_service_tokens_are_rejected(case: str) -> None:
    verifier = HmacJwtVerifier(_settings())
    issuer = HmacJwtIssuer(_settings())
    if case == "expired":
        token = issuer.issue(["workflow.run.read"], now=int(time.time()) - 301)
        required = ["workflow.run.read"]
    elif case == "wrong_audience":
        token = HmacJwtIssuer(_settings(audience="ylcloud-workflow-callback")).issue(
            ["workflow.run.read"]
        )
        required = ["workflow.run.read"]
    elif case == "tampered":
        token = issuer.issue(["workflow.run.read"])
        token = token[:-1] + ("A" if token[-1] != "A" else "B")
        required = ["workflow.run.read"]
    else:
        token = issuer.issue(["workflow.run.read"])
        required = ["workflow.run.cancel"]
    with pytest.raises(ServiceAuthError) as rejected:
        verifier.authenticate(f"Bearer {token}", required)
    assert rejected.value.status_code in {401, 403}


def test_workflow_api_enforces_scope_and_request_bindings() -> None:
    verifier = HmacJwtVerifier(_settings())
    issuer = HmacJwtIssuer(_settings())
    service = StubRunService()
    headers = {"Idempotency-Key": "jwt-302", "X-Request-Id": "request-jwt"}
    bindings = {"userId": 101, "sessionId": 201, "messageId": 302}

    with TestClient(create_app(service, authenticator=verifier)) as client:
        assert client.post("/internal/v1/workflow-runs", json=_payload(), headers=headers).status_code == 401

        wrong_scope = issuer.issue(["workflow.run.read"], bindings=bindings)
        denied = client.post(
            "/internal/v1/workflow-runs",
            json=_payload(),
            headers={**headers, "Authorization": f"Bearer {wrong_scope}"},
        )
        assert denied.status_code == 403
        assert denied.json()["code"] == "INSUFFICIENT_SERVICE_SCOPE"

        wrong_binding = issuer.issue(
            ["workflow.run.create"], bindings={**bindings, "messageId": 999}
        )
        denied = client.post(
            "/internal/v1/workflow-runs",
            json=_payload(),
            headers={**headers, "Authorization": f"Bearer {wrong_binding}"},
        )
        assert denied.status_code == 403
        assert denied.json()["code"] == "SERVICE_TOKEN_BINDING_MISMATCH"

        valid = issuer.issue(["workflow.run.create"], bindings=bindings)
        accepted = client.post(
            "/internal/v1/workflow-runs",
            json=_payload(),
            headers={**headers, "Authorization": f"Bearer {valid}"},
        )
        assert accepted.status_code == 202

        read = issuer.issue(["workflow.run.read"], bindings={"runId": str(RUN_ID)})
        assert client.get(
            f"/internal/v1/workflow-runs/{RUN_ID}",
            headers={"Authorization": f"Bearer {read}"},
        ).status_code == 200

        other = issuer.issue(
            ["workflow.run.read"],
            bindings={"runId": "33333333-3333-4333-8333-333333333333"},
        )
        assert client.get(
            f"/internal/v1/workflow-runs/{RUN_ID}",
            headers={"Authorization": f"Bearer {other}"},
        ).status_code == 403
