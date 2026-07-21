from __future__ import annotations

import json
import os
import shutil
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import pymysql
import pytest
from fastapi.testclient import TestClient

from mini_agent_flow.contracts.models import WorkflowRunCreateRequest
from mini_agent_flow.persistence.migrations import MigrationError
from mini_agent_flow.persistence.mysql_store import (
    MySQLMigrationRunner,
    MySQLSettings,
    MySQLWorkflowStore,
)
from mini_agent_flow.service.api import create_app
from mini_agent_flow.service.mysql_run_service import MySQLRunApplicationService
from mini_agent_flow.service.run_service import RequestMetadata, RunServiceError
from mini_agent_flow.security.service_jwt import AllowAllServiceAuthenticator


pytestmark = pytest.mark.skipif(
    os.getenv("WORKFLOW_TEST_MYSQL") != "1",
    reason="requires the disposable TASK-003 MySQL container",
)

ROOT = Path(__file__).resolve().parents[1]


def _settings() -> MySQLSettings:
    return MySQLSettings(
        host=os.getenv("WORKFLOW_TEST_MYSQL_HOST", "127.0.0.1"),
        port=int(os.getenv("WORKFLOW_TEST_MYSQL_PORT", "13307")),
        user=os.getenv("WORKFLOW_TEST_MYSQL_USER", "root"),
        password=os.getenv("WORKFLOW_TEST_MYSQL_PASSWORD", "task003-root"),
        database=os.getenv("WORKFLOW_TEST_MYSQL_DATABASE", "ylcloud_workflow_task003"),
    )


@pytest.fixture(scope="session", autouse=True)
def mysql_database() -> None:
    settings = _settings()
    connection = pymysql.connect(
        host=settings.host,
        port=settings.port,
        user=settings.user,
        password=settings.password,
        autocommit=True,
    )
    try:
        with connection.cursor() as cursor:
            cursor.execute(
                f"CREATE DATABASE IF NOT EXISTS `{settings.database}` "
                "CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"
            )
    finally:
        connection.close()


@pytest.fixture()
def store(mysql_database: None) -> MySQLWorkflowStore:
    adapter = MySQLWorkflowStore(_settings())
    adapter.migrate()
    with adapter.connection() as connection, connection.cursor() as cursor:
        cursor.execute("DELETE FROM workflow_run")
        connection.commit()
    return adapter


def _request(*, question: str = "测试可靠受理") -> WorkflowRunCreateRequest:
    payload = json.loads(
        (ROOT / "contract-examples" / "workflow-run-create.valid.json").read_text(
            encoding="utf-8"
        )
    )
    payload["question"] = question
    return WorkflowRunCreateRequest.model_validate(payload)


def _metadata(key: str = "assistant-302") -> RequestMetadata:
    return RequestMetadata(key, "request-1", None)


def _count(store: MySQLWorkflowStore, table: str) -> int:
    assert table in {"workflow_run", "workflow_execution", "workflow_event"}
    with store.connection() as connection, connection.cursor() as cursor:
        cursor.execute(f"SELECT COUNT(*) AS total FROM {table}")
        return int(cursor.fetchone()["total"])


def test_migration_is_repeatable_and_rejects_changed_checksum(
    store: MySQLWorkflowStore, tmp_path: Path
) -> None:
    store.migrate()
    copied = tmp_path / "mysql_sql"
    shutil.copytree(ROOT / "mini_agent_flow" / "persistence" / "mysql_sql", copied)
    migration = copied / "0001_workflow_runtime.sql"
    migration.write_text(migration.read_text(encoding="utf-8") + "\n-- changed\n", encoding="utf-8")
    with store.connection() as connection, pytest.raises(MigrationError, match="checksum"):
        MySQLMigrationRunner(copied).apply(connection)


def test_acceptance_is_atomic_idempotent_and_conflict_safe(store: MySQLWorkflowStore) -> None:
    first = store.accept_run(_request(), _metadata())
    replay = store.accept_run(_request(), _metadata())
    assert replay.run_id == first.run_id
    assert replay.execution_id == first.execution_id
    assert _count(store, "workflow_run") == 1
    assert _count(store, "workflow_execution") == 1

    with pytest.raises(RunServiceError) as conflict:
        store.accept_run(_request(question="different"), _metadata())
    assert conflict.value.code == "IDEMPOTENCY_CONFLICT"
    assert _count(store, "workflow_run") == 1
    assert _count(store, "workflow_execution") == 1


