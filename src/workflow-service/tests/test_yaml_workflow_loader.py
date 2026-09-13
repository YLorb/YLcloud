from __future__ import annotations

from pathlib import Path

import pytest

from mini_agent_flow.engine.executor import SequentialWorkflowExecutor
from mini_agent_flow.engine.loader import WorkflowLoadError, YamlWorkflowLoader
from mini_agent_flow.engine.validator import WorkflowValidationError, WorkflowValidator
from mini_agent_flow.llm.mock import MockLLM
from mini_agent_flow.tools.builtin import create_default_tool_registry


EXAMPLE_PATH = Path("examples/level1_manual_workflow.yaml")


def valid_workflow_yaml() -> str:
    """提供最小合法 YAML workflow 文本，方便测试不同文件后缀。"""

    return """
version: "1.0"
name: yaml_demo
inputs:
  message: hello
nodes:
  - id: start
    type: start
    next: echo
  - id: echo
    type: tool
    tool: echo
    input: "{{ message }}"
    output: echoed
    next: end
  - id: end
    type: end
"""


def test_load_valid_yaml_workflow_file() -> None:
    """YAML Loader 应能加载 .yaml 示例文件并返回 Workflow 对象。"""

    workflow = YamlWorkflowLoader(
        validator=WorkflowValidator(allowed_tools={"mock_search"})
    ).load(EXAMPLE_PATH)

    assert workflow.name == "research_summarizer"
    assert len(workflow.nodes) == 5


def test_load_yml_extension_is_supported(tmp_path: Path) -> None:
    """.yml 后缀也应被 YAML Loader 支持。"""

    workflow_path = tmp_path / "workflow.yml"
    workflow_path.write_text(valid_workflow_yaml(), encoding="utf-8")

    workflow = YamlWorkflowLoader(
        validator=WorkflowValidator(allowed_tools={"echo"})
    ).load(workflow_path)

    assert workflow.name == "yaml_demo"


def test_load_non_yaml_extension_fails(tmp_path: Path) -> None:
    """YAML Loader 不接受 .yaml / .yml 之外的后缀。"""

    workflow_path = tmp_path / "workflow.json"
    workflow_path.write_text(valid_workflow_yaml(), encoding="utf-8")

    with pytest.raises(WorkflowLoadError, match=".yaml or .yml"):
        YamlWorkflowLoader().load(workflow_path)


def test_load_invalid_yaml_fails(tmp_path: Path) -> None:
    """YAML 语法错误应在 Loader 层被捕获。"""

    workflow_path = tmp_path / "workflow.yaml"
    workflow_path.write_text("version: [invalid", encoding="utf-8")

    with pytest.raises(WorkflowLoadError, match="invalid YAML"):
        YamlWorkflowLoader().load(workflow_path)


def test_load_empty_yaml_fails(tmp_path: Path) -> None:
    """空 YAML 文件会解析为 None，不能作为 workflow 顶层结构。"""

    workflow_path = tmp_path / "workflow.yaml"
    workflow_path.write_text("", encoding="utf-8")

    with pytest.raises(WorkflowLoadError, match="root must be an object"):
        YamlWorkflowLoader().load(workflow_path)


def test_load_yaml_root_must_be_object(tmp_path: Path) -> None:
    """workflow YAML 顶层必须是 object/mapping，不能是数组。"""

    workflow_path = tmp_path / "workflow.yaml"
    workflow_path.write_text("- start\n- end\n", encoding="utf-8")

    with pytest.raises(WorkflowLoadError, match="root must be an object"):
        YamlWorkflowLoader().load(workflow_path)


def test_allowed_tools_validator_is_used() -> None:
    """调用方传入的 Validator 配置应在 YAML Loader 中生效。"""

    loader = YamlWorkflowLoader(
        validator=WorkflowValidator(allowed_tools={"file_reader"})
    )

    with pytest.raises(WorkflowValidationError, match="unsupported tools"):
        loader.load(EXAMPLE_PATH)


def test_yaml_example_runs_through_full_chain() -> None:
    """YAML 示例应能跑通 Loader、Validator、Executor、MockLLM 和 Mock Tool。"""

    registry = create_default_tool_registry()
    workflow = YamlWorkflowLoader(
        validator=WorkflowValidator(allowed_tools=registry.names())
    ).load(EXAMPLE_PATH)
    executor = SequentialWorkflowExecutor(llm=MockLLM(), tool_registry=registry)

    result = executor.run(workflow)

    assert result.executed_nodes == ["start", "plan", "search", "summarize", "end"]
    assert "goal" in result.context
    assert "keywords" in result.context
    assert "search_results" in result.context
    assert "final_answer" in result.context
    assert result.final_output == result.context["final_answer"]
