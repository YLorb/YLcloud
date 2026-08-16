from __future__ import annotations

import json
import time
from datetime import datetime, timedelta, timezone

import pytest
from typer.testing import CliRunner

from mini_agent_flow.cli import app
from mini_agent_flow.engine.executor import WorkflowExecutionError
from mini_agent_flow.engine.graph_executor import GraphWorkflowExecutor
from mini_agent_flow.engine.graph_models import GraphWorkflow
from mini_agent_flow.engine.run_manager import RunManager
from mini_agent_flow.engine.runtime_config import RuntimeConfig
from mini_agent_flow.llm.mock import MockLLM
from mini_agent_flow.persistence.migrations import MigrationError
from mini_agent_flow.persistence.sqlite_store import SQLiteRunControlStore
from mini_agent_flow.tools.registry import ToolRegistry
from mini_agent_flow.tools.spec import ImportableToolEntrypoint, ToolSpec


def _simple_workflow(name: str = "managed") -> GraphWorkflow:
    return GraphWorkflow.model_validate(
        {
            "version": "2.0",
            "name": name,
            "nodes": [
                {"id": "start", "type": "start"},
                {"id": "end", "type": "end"},
            ],
            "edges": [{"from": "start", "to": "end"}],
        }
    )


def _manager(store: SQLiteRunControlStore, registry: ToolRegistry | None = None, **overrides):
    registry = registry or ToolRegistry()
    config = RuntimeConfig(database_path=str(store.database_path), **overrides)

    def factory() -> GraphWorkflowExecutor:
        return GraphWorkflowExecutor(
            llm=MockLLM(),
            tool_registry=registry,
            run_control_store=store,
            runtime_config=config,
        )

    return RunManager(executor_factory=factory, store=store, config=config)


def test_budget_guard_rejects_definition_and_runtime_calls() -> None:
    registry = ToolRegistry()
    registry.register("echo", lambda value: value, spec=ToolSpec(name="echo", idempotent=True))
    workflow = GraphWorkflow.model_validate(
        {
            "version": "2.0",
            "name": "budget",
            "nodes": [
                {"id": "start", "type": "start"},
                {"id": "a", "type": "tool", "tool": "echo", "input": 1, "output": "a"},
                {"id": "b", "type": "tool", "tool": "echo", "input": 2, "output": "b"},
                {"id": "end", "type": "end"},
            ],
            "edges": [
                {"from": "start", "to": "a"},
                {"from": "a", "to": "b"},
                {"from": "b", "to": "end"},
            ],
        }
    )
    with pytest.raises(WorkflowExecutionError, match="nodes budget"):
        GraphWorkflowExecutor(
            MockLLM(), registry, runtime_config=RuntimeConfig(max_nodes=3)
        ).run(workflow)
    with pytest.raises(WorkflowExecutionError, match="Tool calls budget"):
        GraphWorkflowExecutor(
            MockLLM(), registry, runtime_config=RuntimeConfig(max_tool_calls=1)
        ).run(workflow)


def test_run_manager_recovers_safe_run_in_new_epoch(tmp_path) -> None:
    store = SQLiteRunControlStore(tmp_path / "runs.db")
    store.migrate()
    workflow = _simple_workflow("recovery")
    now = datetime.now(timezone.utc)
    old = (now - timedelta(seconds=30)).isoformat().replace("+00:00", "Z")
    later = (now + timedelta(hours=1)).isoformat().replace("+00:00", "Z")
    store.create_run(
        {
            "run_id": "run-1",
            "workflow_name": workflow.name,
            "workflow_version": workflow.version,
            "workflow_json": json.dumps(workflow.model_dump(mode="json", by_alias=True)),
            "status": "running",
            "started_at_utc": old,
            "deadline_at_utc": later,
            "created_at_utc": old,
            "updated_at_utc": old,
        }
    )
    store.create_execution(
        {
            "execution_id": "execution-1",
            "run_id": "run-1",
            "epoch": 1,
            "status": "running",
            "owner_id": "dead-owner",
            "owner_lease_until_utc": old,
            "started_at_utc": old,
        }
    )
    manager = _manager(store)
    try:
        result = manager.recover_expired()
    finally:
        manager.shutdown()

    assert result.restarted == 1
    assert store.get_run("run-1")["status"] == "completed"
    assert [row["status"] for row in store.list_executions("run-1")] == [
        "abandoned",
        "completed",
    ]


