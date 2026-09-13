from __future__ import annotations

import pytest

from mini_agent_flow.engine.graph_analyzer import GraphAnalysisError, NetworkXGraphAnalyzer
from mini_agent_flow.engine.graph_compiler import V1ToV2Compiler
from mini_agent_flow.engine.graph_executor import GraphWorkflowExecutor
from mini_agent_flow.engine.graph_models import GraphWorkflow
from mini_agent_flow.engine.execution_plan import ExecutionPlanCompiler
from mini_agent_flow.engine.dataflow_validator import (
    DataflowValidationError,
    StaticDataflowValidator,
)
from mini_agent_flow.engine.schema_normalizer import SchemaNormalizer
from mini_agent_flow.engine.executor import WorkflowExecutionError
from mini_agent_flow.engine.loader import WorkflowLoader
from mini_agent_flow.engine.validator import WorkflowValidationError, WorkflowValidator
from mini_agent_flow.llm.mock import MockLLM
from mini_agent_flow.tools.builtin import create_default_tool_registry, echo
from mini_agent_flow.tools.registry import ToolRegistry
from mini_agent_flow.tools.spec import ToolSpec


def graph_executor() -> GraphWorkflowExecutor:
    return GraphWorkflowExecutor(
        llm=MockLLM(),
        tool_registry=create_default_tool_registry(),
        allowed_permissions={"public"},
        max_risk_level=5,
    )


def test_v1_compiles_to_v2_and_removes_condition_node() -> None:
    v1 = WorkflowLoader(validator=WorkflowValidator(allowed_tools=set())).load_data(
        {
            "version": "1.0",
            "name": "condition_compat",
            "inputs": {"flag": True},
            "nodes": [
                {"id": "start", "type": "start", "next": "check"},
                {
                    "id": "check",
                    "type": "condition",
                    "expression": "{{ flag }}",
                    "if_true": "true_end",
                    "if_false": "false_end",
                },
                {"id": "true_end", "type": "end"},
                {"id": "false_end", "type": "end"},
            ],
        }
    )

    v2 = V1ToV2Compiler().compile(v1)

    assert v2.version == "2.0"
    assert "check" not in {node.id for node in v2.nodes}
    true_edge = next(edge for edge in v2.edges if edge.target == "true_end")
    false_edge = next(edge for edge in v2.edges if edge.target == "false_end")
    assert true_edge.condition is not None
    assert true_edge.metadata["compiled_from_condition"] == "check"
    assert false_edge.default is True


def test_graph_executor_runs_v1_through_compatibility_compiler() -> None:
    v1 = WorkflowLoader(validator=WorkflowValidator(allowed_tools=set())).load_data(
        {
            "version": "1.0",
            "name": "condition_compat_run",
            "inputs": {"flag": False},
            "nodes": [
                {"id": "start", "type": "start", "next": "check"},
                {
                    "id": "check",
                    "type": "condition",
                    "expression": "{{ flag }}",
                    "if_true": "a_true_end",
                    "if_false": "b_false_end",
                },
                {"id": "a_true_end", "type": "end"},
                {"id": "b_false_end", "type": "end"},
            ],
        }
    )

    result = graph_executor().run(v1)

    assert result.executed_nodes == ["start", "b_false_end"]
    skipped = next(event for event in result.trace if event["node_id"] == "a_true_end")
    assert skipped["status"] == "skipped"


def test_graph_condition_skips_inactive_branch() -> None:
    workflow = GraphWorkflow.model_validate(
        {
            "version": "2.0",
            "name": "edge_condition",
            "inputs": {"flag": True},
            "nodes": [
                {"id": "start", "type": "start"},
                {"id": "a_true", "type": "tool", "tool": "echo", "input": "yes", "output": "value", "publish": True},
                {"id": "b_false", "type": "tool", "tool": "echo", "input": "no", "output": "unused"},
                {"id": "end", "type": "end"},
            ],
            "outputs": ["value"],
            "edges": [
                {"from": "start", "to": "a_true", "condition": {"source": "{{ flag }}", "operator": "truthy"}},
                {"from": "start", "to": "b_false", "default": True},
                {"from": "a_true", "to": "end"},
                {"from": "b_false", "to": "end"},
            ],
        }
    )

    result = graph_executor().run(workflow)

    assert result.final_output == "yes"
    assert "a_true" in result.executed_nodes
    assert "b_false" not in result.executed_nodes
    inactive = next(event for event in result.trace if event["node_id"] == "b_false")
    assert inactive["status"] == "inactive"
    assert inactive["schema_version"] == "2.0"


