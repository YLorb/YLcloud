from __future__ import annotations

import copy
import json
from pathlib import Path

import pytest

from mini_agent_flow.engine.validator import WorkflowValidationError, WorkflowValidator


EXAMPLE_PATH = Path("examples/level1_manual_workflow.json")


@pytest.fixture()
def valid_workflow_data() -> dict:
    return json.loads(EXAMPLE_PATH.read_text(encoding="utf-8"))


def test_valid_workflow_file_passes_validation() -> None:
    workflow = WorkflowValidator(allowed_tools={"mock_search"}).validate_file(EXAMPLE_PATH)

    assert workflow.name == "research_summarizer"
    assert len(workflow.nodes) == 5


def test_missing_required_top_level_field_fails(valid_workflow_data: dict) -> None:
    data = copy.deepcopy(valid_workflow_data)
    data.pop("version")

    with pytest.raises(WorkflowValidationError):
        WorkflowValidator().validate_data(data)


def test_duplicate_node_id_fails(valid_workflow_data: dict) -> None:
    data = copy.deepcopy(valid_workflow_data)
    data["nodes"][1]["id"] = "start"

    with pytest.raises(WorkflowValidationError, match="unique"):
        WorkflowValidator().validate_data(data)


def test_missing_start_node_fails(valid_workflow_data: dict) -> None:
    data = copy.deepcopy(valid_workflow_data)
    data["nodes"] = [node for node in data["nodes"] if node["type"] != "start"]

    with pytest.raises(WorkflowValidationError, match="start"):
        WorkflowValidator().validate_data(data)


def test_multiple_start_nodes_fails(valid_workflow_data: dict) -> None:
    data = copy.deepcopy(valid_workflow_data)
    data["nodes"].append({"id": "another_start", "type": "start", "next": "end"})

    with pytest.raises(WorkflowValidationError, match="start"):
        WorkflowValidator().validate_data(data)


def test_unknown_next_target_fails(valid_workflow_data: dict) -> None:
    data = copy.deepcopy(valid_workflow_data)
    data["nodes"][0]["next"] = "missing_node"

    with pytest.raises(WorkflowValidationError, match="unknown nodes"):
        WorkflowValidator().validate_data(data)


def test_llm_node_missing_prompt_fails(valid_workflow_data: dict) -> None:
    data = copy.deepcopy(valid_workflow_data)
    data["nodes"][1].pop("prompt")

    with pytest.raises(WorkflowValidationError):
        WorkflowValidator().validate_data(data)


def test_tool_node_missing_tool_fails(valid_workflow_data: dict) -> None:
    data = copy.deepcopy(valid_workflow_data)
    data["nodes"][2].pop("tool")

    with pytest.raises(WorkflowValidationError):
        WorkflowValidator().validate_data(data)


def test_tool_node_missing_input_fails(valid_workflow_data: dict) -> None:
    data = copy.deepcopy(valid_workflow_data)
    data["nodes"][2].pop("input")

    with pytest.raises(WorkflowValidationError):
        WorkflowValidator().validate_data(data)


def test_unsupported_tool_fails(valid_workflow_data: dict) -> None:
    with pytest.raises(WorkflowValidationError, match="unsupported tools"):
        WorkflowValidator(allowed_tools={"file_reader"}).validate_data(valid_workflow_data)
