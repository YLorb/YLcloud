from __future__ import annotations

import json
import re
from typing import Any, Protocol

from mini_agent_flow.engine.models import Workflow
from mini_agent_flow.llm.base import LLMClient
from mini_agent_flow.planner.models import TemplateCandidate, TemplateSelection


class TemplateSelectionError(ValueError):
    """模板选择输入或选择过程非法时抛出的异常。"""


class NoMatchingTemplateError(TemplateSelectionError):
    """Goal 与任何模板都不匹配时抛出的明确异常。"""


class TemplateSelector(Protocol):
    """规则选择器和未来 LLM 选择器共同遵守的接口。"""

    def select(
        self,
        goal: str,
        candidates: list[TemplateCandidate],
    ) -> TemplateSelection:
        """根据 Goal 从候选模板中选择一个模板。"""

        ...


class RuleBasedTemplateSelector:
    """根据模板关键词和优先级进行确定性选择。"""

    def select(
        self,
        goal: str,
        candidates: list[TemplateCandidate],
    ) -> TemplateSelection:
        """按关键词得分、优先级、模板名依次进行稳定排序。"""

        normalized_goal = goal.strip().casefold()
        if not normalized_goal:
            raise TemplateSelectionError("goal must not be empty")
        if not candidates:
            raise TemplateSelectionError("template candidates must not be empty")

        ranked: list[tuple[int, int, str, TemplateCandidate, tuple[str, ...]]] = []
        for candidate in candidates:
            matched = tuple(
                keyword
                for keyword in candidate.metadata.keywords
                if self._matches(normalized_goal, keyword)
            )
            score = len(matched) * 100 + sum(len(keyword) for keyword in matched)
            ranked.append(
                (
                    score,
                    candidate.metadata.priority,
                    candidate.workflow.name,
                    candidate,
                    matched,
                )
            )

        ranked.sort(key=lambda item: (-item[0], -item[1], item[2]))
        score, _, _, candidate, matched = ranked[0]
        if score == 0:
            raise NoMatchingTemplateError("no workflow template matches the provided goal")

        reason = (
            f"模板 {candidate.workflow.name} 匹配关键词：{', '.join(matched)}；"
            f"匹配分数：{score}。"
        )
        return TemplateSelection(
            candidate=candidate,
            selection_reason=reason,
            matched_keywords=matched,
            score=score,
        )

    def _matches(self, normalized_goal: str, keyword: str) -> bool:
        """中文按子串匹配，纯英文数字关键词按单词边界匹配。"""

        normalized_keyword = keyword.strip().casefold()
        if re.fullmatch(r"[a-z0-9_+-]+", normalized_keyword):
            pattern = rf"(?<![a-z0-9_]){re.escape(normalized_keyword)}(?![a-z0-9_])"
            return re.search(pattern, normalized_goal) is not None
        return normalized_keyword in normalized_goal