def test_cancel_request_routes_to_restricted_cleanup(tmp_path) -> None:
    store = SQLiteRunControlStore(tmp_path / "runs.db")
    store.migrate()
    registry = ToolRegistry()
    registry.register(
        "wait",
        lambda value: (time.sleep(float(value)), "done")[1],
        spec=ToolSpec(name="wait", idempotent=True),
    )
    registry.register(
        "business",
        lambda value: value,
        spec=ToolSpec(name="business", idempotent=True),
    )
    registry.register(
        "cleanup",
        lambda value, secrets, idempotency_key: "cleaned",
        spec=ToolSpec(
            name="cleanup",
            idempotent=True,
            cleanup_allowed=True,
            side_effecting=True,
            accepts_idempotency_key=True,
        ),
    )
    workflow = GraphWorkflow.model_validate(
        {
            "version": "2.0",
            "name": "cancel_cleanup",
            "nodes": [
                {"id": "start", "type": "start"},
                {"id": "wait", "type": "tool", "tool": "wait", "input": 0.2, "output": "waited"},
                {"id": "business", "type": "tool", "tool": "business", "input": "x", "output": "value"},
                {"id": "cleanup", "type": "tool", "tool": "cleanup", "input": "x", "output": "cleaned", "timeout_seconds": 1},
                {"id": "end", "type": "end"},
            ],
            "edges": [
                {"from": "start", "to": "wait"},
                {"from": "wait", "to": "business"},
                {"from": "business", "to": "end"},
                {"from": "business", "to": "cleanup", "kind": "cancel"},
                {"from": "cleanup", "to": "end"},
            ],
        }
    )
    manager = _manager(store, registry)
    future = manager.submit(workflow)
    try:
        run_id = None
        for _ in range(100):
            rows = store.list_runs()
            if rows and rows[0]["status"] == "running" and rows[0]["current_execution_id"]:
                run_id = rows[0]["run_id"]
                break
            time.sleep(0.01)
        assert run_id is not None
        manager.request_cancel(run_id, target_node_id="business")
        result = future.result(timeout=5)
    finally:
        manager.shutdown()

    assert result.status == "cancelled"
    assert "cleanup" in result.executed_nodes
    assert "business" not in result.executed_nodes
    assert store.get_run(run_id)["status"] == "cancelled"


def test_running_isolated_tool_consumes_cancel_and_terminates(tmp_path) -> None:
    store = SQLiteRunControlStore(tmp_path / "active-cancel.db")
    store.migrate()
    registry = ToolRegistry()
    registry.register(
        "isolated_sleep",
        lambda value: value,
        spec=ToolSpec(
            name="isolated_sleep",
            execution_mode="isolated_process",
            loader=ImportableToolEntrypoint(
                module="mini_agent_flow.tools.worker_test_tools",
                function="isolated_sleep",
            ),
            idempotent=True,
        ),
    )
    registry.register(
        "cleanup",
        lambda value, secrets, idempotency_key: "cleaned",
        spec=ToolSpec(
            name="cleanup",
            idempotent=True,
            cleanup_allowed=True,
            side_effecting=True,
            accepts_idempotency_key=True,
        ),
    )
    workflow = GraphWorkflow.model_validate(
        {
            "version": "2.0",
            "name": "active_cancel",
            "nodes": [
                {"id": "start", "type": "start"},
                {"id": "wait", "type": "tool", "tool": "isolated_sleep", "input": 10, "output": "waited", "timeout_seconds": 20},
                {"id": "cleanup", "type": "tool", "tool": "cleanup", "input": "x", "output": "cleaned", "timeout_seconds": 1},
                {"id": "end", "type": "end"},
            ],
            "edges": [
                {"from": "start", "to": "wait"},
                {"from": "wait", "to": "end"},
                {"from": "wait", "to": "cleanup", "kind": "cancel"},
                {"from": "cleanup", "to": "end"},
            ],
        }
    )
    manager = _manager(store, registry)
    started = time.perf_counter()
    future = manager.submit(workflow)
    try:
        run_id = None
        for _ in range(300):
            rows = store.list_runs()
            if rows and rows[0]["current_execution_id"]:
                invocations = store.list_invocations(rows[0]["current_execution_id"])
                if any(item["node_id"] == "wait" and item["status"] == "running" for item in invocations):
                    run_id = rows[0]["run_id"]
                    break
            time.sleep(0.01)
        assert run_id is not None
        manager.request_cancel(run_id, target_node_id="wait")
        result = future.result(timeout=5)
    finally:
        manager.shutdown()

    assert result.status == "cancelled"
    assert "cleanup" in result.executed_nodes
    assert time.perf_counter() - started < 5
    wait_event = next(event for event in result.trace if event["node_id"] == "wait")
    assert wait_event["status"] == "cancelled"


def test_migration_refuses_unmanaged_nonempty_database(tmp_path) -> None:
    store = SQLiteRunControlStore(tmp_path / "unmanaged.db")
    with store.connection() as connection:
        connection.execute("CREATE TABLE legacy_data(id INTEGER PRIMARY KEY)")

    with pytest.raises(MigrationError, match="non-empty"):
        store.migrate()


def test_runs_cli_lists_shows_and_dry_run_cleans(tmp_path) -> None:
    database = tmp_path / "cli-runs.db"
    runner = CliRunner()
    run_result = runner.invoke(
        app,
        [
            "run",
            "examples/level1_manual_workflow.yaml",
            "--database",
            str(database),
        ],
    )
    assert run_result.exit_code == 0, run_result.output
    store = SQLiteRunControlStore(database)
    run_id = store.list_runs()[0]["run_id"]

    listed = runner.invoke(app, ["runs", "list", "--database", str(database)])
    shown = runner.invoke(
        app, ["runs", "show", run_id, "--database", str(database)]
    )
    cleaned = runner.invoke(
        app, ["runs", "cleanup", "--database", str(database)]
    )

    assert listed.exit_code == 0 and "Workflow Runs" in listed.output
    assert shown.exit_code == 0 and "workflow_json" not in shown.output
    assert cleaned.exit_code == 0 and "dry_run" in cleaned.output
