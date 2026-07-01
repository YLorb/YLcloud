from __future__ import annotations

import pytest

from mini_agent_flow.engine.executor import SequentialWorkflowExecutor, WorkflowExecutionError
from mini_agent_flow.engine.loader import JsonWorkflowLoader
from mini_agent_flow.engine.models import Workflow
from mini_agent_flow.engine.validator import WorkflowValidator
from mini_agent_flow.llm.mock import MockLLM
from mini_agent_flow.tools.builtin import create_default_tool_registry
from mini_agent_flow.tools.registry import ToolRegistry


def load_example_workflow() -> Workflow:
    """加载 Level 1 示例 workflow。"""

    registry = create_default_tool_registry()
    return JsonWorkflowLoader(
        validator=WorkflowValidator(allowed_tools=registry.names())
    ).load("examples/level1_manual_workflow.json")


def test_executor_runs_level1_manual_workflow() -> None:
    """顺序执行器可以跑通 Level 1 示例 workflow。"""

    workflow = load_example_workflow()
    registry = create_default_tool_registry()
    executor = SequentialWorkflowExecutor(llm=MockLLM(), tool_registry=registry)

    result = executor.run(workflow)

    assert result.workflow_name == "research_summarizer"
    assert result.executed_nodes == ["start", "plan", "search", "summarize", "end"]
    assert result.context["goal"] == "总结 AI Agent 工作流系统的核心组成"
    assert result.context["keywords"] == ["AI Agent", "Workflow Engine", "Tool Calling"]
    assert result.context["search_results"] == [
        {"title": "Mock result for AI Agent", "source": "mock_search"},
        {"title": "Mock result for Workflow Engine", "source": "mock_search"},
        {"title": "Mock result for Tool Calling", "source": "mock_search"},
    ]
    assert result.context["final_answer"].startswith("Mock response:")
    assert result.final_output == result.context["final_answer"]


def test_executor_fails_when_tool_is_missing() -> None:
    """ToolRegistry 缺少工具时，执行 tool 节点应失败。"""

    workflow = load_example_workflow()
    executor = SequentialWorkflowExecutor(llm=MockLLM(), tool_registry=ToolRegistry())

    with pytest.raises(WorkflowExecutionError, match="tool node"):
        executor.run(workflow)


def test_executor_fails_when_variable_is_missing() -> None:
    """节点引用缺失变量时，执行应失败。"""

    workflow = Workflow.model_validate(
        {
            "version": "1.0",
            "name": "missing_variable_workflow",
            "nodes": [
                {"id": "start", "type": "start", "next": "plan"},
                {
                    "id": "plan",
                    "type": "llm",
                    "prompt": "目标：{{ missing_goal }}",
                    "output": "answer",
                    "next": "end",
                },
                {"id": "end", "type": "end"},
            ],
        }
    )
    executor = SequentialWorkflowExecutor(
        llm=MockLLM(),
        tool_registry=create_default_tool_registry(),
    )

    with pytest.raises(WorkflowExecutionError, match="llm node"):
        executor.run(workflow)


def test_executor_runs_condition_node() -> None:
    """顺序执行器可以根据 condition 节点选择分支。"""

    workflow = Workflow.model_validate(
        {
            "version": "1.0",
            "name": "condition_workflow",
            "inputs": {"flag": True},
            "nodes": [
                {"id": "start", "type": "start", "next": "check"},
                {
                    "id": "check",
                    "type": "condition",
                    "expression": "{{ flag }}",
                    "if_true": "end",
                    "if_false": "end",
                },
                {"id": "end", "type": "end"},
            ],
        }
    )
    executor = SequentialWorkflowExecutor(
        llm=MockLLM(),
        tool_registry=create_default_tool_registry(),
    )

    result = executor.run(workflow)

    assert result.executed_nodes == ["start", "check", "end"]
    assert result.trace[1]["output"]["selected_branch"] == "if_true"


def test_executor_returns_multiple_declared_outputs() -> None:
    """workflow.outputs 声明多个字段时，final_output 应返回 dict。"""

    workflow = Workflow.model_validate(
        {
            "version": "1.0",
            "name": "multiple_outputs_workflow",
            "inputs": {"first": "one", "second": "two"},
            "outputs": ["first", "second"],
            "nodes": [
                {"id": "start", "type": "start", "next": "end"},
                {"id": "end", "type": "end"},
            ],
        }
    )
    executor = SequentialWorkflowExecutor(
        llm=MockLLM(),
        tool_registry=create_default_tool_registry(),
    )

    result = executor.run(workflow)

    assert result.final_output == {"first": "one", "second": "two"}
    assert result.trace[-1]["input"] == {"outputs": ["first", "second"]}
    assert result.trace[-1]["output"] == {
        "final_output": {"first": "one", "second": "two"}
    }


def test_executor_fails_when_declared_output_is_missing_at_runtime() -> None:
    """声明的输出字段如果运行结束时不存在，应显式失败。"""

    workflow = Workflow.model_validate(
        {
            "version": "1.0",
            "name": "missing_output_workflow",
            "outputs": ["answer"],
            "nodes": [
                {"id": "start", "type": "start", "next": "end"},
                {"id": "end", "type": "end"},
            ],
        }
    )
    executor = SequentialWorkflowExecutor(
        llm=MockLLM(),
        tool_registry=create_default_tool_registry(),
    )

    with pytest.raises(WorkflowExecutionError, match="final output") as exc_info:
        executor.run(workflow)

    assert exc_info.value.trace[-1]["node_id"] == "end"
    assert exc_info.value.trace[-1]["status"] == "failed"
    assert exc_info.value.trace[-1]["input"] == {"outputs": ["answer"]}


def test_executor_max_steps_prevents_infinite_loop() -> None:
    """max_steps 可以防止错误 workflow 造成无限循环。"""

    workflow = Workflow.model_validate(
        {
            "version": "1.0",
            "name": "looping_workflow",
            "inputs": {"goal": "demo"},
            "nodes": [
                {"id": "start", "type": "start", "next": "plan"},
                {
                    "id": "plan",
                    "type": "llm",
                    "prompt": "目标：{{ goal }}",
                    "output": "answer",
                    "next": "plan",
                },
                {"id": "end", "type": "end"},
            ],
        }
    )
    executor = SequentialWorkflowExecutor(
        llm=MockLLM(),
        tool_registry=create_default_tool_registry(),
        max_steps=3,
    )

    with pytest.raises(WorkflowExecutionError, match="max steps"):
        executor.run(workflow)


def test_executor_rejects_invalid_max_steps() -> None:
    """max_steps 必须大于 0。"""

    with pytest.raises(WorkflowExecutionError, match="max_steps"):
        SequentialWorkflowExecutor(
            llm=MockLLM(),
            tool_registry=create_default_tool_registry(),
            max_steps=0,
        )
