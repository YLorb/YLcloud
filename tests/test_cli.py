from __future__ import annotations

from pathlib import Path

from typer.testing import CliRunner

from mini_agent_flow.cli import app


runner = CliRunner()


def test_cli_run_yaml_example_succeeds() -> None:
    """CLI run 应能执行 YAML Level 1 示例。"""

    result = runner.invoke(app, ["run", "examples/level1_manual_workflow.yaml"])

    assert result.exit_code == 0
    assert "research_summarizer" in result.output
    assert "Final Output" in result.output
    assert "Trace" in result.output
    assert "start" in result.output
    assert "summarize" in result.output


def test_cli_run_json_example_succeeds() -> None:
    """CLI run 应能执行 JSON Level 1 示例。"""

    result = runner.invoke(app, ["run", "examples/level1_manual_workflow.json"])

    assert result.exit_code == 0
    assert "research_summarizer" in result.output
    assert "Mock response:" in result.output
    assert "start -> plan -> search -> summarize -> end" in result.output


def test_cli_run_missing_file_fails(tmp_path: Path) -> None:
    """CLI run 遇到不存在文件时应返回非 0。"""

    missing_path = tmp_path / "missing.yaml"
    result = runner.invoke(app, ["run", str(missing_path)])

    assert result.exit_code == 1
    assert "Error:" in result.output
    assert "does not exist" in result.output
