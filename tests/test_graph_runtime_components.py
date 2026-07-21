from __future__ import annotations

import time

import pytest

from mini_agent_flow.engine.activation import ActivationTracker, JoinCoordinator
from mini_agent_flow.engine.execution_plan import ExecutionPlanCompiler
from mini_agent_flow.engine.graph_executor import GraphWorkflowExecutor
from mini_agent_flow.engine.graph_models import GraphWorkflow
from mini_agent_flow.engine.outcome import OutcomeRouter
from mini_agent_flow.engine.executor import WorkflowExecutionError
from mini_agent_flow.llm.mock import MockLLM
from mini_agent_flow.tools.builtin import create_default_tool_registry
from mini_agent_flow.tools.registry import ToolRegistry
from mini_agent_flow.tools.spec import ToolSpec


def test_activation_tracker_records_explicit_arrivals() -> None:
    tracker = ActivationTracker()
    tracker.activate(
        edge_id="edge_a_b",
        source_node_id="a",
        target_node_id="b",
        source_status="success",
    )

    arrivals = JoinCoordinator().arrivals("b", tracker)

    assert tracker.is_active("b") is True
    assert [token.edge_id for token in arrivals] == ["edge_a_b"]
    assert tracker.is_active("missing") is False


def test_outcome_router_uses_success_default_and_error_priority() -> None:
    workflow = GraphWorkflow.model_validate(
        {
            "version": "2.0",
            "name": "router",
            "nodes": [
                {"id": "start", "type": "start"},
                {"id": "call", "type": "tool", "tool": "echo", "input": "x", "output": "value"},
                {"id": "success", "type": "end"},
                {"id": "fallback", "type": "end"},
                {"id": "error_a", "type": "end"},
                {"id": "error_b", "type": "end"},
            ],
            "edges": [
                {"from": "start", "to": "call"},
                {"id": "conditional", "from": "call", "to": "success", "condition": {"source": True}},
                {"id": "fallback_edge", "from": "call", "to": "fallback", "kind": "default"},
                {"id": "error_b_edge", "from": "call", "to": "error_b", "kind": "error", "priority": 20},
                {"id": "error_a_edge", "from": "call", "to": "error_a", "kind": "error", "priority": 10},
            ],
        }
    )
    router = OutcomeRouter(ExecutionPlanCompiler().compile(workflow).edge_index)

    assert [edge.id for edge in router.success_edges("call", lambda edge: False)] == [
        "fallback_edge"
    ]
    assert router.error_edge("call", lambda edge: True).id == "error_a_edge"


def test_skip_if_marks_activated_node_skipped_and_propagates_control() -> None:
    workflow = GraphWorkflow.model_validate(
        {
            "version": "2.0",
            "name": "skip_if",
            "inputs": {"skip": True},
            "nodes": [
                {"id": "start", "type": "start"},
                {
                    "id": "call",
                    "type": "tool",
                    "tool": "echo",
                    "input": "must-not-run",
                    "output": "value",
                    "skip_if": {"source": "{{ skip }}"},
                },
                {"id": "end", "type": "end"},
            ],
            "edges": [
                {"from": "start", "to": "call"},
                {"from": "call", "to": "end"},
            ],
        }
    )
    executor = GraphWorkflowExecutor(
        llm=MockLLM(),
        tool_registry=create_default_tool_registry(),
    )

    result = executor.run(workflow)

    skipped = next(event for event in result.trace if event["node_id"] == "call")
    assert skipped["status"] == "skipped"
    assert skipped["input"]["reason"] == "skip_if matched"
    assert result.executed_nodes == ["start", "call", "end"]
    assert "call" not in result.context["node_outputs"]


def test_skipped_node_can_attribute_passthrough_output_to_self() -> None:
    workflow = GraphWorkflow.model_validate(
        {
            "version": "2.0",
            "name": "skip_self_output",
            "outputs": ["merged"],
            "nodes": [
                {"id": "start", "type": "start"},
                {"id": "source", "type": "tool", "tool": "echo", "input": "value", "output": "value"},
                {
                    "id": "skip",
                    "type": "tool",
                    "tool": "echo",
                    "input": "unused",
                    "output": "value",
                    "skip_if": {"source": True},
                    "skip_output_attribution": "self",
                    "skip_output_mapping": {"value": "source.value"},
                },
                {
                    "id": "merge",
                    "type": "merge",
                    "inputs": {"item": {"node": "skip", "output": "value"}},
                    "strategy": "first",
                    "output": "merged",
                    "publish": True,
                },
                {"id": "end", "type": "end"},
            ],
            "edges": [
                {"from": "start", "to": "source"},
                {"from": "source", "to": "skip"},
                {"from": "skip", "to": "merge"},
                {"from": "merge", "to": "end"},
            ],
        }
    )
    executor = GraphWorkflowExecutor(
        llm=MockLLM(), tool_registry=create_default_tool_registry()
    )

    result = executor.run(workflow)

    assert result.final_output == "value"
    assert result.context["node_outputs"]["skip"]["value"] == "value"


