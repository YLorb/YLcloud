from __future__ import annotations

from pathlib import Path

import pytest

from mini_agent_flow.engine.loader import WorkflowLoader
from mini_agent_flow.engine.validator import WorkflowValidator
from mini_agent_flow.planner.catalog import TemplateCatalogError, WorkflowTemplateCatalog


def create_catalog(template_dir: str | Path = "templates") -> WorkflowTemplateCatalog:
    """创建使用当前内置工具白名单的测试 Catalog。"""

    validator = WorkflowValidator(allowed_tools={"mock_search", "echo"})
    return WorkflowTemplateCatalog(template_dir, WorkflowLoader(validator=validator))


def test_catalog_loads_valid_templates() -> None:
    """Catalog 应加载并校验两个 Level 2 模板。"""

    candidates = create_catalog().load()

    assert {candidate.workflow.name for candidate in candidates} == {
        "research_summarizer",
        "python_error_analyzer",
    }
    assert all(candidate.metadata.required_inputs == ["goal"] for candidate in candidates)


def test_catalog_rejects_template_without_strict_metadata(tmp_path: Path) -> None:
    """普通 Workflow 可以没有模板 metadata，但进入模板库时必须拒绝。"""

    source = Path("examples/level1_manual_workflow.yaml").read_text(encoding="utf-8")
    (tmp_path / "invalid.yaml").write_text(source, encoding="utf-8")

    with pytest.raises(TemplateCatalogError, match="invalid workflow template"):
        create_catalog(tmp_path).load()


def test_catalog_rejects_missing_directory(tmp_path: Path) -> None:
    """模板目录不存在时应返回明确错误。"""

    with pytest.raises(TemplateCatalogError, match="does not exist"):
        create_catalog(tmp_path / "missing").load()
