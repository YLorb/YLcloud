from __future__ import annotations

import pytest

from mini_agent_flow.engine.executor import SequentialWorkflowExecutor
from mini_agent_flow.engine.loader import WorkflowLoader
from mini_agent_flow.engine.validator import WorkflowValidator
from mini_agent_flow.llm.mock import MockLLM
from mini_agent_flow.planner.catalog import WorkflowTemplateCatalog
from mini_agent_flow.planner.selector import RuleBasedTemplateSelector
from mini_agent_flow.planner.service import Level2ExecutionError, Level2WorkflowService
from mini_agent_flow.tools.builtin import create_default_tool_registry


def create_service() -> Level2WorkflowService:
    """组装完整 Level 2 测试服务。"""

    registry = create_default_tool_registry()
    validator = WorkflowValidator(allowed_tools=registry.names())
    catalog = WorkflowTemplateCatalog("templates", WorkflowLoader(validator=validator))
    executor = SequentialWorkflowExecutor(MockLLM(), registry)
    return Level2WorkflowService(catalog, RuleBasedTemplateSelector(), validator, executor)


def test_level2_service_selects_fills_and_executes_template() -> None:
    """Service 应完成选择、Goal 填充、校验和 Engine 执行全链路。"""

    goal = "分析这个 Python 报错并提供 debug 建议"
    result = create_service().run(goal)

    assert result.selected_workflow == "python_error_analyzer.yaml"
    assert result.context["goal"] == goal
    assert result.final_output.startswith("Mock response:")
    assert result.executed_nodes == ["start", "analyze", "normalize", "suggest", "end"]
    assert len(result.trace) == 5


@pytest.mark.parametrize("goal", ["", "   "])
def test_level2_service_rejects_empty_goal(goal: str) -> None:
    """Service 应在加载模板前拒绝空 Goal。"""

    with pytest.raises(Level2ExecutionError, match="must not be empty"):
        create_service().run(goal)


def test_level2_service_rejects_oversized_goal() -> None:
    """Service 应限制 Goal 长度，避免无界输入进入后续规划和执行。"""

    with pytest.raises(Level2ExecutionError, match="must not exceed"):
        create_service().run("调研" * 5_001)
