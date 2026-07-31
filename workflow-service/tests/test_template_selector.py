from __future__ import annotations

from dataclasses import replace

import pytest

from mini_agent_flow.engine.loader import WorkflowLoader
from mini_agent_flow.engine.validator import WorkflowValidator
from mini_agent_flow.planner.catalog import WorkflowTemplateCatalog
from mini_agent_flow.planner.selector import (
    NoMatchingTemplateError,
    RuleBasedTemplateSelector,
    TemplateSelectionError,
)


def load_candidates():
    """加载真实模板作为 Selector 候选。"""

    validator = WorkflowValidator(allowed_tools={"mock_search", "echo"})
    catalog = WorkflowTemplateCatalog("templates", WorkflowLoader(validator=validator))
    return catalog.load()


def test_selector_selects_python_template_with_reason() -> None:
    """Python 报错 Goal 应选择错误分析模板并解释匹配依据。"""

    selection = RuleBasedTemplateSelector().select(
        "帮我分析这个 Python traceback 报错",
        load_candidates(),
    )

    assert selection.candidate.workflow.name == "python_error_analyzer"
    assert selection.matched_keywords == ("python", "traceback", "报错")
    assert "匹配关键词" in selection.selection_reason


def test_selector_selects_research_template() -> None:
    """调研总结 Goal 应选择研究模板。"""

    selection = RuleBasedTemplateSelector().select(
        "调研 Agent Workflow 的发展趋势并总结",
        load_candidates(),
    )

    assert selection.candidate.workflow.name == "research_summarizer"
    assert selection.score > 0


def test_selector_rejects_empty_goal() -> None:
    """空 Goal 不应进入匹配流程。"""

    with pytest.raises(TemplateSelectionError, match="must not be empty"):
        RuleBasedTemplateSelector().select("  ", load_candidates())


def test_selector_reports_no_matching_template() -> None:
    """没有任何关键词匹配时必须明确失败，不能随意选择。"""

    with pytest.raises(NoMatchingTemplateError, match="no workflow template matches"):
        RuleBasedTemplateSelector().select("制作一份财务预算表", load_candidates())


def test_selector_breaks_ties_by_priority_then_workflow_name() -> None:
    """同分模板的选择不应受目录遍历或候选传入顺序影响。"""

    candidates = load_candidates()
    tied_candidates = [
        replace(
            candidate,
            metadata=candidate.metadata.model_copy(
                update={"keywords": ["共同"], "priority": 10}
            ),
        )
        for candidate in reversed(candidates)
    ]

    selection = RuleBasedTemplateSelector().select("共同需求", tied_candidates)

    assert selection.candidate.workflow.name == "python_error_analyzer"
