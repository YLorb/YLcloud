from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any

import typer
from rich.console import Console
from rich.panel import Panel
from rich.table import Table

from mini_agent_flow.engine.executor import WorkflowExecutionError
from mini_agent_flow.engine.graph_executor import GraphWorkflowExecutor
from mini_agent_flow.engine.run_manager import RunManager, RunManagerError
from mini_agent_flow.engine.runtime_config import RuntimeConfig
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
from mini_agent_flow.persistence.sqlite_store import SQLiteRunControlStore


app = typer.Typer(help="Mini Agent Flow command line interface.", no_args_is_help=True)
runs_app = typer.Typer(help="Inspect and control persisted workflow runs.", no_args_is_help=True)
app.add_typer(runs_app, name="runs")
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
    database: Path = typer.Option(
        Path(".mini-agent-flow/runs.db"), "--database", help="SQLite run database."
    ),
) -> None:
    """Run a Level 1 workflow file with the selected LLM provider."""

    manager: RunManager | None = None
    try:
        registry = create_default_tool_registry()
        tool_specs = _tool_specs_from_registry(registry)
        validator = WorkflowValidator(
            allowed_tools=registry.names(),
            tool_specs=tool_specs,
        )
        loader = WorkflowLoader(validator=validator)
        manager = _create_run_manager(
            database,
            provider=provider,
            model=model,
            start_services=True,
        )
        workflow = loader.load(workflow_path)
        result = manager.run(workflow)
    except (
        LLMClientError,
        WorkflowLoadError,
        WorkflowValidationError,
        WorkflowExecutionError,
        RunManagerError,
    ) as exc:
        _print_error(exc)
        raise typer.Exit(code=1) from exc
    finally:
        if manager is not None:
            manager.shutdown()

    _print_result(result)


@runs_app.command("list")
def list_runs(
    database: Path = typer.Option(Path(".mini-agent-flow/runs.db"), "--database"),
    limit: int = typer.Option(100, min=1, max=1000),
) -> None:
    """List recent logical workflow runs."""

    manager = _create_run_manager(database)
    try:
        rows = manager.list_runs(limit=limit)
        table = Table(title="Workflow Runs")
        for name in ("run_id", "workflow_name", "status", "restart_count", "updated_at_utc"):
            table.add_column(name)
        for row in rows:
            table.add_row(*(str(row.get(name, "")) for name in (
                "run_id", "workflow_name", "status", "restart_count", "updated_at_utc"
            )))
        console.print(table)
    finally:
        manager.shutdown()


@runs_app.command("show")
def show_run(
    run_id: str = typer.Argument(...),
    database: Path = typer.Option(Path(".mini-agent-flow/runs.db"), "--database"),
) -> None:
    """Show a run, its execution epochs, invocations and trace."""

    manager = _create_run_manager(database)
    try:
        details = manager.show_run(run_id)
        run = dict(details["run"])
        run.pop("workflow_json", None)
        console.print(Panel(_format_value(run), title="Run"))
        console.print(Panel(_format_value(details["executions"]), title="Executions"))
    except RunManagerError as exc:
        _print_error(exc)
        raise typer.Exit(code=1) from exc
    finally:
        manager.shutdown()


@runs_app.command("cancel")
def cancel_run(
    run_id: str = typer.Argument(...),
    reason: str = typer.Option("USER_CANCELLED", "--reason"),
    database: Path = typer.Option(Path(".mini-agent-flow/runs.db"), "--database"),
) -> None:
    """Request cancellation of the complete active run."""

    _request_cancel(database, run_id, target_node_id=None, reason=reason)


@runs_app.command("cancel-node")
def cancel_node(
    run_id: str = typer.Argument(...),
    node_id: str = typer.Argument(...),
    reason: str = typer.Option("USER_CANCELLED", "--reason"),
    database: Path = typer.Option(Path(".mini-agent-flow/runs.db"), "--database"),
) -> None:
    """Request cancellation when the selected pending/running node is reached."""

    _request_cancel(database, run_id, target_node_id=node_id, reason=reason)


@runs_app.command("retry")
def retry_run(
    run_id: str = typer.Argument(...),
    database: Path = typer.Option(Path(".mini-agent-flow/runs.db"), "--database"),
    provider: LLMProvider = typer.Option(LLMProvider.mock),
    model: str = typer.Option(DEFAULT_DEEPSEEK_MODEL),
) -> None:
    """Explicitly retry an unsuccessful logical run in a new execution epoch."""

    manager = _create_run_manager(database, provider=provider, model=model)
    try:
        _print_result(manager.retry(run_id))
    except (RunManagerError, WorkflowExecutionError) as exc:
        _print_error(exc)
        raise typer.Exit(code=1) from exc
    finally:
        manager.shutdown()


