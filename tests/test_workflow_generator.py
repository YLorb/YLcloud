from __future__ import annotations

import json

import pytest

from mini_agent_flow.engine.validator import WorkflowValidator
from mini_agent_flow.planner.generator import GeneratedWorkflow, WorkflowGenerationError, WorkflowGenerator


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


def test_generator_returns_valid_workflow() -> None:
    """LLM 生成合法 workflow 时，应返回 GeneratedWorkflow。"""

    validator = WorkflowValidator(allowed_tools=set())
    generator = WorkflowGenerator(llm=FixedLLM(_valid_workflow_json()), validator=validator)

    result = generator.generate("测试")

    assert isinstance(result, GeneratedWorkflow)
    assert result.workflow.name == "test_workflow"
    assert result.validation_status == "success"
    assert result.repair_attempts == 0
    assert "test_workflow" in result.generated_yaml


def test_generator_repairs_invalid_workflow() -> None:
    """首次生成不合法但修复后合法时，应通过 self-repair 成功。"""

    invalid_first = json.dumps(
        {
            "version": "1.0",
            "name": "bad_workflow",
            # 缺少 outputs，但 repair 后会补全
            "inputs": {"goal": "测试"},
            "nodes": [
                {"id": "start", "type": "start", "next": "plan"},
                {"id": "plan", "type": "llm", "prompt": "{{ goal }}", "output": "final_answer", "next": "end"},
                {"id": "end", "type": "end"},
            ],
        },
        ensure_ascii=False,
    )

    class RepairLLM:
        def __init__(self) -> None:
            self.calls = 0

        def generate(self, prompt: str) -> str:
            self.calls += 1
            if self.calls == 1:
                return invalid_first
            return _valid_workflow_json()

    validator = WorkflowValidator(allowed_tools=set())
    generator = WorkflowGenerator(llm=RepairLLM(), validator=validator)

    result = generator.generate("测试", max_repair_attempts=2)

    assert result.validation_status == "success"
    assert result.repair_attempts == 1


def test_generator_raises_after_max_repair_attempts() -> None:
    """多次修复仍失败时，应抛出 WorkflowGenerationError。"""

    invalid = json.dumps(
        {
            "version": "1.0",
            "name": "bad",
            "nodes": [],
        },
        ensure_ascii=False,
    )

    validator = WorkflowValidator(allowed_tools=set())
    generator = WorkflowGenerator(llm=FixedLLM(invalid), validator=validator)

    with pytest.raises(WorkflowGenerationError, match="after 2 repair attempts"):
        generator.generate("测试", max_repair_attempts=2)


def test_generator_rejects_empty_goal() -> None:
    """空 goal 应直接拒绝。"""

    validator = WorkflowValidator(allowed_tools=set())
    generator = WorkflowGenerator(llm=FixedLLM("{}"), validator=validator)

    with pytest.raises(WorkflowGenerationError, match="must not be empty"):
        generator.generate("  ")


def test_generator_extracts_json_from_code_block() -> None:
    """LLM 返回 markdown 代码块时，应正确提取 JSON。"""

    response = "```json\n" + _valid_workflow_json() + "\n```"
    validator = WorkflowValidator(allowed_tools=set())
    generator = WorkflowGenerator(llm=FixedLLM(response), validator=validator)

    result = generator.generate("测试")

    assert result.workflow.name == "test_workflow"