class LLMTemplateSelector:
    """基于 LLM 的模板选择器。

    通过 LLM 理解用户 goal，从候选模板中选择最合适的一个，并填充必需输入。
    当 LLM 认为没有合适模板时，抛出 NoMatchingTemplateError。
    """

    def __init__(self, llm: LLMClient) -> None:
        """初始化并绑定 LLM 客户端。"""

        self.llm = llm

    def select(
        self,
        goal: str,
        candidates: list[TemplateCandidate],
    ) -> TemplateSelection:
        """根据 Goal 从候选模板中选择一个模板。"""

        if not (normalized_goal := goal.strip()):
            raise TemplateSelectionError("goal must not be empty")
        if not candidates:
            raise TemplateSelectionError("template candidates must not be empty")

        prompt = self._build_prompt(normalized_goal, candidates)
        raw_output = str(self.llm.generate(prompt))
        selection = self._parse_output(raw_output)

        selected_name = selection.get("selected_workflow")
        if not selected_name:
            raise NoMatchingTemplateError("LLM did not select any workflow")

        candidate = self._find_candidate(selected_name, candidates)
        reason = selection.get("reason", "selected by LLM")
        filled_inputs = selection.get("filled_inputs", {})

        # 用 LLM 填写的 inputs 覆盖模板默认 inputs
        workflow_data = candidate.workflow.model_dump(mode="python")
        workflow_data["inputs"] = dict(workflow_data.get("inputs", {}))
        for name, value in filled_inputs.items():
            workflow_data["inputs"][name] = value
        # goal 始终由用户输入决定
        workflow_data["inputs"]["goal"] = normalized_goal

        updated_candidate = TemplateCandidate(
            path=candidate.path,
            workflow=Workflow.model_validate(workflow_data),
            metadata=candidate.metadata,
        )

        matched_keywords = tuple(selection.get("matched_keywords", []) or [])
        score = selection.get("score", 0)
        return TemplateSelection(
            candidate=updated_candidate,
            selection_reason=reason,
            matched_keywords=matched_keywords,
            score=score,
        )

    def _build_prompt(self, goal: str, candidates: list[TemplateCandidate]) -> str:
        """构造让 LLM 选择模板并填充输入的 prompt。"""

        templates = []
        for candidate in candidates:
            meta = candidate.metadata
            templates.append(
                {
                    "name": candidate.workflow.name,
                    "description": meta.description,
                    "category": meta.category,
                    "keywords": meta.keywords,
                    "required_inputs": meta.required_inputs,
                }
            )

        example = {
            "selected_workflow": "python_error_analyzer",
            "reason": "用户想分析 Python 报错，python_error_analyzer 模板最匹配。",
            "confidence": "high",
            "matched_keywords": ["python", "error"],
            "score": 200,
            "filled_inputs": {
                "goal": "用户原始 goal 或稍微整理后的目标描述",
            },
        }

        return (
            "你是一个 Workflow 模板选择助手。"
            "请根据用户的 Goal，从下面的模板列表中选择最合适的一个，"
            "并返回一个 JSON 对象。如果没有合适的模板，请把 selected_workflow 设为空字符串。\n\n"
            f"Goal: {goal}\n\n"
            f"候选模板: {json.dumps(templates, ensure_ascii=False, indent=2)}\n\n"
            "输出格式要求：\n"
            "1. 只返回 JSON，不要 markdown 代码块，不要解释。\n"
            "2. selected_workflow 必须是候选模板中存在的 name。\n"
            "3. filled_inputs 只包含该模板 required_inputs 中的字段。\n"
            "4. confidence 只能是 high、medium、low 之一。\n\n"
            f"输出示例：\n{json.dumps(example, ensure_ascii=False, indent=2)}"
        )

    def _parse_output(self, raw_output: str) -> dict[str, Any]:
        """解析 LLM 返回的 JSON 字符串，清理可能的 markdown 代码块。"""

        cleaned = raw_output.strip()
        if cleaned.startswith("```"):
            cleaned = re.sub(r"^```(?:json)?\s*", "", cleaned)
            cleaned = re.sub(r"\s*```$", "", cleaned)
        try:
            data = json.loads(cleaned)
        except json.JSONDecodeError as exc:
            raise TemplateSelectionError(f"LLM selection output is not valid JSON: {exc}") from exc

        if not isinstance(data, dict):
            raise TemplateSelectionError("LLM selection output must be a JSON object")
        return data

    def _find_candidate(self, name: str, candidates: list[TemplateCandidate]) -> TemplateCandidate:
        """根据模板名找到候选对象。"""

        for candidate in candidates:
            if candidate.workflow.name == name:
                return candidate
        raise NoMatchingTemplateError(f"LLM selected unknown workflow: {name}")


class HybridTemplateSelector:
    """先尝试规则匹配，匹配不到再使用 LLM 的混合选择器。

    默认策略是规则优先，只有在规则无法匹配或用户明确要求 LLM 时才启用 LLM，
    兼顾速度、成本和可解释性。
    """

    def __init__(
        self,
        llm: LLMClient,
        *,
        use_llm: bool = False,
    ) -> None:
        """初始化混合选择器。"""

        self.rule_selector = RuleBasedTemplateSelector()
        self.llm_selector = LLMTemplateSelector(llm)
        self.use_llm = use_llm

    def select(
        self,
        goal: str,
        candidates: list[TemplateCandidate],
    ) -> TemplateSelection:
        """先规则后 LLM 的模板选择。"""

        if not self.use_llm:
            try:
                return self.rule_selector.select(goal, candidates)
            except NoMatchingTemplateError:
                pass

        return self.llm_selector.select(goal, candidates)


def create_selector(
    strategy: str,
    llm: LLMClient,
    *,
    use_llm: bool = False,
) -> TemplateSelector:
    """根据策略创建对应的模板选择器。"""

    if strategy == "rule":
        return RuleBasedTemplateSelector()
    if strategy == "llm":
        return LLMTemplateSelector(llm)
    if strategy == "hybrid":
        return HybridTemplateSelector(llm, use_llm=use_llm)
    raise TemplateSelectionError(f"unknown selector strategy: {strategy}")
