from __future__ import annotations

import json
from pathlib import Path

import pytest

from mini_agent_flow.engine.loader import JsonWorkflowLoader, WorkflowLoader, WorkflowLoadError
from mini_agent_flow.engine.validator import WorkflowValidationError, WorkflowValidator


EXAMPLE_PATH = Path("examples/level1_manual_workflow.json")
YAML_EXAMPLE_PATH = Path("examples/level1_manual_workflow.yaml")


@pytest.fixture()
def valid_workflow_data() -> dict:
    """提供合法 workflow dict，方便构造 Loader 的不同输入场景。"""

    return json.loads(EXAMPLE_PATH.read_text(encoding="utf-8"))


def test_load_valid_json_workflow_file() -> None:
    """Loader 应能从合法 .json 文件返回已校验的 Workflow 对象。"""

    workflow = JsonWorkflowLoader(
        validator=WorkflowValidator(allowed_tools={"mock_search"})
    ).load(EXAMPLE_PATH)

    assert workflow.name == "research_summarizer"
    assert len(workflow.nodes) == 5


def test_load_missing_file_fails(tmp_path: Path) -> None:
    """不存在的路径属于加载错误，而不是 workflow 内容校验错误。"""

    missing_path = tmp_path / "missing.json"

    with pytest.raises(WorkflowLoadError, match="does not exist"):
        JsonWorkflowLoader().load(missing_path)


def test_load_directory_path_fails(tmp_path: Path) -> None:
    """目录不能作为 workflow 文件加载。"""

    with pytest.raises(WorkflowLoadError, match="not a file"):
        JsonWorkflowLoader().load(tmp_path)


def test_load_non_json_extension_fails(tmp_path: Path) -> None:
    """固定 JSON Workflow Loader 只接受 .json 后缀。"""

    workflow_path = tmp_path / "workflow.txt"
    workflow_path.write_text("{}", encoding="utf-8")

    with pytest.raises(WorkflowLoadError, match=".json"):
        JsonWorkflowLoader().load(workflow_path)


def test_load_invalid_json_fails(tmp_path: Path) -> None:
    """JSON 语法错误应在 Loader 层被捕获。"""

    workflow_path = tmp_path / "workflow.json"
    workflow_path.write_text("{invalid json", encoding="utf-8")

    with pytest.raises(WorkflowLoadError, match="invalid JSON"):
        JsonWorkflowLoader().load(workflow_path)


def test_load_json_root_must_be_object(tmp_path: Path) -> None:
    """workflow JSON 顶层必须是 object，不能是数组或字符串。"""

    workflow_path = tmp_path / "workflow.json"
    workflow_path.write_text("[]", encoding="utf-8")

    with pytest.raises(WorkflowLoadError, match="root must be an object"):
        JsonWorkflowLoader().load(workflow_path)


def test_load_data_root_must_be_object() -> None:
    """后续 Planner 直接传入数据时，也必须保证顶层是 object。"""

    with pytest.raises(WorkflowLoadError, match="root must be an object"):
        JsonWorkflowLoader().load_data([])


def test_load_semantically_invalid_workflow_raises_validation_error(
    valid_workflow_data: dict,
) -> None:
    """结构已成功加载后，节点引用等语义错误应由 Validator 抛出。"""

    valid_workflow_data["nodes"][0]["next"] = "missing_node"

    with pytest.raises(WorkflowValidationError, match="unknown nodes"):
        JsonWorkflowLoader().load_data(valid_workflow_data)


def test_allowed_tools_validator_is_used(valid_workflow_data: dict) -> None:
    """调用方传入的 Validator 配置应在 Loader 中生效。"""

    loader = JsonWorkflowLoader(validator=WorkflowValidator(allowed_tools={"file_reader"}))

    with pytest.raises(WorkflowValidationError, match="unsupported tools"):
        loader.load_data(valid_workflow_data)


def test_workflow_loader_loads_json_by_extension() -> None:
    """统一 WorkflowLoader 应能根据 .json 后缀选择 JSON Loader。"""

    workflow = WorkflowLoader(
        validator=WorkflowValidator(allowed_tools={"mock_search"})
    ).load(EXAMPLE_PATH)

    assert workflow.name == "research_summarizer"
    assert len(workflow.nodes) == 5


def test_workflow_loader_loads_yaml_by_extension() -> None:
    """统一 WorkflowLoader 应能根据 .yaml 后缀选择 YAML Loader。"""

    workflow = WorkflowLoader(
        validator=WorkflowValidator(allowed_tools={"mock_search"})
    ).load(YAML_EXAMPLE_PATH)

    assert workflow.name == "research_summarizer"
    assert len(workflow.nodes) == 5


def test_workflow_loader_loads_yml_by_extension(tmp_path: Path) -> None:
    """统一 WorkflowLoader 应支持 .yml 后缀。"""

    workflow_path = tmp_path / "workflow.yml"
    workflow_path.write_text(YAML_EXAMPLE_PATH.read_text(encoding="utf-8"), encoding="utf-8")

    workflow = WorkflowLoader(
        validator=WorkflowValidator(allowed_tools={"mock_search"})
    ).load(workflow_path)

    assert workflow.name == "research_summarizer"


def test_workflow_loader_unknown_extension_fails(tmp_path: Path) -> None:
    """统一 WorkflowLoader 遇到未知后缀时应抛出加载错误。"""

    workflow_path = tmp_path / "workflow.txt"
    workflow_path.write_text("{}", encoding="utf-8")

    with pytest.raises(WorkflowLoadError, match=".json, .yaml or .yml"):
        WorkflowLoader().load(workflow_path)


def test_workflow_loader_load_data_returns_workflow(valid_workflow_data: dict) -> None:
    """统一 WorkflowLoader.load_data 应复用 Validator 返回 Workflow 对象。"""

    workflow = WorkflowLoader(
        validator=WorkflowValidator(allowed_tools={"mock_search"})
    ).load_data(valid_workflow_data)

    assert workflow.name == "research_summarizer"


def test_workflow_loader_load_data_root_must_be_object() -> None:
    """统一 WorkflowLoader.load_data 只接受 dict 顶层数据。"""

    with pytest.raises(WorkflowLoadError, match="root must be an object"):
        WorkflowLoader().load_data([])


def test_validator_validate_file_uses_unified_loader_for_yaml() -> None:
    """WorkflowValidator.validate_file 应通过统一 Loader 支持 YAML。"""

    workflow = WorkflowValidator(allowed_tools={"mock_search"}).validate_file(YAML_EXAMPLE_PATH)

    assert workflow.name == "research_summarizer"
