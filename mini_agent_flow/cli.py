from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any

import typer
from rich.console import Console
from rich.panel import Panel
from rich.table import Table

from mini_agent_flow.engine.executor import SequentialWorkflowExecutor, WorkflowExecutionError
from mini_agent_flow.engine.loader import WorkflowLoadError, WorkflowLoader
from mini_agent_flow.engine.validator import WorkflowValidationError, WorkflowValidator
from mini_agent_flow.llm.base import LLMClientError
from mini_agent_flow.llm.deepseek import DEFAULT_DEEPSEEK_MODEL
from mini_agent_flow.llm.factory import LLMProvider, create_llm
from mini_agent_flow.planner.catalog import TemplateCatalogError, WorkflowTemplateCatalog
from mini_agent_flow.planner.corrector import WorkflowCorrector
from mini_agent_flow.planner.generator import GeneratedWorkflow, WorkflowGenerator
from mini_agent_flow.planner.models import Level2RunResult
from mini_agent_flow.planner.selector import (
    NoMatchingTemplateError,
    RuleBasedTemplateSelector,
    TemplateSelectionError,
    create_selector,
)
from mini_agent_flow.planner.service import Level2ExecutionError, Level2WorkflowService
from mini_agent_flow.tools.builtin import create_default_tool_registry


app = typer.Typer(help="Mini Agent Flow command line interface.", no_args_is_help=True)
console = Console(highlight=False)
error_console = Console(stderr=True, highlight=False)


@app.callback()
def main() -> None:
    """Mini Agent Flow command line interface."""

    _configure_standard_streams()


@app.command("run")
def run_workflow(
    workflow_path: Path = typer.Argument(..., help="Path to a workflow .json/.yaml/.yml/.md file."),
    provider: LLMProvider = typer.Option(LLMProvider.mock, help="LLM provider."),
    model: str = typer.Option(DEFAULT_DEEPSEEK_MODEL, help="Model used by remote providers."),
) -> None:
    """Run a Level 1 workflow file with the selected LLM provider."""

    try:
        registry = create_default_tool_registry()
        tool_specs = _tool_specs_from_registry(registry)
        validator = WorkflowValidator(
            allowed_tools=registry.names(),
            tool_specs=tool_specs,
        )
        loader = WorkflowLoader(validator=validator)
        executor = SequentialWorkflowExecutor(
            llm=create_llm(provider, model=model),
            tool_registry=registry,
            allowed_permissions={"public"},
            max_risk_level=5,
        )
        workflow = loader.load(workflow_path)
        result = executor.run(workflow)
    except (
        LLMClientError,
        WorkflowLoadError,
        WorkflowValidationError,
        WorkflowExecutionError,
    ) as exc:
        _print_error(exc)
        raise typer.Exit(code=1) from exc

    _print_result(result)


@app.command("select")
def select_workflow(
    goal: str = typer.Option(..., "--goal", help="Goal used to select a workflow template."),
    templates_dir: Path = typer.Option(
        Path("templates"),
        "--templates",
        help="Directory containing workflow templates.",
    ),
    show_trace: bool = typer.Option(
        True,
        "--trace/--no-trace",
        help="Show the workflow execution trace.",
    ),
    provider: LLMProvider = typer.Option(LLMProvider.mock, help="LLM provider."),
    model: str = typer.Option(DEFAULT_DEEPSEEK_MODEL, help="Model used by remote providers."),
    selector: str = typer.Option(
        "rule",
        "--selector",
        help="Template selection strategy: rule, llm, or hybrid.",
    ),
) -> None:
    """Select and run a Level 2 workflow template for a Goal."""

    try:
        registry = create_default_tool_registry()
        tool_specs = _tool_specs_from_registry(registry)
        validator = WorkflowValidator(
            allowed_tools=registry.names(),
            tool_specs=tool_specs,
        )
        loader = WorkflowLoader(validator=validator)
        catalog = WorkflowTemplateCatalog(templates_dir, loader=loader)
        llm = create_llm(provider, model=model)
        executor = SequentialWorkflowExecutor(
            llm=llm,
            tool_registry=registry,
            allowed_permissions={"public"},
            max_risk_level=5,
        )
        service = Level2WorkflowService(
            catalog=catalog,
            selector=create_selector(selector, llm, use_llm=False),
            validator=validator,
            executor=executor,
        )
        result = service.run(goal)
    except (
        LLMClientError,
        TemplateCatalogError,
        TemplateSelectionError,
        NoMatchingTemplateError,
        Level2ExecutionError,
        WorkflowValidationError,
        WorkflowExecutionError,
    ) as exc:
        _print_error(exc)
        raise typer.Exit(code=1) from exc

    _print_level2_result(result, show_trace=show_trace)