@runs_app.command("cleanup")
def cleanup_runs(
    database: Path = typer.Option(Path(".mini-agent-flow/runs.db"), "--database"),
    apply: bool = typer.Option(False, "--apply", help="Delete rows; default is dry-run."),
    batch_size: int = typer.Option(100, min=1, max=1000),
) -> None:
    """Preview or apply retention cleanup for terminal runs."""

    manager = _create_run_manager(database)
    try:
        result = manager.cleanup(dry_run=not apply, batch_size=batch_size)
        console.print(
            Panel(
                _format_value({"dry_run": result.dry_run, "count": result.count, "run_ids": result.run_ids}),
                title="Cleanup",
            )
        )
    finally:
        manager.shutdown()


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
        executor = GraphWorkflowExecutor(
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
    if len(getattr(result, "active_end_nodes", [])) > 1:
        console.print(
            Panel(
                ", ".join(result.active_end_nodes),
                title="Multiple Active End Nodes",
                expand=False,
            )
        )
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


@app.command("make-and-run")
def make_and_run_workflow(
    goal: str = typer.Option(..., "--goal", help="Goal used to generate and run a workflow."),
    output: Path | None = typer.Option(
        None,
        "--output",
        "-o",
        help="Optional path to save the generated workflow YAML.",
    ),
    provider: LLMProvider = typer.Option(LLMProvider.mock, help="LLM provider."),
    model: str = typer.Option(DEFAULT_DEEPSEEK_MODEL, help="Model used by remote providers."),
    yes: bool = typer.Option(False, "--yes", "-y", help="Skip confirmation prompt."),
) -> None:
    """Generate a workflow from a goal, then save and run it after user confirmation."""

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

    save_path = output or Path(f"examples/generated_{result.workflow.name}.yaml")

    console.print(Panel(result.workflow.name, title="Generated Workflow", expand=False))
    console.print(Panel(_build_flow_preview(result.workflow), title="Flow", expand=False))
    console.print(Panel(str(save_path), title="Save Path", expand=False))
    console.print(Panel(result.generated_yaml, title="YAML", expand=False))
    console.print(Panel(f"repair attempts: {result.repair_attempts}", title="Validation", expand=False))

    if not yes:
        confirmed = typer.confirm("是否保存并执行该 workflow？", default=False)
        if not confirmed:
            console.print("已取消，workflow 未保存或执行。")
            return

    try:
        save_path.parent.mkdir(parents=True, exist_ok=True)
        save_path.write_text(result.generated_yaml, encoding="utf-8")
        console.print(f"Saved workflow to {save_path}")
        executor = GraphWorkflowExecutor(
            llm=create_llm(provider, model=model),
            tool_registry=registry,
            allowed_permissions={"public"},
            max_risk_level=5,
        )
        run_result = executor.run(result.workflow)
    except (
        LLMClientError,
        WorkflowValidationError,
        WorkflowExecutionError,
    ) as exc:
        _print_error(exc)
        raise typer.Exit(code=1) from exc

    _print_result(run_result)


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


def _open_store(database: Path) -> SQLiteRunControlStore:
    store = SQLiteRunControlStore(database)
    store.migrate()
    return store


def _create_run_manager(
    database: Path,
    *,
    provider: LLMProvider = LLMProvider.mock,
    model: str = DEFAULT_DEEPSEEK_MODEL,
    start_services: bool = False,
) -> RunManager:
    store = _open_store(database)
    config = RuntimeConfig(database_path=str(database))

    def executor_factory() -> GraphWorkflowExecutor:
        registry = create_default_tool_registry()
        return GraphWorkflowExecutor(
            llm=create_llm(provider, model=model),
            tool_registry=registry,
            allowed_permissions={"public"},
            max_risk_level=5,
            run_control_store=store,
            runtime_config=config,
        )

    return RunManager(
        executor_factory=executor_factory,
        store=store,
        config=config,
        start_services=start_services,
    )


def _request_cancel(
    database: Path,
    run_id: str,
    *,
    target_node_id: str | None,
    reason: str,
) -> None:
    manager = _create_run_manager(database)
    try:
        request_id = manager.request_cancel(
            run_id, target_node_id=target_node_id, reason=reason
        )
        console.print(Panel(request_id, title="Cancellation Request"))
    except RunManagerError as exc:
        _print_error(exc)
        raise typer.Exit(code=1) from exc
    finally:
        manager.shutdown()


def _workflow_to_yaml(workflow: Any) -> str:
    """将 Workflow 对象转成 YAML 字符串。"""

    import yaml

    data = workflow.model_dump(mode="python", by_alias=True)
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


def _build_flow_preview(workflow: Any) -> str:
    """Build a simple flow preview from the workflow nodes."""

    return " -> ".join(node.id for node in workflow.nodes)


def _configure_standard_streams() -> None:
    """在支持 reconfigure 的终端中使用 UTF-8，避免真实模型 Unicode 输出失败。"""

    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if callable(reconfigure):
            reconfigure(encoding="utf-8")
