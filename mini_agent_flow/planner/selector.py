from __future__ import annotations

import re
from typing import Protocol

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
