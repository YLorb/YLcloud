from __future__ import annotations

import json
from pathlib import Path
from typing import Any
from uuid import UUID

import httpx
import pytest
from jsonschema import Draft202012Validator, FormatChecker
from pydantic import ValidationError

from mini_agent_flow.intent.compiler import IntentTaskGraphCompiler
from mini_agent_flow.intent.model_client import (
    IntentPlanClientError,
    IntentPlanClientSettings,
    ModelServiceIntentPlanClient,
)
from mini_agent_flow.intent.models import IntentCode, IntentPlanResponse, StructuredIntentPlan
from mini_agent_flow.intent.planner import IntentPlanner, IntentPlanningSettings
from mini_agent_flow.intent.validator import TaskGraphValidationError, TaskGraphValidator
from mini_agent_flow.security.service_jwt import (
    HmacJwtVerifier,
    ServiceJwtSettings,
    WorkflowServiceTokenIssuer,
)


RUN_ID = UUID("11111111-1111-4111-8111-111111111111")
SECRET = "active-service-secret-32-bytes-minimum-0001"


def _task(task_id: str, role: str, **overrides: Any) -> dict[str, Any]:
    value = {
        "taskId": task_id,
        "role": role,
        "intent": "GENERAL_QA",
        "instruction": task_id,
        "dependencies": [],
        "confidence": 0.95,
        "contextSufficiency": "SUFFICIENT",
        "relevance": "REQUIRED",
        "uncertainty": "NONE",
        "disposition": "ACTIVE",
    }
    value.update(overrides)
    return value


def _response(tasks: list[dict[str, Any]], primary: str = "primary") -> IntentPlanResponse:
    return IntentPlanResponse.model_validate(
        {
            "contractVersion": "1.0",
            "schemaVersion": "intent-plan/1.0",
            "promptVersion": "intent-plan-prompt/1.0",
            "modelVersion": "test-model",
            "plan": {
                "primaryTaskId": primary,
                "complexity": "COMPLEX" if len(tasks) > 1 else "SIMPLE",
                "tasks": tasks,
            },
        }
    )


class StubPlanModel:
    def __init__(self, responses: list[IntentPlanResponse]) -> None:
        self.responses = responses
        self.requests = []

    def plan(self, run_id, request):
        self.requests.append((run_id, request))
        return self.responses[min(len(self.requests) - 1, len(self.responses) - 1)]


class RetryableBrokenModel:
    def __init__(self) -> None:
        self.attempts = 0

    def plan(self, run_id, request):
        self.attempts += 1
        error = IntentPlanClientError(
            "INVALID_PLAN_RESPONSE", "invalid structured response", retryable=True
        )
        raise error


def test_authoritative_examples_match_schema_and_python_models() -> None:
    schema = json.loads(Path("schemas/intent-plan.schema.json").read_text(encoding="utf-8"))
    Draft202012Validator.check_schema(schema)
    validator = Draft202012Validator(schema, format_checker=FormatChecker())
    for name in ("intent-plan-request.valid.json", "intent-plan-response.valid.json"):
        payload = json.loads(Path("contract-examples", name).read_text(encoding="utf-8"))
        validator.validate(payload)
    response = IntentPlanResponse.model_validate(
        json.loads(Path("contract-examples/intent-plan-response.valid.json").read_text(encoding="utf-8"))
    )
    assert response.model_dump(mode="json", by_alias=True)["schemaVersion"] == "intent-plan/1.0"
    assert set(schema["$defs"]["IntentCode"]["enum"]) == {item.value for item in IntentCode}


def test_validator_rejects_cycle_duplicate_over_limit_and_unnecessary_subtask() -> None:
    cycle = _response(
        [
            _task("primary", "PRIMARY", dependencies=["sub"]),
            _task("sub", "SUBTASK", dependencies=["primary"]),
        ]
    ).plan
    with pytest.raises(TaskGraphValidationError, match="acyclic"):
        TaskGraphValidator().validate(cycle)
    with pytest.raises(ValidationError, match="unique"):
        _response([_task("primary", "PRIMARY"), _task("primary", "SUBTASK")])
    with pytest.raises(ValidationError):
        _response(
            [_task("primary", "PRIMARY")]
            + [_task(f"sub_{i}", "SUBTASK") for i in range(6)]
        )
    unnecessary = _response(
        [_task("primary", "PRIMARY"), _task("orphan", "SUBTASK")]
    ).plan
    with pytest.raises(TaskGraphValidationError, match="does not contribute"):
        TaskGraphValidator().validate(unnecessary)


def test_low_confidence_irrelevant_subtask_is_dropped_without_retry() -> None:
    model = StubPlanModel(
        [
            _response(
                [
                    _task("primary", "PRIMARY"),
                    _task(
                        "noise",
                        "SUBTASK",
                        confidence=0.2,
                        relevance="IRRELEVANT",
                        uncertainty="IRRELEVANT_SUBTASK",
                    ),
                ]
            )
        ]
    )
    resolved = IntentPlanner(model).resolve(RUN_ID, "hello", [])
    assert resolved.attempts == 1
    assert resolved.dropped_task_ids == ("noise",)
    assert [task.task_id for task in resolved.plan.tasks] == ["primary"]
    assert len(model.requests) == 1


