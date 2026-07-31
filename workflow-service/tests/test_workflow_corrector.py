from __future__ import annotations

import json

import pytest

from mini_agent_flow.engine.validator import WorkflowValidator
from mini_agent_flow.planner.corrector import WorkflowCorrectionError, WorkflowCorrector


class FixedLLM:
    """返回固定字符串的测试用 LLM。"""

    def __init__(self, response: str) -> None:
        self.response = response

    def generate(self, prompt: str) -> str:
        return self.response


def _valid_workflow_json() -> str:
    return json.dumps(
        {
            "version": "1.0",
            "name": "test_workflow",
            "description": "测试 workflow",
            "inputs": {"goal": "测试"},
            "outputs": ["final_answer"],
            "nodes": [
                {"id": "start", "type": "start", "next": "plan"},
                {
                    "id": "plan",
                    "type": "llm",
                    "prompt": "{{ goal }}",
                    "output": "final_answer",
                    "next": "end",
                },
                {"id": "end", "type": "end"},
            ],
        },
        ensure_ascii=False,
    )


def test_corrector_fixes_missing_outputs() -> None:
    """Corrector 应能根据错误信息修复 workflow。"""

    validator = WorkflowValidator(allowed_tools=set())
    corrector = WorkflowCorrector(llm=FixedLLM(_valid_workflow_json()), validator=validator)

    broken = json.dumps(
        {
            "version": "1.0",
            "name": "broken",
            "inputs": {"goal": "测试"},
            "nodes": [
                {"id": "start", "type": "start", "next": "plan"},
                {
                    "id": "plan",
                    "type": "llm",
                    "prompt": "{{ goal }}",
                    "output": "final_answer",
                    "next": "end",
                },
                {"id": "end", "type": "end"},
            ],
        },
        ensure_ascii=False,
    )

    workflow = corrector.correct(broken, "missing outputs")

    assert workflow.name == "test_workflow"


def test_corrector_repair_loop_on_failure() -> None:
    """首次修复失败时，应在 repair 循环中继续尝试。"""

    invalid = json.dumps(
        {
            "version": "1.0",
            "name": "bad",
            "nodes": [],
        },
        ensure_ascii=False,
    )

    class RepairLLM:
        def __init__(self) -> None:
            self.calls = 0

        def generate(self, prompt: str) -> str:
            self.calls += 1
            if self.calls == 1:
                return invalid
            return _valid_workflow_json()

    validator = WorkflowValidator(allowed_tools=set())
    corrector = WorkflowCorrector(llm=RepairLLM(), validator=validator)

    workflow = corrector.correct(invalid, "invalid nodes", max_attempts=2)

    assert workflow.name == "test_workflow"


def test_corrector_raises_after_max_attempts() -> None:
    """超过最大修复次数仍失败时，应抛出 WorkflowCorrectionError。"""

    invalid = json.dumps(
        {
            "version": "1.0",
            "name": "bad",
            "nodes": [],
        },
        ensure_ascii=False,
    )

    validator = WorkflowValidator(allowed_tools=set())
    corrector = WorkflowCorrector(llm=FixedLLM(invalid), validator=validator)

    with pytest.raises(WorkflowCorrectionError, match="after 2 attempts"):
        corrector.correct(invalid, "invalid nodes", max_attempts=2)


def test_corrector_rejects_empty_text() -> None:
    """空 workflow 文本应直接拒绝。"""

    validator = WorkflowValidator(allowed_tools=set())
    corrector = WorkflowCorrector(llm=FixedLLM("{}"), validator=validator)

    with pytest.raises(WorkflowCorrectionError, match="must not be empty"):
        corrector.correct("  ", "error")


def test_corrector_extracts_json_from_code_block() -> None:
    """LLM 返回 markdown 代码块时，应正确提取 JSON。"""

    response = "```json\n" + _valid_workflow_json() + "\n```"
    validator = WorkflowValidator(allowed_tools=set())
    corrector = WorkflowCorrector(llm=FixedLLM(response), validator=validator)

    broken = json.dumps(
        {
            "version": "1.0",
            "name": "broken",
            "nodes": [],
        },
        ensure_ascii=False,
    )

    workflow = corrector.correct(broken, "invalid nodes")

    assert workflow.name == "test_workflow"
