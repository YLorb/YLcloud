from __future__ import annotations

import hashlib
import json
import os
import threading
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterator, Sequence
from uuid import uuid4

import pymysql
from pymysql.connections import Connection
from pymysql.cursors import DictCursor

from mini_agent_flow.contracts.models import (
    WorkflowRunAccepted,
    WorkflowRunCreateRequest,
)
from mini_agent_flow.persistence.migrations import MigrationError
from mini_agent_flow.service.run_service import RequestMetadata, RunServiceError


@dataclass(frozen=True, slots=True)
class MySQLSettings:
    host: str
    port: int
    user: str
    password: str
    database: str = "ylcloud_workflow"
    connect_timeout_seconds: int = 5

    @classmethod
    def from_env(cls) -> "MySQLSettings":
        return cls(
            host=os.getenv("WORKFLOW_MYSQL_HOST", "mysql"),
            port=int(os.getenv("WORKFLOW_MYSQL_PORT", "3306")),
            user=os.getenv("WORKFLOW_MYSQL_USER", "workflow"),
            password=os.getenv("WORKFLOW_MYSQL_PASSWORD", ""),
            database=os.getenv("WORKFLOW_MYSQL_DATABASE", "ylcloud_workflow"),
            connect_timeout_seconds=int(
                os.getenv("WORKFLOW_MYSQL_CONNECT_TIMEOUT_SECONDS", "5")
            ),
        )


class MySQLMigrationRunner:
    """只执行向前 migration，并用 SHA-256 拒绝已应用脚本被篡改。"""

    def __init__(self, sql_directory: Path | None = None) -> None:
        self.sql_directory = sql_directory or Path(__file__).with_name("mysql_sql")

    def apply(self, connection: Connection) -> None:
        files = sorted(self.sql_directory.glob("[0-9][0-9][0-9][0-9]_*.sql"))
        if not files:
            raise MigrationError("no MySQL migration files were found")
        with connection.cursor() as cursor:
            cursor.execute(
                "CREATE TABLE IF NOT EXISTS schema_migrations ("
                "version INT PRIMARY KEY, name VARCHAR(255) NOT NULL, "
                "checksum CHAR(64) NOT NULL, applied_at DATETIME(6) NOT NULL) ENGINE=InnoDB"
            )
            cursor.execute(
                "SELECT version, name, checksum FROM schema_migrations ORDER BY version"
            )
            applied = {int(row["version"]): row for row in cursor.fetchall()}
        supported = {self._version(path) for path in files}
        unsupported = sorted(set(applied) - supported)
        if unsupported:
            raise MigrationError(
                f"database schema is newer than this code: {unsupported[-1]}"
            )
        for path in files:
            version = self._version(path)
            checksum = hashlib.sha256(path.read_bytes()).hexdigest()
            if version in applied:
                if applied[version]["checksum"] != checksum:
                    raise MigrationError(f"migration checksum mismatch: {path.name}")
                continue
            for statement in self._statements(path.read_text(encoding="utf-8")):
                with connection.cursor() as cursor:
                    cursor.execute(statement)
            with connection.cursor() as cursor:
                cursor.execute(
                    "INSERT INTO schema_migrations(version,name,checksum,applied_at) "
                    "VALUES(%s,%s,%s,UTC_TIMESTAMP(6))",
                    (version, path.name, checksum),
                )
            connection.commit()

    @staticmethod
    def _version(path: Path) -> int:
        return int(path.name.split("_", 1)[0])

    @staticmethod
    def _statements(script: str) -> list[str]:
        return [statement.strip() for statement in script.split(";") if statement.strip()]


