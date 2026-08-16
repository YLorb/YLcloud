from __future__ import annotations

import json
from pathlib import Path

import pytest

from mini_agent_flow.engine.loader import WorkflowLoader
from mini_agent_flow.engine.validator import WorkflowValidator
from mini_agent_flow.planner.catalog import WorkflowTemplateCatalog
from mini_agent_flow.planner.selector import (
    LLMTemplateSelector,
    NoMatchingTemplateError,
    RuleBasedTemplateSelector,
    TemplateSelectionError,
)


class FixedLLM:
    """返回固定字符串的测试用 LLM。"""

    def __init__(self, response: str) -> None:
        self.response = response

    def generate(self, prompt: str) -> str:
        return self.response


def load_candidates():
    """加载真实模板作为 Selector 候选。"""

    validator = WorkflowValidator(allowed_tools={"mock_search", "echo"})
    catalog = WorkflowTemplateCatalog("templates", WorkflowLoader(validator=validator))
    return catalog.load()


def test_llm_selector_parses_json_output() -> None:
    """LLM 返回合法 JSON 时，应正确选择模板并填充输入。"""

    response = json.dumps(
        {
            "selected_workflow": "python_error_analyzer",
            "reason": "用户想分析 Python 报错。",
            "confidence": "high",
            "matched_keywords": ["python", "报错"],
            "score": 200,
            "filled_inputs": {"goal": "分析 Python 报错并给出修复建议"},
        },
        ensure_ascii=False,
    )
    selector = LLMTemplateSelector(FixedLLM(response))

    selection = selector.select("分析 Python 报错", load_candidates())

    assert selection.candidate.workflow.name == "python_error_analyzer"
    assert "Python 报错" in selection.selection_reason
    assert selection.score == 200


def test_llm_selector_rejects_empty_goal() -> None:
    """空 Goal 不应进入 LLM 匹配流程。"""

    selector = LLMTemplateSelector(FixedLLM("{}"))

    with pytest.raises(TemplateSelectionError, match="must not be empty"):
        selector.select("  ", load_candidates())


def test_llm_selector_reports_no_match() -> None:
    """LLM 选择空字符串时应报告无匹配模板。"""

    response = json.dumps({"selected_workflow": ""}, ensure_ascii=False)
    selector = LLMTemplateSelector(FixedLLM(response))

    with pytest.raises(NoMatchingTemplateError, match="LLM did not select any workflow"):
        selector.select("任意目标", load_candidates())


def test_llm_selector_rejects_unknown_workflow() -> None:
    """LLM 选择不存在的模板名时应明确失败。"""

    response = json.dumps({"selected_workflow": "unknown_template"}, ensure_ascii=False)
    selector = LLMTemplateSelector(FixedLLM(response))

    with pytest.raises(NoMatchingTemplateError, match="unknown workflow"):
        selector.select("任意目标", load_candidates())


def test_hybrid_selector_uses_rule_first() -> None:
    """hybrid 选择器在规则能匹配时直接使用规则结果，不调用 LLM。"""

    from mini_agent_flow.planner.selector import HybridTemplateSelector

    calls = []

    class TrackingLLM:
        def generate(self, prompt: str) -> str:
            calls.append(prompt)
            return json.dumps({"selected_workflow": ""}, ensure_ascii=False)

    selector = HybridTemplateSelector(TrackingLLM(), use_llm=False)

    selection = selector.select("分析 Python 报错", load_candidates())

    assert selection.candidate.workflow.name == "python_error_analyzer"
    assert not calls


def test_hybrid_selector_falls_back_to_llm() -> None:
    """hybrid 选择器在规则无法匹配时回退到 LLM。"""

    from mini_agent_flow.planner.selector import HybridTemplateSelector

    response = json.dumps(
        {
            "selected_workflow": "research_summarizer",
            "reason": " fallback",
            "confidence": "medium",
            "matched_keywords": ["研究"],
            "score": 100,
            "filled_inputs": {"goal": "研究"},
        },
        ensure_ascii=False,
    )
    selector = HybridTemplateSelector(FixedLLM(response), use_llm=False)

    selection = selector.select("任意无法规则匹配的目标", load_candidates())

    assert selection.candidate.workflow.name == "research_summarizer"


def test_hybrid_selector_can_force_llm() -> None:
    """hybrid 选择器在 use_llm=True 时直接调用 LLM。"""

    from mini_agent_flow.planner.selector import HybridTemplateSelector

    response = json.dumps(
        {
            "selected_workflow": "python_error_analyzer",
            "reason": "直接 LLM 选择",
            "confidence": "high",
            "matched_keywords": ["python"],
            "score": 100,
            "filled_inputs": {"goal": "分析"},
        },
        ensure_ascii=False,
    )
    selector = HybridTemplateSelector(FixedLLM(response), use_llm=True)

    selection = selector.select("分析 Python 报错", load_candidates())

    assert selection.candidate.workflow.name == "python_error_analyzer"


def test_llm_selector_cleans_markdown_codeblock() -> None:
    """LLM 返回 markdown 代码块时，应提取内部 JSON。"""

    response = "```json\n" + json.dumps(
        {
            "selected_workflow": "python_error_analyzer",
            "reason": "代码块测试",
            "confidence": "high",
            "matched_keywords": ["python"],
            "score": 100,
            "filled_inputs": {"goal": "分析"},
        },
        ensure_ascii=False,
    ) + "\n```"
    selector = LLMTemplateSelector(FixedLLM(response))

    selection = selector.select("分析 Python 报错", load_candidates())

    assert selection.candidate.workflow.name == "python_error_analyzer"


def test_rule_selector_still_works() -> None:
    """规则选择器保持原有行为。"""

    selection = RuleBasedTemplateSelector().select("分析 Python 报错", load_candidates())

    assert selection.candidate.workflow.name == "python_error_analyzer"