def test_api_returns_202_only_after_committed_rows_are_visible(store: MySQLWorkflowStore) -> None:
    service = MySQLRunApplicationService(store)
    payload = _request().model_dump(by_alias=True, mode="json")
    with TestClient(create_app(service, authenticator=AllowAllServiceAuthenticator())) as client:
        response = client.post(
            "/internal/v1/workflow-runs",
            json=payload,
            headers={"Idempotency-Key": "api-302", "X-Request-Id": "request-api"},
        )
        assert response.status_code == 202
        run_id = response.json()["runId"]
        with store.connection() as connection, connection.cursor() as cursor:
            cursor.execute(
                "SELECT r.status AS run_status,e.status AS execution_status "
                "FROM workflow_run r JOIN workflow_execution e "
                "ON e.execution_id=r.current_execution_id WHERE r.run_id=%s",
                (run_id,),
            )
            assert cursor.fetchone() == {
                "run_status": "QUEUED",
                "execution_status": "QUEUED",
            }


def test_cas_has_one_winner_and_expired_lease_is_recovered_after_restart(
    store: MySQLWorkflowStore,
) -> None:
    accepted = store.accept_run(_request(), _metadata("cas-run"))
    claimed = store.claim_recoverable_run("worker-a", 30)
    assert claimed is not None
    assert claimed["run_id"] == str(accepted.run_id)
    version = int(claimed["version"])

    with ThreadPoolExecutor(max_workers=2) as pool:
        outcomes = list(
            pool.map(
                lambda _: store.compare_and_set_status(
                    str(accepted.run_id), version, "PLANNING", "VALIDATING"
                ),
                range(2),
            )
        )
    assert sorted(outcomes) == [False, True]
    with store.connection() as connection, connection.cursor() as cursor:
        cursor.execute(
            "SELECT r.status AS run_status,e.status AS execution_status "
            "FROM workflow_run r JOIN workflow_execution e "
            "ON r.current_execution_id=e.execution_id WHERE r.run_id=%s",
            (str(accepted.run_id),),
        )
        assert cursor.fetchone() == {
            "run_status": "VALIDATING",
            "execution_status": "VALIDATING",
        }

    with store.connection() as connection, connection.cursor() as cursor:
        cursor.execute(
            "UPDATE workflow_run SET status='RUNNING',owner_lease_until=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) "
            "WHERE run_id=%s",
            (str(accepted.run_id),),
        )
        connection.commit()

    restarted_store = MySQLWorkflowStore(_settings())
    recovered = restarted_store.claim_recoverable_run("worker-after-restart", 30)
    assert recovered is not None
    assert recovered["run_id"] == str(accepted.run_id)
    assert recovered["owner_id"] == "worker-after-restart"


def test_batch_events_and_temporary_result_ack(store: MySQLWorkflowStore) -> None:
    accepted = store.accept_run(_request(), _metadata("result-run"))
    run_id = str(accepted.run_id)
    execution_id = str(accepted.execution_id)
    store.append_events(
        [
            {"run_id": run_id, "execution_id": execution_id, "event_type": "QUEUED"},
            {"run_id": run_id, "execution_id": execution_id, "event_type": "PLANNING"},
        ]
    )
    assert _count(store, "workflow_event") == 2

    result = json.loads(
        (ROOT / "contract-examples" / "workflow-result.valid.json").read_text(
            encoding="utf-8"
        )
    )
    result["runId"] = run_id
    result["executionId"] = execution_id
    result_hash = store.put_result(run_id, execution_id, result, ttl_seconds=60)
    assert result_hash == result["resultHash"]
    assert store.get_result(run_id) == result
    assert store.acknowledge_result(run_id, "0" * 64) is False
    assert store.acknowledge_result(run_id, result_hash) is True
    assert store.acknowledge_result(run_id, result_hash) is False
    assert store.get_result(run_id) is None


def test_cancel_updates_run_and_current_execution_atomically(store: MySQLWorkflowStore) -> None:
    accepted = store.accept_run(_request(), _metadata("cancel-run"))
    assert store.cancel_run(str(accepted.run_id)) is True
    assert store.cancel_run(str(accepted.run_id)) is False
    with store.connection() as connection, connection.cursor() as cursor:
        cursor.execute(
            "SELECT r.status AS run_status,e.status AS execution_status,e.finished_at "
            "FROM workflow_run r JOIN workflow_execution e "
            "ON r.current_execution_id=e.execution_id WHERE r.run_id=%s",
            (str(accepted.run_id),),
        )
        row = cursor.fetchone()
    assert row["run_status"] == "CANCELLED"
    assert row["execution_status"] == "CANCELLED"
    assert row["finished_at"] is not None