class MySQLWorkflowStore:
    """Workflow 运行域 MySQL Adapter；所有跨表受理操作使用短事务。"""

    def __init__(self, settings: MySQLSettings) -> None:
        self.settings = settings
        self._migration_lock = threading.Lock()

    @contextmanager
    def connection(self) -> Iterator[Connection]:
        connection = pymysql.connect(
            host=self.settings.host,
            port=self.settings.port,
            user=self.settings.user,
            password=self.settings.password,
            database=self.settings.database,
            charset="utf8mb4",
            autocommit=False,
            connect_timeout=self.settings.connect_timeout_seconds,
            cursorclass=DictCursor,
        )
        try:
            yield connection
        finally:
            connection.close()

    def migrate(self) -> None:
        with self._migration_lock, self.connection() as connection:
            MySQLMigrationRunner().apply(connection)

    def ping(self) -> bool:
        try:
            with self.connection() as connection, connection.cursor() as cursor:
                cursor.execute("SELECT 1 AS healthy")
                return cursor.fetchone()["healthy"] == 1
        except pymysql.MySQLError:
            return False

    def accept_run(
        self, request: WorkflowRunCreateRequest, metadata: RequestMetadata
    ) -> WorkflowRunAccepted:
        request_json = request.model_dump_json(by_alias=True)
        request_hash = hashlib.sha256(request_json.encode("utf-8")).hexdigest()
        run_id = str(uuid4())
        execution_id = str(uuid4())
        now = datetime.now(timezone.utc)
        response = WorkflowRunAccepted(
            contract_version="1.0",
            run_id=run_id,
            execution_id=execution_id,
            execution_epoch=1,
            status="QUEUED",
            accepted_at=now,
        )
        response_json = response.model_dump_json(by_alias=True)
        try:
            with self.connection() as connection:
                try:
                    with connection.cursor() as cursor:
                        cursor.execute(
                            "INSERT INTO workflow_run("
                            "run_id,assistant_message_id,user_id,session_id,workflow_type,"
                            "workflow_version,request_context_hash,request_json,status,"
                            "current_execution_id,execution_epoch,created_at,updated_at) "
                            "VALUES(%s,%s,%s,%s,%s,%s,%s,%s,'QUEUED',%s,1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                            (
                                run_id,
                                request.assistant_message_id,
                                request.user_id,
                                request.session_id,
                                request.workflow_type,
                                request.workflow_version,
                                request.request_context_hash,
                                request_json,
                                execution_id,
                            ),
                        )
                        cursor.execute(
                            "INSERT INTO workflow_execution("
                            "execution_id,run_id,epoch,status,created_at,updated_at) "
                            "VALUES(%s,%s,1,'QUEUED',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                            (execution_id, run_id),
                        )
                        cursor.execute(
                            "INSERT INTO workflow_idempotency_record("
                            "idempotency_key,operation,request_hash,run_id,execution_id,"
                            "response_json,created_at,expires_at) "
                            "VALUES(%s,'CREATE_RUN',%s,%s,%s,%s,UTC_TIMESTAMP(6),"
                            "DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 24 HOUR))",
                            (
                                metadata.idempotency_key,
                                request_hash,
                                run_id,
                                execution_id,
                                response_json,
                            ),
                        )
                    connection.commit()
                    return response
                except Exception:
                    connection.rollback()
                    raise
        except pymysql.err.IntegrityError:
            return self._load_idempotent_acceptance(metadata.idempotency_key, request_hash)

    def _load_idempotent_acceptance(
        self, idempotency_key: str, request_hash: str
    ) -> WorkflowRunAccepted:
        with self.connection() as connection, connection.cursor() as cursor:
            cursor.execute(
                "SELECT request_hash,response_json FROM workflow_idempotency_record "
                "WHERE idempotency_key=%s AND operation='CREATE_RUN'",
                (idempotency_key,),
            )
            row = cursor.fetchone()
        if row is None or row["request_hash"] != request_hash:
            raise RunServiceError(
                "IDEMPOTENCY_CONFLICT",
                "idempotency key was reused with a different request",
                status_code=409,
            )
        payload = row["response_json"]
        if isinstance(payload, str):
            payload = json.loads(payload)
        return WorkflowRunAccepted.model_validate(payload)

    def get_run(self, run_id: str) -> dict[str, Any] | None:
        with self.connection() as connection, connection.cursor() as cursor:
            cursor.execute("SELECT * FROM workflow_run WHERE run_id=%s", (run_id,))
            return cursor.fetchone()

    def get_result(self, run_id: str) -> dict[str, Any] | None:
        with self.connection() as connection, connection.cursor() as cursor:
            cursor.execute(
                "SELECT result_json FROM workflow_result_temp WHERE run_id=%s AND expires_at>UTC_TIMESTAMP(6)",
                (run_id,),
            )
            row = cursor.fetchone()
        if row is None:
            return None
        payload = row["result_json"]
        return json.loads(payload) if isinstance(payload, str) else payload

    def put_result(
        self,
        run_id: str,
        execution_id: str,
        result: dict[str, Any],
        *,
        ttl_seconds: int = 86_400,
    ) -> str:
        """Persist a terminal result until Java confirms durable receipt."""
        if ttl_seconds < 1:
            raise ValueError("ttl_seconds must be positive")
        result_json = json.dumps(result, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
        result_hash = result.get("resultHash") or result.get("result_hash")
        if not isinstance(result_hash, str) or len(result_hash) != 64:
            raise ValueError("result must contain the contractual SHA-256 resultHash")
        with self.connection() as connection, connection.cursor() as cursor:
            cursor.execute(
                "INSERT INTO workflow_result_temp("
                "run_id,execution_id,result_hash,result_json,expires_at,created_at) "
                "VALUES(%s,%s,%s,%s,DATE_ADD(UTC_TIMESTAMP(6),INTERVAL %s SECOND),UTC_TIMESTAMP(6)) "
                "ON DUPLICATE KEY UPDATE execution_id=VALUES(execution_id),"
                "result_hash=VALUES(result_hash),result_json=VALUES(result_json),"
                "expires_at=VALUES(expires_at)",
                (run_id, execution_id, result_hash, result_json, ttl_seconds),
            )
            connection.commit()
        return result_hash

    def acknowledge_result(self, run_id: str, result_hash: str) -> bool:
        """Delete only the exact result Java acknowledged; duplicate ACK is harmless."""
        with self.connection() as connection, connection.cursor() as cursor:
            affected = cursor.execute(
                "DELETE FROM workflow_result_temp WHERE run_id=%s AND result_hash=%s",
                (run_id, result_hash),
            )
            connection.commit()
            return affected == 1

    def cancel_run(self, run_id: str) -> bool:
        with self.connection() as connection:
            try:
                with connection.cursor() as cursor:
                    affected = cursor.execute(
                        "UPDATE workflow_run SET status='CANCELLED',version=version+1,"
                        "owner_id=NULL,owner_lease_until=NULL,updated_at=UTC_TIMESTAMP(6) "
                        "WHERE run_id=%s AND status IN ('QUEUED','PLANNING','VALIDATING','RUNNING')",
                        (run_id,),
                    )
                    if affected == 1:
                        cursor.execute(
                            "UPDATE workflow_execution e JOIN workflow_run r "
                            "ON r.current_execution_id=e.execution_id "
                            "SET e.status='CANCELLED',e.version=e.version+1,"
                            "e.owner_id=NULL,e.owner_lease_until=NULL,e.finished_at=UTC_TIMESTAMP(6),"
                            "e.updated_at=UTC_TIMESTAMP(6) WHERE r.run_id=%s",
                            (run_id,),
                        )
                connection.commit()
                return affected == 1
            except Exception:
                connection.rollback()
                raise

    def retry_run(self, run_id: str) -> WorkflowRunAccepted:
        with self.connection() as connection:
            try:
                with connection.cursor() as cursor:
                    cursor.execute(
                        "SELECT execution_epoch FROM workflow_run WHERE run_id=%s FOR UPDATE",
                        (run_id,),
                    )
                    row = cursor.fetchone()
                    if row is None:
                        raise RunServiceError("NOT_FOUND", "workflow run not found", status_code=404)
                    epoch = int(row["execution_epoch"]) + 1
                    execution_id = str(uuid4())
                    cursor.execute(
                        "INSERT INTO workflow_execution(execution_id,run_id,epoch,status,created_at,updated_at) "
                        "VALUES(%s,%s,%s,'QUEUED',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                        (execution_id, run_id, epoch),
                    )
                    cursor.execute(
                        "UPDATE workflow_run SET current_execution_id=%s,execution_epoch=%s,status='QUEUED',"
                        "owner_id=NULL,owner_lease_until=NULL,version=version+1,updated_at=UTC_TIMESTAMP(6) "
                        "WHERE run_id=%s",
                        (execution_id, epoch, run_id),
                    )
                connection.commit()
            except Exception:
                connection.rollback()
                raise
        return WorkflowRunAccepted(
            contract_version="1.0",
            run_id=run_id,
            execution_id=execution_id,
            execution_epoch=epoch,
            status="QUEUED",
            accepted_at=datetime.now(timezone.utc),
        )

    def claim_recoverable_run(self, worker_id: str, lease_seconds: int) -> dict[str, Any] | None:
        with self.connection() as connection:
            try:
                with connection.cursor() as cursor:
                    cursor.execute(
                        "SELECT run_id FROM workflow_run WHERE "
                        "status='QUEUED' OR (status IN ('PLANNING','VALIDATING','RUNNING') "
                        "AND (owner_lease_until IS NULL OR owner_lease_until<UTC_TIMESTAMP(6))) "
                        "ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED"
                    )
                    row = cursor.fetchone()
                    if row is None:
                        connection.rollback()
                        return None
                    run_id = row["run_id"]
                    cursor.execute(
                        "UPDATE workflow_run SET status='PLANNING',owner_id=%s,"
                        "owner_lease_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL %s SECOND),"
                        "version=version+1,updated_at=UTC_TIMESTAMP(6) WHERE run_id=%s",
                        (worker_id, lease_seconds, run_id),
                    )
                    cursor.execute(
                        "UPDATE workflow_execution e JOIN workflow_run r "
                        "ON r.current_execution_id=e.execution_id "
                        "SET e.status='PLANNING',e.owner_id=%s,"
                        "e.owner_lease_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL %s SECOND),"
                        "e.version=e.version+1,e.started_at=COALESCE(e.started_at,UTC_TIMESTAMP(6)),"
                        "e.updated_at=UTC_TIMESTAMP(6) WHERE r.run_id=%s",
                        (worker_id, lease_seconds, run_id),
                    )
                    cursor.execute("SELECT * FROM workflow_run WHERE run_id=%s", (run_id,))
                    claimed = cursor.fetchone()
                connection.commit()
                return claimed
            except Exception:
                connection.rollback()
                raise

    def compare_and_set_status(
        self, run_id: str, expected_version: int, from_status: str, to_status: str
    ) -> bool:
        with self.connection() as connection:
            try:
                with connection.cursor() as cursor:
                    affected = cursor.execute(
                        "UPDATE workflow_run SET status=%s,version=version+1,updated_at=UTC_TIMESTAMP(6) "
                        "WHERE run_id=%s AND version=%s AND status=%s",
                        (to_status, run_id, expected_version, from_status),
                    )
                    if affected == 1:
                        cursor.execute(
                            "UPDATE workflow_execution e JOIN workflow_run r "
                            "ON r.current_execution_id=e.execution_id "
                            "SET e.status=%s,e.version=e.version+1,e.updated_at=UTC_TIMESTAMP(6) "
                            "WHERE r.run_id=%s AND e.status=%s",
                            (to_status, run_id, from_status),
                        )
                connection.commit()
                return affected == 1
            except Exception:
                connection.rollback()
                raise

    def append_events(self, events: Sequence[dict[str, Any]]) -> None:
        if not events:
            return
        rows = [
            (
                event.get("event_id", str(uuid4())),
                event["run_id"],
                event.get("execution_id"),
                event["event_type"],
                json.dumps(event.get("safe_payload", {}), ensure_ascii=False),
            )
            for event in events
        ]
        with self.connection() as connection, connection.cursor() as cursor:
            cursor.executemany(
                "INSERT INTO workflow_event(event_id,run_id,execution_id,event_type,safe_payload_json,created_at) "
                "VALUES(%s,%s,%s,%s,%s,UTC_TIMESTAMP(6))",
                rows,
            )
            connection.commit()

    def delete_expired_temporary_data(self) -> int:
        with self.connection() as connection, connection.cursor() as cursor:
            result_count = cursor.execute(
                "DELETE FROM workflow_result_temp WHERE expires_at<=UTC_TIMESTAMP(6)"
            )
            idempotency_count = cursor.execute(
                "DELETE FROM workflow_idempotency_record WHERE expires_at<=UTC_TIMESTAMP(6)"
            )
            connection.commit()
            return result_count + idempotency_count
