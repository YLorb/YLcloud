from __future__ import annotations

import copy
import json
from pathlib import Path

import pytest

from mini_agent_flow.engine.validator import WorkflowValidationError, WorkflowValidator


EXAMPLE_PATH = Path("examples/level1_manual_workflow.json")


@pytest.fixture()
def valid_workflow_data() -> dict:
    """提供一份合法 workflow 数据，测试用例会复制后构造不同错误场景。"""

    return json.loads(EXAMPLE_PATH.read_text(encoding="utf-8"))


def test_valid_workflow_file_passes_validation() -> None:
    """合法 JSON workflow 文件应能被读取并通过完整校验。"""

    workflow = WorkflowValidator(allowed_tools={"mock_search"}).validate_file(EXAMPLE_PATH)

    assert workflow.name == "research_summarizer"
    assert len(workflow.nodes) == 5


def test_missing_required_top_level_field_fails(valid_workflow_data: dict) -> None:
    """缺少顶层必填字段时，应在模型结构校验阶段失败。"""

    data = copy.deepcopy(valid_workflow_data)
    data.pop("version")

    with pytest.raises(WorkflowValidationError):
        WorkflowValidator().validate_data(data)


def test_duplicate_node_id_fails(valid_workflow_data: dict) -> None:
    """node id 必须唯一，否则执行器无法明确定位节点。"""

    data = copy.deepcopy(valid_workflow_data)
    data["nodes"][1]["id"] = "start"

    with pytest.raises(WorkflowValidationError, match="unique"):
        WorkflowValidator().validate_data(data)


def test_missing_start_node_fails(valid_workflow_data: dict) -> None:
    """workflow 必须有一个 start 节点作为唯一入口。"""

    data = copy.deepcopy(valid_workflow_data)
    data["nodes"] = [node for node in data["nodes"] if node["type"] != "start"]

    with pytest.raises(WorkflowValidationError, match="start"):
        WorkflowValidator().validate_data(data)


def test_multiple_start_nodes_fails(valid_workflow_data: dict) -> None:
    """多个 start 会导致入口不确定，因此必须拒绝。"""

    data = copy.deepcopy(valid_workflow_data)
    data["nodes"].append({"id": "another_start", "type": "start", "next": "end"})

    with pytest.raises(WorkflowValidationError, match="start"):
        WorkflowValidator().validate_data(data)


def test_unknown_next_target_fails(valid_workflow_data: dict) -> None:
    """next 指向不存在节点时，应被语义校验捕获。"""

    data = copy.deepcopy(valid_workflow_data)
    data["nodes"][0]["next"] = "missing_node"

    with pytest.raises(WorkflowValidationError, match="unknown nodes"):
        WorkflowValidator().validate_data(data)


def test_llm_node_missing_prompt_fails(valid_workflow_data: dict) -> None:
    """LLM 节点必须有 prompt，否则后续无法构造模型输入。"""

    data = copy.deepcopy(valid_workflow_data)
    data["nodes"][1].pop("prompt")

    with pytest.raises(WorkflowValidationError):
        WorkflowValidator().validate_data(data)


def test_tool_node_missing_tool_fails(valid_workflow_data: dict) -> None:
    """Tool 节点必须声明工具名，后续才能从工具注册表中查找实现。"""

    data = copy.deepcopy(valid_workflow_data)
    data["nodes"][2].pop("tool")

    with pytest.raises(WorkflowValidationError):
        WorkflowValidator().validate_data(data)


def test_tool_node_missing_input_fails(valid_workflow_data: dict) -> None:
    """Tool 节点必须显式声明 input，避免工具调用输入来源不清晰。"""

    data = copy.deepcopy(valid_workflow_data)
    data["nodes"][2].pop("input")

    with pytest.raises(WorkflowValidationError):
        WorkflowValidator().validate_data(data)


def test_unsupported_tool_fails(valid_workflow_data: dict) -> None:
    """开启 allowed_tools 后，未授权工具名应被拒绝。"""

    with pytest.raises(WorkflowValidationError, match="unsupported tools"):
        WorkflowValidator(allowed_tools={"file_reader"}).validate_data(valid_workflow_data)


def test_declared_output_must_exist_in_inputs_or_node_outputs(
    valid_workflow_data: dict,
) -> None:
    """workflow.outputs 只能声明已有输入或节点会写入的 output。"""

    data = copy.deepcopy(valid_workflow_data)
    data["outputs"] = ["missing_answer"]

    with pytest.raises(WorkflowValidationError, match="outputs are not produced"):
        WorkflowValidator(allowed_tools={"mock_search"}).validate_data(data)


def test_declared_outputs_must_be_unique(valid_workflow_data: dict) -> None:
    """workflow.outputs 中不应重复声明同一个字段。"""

    data = copy.deepcopy(valid_workflow_data)
    data["outputs"] = ["final_answer", "final_answer"]

    with pytest.raises(WorkflowValidationError, match="duplicate"):
        WorkflowValidator(allowed_tools={"mock_search"}).validate_data(data)
