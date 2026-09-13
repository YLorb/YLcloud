from __future__ import annotations

from pathlib import Path
from typing import Any

import pytest

from mini_agent_flow.engine.executor import SequentialWorkflowExecutor, WorkflowExecutionError
from mini_agent_flow.engine.loader import JsonWorkflowLoader
from mini_agent_flow.engine.models import Workflow
from mini_agent_flow.engine.validator import WorkflowValidator
from mini_agent_flow.llm.mock import MockLLM
from mini_agent_flow.tools.builtin import create_default_tool_registry
from mini_agent_flow.tools.registry import ToolRegistry


def load_workflow_data(data: dict[str, Any], allowed_tools: set[str]) -> Workflow:
    """通过 Loader 和 Validator 加载内存中的 workflow 数据。

    这样测试不会绕过当前真实入口，可以同时覆盖模型校验、tool 白名单校验，
    以及 Executor 对已校验 Workflow 对象的执行行为。
    """

    return JsonWorkflowLoader(
        validator=WorkflowValidator(allowed_tools=allowed_tools)
    ).load_data(data)


def run_workflow(workflow: Workflow, registry: ToolRegistry | None = None):
    """使用 MockLLM 和指定工具注册表运行 workflow。"""

    executor = SequentialWorkflowExecutor(
        llm=MockLLM(),
        tool_registry=registry or create_default_tool_registry(),
    )
    return executor.run(workflow)


def test_echo_tool_chain_preserves_dict_input() -> None:
    """echo 工具链路应保留完整变量引用的原始 dict 类型。"""

    message = {"title": "demo", "count": 2}
    registry = create_default_tool_registry()
    workflow = load_workflow_data(
        {
            "version": "1.0",
            "name": "echo_dict_workflow",
            "inputs": {"message": message},
            "nodes": [
                {"id": "start", "type": "start", "next": "echo"},
                {
                    "id": "echo",
                    "type": "tool",
                    "tool": "echo",
                    "input": "{{ message }}",
                    "output": "echoed",
                    "next": "end",
                },
                {"id": "end", "type": "end"},
            ],
        },
        allowed_tools=registry.names(),
    )

    result = run_workflow(workflow, registry)

    assert result.executed_nodes == ["start", "echo", "end"]
    assert result.context["echoed"] == message
    assert result.final_output is None


def test_mock_search_tool_chain_preserves_list_input() -> None:
    """mock_search 应能接收从 context 读取出的 list 输入。"""

    registry = create_default_tool_registry()
    workflow = load_workflow_data(
        {
            "version": "1.0",
            "name": "mock_search_list_workflow",
            "inputs": {"keywords": ["LangGraph", "Dify"]},
            "nodes": [
                {"id": "start", "type": "start", "next": "search"},
                {
                    "id": "search",
                    "type": "tool",
                    "tool": "mock_search",
                    "input": "{{ keywords }}",
                    "output": "search_results",
                    "next": "end",
                },
                {"id": "end", "type": "end"},
            ],
        },
        allowed_tools=registry.names(),
    )

    result = run_workflow(workflow, registry)

    assert result.executed_nodes == ["start", "search", "end"]
    assert result.context["search_results"] == [
        {"title": "Mock result for LangGraph", "source": "mock_search"},
        {"title": "Mock result for Dify", "source": "mock_search"},
    ]


def test_embedded_template_tool_input_is_converted_to_string() -> None:
    """变量嵌入字符串模板时，tool input 应变成字符串。"""

    registry = create_default_tool_registry()
    workflow = load_workflow_data(
        {
            "version": "1.0",
            "name": "embedded_template_workflow",
            "inputs": {"keyword": "LangGraph"},
            "nodes": [
                {"id": "start", "type": "start", "next": "echo"},
                {
                    "id": "echo",
                    "type": "tool",
                    "tool": "echo",
                    "input": "query={{ keyword }}",
                    "output": "echoed",
                    "next": "end",
                },
                {"id": "end", "type": "end"},
            ],
        },
        allowed_tools=registry.names(),
    )

    result = run_workflow(workflow, registry)

    assert result.context["echoed"] == "query=LangGraph"


def test_executor_fails_when_validated_tool_is_not_registered() -> None:
    """Validator 白名单通过，不代表 Executor 的 Registry 一定有可调用工具。"""

    workflow = load_workflow_data(
        {
            "version": "1.0",
            "name": "missing_registered_tool_workflow",
            "inputs": {"message": "hello"},
            "nodes": [
                {"id": "start", "type": "start", "next": "call_tool"},
                {
                    "id": "call_tool",
                    "type": "tool",
                    "tool": "missing_tool",
                    "input": "{{ message }}",
                    "output": "result",
                    "next": "end",
                },
                {"id": "end", "type": "end"},
            ],
        },
        allowed_tools={"missing_tool"},
    )

    with pytest.raises(WorkflowExecutionError, match="tool node"):
        run_workflow(workflow, ToolRegistry())


def test_level1_example_file_runs_through_full_chain() -> None:
    """示例文件应能跑通 Loader、Validator、Executor、LLM、Tool、Context 全链路。"""

    registry = create_default_tool_registry()
    workflow = JsonWorkflowLoader(
        validator=WorkflowValidator(allowed_tools=registry.names())
    ).load(Path("examples/level1_manual_workflow.json"))

    result = run_workflow(workflow, registry)

    assert result.executed_nodes == ["start", "plan", "search", "summarize", "end"]
    assert "goal" in result.context
    assert "keywords" in result.context
    assert "search_results" in result.context
    assert "final_answer" in result.context
    assert result.final_output == result.context["final_answer"]