def test_graph_uses_stable_natural_id_topological_order() -> None:
    workflow = GraphWorkflow.model_validate(
        {
            "version": "2.0",
            "name": "stable_order",
            "nodes": [
                {"id": "start", "type": "start"},
                {"id": "node_10", "type": "tool", "tool": "echo", "input": 10, "output": "value"},
                {"id": "node_2", "type": "tool", "tool": "echo", "input": 2, "output": "value"},
                {"id": "end", "type": "end"},
            ],
            "edges": [
                {"from": "start", "to": "node_10"},
                {"from": "start", "to": "node_2"},
                {"from": "node_10", "to": "end"},
                {"from": "node_2", "to": "end"},
            ],
        }
    )

    compiled = NetworkXGraphAnalyzer().analyze(workflow)

    assert compiled.topological_order == ("start", "node_2", "node_10", "end")


def test_graph_merge_isolates_same_named_node_outputs() -> None:
    workflow = GraphWorkflow.model_validate(
        {
            "version": "2.0",
            "name": "merge_outputs",
            "outputs": ["merged"],
            "nodes": [
                {"id": "start", "type": "start"},
                {"id": "left", "type": "tool", "tool": "echo", "input": "L", "output": "result"},
                {"id": "right", "type": "tool", "tool": "echo", "input": "R", "output": "result"},
                {
                    "id": "merge",
                    "type": "merge",
                    "inputs": {
                        "left_value": {"node": "left", "output": "result"},
                        "right_value": {"node": "right", "output": "result"},
                    },
                    "strategy": "object",
                    "output": "merged",
                },
                {"id": "end", "type": "end"},
            ],
            "edges": [
                {"from": "start", "to": "left"},
                {"from": "start", "to": "right"},
                {"from": "left", "to": "merge"},
                {"from": "right", "to": "merge"},
                {"from": "merge", "to": "end"},
            ],
        }
    )

    result = graph_executor().run(workflow)

    assert result.final_output == {"left_value": "L", "right_value": "R"}
    assert result.context["node_outputs"]["left"]["result"] == "L"
    assert result.context["node_outputs"]["right"]["result"] == "R"


def test_merge_uses_explicit_default_for_inactive_branch() -> None:
    workflow = GraphWorkflow.model_validate(
        {
            "version": "2.0",
            "name": "merge_branch_default",
            "inputs": {"flag": True},
            "outputs": ["merged"],
            "nodes": [
                {"id": "start", "type": "start"},
                {"id": "left", "type": "tool", "tool": "echo", "input": "L", "output": "result"},
                {"id": "right", "type": "tool", "tool": "echo", "input": "R", "output": "result"},
                {
                    "id": "merge",
                    "type": "merge",
                    "inputs": {
                        "left": {"node": "left", "output": "result"},
                        "right": {"node": "right", "output": "result", "default": None},
                    },
                    "output": "merged",
                },
                {"id": "end", "type": "end"},
            ],
            "edges": [
                {"from": "start", "to": "left", "condition": {"source": "{{ flag }}"}},
                {"from": "start", "to": "right", "default": True},
                {"from": "left", "to": "merge"},
                {"from": "right", "to": "merge"},
                {"from": "merge", "to": "end"},
            ],
        }
    )

    result = graph_executor().run(workflow)

    assert result.final_output == {"left": "L", "right": None}


def test_loop_scc_executes_body_and_collects_results() -> None:
    registry = create_default_tool_registry()
    workflow = WorkflowLoader(
        validator=WorkflowValidator(allowed_tools=registry.names())
    ).load("examples/level_graph_workflow.yaml")
    assert isinstance(workflow, GraphWorkflow)

    result = graph_executor().run(workflow)

    assert result.final_output == ["first", "second"]
    assert result.executed_nodes == [
        "start",
        "process_items",
        "echo_item",
        "echo_item",
        "end",
    ]
    assert "loop_outputs" not in result.context
    assert "echo_item" not in result.context["node_outputs"]
    assert result.context["node_outputs"]["process_items"]["results"] == [
        "first",
        "second",
    ]
    body_events = [event for event in result.trace if event["node_id"] == "echo_item"]
    assert [(event["loop_id"], event["iteration"]) for event in body_events] == [
        ("process_items", 0),
        ("process_items", 1),
    ]


def test_plain_cycle_without_explicit_loop_is_rejected() -> None:
    workflow = GraphWorkflow.model_validate(
        {
            "version": "2.0",
            "name": "illegal_cycle",
            "nodes": [
                {"id": "start", "type": "start"},
                {"id": "a", "type": "tool", "tool": "echo", "input": "a", "output": "a"},
                {"id": "b", "type": "tool", "tool": "echo", "input": "b", "output": "b"},
                {"id": "end", "type": "end"},
            ],
            "edges": [
                {"from": "start", "to": "a"},
                {"from": "a", "to": "b"},
                {"from": "b", "to": "a"},
                {"from": "b", "to": "end"},
            ],
        }
    )

    with pytest.raises(GraphAnalysisError, match="explicit loop"):
        NetworkXGraphAnalyzer().analyze(workflow)


