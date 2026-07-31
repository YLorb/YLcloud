from __future__ import annotations

import json
from typing import Any

from mini_agent_flow.engine.loader import WorkflowLoadError, WorkflowLoader
from mini_agent_flow.engine.models import Workflow
from mini_agent_flow.engine.validator import WorkflowValidationError, WorkflowValidator
from mini_agent_flow.llm.base import LLMClient


class WorkflowCorrectionError(RuntimeError):
    """LLM 修正 workflow 失败或多次修复无效时抛出的异常。"""


class WorkflowCorrector:
    """基于 LLM 的 Workflow 修正器。

    接收一个不合规的 workflow 文本和校验错误信息，调用 LLM 在最小改动下进行
    修复，并返回校验通过的 Workflow 对象。
    """

    def __init__(
        self,
        llm: LLMClient,
        validator: WorkflowValidator,
        loader: WorkflowLoader | None = None,
    ) -> None:
        """初始化修正器。"""

        self.llm = llm
        self.validator = validator
        self.loader = loader or WorkflowLoader(validator=validator)

    def correct(
        self,
        workflow_text: str,
        error_message: str,
        *,
        max_attempts: int = 2,
    ) -> Workflow:
        """修正不合规的 workflow 文本，返回校验通过的 Workflow 对象。"""

        if not workflow_text.strip():
            raise WorkflowCorrectionError("workflow text must not be empty")

        prompt = self._build_prompt(workflow_text, error_message)
        raw_output = str(self.llm.generate(prompt))
        data = self._extract_json(raw_output)

        attempt = 0
        while True:
            try:
                return self.loader.load_data(data)
            except (WorkflowLoadError, WorkflowValidationError) as exc:
                if attempt >= max_attempts:
                    raise WorkflowCorrectionError(
                        f"workflow correction failed after {attempt} attempts: {exc}"
                    ) from exc

                repair_prompt = self._build_repair_prompt(workflow_text, data, str(exc))
                raw_output = str(self.llm.generate(repair_prompt))
                data = self._extract_json(raw_output)
                attempt += 1

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
            raise WorkflowCorrectionError(f"LLM output is not valid JSON: {exc}") from exc

        if not isinstance(data, dict):
            raise WorkflowCorrectionError("LLM output JSON root must be an object")
        return data

    def _build_prompt(self, workflow_text: str, error_message: str) -> str:
        """构造首次修正的 prompt。"""

        return (
            "你是一个 Workflow 专家。下面这个 workflow 在校验时失败，请修复它。\n\n"
            "原始 workflow：\n"
            f"{workflow_text}\n\n"
            f"校验错误：{error_message}\n\n"
            "要求：\n"
            "1. 只返回修复后的纯 JSON，不要 markdown 代码块，不要解释。\n"
            "2. 在最小改动下修复错误，不要改变原流程语义。\n"
            "3. 所有 next/if_true/if_false 必须指向真实存在的节点 id。\n"
            "4. tool 节点只能使用已有的工具。\n\n"
            "修复后的 workflow："
        )

    def _build_repair_prompt(
        self,
        original_text: str,
        data: dict[str, Any],
        error_message: str,
    ) -> str:
        """构造再次修正的 prompt。"""

        return (
            "修复仍然失败，请继续修正。\n\n"
            "原始 workflow：\n"
            f"{original_text}\n\n"
            "当前 workflow：\n"
            f"{json.dumps(data, ensure_ascii=False, indent=2)}\n\n"
            f"校验错误：{error_message}\n\n"
            "要求：\n"
            "1. 只返回修复后的纯 JSON，不要 markdown 代码块，不要解释。\n"
            "2. 在最小改动下修复错误。\n\n"
            "修复后的 workflow："
        )