def test_error_edge_runs_restricted_handler_and_marks_recovery() -> None:
    registry = ToolRegistry()

    def fail(_: object) -> object:
        raise RuntimeError("safe failure")

    registry.register("fail", fail, spec=ToolSpec(name="fail"))
    registry.register(
        "echo",
        lambda value: value,
        spec=ToolSpec(name="echo", idempotent=True),
    )
    workflow = GraphWorkflow.model_validate(
        {
            "version": "2.0",
            "name": "error_recovery",
            "outputs": ["recovered"],
            "nodes": [
                {"id": "start", "type": "start"},
                {"id": "call", "type": "tool", "tool": "fail", "input": {}, "output": "value"},
                {
                    "id": "handler",
                    "type": "tool",
                    "tool": "echo",
                    "input": "{{ error_message }}",
                    "output": "recovered",
                    "publish": True,
                    "outcome_inputs": {"error_message": "safe_error_message"},
                },
                {"id": "end", "type": "end"},
            ],
            "edges": [
                {"from": "start", "to": "call"},
                {"from": "call", "to": "handler", "kind": "error", "priority": 1},
                {"from": "handler", "to": "end"},
            ],
        }
    )

    result = GraphWorkflowExecutor(llm=MockLLM(), tool_registry=registry).run(workflow)

    assert result.status == "completed_with_recovery"
    assert result.final_output == "safe failure"
    assert result.active_end_nodes == ["end"]
    assert result.handled_outcomes[0]["outcome"] == "error"
    assert "safe_error_message" not in result.context


def test_timeout_retries_idempotent_tool_once_then_routes_timeout_edge() -> None:
    registry = ToolRegistry()
    calls: list[int] = []

    def slow(value: object) -> object:
        calls.append(1)
        time.sleep(0.005)
        return value

    registry.register(
        "slow",
        slow,
        spec=ToolSpec(name="slow", idempotent=True),
    )
    workflow = GraphWorkflow.model_validate(
        {
            "version": "2.0",
            "name": "timeout_recovery",
            "nodes": [
                {"id": "start", "type": "start"},
                {"id": "call", "type": "tool", "tool": "slow", "input": "x", "output": "value", "timeout_seconds": 0.001},
                {"id": "timeout_end", "type": "end"},
            ],
            "edges": [
                {"from": "start", "to": "call"},
                {"from": "call", "to": "timeout_end", "kind": "timeout"},
            ],
        }
    )

    result = GraphWorkflowExecutor(llm=MockLLM(), tool_registry=registry).run(workflow)

    assert len(calls) == 2
    assert result.status == "completed_with_recovery"
    assert result.handled_outcomes[0]["outcome"] == "timeout"
    assert "call" not in result.context["node_outputs"]


def test_worker_timeout_does_not_retry_non_idempotent_tool() -> None:
    registry = ToolRegistry()
    calls: list[int] = []

    def slow(value: object) -> object:
        calls.append(1)
        time.sleep(0.004)
        return value

    registry.register("slow", slow, spec=ToolSpec(name="slow", idempotent=False))
    workflow = GraphWorkflow.model_validate(
        {
            "version": "2.0",
            "name": "non_idempotent_timeout",
            "nodes": [
                {"id": "start", "type": "start"},
                {"id": "call", "type": "tool", "tool": "slow", "input": "x", "output": "value", "timeout_seconds": 0.001},
                {"id": "timeout_end", "type": "end"},
            ],
            "edges": [
                {"from": "start", "to": "call"},
                {"from": "call", "to": "timeout_end", "kind": "timeout"},
            ],
        }
    )

    result = GraphWorkflowExecutor(llm=MockLLM(), tool_registry=registry).run(workflow)

    assert len(calls) == 1
    assert result.status == "completed_with_recovery"


def test_run_fails_when_no_active_end_is_reached() -> None:
    workflow = GraphWorkflow.model_validate(
        {
            "version": "2.0",
            "name": "inactive_end",
            "nodes": [
                {"id": "start", "type": "start"},
                {"id": "end", "type": "end"},
            ],
            "edges": [
                {"from": "start", "to": "end", "condition": {"source": False}},
            ],
        }
    )

    with pytest.raises(WorkflowExecutionError, match="active end"):
        GraphWorkflowExecutor(
            llm=MockLLM(), tool_registry=create_default_tool_registry()
        ).run(workflow)


def test_multiple_active_ends_are_summarized() -> None:
    workflow = GraphWorkflow.model_validate(
        {
            "version": "2.0",
            "name": "multiple_ends",
            "nodes": [
                {"id": "start", "type": "start"},
                {"id": "end_a", "type": "end"},
                {"id": "end_b", "type": "end"},
            ],
            "edges": [
                {"from": "start", "to": "end_a"},
                {"from": "start", "to": "end_b"},
            ],
        }
    )

    result = GraphWorkflowExecutor(
        llm=MockLLM(), tool_registry=create_default_tool_registry()
    ).run(workflow)

    assert result.active_end_nodes == ["end_a", "end_b"]