def test_validator_rejects_unknown_v2_edge_target() -> None:
    data = {
        "version": "2.0",
        "name": "bad_edge",
        "nodes": [
            {"id": "start", "type": "start"},
            {"id": "end", "type": "end"},
        ],
        "edges": [{"from": "start", "to": "missing"}],
    }

    with pytest.raises(WorkflowValidationError, match="unknown node"):
        WorkflowValidator().validate_data(data)


def test_loop_rejects_items_over_max_iterations() -> None:
    registry = create_default_tool_registry()
    workflow = WorkflowLoader(
        validator=WorkflowValidator(allowed_tools=registry.names())
    ).load("examples/level_graph_workflow.yaml")
    workflow.inputs["items"] = list(range(11))

    with pytest.raises(WorkflowExecutionError, match="max_iterations") as exc_info:
        graph_executor().run(workflow)

    assert exc_info.value.trace[-1]["node_id"] == "process_items"
    assert exc_info.value.trace[-1]["status"] == "failed"


def test_empty_loop_warns_and_returns_empty_collection() -> None:
    registry = create_default_tool_registry()
    workflow = WorkflowLoader(
        validator=WorkflowValidator(allowed_tools=registry.names())
    ).load("examples/level_graph_workflow.yaml")
    workflow.inputs["items"] = []

    with pytest.warns(RuntimeWarning, match="empty items"):
        result = graph_executor().run(workflow)

    assert result.final_output == []
    assert "echo_item" not in result.context["node_outputs"]


