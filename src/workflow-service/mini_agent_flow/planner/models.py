from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Any

from pydantic import BaseModel, ConfigDict, Field, field_validator

from mini_agent_flow.engine.models import VariableName, Workflow


class TemplateMetadata(BaseModel):
    """Level 2 模板用于检索和输入填充的严格元数据。"""

    model_config = ConfigDict(extra="forbid")

    category: str = Field(min_length=1, pattern=r"^[A-Za-z_][A-Za-z0-9_-]*$")
    description: str = Field(min_length=1)
    keywords: list[str] = Field(min_length=1)
    priority: int = Field(default=0, ge=0, le=100)
    required_inputs: list[VariableName] = Field(default_factory=lambda: ["goal"])

    @field_validator("keywords")
    @classmethod
    def validate_keywords(cls, keywords: list[str]) -> list[str]:
        """拒绝空关键词和重复关键词，避免选择分数被无意放大。"""

        normalized = [keyword.strip() for keyword in keywords]
        if any(not keyword for keyword in normalized):
            raise ValueError("template keywords must not be empty")
        if len({keyword.casefold() for keyword in normalized}) != len(normalized):
            raise ValueError("template keywords must be unique")
        return normalized

    @field_validator("required_inputs")
    @classmethod
    def validate_required_inputs(cls, inputs: list[str]) -> list[str]:
        """模板必需输入不能重复声明。"""

        if len(inputs) != len(set(inputs)):
            raise ValueError("template required_inputs must be unique")
        return inputs


@dataclass(frozen=True)
class TemplateCandidate:
    """Catalog 中一个已经完成加载和校验的模板。"""

    path: Path
    workflow: Workflow
    metadata: TemplateMetadata


@dataclass(frozen=True)
class TemplateSelection:
    """Selector 的确定性选择结果。"""

    candidate: TemplateCandidate
    selection_reason: str
    matched_keywords: tuple[str, ...]
    score: int


@dataclass(frozen=True)
class Level2RunResult:
    """Level 2 从模板选择到执行完成的聚合结果。"""

    goal: str
    selected_workflow: str
    selection_reason: str
    matched_keywords: tuple[str, ...]
    score: int
    final_output: Any
    context: dict[str, Any]
    executed_nodes: list[str]
    trace: list[dict[str, Any]]