def test_critical_routing_uncertainty_retries_then_compiles_dependency_dag() -> None:
    uncertain = _response(
        [_task("primary", "PRIMARY", confidence=0.4, uncertainty="ROUTING")]
    )
    valid = _response(
        [
            _task("lookup", "SUBTASK"),
            _task("primary", "PRIMARY", intent="ANALYZE", dependencies=["lookup"]),
        ]
    )
    model = StubPlanModel([uncertain, valid])
    resolved = IntentPlanner(model).resolve(RUN_ID, "analyze", [])
    execution_plan = IntentTaskGraphCompiler().compile(resolved)

    assert resolved.attempts == 2
    assert [request.attempt for _, request in model.requests] == [1, 2]
    workflow = execution_plan.workflow
    by_task = {
        node.metadata.get("task_id"): node.id
        for node in workflow.nodes
        if node.type == "llm"
    }
    edge_pairs = {(edge.source, edge.target) for edge in workflow.edges}
    assert (by_task["lookup"], by_task["primary"]) in edge_pairs
    assert all("最终用户回答" in node.prompt for node in workflow.nodes if node.type == "llm")


def test_retry_limit_degrades_and_security_unknown_executes_no_task() -> None:
    unsafe = _response(
        [
            _task("prepare", "SUBTASK"),
            _task(
                "primary",
                "PRIMARY",
                intent="EMAIL_SEND",
                dependencies=["prepare"],
                confidence=0.6,
                uncertainty="SECURITY",
            ),
        ]
    )
    model = StubPlanModel([unsafe])
    resolved = IntentPlanner(
        model, settings=IntentPlanningSettings(max_attempts=3)
    ).resolve(RUN_ID, "send", [])
    execution_plan = IntentTaskGraphCompiler().compile(resolved)

    assert resolved.attempts == 3
    assert resolved.degraded is True
    assert all(task.disposition == "SKIPPED_UNCERTAIN" for task in resolved.plan.tasks)
    assert [node.type for node in execution_plan.workflow.nodes] == ["start", "end"]
    assert len(execution_plan.workflow.edges) == 1
    with pytest.raises(ValueError):
        IntentPlanningSettings(max_attempts=0)
    assert IntentPlanningSettings(max_attempts=10).max_attempts == 10


def test_invalid_structured_responses_retry_then_fall_back_to_context_only() -> None:
    model = RetryableBrokenModel()
    resolved = IntentPlanner(
        model, settings=IntentPlanningSettings(max_attempts=2)
    ).resolve(RUN_ID, "current context question", [])
    execution_plan = IntentTaskGraphCompiler().compile(resolved)

    assert model.attempts == 2
    assert resolved.degraded is True
    assert resolved.plan.tasks[0].intent.value == "UNKNOWN"
    assert resolved.plan.tasks[0].uncertainty == "ROUTING"
    assert all(node.type != "tool" for node in execution_plan.workflow.nodes)


def test_model_service_client_uses_plan_scope_run_binding_and_fixed_endpoint() -> None:
    payload = _response([_task("primary", "PRIMARY")]).model_dump(mode="json", by_alias=True)

    def handler(request: httpx.Request) -> httpx.Response:
        assert str(request.url) == "http://model-service:8001/plan"
        verifier = HmacJwtVerifier(
            ServiceJwtSettings(
                issuer="ylcloud-workflow",
                subject="ylcloud-workflow",
                audience="ylcloud-model-service",
                active_secret=SECRET,
            )
        )
        identity = verifier.authenticate(request.headers["Authorization"], ["model.plan"])
        assert identity.run_id == str(RUN_ID)
        return httpx.Response(200, json=payload)

    client = ModelServiceIntentPlanClient(
        IntentPlanClientSettings("http://model-service:8001"),
        WorkflowServiceTokenIssuer(SECRET),
        transport=httpx.MockTransport(handler),
    )
    result = client.plan(
        RUN_ID,
        __import__("mini_agent_flow.intent.models", fromlist=["IntentPlanRequest"]).IntentPlanRequest(
            contract_version="1.0",
            schema_version="intent-plan/1.0",
            prompt_version="intent-plan-prompt/1.0",
            question="hello",
            short_term_context=[],
            attempt=1,
            confidence_threshold=0.7,
        ),
    )
    assert result.plan.primary_task_id == "primary"


def test_model_client_rejects_redirect_invalid_contract_and_unsafe_url() -> None:
    with pytest.raises(ValueError, match="service root"):
        IntentPlanClientSettings("http://user:pass@model/private")

    def invoke(response: httpx.Response) -> None:
        client = ModelServiceIntentPlanClient(
            IntentPlanClientSettings("http://model-service:8001"),
            WorkflowServiceTokenIssuer(SECRET),
            transport=httpx.MockTransport(lambda request: response),
        )
        request_payload = json.loads(
            Path("contract-examples/intent-plan-request.valid.json").read_text(encoding="utf-8")
        )
        from mini_agent_flow.intent.models import IntentPlanRequest

        client.plan(RUN_ID, IntentPlanRequest.model_validate(request_payload))

    with pytest.raises(IntentPlanClientError) as redirected:
        invoke(httpx.Response(302, headers={"Location": "http://attacker.invalid"}))
    assert redirected.value.retryable is False
    assert "attacker" not in str(redirected.value)
    with pytest.raises(IntentPlanClientError) as invalid:
        invoke(httpx.Response(200, json={"private": "model output"}))
    assert invalid.value.code == "INVALID_PLAN_RESPONSE"
    assert "private" not in str(invalid.value)
