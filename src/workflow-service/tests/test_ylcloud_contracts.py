from __future__ import annotations

import copy
import json
from pathlib import Path

import pytest
from jsonschema import Draft202012Validator, FormatChecker
from pydantic import ValidationError

from mini_agent_flow.contracts.models import (
    ContractError,
    ContractErrorCode,
    IntentPlan,
    IntentTask,
    IntentType,
    JavaMessageStatus,
    ToolInvokeResponse,
    ToolInvokeRequest,
    WorkflowDeliveryAck,
    WorkflowResult,
    WorkflowRunCreateRequest,
    WorkflowRunStatus,
    WorkflowTerminalCallback,
    map_java_status,
)


SCHEMA_PATH = Path("schemas/workflow-run-contracts.schema.json")
EXAMPLE_ROOT = Path("contract-examples")


@pytest.fixture(scope="module")
def contract_schema() -> dict:
    schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
    Draft202012Validator.check_schema(schema)
    return schema


def _validator(schema: dict, definition: str) -> Draft202012Validator:
    wrapper = {
        "$schema": schema["$schema"],
        "$defs": schema["$defs"],
        "$ref": f"#/$defs/{definition}",
    }
    return Draft202012Validator(wrapper, format_checker=FormatChecker())


def _example(name: str) -> dict:
    return json.loads((EXAMPLE_ROOT / name).read_text(encoding="utf-8"))


@pytest.mark.parametrize(
    ("definition", "fixture"),
    [
        ("WorkflowRunCreateRequest", "workflow-run-create.valid.json"),
        ("WorkflowResult", "workflow-result.valid.json"),
        ("WorkflowTerminalCallback", "workflow-callback.valid.json"),
        ("WorkflowDeliveryAck", "workflow-delivery-ack.valid.json"),
    ],
)
def test_shared_examples_pass_json_schema(
    contract_schema: dict, definition: str, fixture: str
) -> None:
    errors = list(_validator(contract_schema, definition).iter_errors(_example(fixture)))
    assert errors == []


def test_python_models_round_trip_shared_examples() -> None:
    request = WorkflowRunCreateRequest.model_validate(_example("workflow-run-create.valid.json"))
    result = WorkflowResult.model_validate(_example("workflow-result.valid.json"))
    callback = WorkflowTerminalCallback.model_validate(_example("workflow-callback.valid.json"))
    ack = WorkflowDeliveryAck.model_validate(_example("workflow-delivery-ack.valid.json"))

    assert request.model_dump(by_alias=True, mode="json") == _example(
        "workflow-run-create.valid.json"
    )
    assert result.model_dump(by_alias=True, mode="json") == _example(
        "workflow-result.valid.json"
    )
    assert callback.model_dump(by_alias=True, mode="json") == _example(
        "workflow-callback.valid.json"
    )
    assert ack.model_dump(by_alias=True, mode="json") == _example(
        "workflow-delivery-ack.valid.json"
    )


@pytest.mark.parametrize(
    "mutation",
    [
        lambda data: data.pop("assistantMessageId"),
        lambda data: data.__setitem__("contractVersion", "2.0"),
        lambda data: data.__setitem__("workflowType", "UNKNOWN"),
        lambda data: data["budget"].__setitem__("maxBusinessNodes", 7),
    ],
)
def test_run_request_rejects_missing_unknown_or_excessive_values(
    contract_schema: dict, mutation
) -> None:
    data = copy.deepcopy(_example("workflow-run-create.valid.json"))
    mutation(data)
    assert list(_validator(contract_schema, "WorkflowRunCreateRequest").iter_errors(data))
    with pytest.raises(ValidationError):
        WorkflowRunCreateRequest.model_validate(data)


def test_explicit_knowledge_scope_cannot_auto_add(contract_schema: dict) -> None:
    data = copy.deepcopy(_example("workflow-run-create.valid.json"))
    data["knowledgeScope"]["explicitSelection"] = True
    assert list(_validator(contract_schema, "WorkflowRunCreateRequest").iter_errors(data))
    with pytest.raises(ValidationError, match="cannot auto-add"):
        WorkflowRunCreateRequest.model_validate(data)


def test_result_rejects_final_answer_and_hash_mismatch(contract_schema: dict) -> None:
    data = copy.deepcopy(_example("workflow-result.valid.json"))
    data["finalAnswer"] = "Workflow 不得生成最终回答"
    assert list(_validator(contract_schema, "WorkflowResult").iter_errors(data))
    with pytest.raises(ValidationError):
        WorkflowResult.model_validate(data)

    mismatch = copy.deepcopy(_example("workflow-result.valid.json"))
    mismatch["snapshotDraft"]["snapshotHash"] = "f" * 64
    with pytest.raises(ValidationError, match="snapshotHash"):
        WorkflowResult.model_validate(mismatch)


