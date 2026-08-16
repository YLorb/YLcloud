from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Any

import yaml

from mini_agent_flow.engine.loader import WorkflowLoadError, WorkflowLoader
from mini_agent_flow.engine.models import Workflow
from mini_agent_flow.engine.validator import WorkflowValidationError, WorkflowValidator
from mini_agent_flow.llm.base import LLMClient


class WorkflowGenerationError(RuntimeError):
    """LLM 生成 workflow 失败或多次修复无效时抛出的异常。"""


@dataclass(frozen=True)
class GeneratedWorkflow:
    """LLM 生成并校验通过的 workflow 结果。"""

    workflow: Workflow
    generated_yaml: str
    repair_attempts: int
    validation_status: str


class WorkflowGenerator:
    """基于 LLM 的 Workflow 生成器。

    接受自然语言 goal，调用 LLM 生成 workflow dict，经过 Validator 校验后返回
    Workflow 对象。如果首次生成不合法，会把错误信息反馈给 LLM 进行 self-repair。
    """

    def __init__(
        self,
        llm: LLMClient,
        validator: WorkflowValidator,
        loader: WorkflowLoader | None = None,
    ) -> None:
        """初始化生成器。"""

        self.llm = llm
        self.validator = validator
        self.loader = loader or WorkflowLoader(validator=validator)

    def generate(
        self,
        goal: str,
        *,
        allowed_tools: set[str] | None = None,
        max_repair_attempts: int = 2,
    ) -> GeneratedWorkflow:
        """根据 goal 生成 workflow，校验不通过时自动修复。"""

        if not (normalized_goal := goal.strip()):
            raise WorkflowGenerationError("goal must not be empty")

        prompt = self._build_generate_prompt(normalized_goal, allowed_tools)
        raw_output = str(self.llm.generate(prompt))
        data, repair_attempts = self._validate_with_repair(
            raw_output,
            normalized_goal,
            allowed_tools,
            max_repair_attempts,
        )

        workflow = self.loader.load_data(data)
        generated_yaml = yaml.safe_dump(
            data,
            allow_unicode=True,
            sort_keys=False,
            default_flow_style=False,
        )

        return GeneratedWorkflow(
            workflow=workflow,
            generated_yaml=generated_yaml,
            repair_attempts=repair_attempts,
            validation_status="success",
        )

    def _validate_with_repair(
        self,
        raw_output: str,
        goal: str,
        allowed_tools: set[str] | None,
        max_repair_attempts: int,
    ) -> tuple[dict[str, Any], int]:
        """尝试解析并校验 LLM 输出，失败时进入修复循环。"""

        data = self._extract_json(raw_output)
        repair_attempts = 0

        while True:
            try:
                self.validator.validate_data(data)
                return data, repair_attempts
            except (WorkflowLoadError, WorkflowValidationError) as exc:
                if repair_attempts >= max_repair_attempts:
                    raise WorkflowGenerationError(
                        f"workflow generation failed after {repair_attempts} repair attempts: {exc}"
                    ) from exc

                repair_prompt = self._build_repair_prompt(
                    goal,
                    data,
                    str(exc),
                    allowed_tools,
                )
                raw_output = str(self.llm.generate(repair_prompt))
                data = self._extract_json(raw_output)
                repair_attempts += 1

    def _extract_json(self, raw_output: str) -> dict[str, Any]:
        """从 LLM 输出中提取 JSON 对象。"""

        cleaned = raw_output.strip()
        if cleaned.startswith("```"):
            import re as _re

            cleaned = _re.sub(r"^```(?:json)?\s*", "", cleaned)
            cleaned = _re.sub(r"\s*```$", "", cleaned)

        try:
            data = json.loads(cleaned)
        except json.JSONDecodeError as exc:
            raise WorkflowGenerationError(f"LLM output is not valid JSON: {exc}") from exc

        if not isinstance(data, dict):
            raise WorkflowGenerationError("LLM output JSON root must be an object")
        return data

    def _build_generate_prompt(
        self,
        goal: str,
        allowed_tools: set[str] | None,
    ) -> str:
        """构造让 LLM 生成 workflow 的 prompt。"""

        schema = Workflow.model_json_schema()
        tool_list = sorted(allowed_tools) if allowed_tools else ["mock_search", "echo"]

        example = {
            "version": "1.0",
            "name": "research_summarizer",
            "description": "总结 AI Agent 工作流系统的核心组成。",
            "inputs": {"goal": goal},
            "outputs": ["final_answer"],
            "nodes": [
                {"id": "start", "type": "start", "next": "plan"},
                {
                    "id": "plan",
                    "type": "llm",
                    "prompt": "请为这个目标生成 3 个搜索关键词：{{ goal }}",
                    "output": "keywords",
                    "next": "search",
                },
                {
                    "id": "search",
                    "type": "tool",
                    "tool": "mock_search",
                    "input": "{{ keywords }}",
                    "output": "search_results",
                    "next": "summarize",
                },
                {
                    "id": "summarize",
                    "type": "llm",
                    "prompt": "请总结这些资料：{{ search_results }}",
                    "output": "final_answer",
                    "next": "end",
                },
                {"id": "end", "type": "end"},
            ],
        }

        return (
            "你是一个 Workflow 编排助手。请根据用户目标生成一个可执行的 workflow。\n\n"
            f"用户目标：{goal}\n\n"
            "要求：\n"
            "1. 输出必须是纯 JSON，不要 markdown 代码块，不要解释。\n"
            "2. workflow 必须严格符合以下 JSON Schema。\n"
            "3. 所有 tool 节点的 tool 字段必须来自允许工具列表。\n"
            "4. 所有 next/if_true/if_false 必须指向真实存在的节点 id。\n"
            "5. 所有 outputs 字段必须由 inputs 或 llm/tool 节点的 output 产出。\n"
            "6. 只能使用 condition、end、llm、start、tool 五种节点类型。\n\n"
            f"允许工具：{tool_list}\n\n"
            f"JSON Schema：{json.dumps(schema, ensure_ascii=False, indent=2)}\n\n"
            "输出示例：\n"
            f"{json.dumps(example, ensure_ascii=False, indent=2)}"
        )

    def _build_repair_prompt(
        self,
        goal: str,
        data: dict[str, Any],
        error_message: str,
        allowed_tools: set[str] | None,
    ) -> str:
        """构造修复 workflow 的 prompt。"""

        tool_list = sorted(allowed_tools) if allowed_tools else ["mock_search", "echo"]

        return (
            "你之前生成的 workflow 在校验时失败，请修复它。\n\n"
            f"用户目标：{goal}\n\n"
            "当前 workflow：\n"
            f"{json.dumps(data, ensure_ascii=False, indent=2)}\n\n"
            f"校验错误：{error_message}\n\n"
            "要求：\n"
            "1. 只返回修复后的纯 JSON，不要 markdown 代码块，不要解释。\n"
            "2. 在最小改动下修复错误，不要改变原流程语义。\n"
            f"3. tool 字段必须来自允许工具：{tool_list}。\n"
            "4. 所有 next/if_true/if_false 必须指向真实存在的节点 id。\n\n"
            "修复后的 workflow："
        )
