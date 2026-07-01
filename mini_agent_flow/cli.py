from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import typer
from rich.console import Console
from rich.panel import Panel
from rich.table import Table

from mini_agent_flow.engine.executor import SequentialWorkflowExecutor, WorkflowExecutionError
from mini_agent_flow.engine.loader import WorkflowLoadError, WorkflowLoader
from mini_agent_flow.engine.validator import WorkflowValidationError, WorkflowValidator
from mini_agent_flow.llm.mock import MockLLM
from mini_agent_flow.tools.builtin import create_default_tool_registry


app = typer.Typer(help="Mini Agent Flow command line interface.", no_args_is_help=True)
console = Console(highlight=False)
error_console = Console(stderr=True, highlight=False)


@app.callback()
def main() -> None:
    """Mini Agent Flow command line interface."""


@app.command("run")
def run_workflow(
    workflow_path: Path = typer.Argument(..., help="Path to a workflow .json/.yaml/.yml file."),
) -> None:
    """Run a Level 1 workflow file with MockLLM and built-in tools."""

    registry = create_default_tool_registry()
    validator = WorkflowValidator(allowed_tools=registry.names())
    loader = WorkflowLoader(validator=validator)
    executor = SequentialWorkflowExecutor(llm=MockLLM(), tool_registry=registry)

    try:
        workflow = loader.load(workflow_path)
        result = executor.run(workflow)
    except (WorkflowLoadError, WorkflowValidationError, WorkflowExecutionError) as exc:
        _print_error(exc)
        raise typer.Exit(code=1) from exc

    _print_result(result)


def _print_result(result: Any) -> None:
    """Print a workflow run result in a readable CLI layout."""

    console.print(Panel(result.workflow_name, title="Workflow", expand=False))
    console.print(Panel(_format_value(result.final_output), title="Final Output", expand=False))
    console.print(Panel(" -> ".join(result.executed_nodes), title="Executed Nodes", expand=False))
    console.print(Panel(_format_value(result.context), title="Context", expand=False))
    console.print(_build_trace_table(result.trace))


def _print_error(exc: Exception) -> None:
    """Print a compact error message and partial trace when available."""

    error_console.print(f"[bold red]Error:[/bold red] {exc}")
    trace = getattr(exc, "trace", None)
    if trace:
        error_console.print(_build_trace_table(trace))


def _build_trace_table(trace: list[dict[str, Any]]) -> Table:
    """Build a compact trace table for successful and failed runs."""

    table = Table(title="Trace")
    table.add_column("Node")
    table.add_column("Type")
    table.add_column("Status")
    table.add_column("Attempt")

    for event in trace:
        table.add_row(
            str(event.get("node_id", "")),
            str(event.get("node_type", "")),
            str(event.get("status", "")),
            str(event.get("attempt", "")),
        )

    return table


def _format_value(value: Any) -> str:
    """Format arbitrary Python values for CLI output."""

    if value is None:
        return "None"
    if isinstance(value, str):
        return value
    return json.dumps(value, ensure_ascii=False, indent=2, default=str)