def test_graph_runtime_rechecks_tool_risk_policy() -> None:
    registry = ToolRegistry()
    registry.register("echo", echo, spec=ToolSpec(name="echo", risk_level=10))
    workflow = GraphWorkflow.model_validate(
        {
            "version": "2.0",
            "name": "tool_risk",
            "nodes": [
                {"id": "start", "type": "start"},
                {"id": "call", "type": "tool", "tool": "echo", "input": "x", "output": "value"},
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
        tool_registry=registry,
        max_risk_level=5,
    )

    with pytest.raises(WorkflowExecutionError, match="risk level") as exc_info:
        executor.run(workflow)

    assert exc_info.value.trace[-1]["node_id"] == "call"
    assert exc_info.value.trace[-1]["status"] == "failed"


def test_graph_normalizes_stable_edge_ids_and_end_kind() -> None:
    workflow = GraphWorkflow.model_validate(
        {
            "version": "2.0",
            "name": "normalized_edges",
            "nodes": [
                {"id": "start", "type": "start"},
                {"id": "end", "type": "end"},
            ],
            "edges": [{"from": "start", "to": "end"}],
        }
    )

    assert workflow.edges[0].id == "edge_start_end_0"
    assert workflow.edges[0].kind == "end"


def test_graph_rejects_duplicate_error_priority_and_timeout_edges() -> None:
    base = {
        "version": "2.0",
        "name": "outcome_edges",
        "nodes": [
            {"id": "start", "type": "start"},
            {"id": "call", "type": "tool", "tool": "echo", "input": "x", "output": "value"},
            {"id": "recover_a", "type": "end"},
            {"id": "recover_b", "type": "end"},
        ],
    }
    with pytest.raises(ValueError, match="error edge priorities"):
        GraphWorkflow.model_validate(
            {
                **base,
                "edges": [
                    {"from": "start", "to": "call"},
                    {"from": "call", "to": "recover_a", "kind": "error", "priority": 1},
                    {"from": "call", "to": "recover_b", "kind": "error", "priority": 1},
                ],
            }
        )
    with pytest.raises(ValueError, match="at most one timeout edge"):
        GraphWorkflow.model_validate(
            {
                **base,
                "edges": [
                    {"from": "start", "to": "call"},
                    {"from": "call", "to": "recover_a", "kind": "timeout"},
                    {"from": "call", "to": "recover_b", "kind": "timeout"},
                ],
            }
        )


def test_structural_graph_allows_parallel_business_edges() -> None:
    workflow = GraphWorkflow.model_validate(
        {
            "version": "2.0",
            "name": "parallel_business_edges",
            "nodes": [
                {"id": "start", "type": "start"},
                {"id": "call", "type": "tool", "tool": "echo", "input": "x", "output": "value"},
                {"id": "handler", "type": "tool", "tool": "echo", "input": "handled", "output": "handled"},
                {"id": "end", "type": "end"},
            ],
            "edges": [
                {"from": "start", "to": "call"},
                {"id": "error_1", "from": "call", "to": "handler", "kind": "error", "priority": 1},
                {"id": "error_2", "from": "call", "to": "handler", "kind": "error", "priority": 2},
                {"from": "call", "to": "end"},
                {"from": "handler", "to": "end"},
            ],
        }
    )

    plan = ExecutionPlanCompiler().compile(workflow)

    assert [edge.id for edge in plan.edge_index.outgoing("call", "error")] == [
        "error_1",
        "error_2",
    ]


def test_schema_normalizer_and_runtime_output_contract() -> None:
    normalized = SchemaNormalizer().normalize(
        {
            "type": "object",
            "properties": {"count": {"type": "integer"}},
            "required": ["count"],
            "additionalProperties": False,
        }
    )
    assert normalized["properties"]["count"] == {"type": "integer"}

    workflow = GraphWorkflow.model_validate(
        {
            "version": "2.0",
            "name": "output_contract",
            "nodes": [
                {"id": "start", "type": "start"},
                {
                    "id": "call",
                    "type": "tool",
                    "tool": "echo",
                    "input": "not-an-integer",
                    "output": "value",
                    "output_schema": {"type": "integer"},
                },
                {"id": "end", "type": "end"},
            ],
            "edges": [
                {"from": "start", "to": "call"},
                {"from": "call", "to": "end"},
            ],
        }
    )

    with pytest.raises(WorkflowExecutionError, match="output_schema"):
        graph_executor().run(workflow)


def test_output_contract_failure_does_not_enter_business_error_edge() -> None:
    workflow = GraphWorkflow.model_validate(
        {
            "version": "2.0",
            "name": "contract_fail_fast",
            "nodes": [
                {"id": "start", "type": "start"},
                {"id": "call", "type": "tool", "tool": "echo", "input": "bad", "output": "value", "output_schema": {"type": "integer"}},
                {"id": "handler", "type": "tool", "tool": "echo", "input": "handled", "output": "handled"},
                {"id": "end", "type": "end"},
            ],
            "edges": [
                {"from": "start", "to": "call"},
                {"from": "call", "to": "end"},
                {"from": "call", "to": "handler", "kind": "error"},
                {"from": "handler", "to": "end"},
            ],
        }
    )

    with pytest.raises(WorkflowExecutionError, match="output_schema") as exc_info:
        graph_executor().run(workflow)

    assert all(event["node_id"] != "handler" for event in exc_info.value.trace)


def test_static_dataflow_rejects_unmerged_public_publishers() -> None:
    workflow = GraphWorkflow.model_validate(
        {
            "version": "2.0",
            "name": "publish_conflict",
            "nodes": [
                {"id": "start", "type": "start"},
                {"id": "left", "type": "tool", "tool": "echo", "input": "L", "output": "value", "publish": True},
                {"id": "right", "type": "tool", "tool": "echo", "input": "R", "output": "value", "publish": True},
                {"id": "end", "type": "end"},
            ],
            "edges": [
                {"from": "start", "to": "left"},
                {"from": "start", "to": "right"},
                {"from": "left", "to": "end"},
                {"from": "right", "to": "end"},
            ],
        }
    )
    plan = ExecutionPlanCompiler().compile(workflow)

    with pytest.raises(DataflowValidationError, match="multiple publishers"):
        StaticDataflowValidator().analyze(plan)


def test_static_dataflow_rejects_non_guaranteed_template_field() -> None:
    workflow = GraphWorkflow.model_validate(
        {
            "version": "2.0",
            "name": "missing_field",
            "nodes": [
                {"id": "start", "type": "start"},
                {"id": "call", "type": "tool", "tool": "echo", "input": "{{ not_declared }}", "output": "value"},
                {"id": "end", "type": "end"},
            ],
            "edges": [
                {"from": "start", "to": "call"},
                {"from": "call", "to": "end"},
            ],
        }
    )

    with pytest.raises(DataflowValidationError, match="not guaranteed available"):
        graph_executor().run(workflow)


def test_nullable_state_field_is_materialized_for_all_paths() -> None:
    workflow = GraphWorkflow.model_validate(
        {
            "version": "2.0",
            "name": "nullable_field",
            "state": {"optional_value": {"type": "string", "nullable": True}},
            "outputs": ["optional_value"],
            "nodes": [
                {"id": "start", "type": "start"},
                {"id": "end", "type": "end"},
            ],
            "edges": [{"from": "start", "to": "end"}],
        }
    )

    result = graph_executor().run(workflow)

    assert result.final_output is None
    assert result.context["optional_value"] is None
