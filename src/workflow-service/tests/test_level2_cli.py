from __future__ import annotations

from typer.testing import CliRunner

from mini_agent_flow.cli import app


runner = CliRunner()


def test_cli_select_runs_complete_level2_flow() -> None:
    """CLI select 应展示模板、选择原因、最终输出和 Trace。"""

    result = runner.invoke(app, ["select", "--goal", "分析这个 Python traceback 报错"])

    assert result.exit_code == 0
    assert "python_error_analyzer.yaml" in result.output
    assert "Selection Reason" in result.output
    assert "Final Output" in result.output
    assert "Trace" in result.output


def test_cli_select_can_hide_trace() -> None:
    """--no-trace 应隐藏 Trace 表格。"""

    result = runner.invoke(
        app,
        ["select", "--goal", "调研 Agent 发展趋势", "--no-trace"],
    )

    assert result.exit_code == 0
    assert "research_summarizer.yaml" in result.output
    assert "Trace" not in result.output


def test_cli_select_reports_no_match() -> None:
    """无匹配模板时 CLI 应以非 0 状态退出并给出原因。"""

    result = runner.invoke(app, ["select", "--goal", "制作财务预算表"])

    assert result.exit_code == 1
    assert "no workflow template matches" in result.output