def _print_result(result: Any) -> None:
    """Print a workflow run result in a readable CLI layout."""

    console.print(Panel(result.workflow_name, title="Workflow", expand=False))
    console.print(Panel(_format_value(result.final_output), title="Final Output", expand=False))
    console.print(Panel(" -> ".join(result.executed_nodes), title="Executed Nodes", expand=False))
    console.print(Panel(_format_value(result.context), title="Context", expand=False))
    console.print(_build_trace_table(result.trace))


def _print_level2_result(result: Level2RunResult, show_trace: bool) -> None:
    """Print template selection details and the resulting workflow output."""

    console.print(Panel(result.selected_workflow, title="Selected Workflow", expand=False))
    console.print(Panel(result.selection_reason, title="Selection Reason", expand=False))
    console.print(Panel(_format_value(result.final_output), title="Final Output", expand=False))
    console.print(Panel(" -> ".join(result.executed_nodes), title="Executed Nodes", expand=False))
    if show_trace:
        console.print(_build_trace_table(result.trace))


@app.command("make")
def make_workflow(
    goal: str = typer.Option(..., "--goal", help="Goal used to generate a workflow."),
    output: Path | None = typer.Option(
        None,
        "--output",
        "-o",
        help="Optional path to save the generated workflow YAML.",
    ),
    provider: LLMProvider = typer.Option(LLMProvider.mock, help="LLM provider."),
    model: str = typer.Option(DEFAULT_DEEPSEEK_MODEL, help="Model used by remote providers."),
) -> None:
    """Generate a workflow from a natural language goal using LLM."""

    try:
        registry = create_default_tool_registry()
        tool_specs = _tool_specs_from_registry(registry)
        validator = WorkflowValidator(
            allowed_tools=registry.names(),
            tool_specs=tool_specs,
        )
        llm = create_llm(provider, model=model)
        generator = WorkflowGenerator(llm=llm, validator=validator)
        result = generator.generate(goal, allowed_tools=registry.names())
    except (
        LLMClientError,
        WorkflowValidationError,
        WorkflowLoadError,
    ) as exc:
        _print_error(exc)
        raise typer.Exit(code=1) from exc

    console.print(Panel(result.workflow.name, title="Generated Workflow", expand=False))
    console.print(Panel(result.generated_yaml, title="YAML", expand=False))
    console.print(Panel(f"repair attempts: {result.repair_attempts}", title="Validation", expand=False))

    if output:
        output.write_text(result.generated_yaml, encoding="utf-8")
        console.print(f"Saved workflow to {output}")


@app.command("correct")
def correct_workflow(
    workflow_path: Path = typer.Argument(..., help="Path to a workflow file to correct."),
    error_message: str = typer.Option(
        ...,
        "--error",
        help="Validation error message used to guide the correction.",
    ),
    output: Path | None = typer.Option(
        None,
        "--output",
        "-o",
        help="Optional path to save the corrected workflow YAML.",
    ),
    provider: LLMProvider = typer.Option(LLMProvider.mock, help="LLM provider."),
    model: str = typer.Option(DEFAULT_DEEPSEEK_MODEL, help="Model used by remote providers."),
) -> None:
    """Correct an invalid workflow using LLM."""

    try:
        registry = create_default_tool_registry()
        tool_specs = _tool_specs_from_registry(registry)
        validator = WorkflowValidator(
            allowed_tools=registry.names(),
            tool_specs=tool_specs,
        )
        llm = create_llm(provider, model=model)
        corrector = WorkflowCorrector(llm=llm, validator=validator)
        workflow_text = workflow_path.read_text(encoding="utf-8")
        workflow = corrector.correct(workflow_text, error_message)
    except (
        LLMClientError,
        WorkflowValidationError,
        WorkflowLoadError,
    ) as exc:
        _print_error(exc)
        raise typer.Exit(code=1) from exc

    console.print(Panel(workflow.name, title="Corrected Workflow", expand=False))

    corrected_yaml = _workflow_to_yaml(workflow)
    console.print(Panel(corrected_yaml, title="YAML", expand=False))

    if output:
        output.write_text(corrected_yaml, encoding="utf-8")
        console.print(f"Saved corrected workflow to {output}")


def _tool_specs_from_registry(registry: Any) -> dict[str, Any]:
    """从 ToolRegistry 中提取所有已注册的 ToolSpec。"""

    specs: dict[str, Any] = {}
    for name in registry.names():
        spec = registry.get_spec(name)
        if spec is not None:
            specs[name] = spec
    return specs


def _workflow_to_yaml(workflow: Any) -> str:
    """将 Workflow 对象转成 YAML 字符串。"""

    import yaml

    data = workflow.model_dump(mode="python")
    return yaml.safe_dump(data, allow_unicode=True, sort_keys=False, default_flow_style=False)


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


def _configure_standard_streams() -> None:
    """在支持 reconfigure 的终端中使用 UTF-8，避免真实模型 Unicode 输出失败。"""

    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if callable(reconfigure):
            reconfigure(encoding="utf-8")
