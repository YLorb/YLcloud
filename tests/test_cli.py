from __future__ import annotations

import json
from pathlib import Path
from types import SimpleNamespace

from typer.testing import CliRunner

from mini_agent_flow.cli import app
from mini_agent_flow import cli


runner = CliRunner()


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
            "name": "generated_test",
            "description": "Generated workflow for test",
            "inputs": {"goal": "测试"},
            "outputs": ["final_answer"],
            "nodes": [
                {"id": "start", "type": "start", "next": "plan"},
                {
                    "id": "plan",
                    "type": "llm",
                    "prompt": "Plan: {{ goal }}",
                    "output": "final_answer",
                    "next": "end",
                },
                {"id": "end", "type": "end"},
            ],
        },
        ensure_ascii=False,
    )


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


def test_cli_deepseek_provider_requires_key(monkeypatch) -> None:
    """CLI 选择 DeepSeek 但缺少本地 Key 时应安全失败。"""

    monkeypatch.delenv("DEEPSEEK_API_KEY", raising=False)
    result = runner.invoke(
        app,
        [
            "run",
            "examples/level1_manual_workflow.yaml",
            "--provider",
            "deepseek",
        ],
    )

    assert result.exit_code == 1
    assert "DEEPSEEK_API_KEY is not configured" in result.output


def test_cli_select_with_rule_selector_succeeds() -> None:
    """CLI select 支持 --selector rule 参数。"""

    result = runner.invoke(
        app,
        [
            "select",
            "--goal",
            "分析 Python 报错",
            "--selector",
            "rule",
        ],
    )

    assert result.exit_code == 0
    assert "python_error_analyzer" in result.output
    assert "Selected Workflow" in result.output


def test_cli_configures_supported_streams_as_utf8(monkeypatch) -> None:
    """CLI 应将可配置的标准流切换到 UTF-8，支持模型返回完整 Unicode。"""

    configured: list[str] = []
    stream = SimpleNamespace(reconfigure=lambda **kwargs: configured.append(kwargs["encoding"]))
    monkeypatch.setattr(cli.sys, "stdout", stream)
    monkeypatch.setattr(cli.sys, "stderr", stream)

    cli._configure_standard_streams()

    assert configured == ["utf-8", "utf-8"]


def test_cli_make_and_run_confirmed_executes_workflow(monkeypatch, tmp_path: Path) -> None:
    """make-and-run --yes 应保存并执行生成的 workflow。"""

    output_path = tmp_path / "out.yaml"

    def _create_llm(provider, model=None):
        return FixedLLM(_valid_workflow_json())

    monkeypatch.setattr(cli, "create_llm", _create_llm)

    result = runner.invoke(
        app,
        [
            "make-and-run",
            "--goal",
            "测试 goal",
            "--yes",
            "-o",
            str(output_path),
        ],
    )

    assert result.exit_code == 0
    assert output_path.exists()
    assert "Generated Workflow" in result.output
    assert "Flow" in result.output
    assert "Save Path" in result.output
    assert "Validation" in result.output
    assert "Final Output" in result.output
    assert "Trace" in result.output


def test_cli_make_and_run_declined_does_not_save(monkeypatch, tmp_path: Path) -> None:
    """未确认时不保存 workflow。"""

    output_path = tmp_path / "out.yaml"

    def _create_llm(provider, model=None):
        return FixedLLM(_valid_workflow_json())

    monkeypatch.setattr(cli, "create_llm", _create_llm)

    result = runner.invoke(
        app,
        [
            "make-and-run",
            "--goal",
            "测试 goal",
            "-o",
            str(output_path),
        ],
        input="n\n",
    )

    assert result.exit_code == 0
    assert not output_path.exists()
    assert "已取消" in result.output
