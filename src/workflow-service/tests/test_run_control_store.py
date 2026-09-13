from __future__ import annotations

from datetime import datetime, timedelta, timezone
from uuid import uuid4

from mini_agent_flow.persistence.cleanup import CleanupJob
from mini_agent_flow.persistence.sqlite_store import SQLiteRunControlStore
from mini_agent_flow.engine.graph_executor import GraphWorkflowExecutor
from mini_agent_flow.engine.graph_models import GraphWorkflow
from mini_agent_flow.llm.mock import MockLLM
from mini_agent_flow.tools.builtin import create_default_tool_registry


def utc(value: datetime) -> str:
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def create_records(store: SQLiteRunControlStore, *, status: str = "running") -> tuple[str, str, str]:
    now = datetime.now(timezone.utc)
    run_id = str(uuid4())
    execution_id = str(uuid4())
    invocation_id = str(uuid4())
    store.create_run(
        {
            "run_id": run_id,
            "workflow_name": "test",
            "workflow_version": "2.0",
            "status": status,
            "started_at_utc": utc(now),
            "deadline_at_utc": utc(now + timedelta(minutes=5)),
            "created_at_utc": utc(now),
            "updated_at_utc": utc(now),
        }
    )
    store.create_execution(
        {
            "execution_id": execution_id,
            "run_id": run_id,
            "epoch": 1,
            "status": "running",
            "owner_id": "owner",
            "owner_lease_until_utc": utc(now + timedelta(seconds=6)),
            "started_at_utc": utc(now),
        }
    )
    store.create_invocation(
        {
            "invocation_id": invocation_id,
            "execution_id": execution_id,
            "node_id": "node",
            "iteration_path_json": "[]",
            "attempt": 1,
            "status": "running",
            "idempotent": 1,
            "idempotency_key": "key",
            "deadline_at_utc": utc(now + timedelta(seconds=1)),
            "created_at_utc": utc(now),
            "updated_at_utc": utc(now),
        }
    )
    return run_id, execution_id, invocation_id


def test_migration_configures_wal_full_and_is_idempotent(tmp_path) -> None:
    store = SQLiteRunControlStore(tmp_path / "runs.db")

    store.migrate()
    store.migrate()

    with store.connection() as connection:
        assert connection.execute("PRAGMA journal_mode").fetchone()[0] == "wal"
        assert connection.execute("PRAGMA synchronous").fetchone()[0] == 2
        versions = connection.execute(
            "SELECT version FROM schema_migrations ORDER BY version"
        ).fetchall()
    assert [row[0] for row in versions] == [1, 2]


def test_invocation_cas_accepts_one_winner_and_rejects_stale_update(tmp_path) -> None:
    store = SQLiteRunControlStore(tmp_path / "runs.db")
    store.migrate()
    _, _, invocation_id = create_records(store)

    first = store.compare_and_set_invocation(
        invocation_id,
        expected_version=0,
        from_statuses={"running"},
        to_status="success",
    )
    stale = store.compare_and_set_invocation(
        invocation_id,
        expected_version=0,
        from_statuses={"running"},
        to_status="timed_out",
    )

    assert first is True
    assert stale is False


def test_control_event_deduplication_and_poller_lease(tmp_path) -> None:
    store = SQLiteRunControlStore(tmp_path / "runs.db")
    store.migrate()
    run_id, execution_id, _ = create_records(store)
    now = store.database_utc_now()
    event = {
        "event_id": str(uuid4()),
        "run_id": run_id,
        "execution_id": execution_id,
        "invocation_id": None,
        "event_type": "test",
        "deduplication_key": "same-event",
        "safe_payload_json": {"ok": True},
        "occurred_at_utc": now,
    }

    assert store.append_control_event(event) is True
    assert store.append_control_event({**event, "event_id": str(uuid4())}) is False
    assert store.acquire_poller_lease(
        lease_name="deadline",
        owner_id="owner-a",
        lease_until_utc="9999-01-01T00:00:00Z",
        now_utc=now,
    ) is True
    assert store.acquire_poller_lease(
        lease_name="deadline",
        owner_id="owner-b",
        lease_until_utc="9999-01-01T00:00:00Z",
        now_utc=now,
    ) is False


def test_cleanup_job_dry_run_and_cascade(tmp_path) -> None:
    store = SQLiteRunControlStore(tmp_path / "runs.db")
    store.migrate()
    run_id, _, _ = create_records(store, status="completed")
    old = utc(datetime.now(timezone.utc) - timedelta(days=8))
    run = store.get_run(run_id)
    assert run is not None
    assert store.compare_and_set_run(
        run_id,
        expected_version=0,
        from_statuses={"completed"},
        to_status="completed",
        updates={"finished_at_utc": old},
    ) is True
    job = CleanupJob(store)

    preview = job.run(dry_run=True)
    deleted = job.run(dry_run=False)

    assert preview.run_ids == (run_id,)
    assert deleted.run_ids == (run_id,)
    assert store.get_run(run_id) is None


def test_graph_executor_persists_run_invocations_trace_context_and_outputs(tmp_path) -> None:
    store = SQLiteRunControlStore(tmp_path / "runs.db")
    store.migrate()
    registry = create_default_tool_registry()
    workflow = GraphWorkflow.model_validate(
        {
            "version": "2.0",
            "name": "persisted_graph",
            "outputs": ["value"],
            "nodes": [
                {"id": "start", "type": "start"},
                {"id": "call", "type": "tool", "tool": "echo", "input": "ok", "output": "value", "publish": True},
                {"id": "end", "type": "end"},
            ],
            "edges": [
                {"from": "start", "to": "call"},
                {"from": "call", "to": "end"},
            ],
        }
    )

    result = GraphWorkflowExecutor(
        llm=MockLLM(),
        tool_registry=registry,
        run_control_store=store,
    ).run(workflow)

    persisted = store.get_run(result.run_id or "")
    assert persisted is not None
    assert persisted["status"] == "completed"
    with store.connection() as connection:
        invocation_count = connection.execute(
            "SELECT COUNT(*) FROM node_invocations WHERE execution_id=?",
            (result.execution_id,),
        ).fetchone()[0]
        trace_count = connection.execute(
            "SELECT COUNT(*) FROM trace_events WHERE execution_id=?",
            (result.execution_id,),
        ).fetchone()[0]
        snapshot_count = connection.execute(
            "SELECT COUNT(*) FROM context_snapshots WHERE execution_id=?",
            (result.execution_id,),
        ).fetchone()[0]
        output_count = connection.execute(
            "SELECT COUNT(*) FROM node_outputs WHERE execution_id=?",
            (result.execution_id,),
        ).fetchone()[0]
    assert invocation_count == 3
    assert trace_count == 3
    assert snapshot_count == 3
    assert output_count == 3
