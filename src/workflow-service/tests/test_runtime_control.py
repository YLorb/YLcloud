from __future__ import annotations

from datetime import datetime, timedelta, timezone
from uuid import uuid4

from mini_agent_flow.engine.deadline import CancellationToken, DeadlineController, DeadlineExceededError
from mini_agent_flow.engine.poller import DeadlinePoller
from mini_agent_flow.engine.loop_controller import LoopController, LoopReturnEvent
from mini_agent_flow.engine.runtime_config import RuntimeConfigLoader
from mini_agent_flow.persistence.sqlite_store import SQLiteRunControlStore


def iso(value: datetime) -> str:
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def test_runtime_config_precedence_cli_env_yaml_defaults(tmp_path) -> None:
    path = tmp_path / "runtime.yaml"
    path.write_text("runtime:\n  max_concurrent_runs: 2\n  poll_interval_seconds: 1\n", encoding="utf-8")

    config = RuntimeConfigLoader().load(
        yaml_path=path,
        environ={
            "MINI_AGENT_FLOW_MAX_CONCURRENT_RUNS": "3",
            "MINI_AGENT_FLOW_HEARTBEAT_INTERVAL_SECONDS": "4",
        },
        cli_overrides={"max_concurrent_runs": 5},
    )

    assert config.max_concurrent_runs == 5
    assert config.poll_interval_seconds == 1
    assert config.heartbeat_interval_seconds == 4
    assert config.lease_seconds == 6


def test_deadline_and_cancellation_token() -> None:
    deadline = DeadlineController().create(
        global_remaining_seconds=10,
        node_timeout_seconds=2,
        runtime_hard_remaining_seconds=5,
    )
    token = CancellationToken()

    assert 0 < deadline.remaining_seconds() <= 2
    token.cancel("timeout")
    assert token.cancelled is True
    try:
        token.raise_if_cancelled()
    except DeadlineExceededError as exc:
        assert str(exc) == "timeout"
    else:
        raise AssertionError("cancelled token did not raise")


def test_deadline_poller_uses_lease_and_cas(tmp_path) -> None:
    store = SQLiteRunControlStore(tmp_path / "runs.db")
    store.migrate()
    now = datetime.now(timezone.utc)
    run_id = str(uuid4())
    execution_id = str(uuid4())
    invocation_id = str(uuid4())
    store.create_run(
        {
            "run_id": run_id,
            "workflow_name": "poll",
            "workflow_version": "2.0",
            "status": "running",
            "started_at_utc": iso(now),
            "deadline_at_utc": iso(now + timedelta(minutes=1)),
            "created_at_utc": iso(now),
            "updated_at_utc": iso(now),
        }
    )
    store.create_execution(
        {
            "execution_id": execution_id,
            "run_id": run_id,
            "epoch": 1,
            "status": "running",
            "owner_id": "runtime",
            "owner_lease_until_utc": iso(now + timedelta(seconds=6)),
            "started_at_utc": iso(now),
        }
    )
    store.create_invocation(
        {
            "invocation_id": invocation_id,
            "execution_id": execution_id,
            "node_id": "call",
            "iteration_path_json": "[]",
            "attempt": 1,
            "status": "running",
            "idempotent": 1,
            "idempotency_key": "key",
            "deadline_at_utc": iso(now - timedelta(seconds=1)),
            "created_at_utc": iso(now),
            "updated_at_utc": iso(now),
        }
    )

    result = DeadlinePoller(store, owner_id="poller-a").tick()
    second_owner = DeadlinePoller(store, owner_id="poller-b").tick()

    assert result.scanned == 1
    assert result.timed_out == 1
    assert second_owner.scanned == 0
    with store.connection() as connection:
        row = connection.execute(
            "SELECT status, safe_error_summary FROM node_invocations WHERE invocation_id=?",
            (invocation_id,),
        ).fetchone()
    assert row["status"] == "timed_out"
    assert row["safe_error_summary"] == "BUSINESS_DEADLINE"


def test_loop_controller_rejects_duplicate_and_late_return_events() -> None:
    controller = LoopController(run_id="run", loop_id="loop", item_count=2)
    controller.begin_iteration(0)
    event = LoopReturnEvent.create(
        run_id="run",
        loop_id="loop",
        loop_invocation_id=controller.loop_invocation_id,
        iteration=0,
        attempt=1,
        source_node_id="exit",
        source_edge_id="return",
        arrival_reason="BODY_COMPLETED",
        controller_version=controller.version,
    )

    assert controller.accept(event) == "NEXT_ITERATION"
    assert controller.accept(event) == "DUPLICATE_REJECTED"
    controller.begin_iteration(1)
    late_event = LoopReturnEvent.create(
        run_id="run",
        loop_id="loop",
        loop_invocation_id=controller.loop_invocation_id,
        iteration=0,
        attempt=2,
        source_node_id="exit",
        source_edge_id="late-return",
        arrival_reason="BODY_COMPLETED",
        controller_version=controller.version,
    )
    assert controller.accept(late_event) == "LATE_REJECTED"