def test_high_risk_tool_requires_bound_confirmation(contract_schema: dict) -> None:
    invocation = {
        "contractVersion": "1.0",
        "runId": "11111111-1111-4111-8111-111111111111",
        "executionId": "22222222-2222-4222-8222-222222222222",
        "nodeId": "send_email",
        "invocationId": "44444444-4444-4444-8444-444444444444",
        "userId": 101,
        "sessionId": 201,
        "toolName": "email.send",
        "riskLevel": "HIGH",
        "arguments": {"to": "sandbox@example.com"},
    }
    assert list(_validator(contract_schema, "ToolInvokeRequest").iter_errors(invocation))
    with pytest.raises(ValidationError, match="requires confirmation"):
        ToolInvokeRequest.model_validate(invocation)


def test_intent_plan_and_tool_response_enforce_semantic_contracts(
    contract_schema: dict,
) -> None:
    with pytest.raises(ValidationError, match="unknown"):
        IntentPlan(
            plan_version="1.0",
            primary_intent=IntentType.MIXED,
            tasks=[
                IntentTask(
                    task_id="send",
                    intent=IntentType.EXTERNAL_ACTION,
                    instruction="发送邮件",
                    dependencies=["missing"],
                )
            ],
        )

    failed_response = {
        "contractVersion": "1.0",
        "invocationId": "44444444-4444-4444-8444-444444444444",
        "status": "FAILED",
    }
    assert list(_validator(contract_schema, "ToolInvokeResponse").iter_errors(failed_response))
    with pytest.raises(ValidationError, match="requires error"):
        ToolInvokeResponse.model_validate(failed_response)

    error = ContractError(
        contract_version="1.0",
        code=ContractErrorCode.TOOL_CONFIRMATION_REQUIRED,
        message="需要用户确认",
        retryable=False,
    )
    response = ToolInvokeResponse(
        contract_version="1.0",
        invocation_id="44444444-4444-4444-8444-444444444444",
        status="FAILED",
        error=error,
    )
    assert response.error is error

    with pytest.raises(ValidationError, match="cannot include result"):
        ToolInvokeResponse(
            contract_version="1.0",
            invocation_id="44444444-4444-4444-8444-444444444444",
            status="FAILED",
            result={"sent": False},
            error=error,
        )


def test_status_budget_and_confirmation_reject_ambiguous_values() -> None:
    from mini_agent_flow.contracts.models import ConfirmationGrant, RunBudget, StatusMapping

    with pytest.raises(ValidationError, match="inconsistent"):
        StatusMapping(
            java_status=JavaMessageStatus.SUCCESS,
            workflow_status=WorkflowRunStatus.CANCELLED,
            degraded=False,
        )
    with pytest.raises(ValidationError, match="parallel"):
        RunBudget(
            max_business_nodes=1,
            max_parallel_nodes=2,
            max_model_calls=1,
            max_runtime_ms=1_000,
        )
    with pytest.raises(ValidationError):
        ConfirmationGrant(
            mode="ALLOW_ONCE",
            grant_id="55555555-5555-4555-8555-555555555555",
            user_id=101,
            tool_name="email.send",
            parameter_hash="a" * 64,
            issued_at="2026-07-22T08:00:00",
            expires_at="2026-07-22T08:01:00",
        )


@pytest.mark.parametrize(
    ("workflow_status", "java_status", "degraded"),
    [
        (WorkflowRunStatus.QUEUED, JavaMessageStatus.QUEUED, False),
        (WorkflowRunStatus.PLANNING, JavaMessageStatus.RUNNING, False),
        (WorkflowRunStatus.VALIDATING, JavaMessageStatus.RUNNING, False),
        (WorkflowRunStatus.RUNNING, JavaMessageStatus.RUNNING, False),
        (WorkflowRunStatus.SUCCEEDED, JavaMessageStatus.SUCCESS, False),
        (WorkflowRunStatus.DEGRADED, JavaMessageStatus.SUCCESS, True),
        (WorkflowRunStatus.FAILED, JavaMessageStatus.FAILED, False),
        (WorkflowRunStatus.TIMED_OUT, JavaMessageStatus.FAILED, False),
        (WorkflowRunStatus.CANCELLED, JavaMessageStatus.FAILED, False),
        (WorkflowRunStatus.ABANDONED, JavaMessageStatus.FAILED, False),
    ],
)
def test_two_level_status_mapping(
    workflow_status: WorkflowRunStatus,
    java_status: JavaMessageStatus,
    degraded: bool,
) -> None:
    mapping = map_java_status(workflow_status)
    assert mapping.java_status is java_status
    assert mapping.workflow_status is workflow_status
    assert mapping.degraded is degraded
