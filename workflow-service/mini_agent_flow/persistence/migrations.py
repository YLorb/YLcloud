from __future__ import annotations

import hashlib
import sqlite3
from pathlib import Path


class MigrationError(RuntimeError):
    """数据库 Schema 版本或 checksum 不可安全接受。"""


class MigrationRunner:
    def __init__(self, sql_directory: Path | None = None) -> None:
        self.sql_directory = sql_directory or Path(__file__).with_name("sql")

    def apply(self, connection: sqlite3.Connection) -> None:
        files = sorted(self.sql_directory.glob("[0-9][0-9][0-9][0-9]_*.sql"))
        if not files:
            raise MigrationError("no SQLite migration files were found")
        has_table = connection.execute(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name='schema_migrations'"
        ).fetchone()
        if not has_table:
            existing_tables = {
                str(row["name"])
                for row in connection.execute(
                    "SELECT name FROM sqlite_master WHERE type='table' "
                    "AND name NOT LIKE 'sqlite_%'"
                ).fetchall()
            }
            if existing_tables:
                raise MigrationError(
                    "refusing to migrate a non-empty database without schema_migrations"
                )
            self._apply_first(connection, files[0])
            files = files[1:]
        applied = {
            int(row["version"]): row
            for row in connection.execute(
                "SELECT version, name, checksum FROM schema_migrations ORDER BY version"
            ).fetchall()
        }
        supported = {self._version(path) for path in self.sql_directory.glob("*.sql")}
        unsupported = sorted(set(applied) - supported)
        if unsupported:
            raise MigrationError(
                f"database schema is newer than this code: {unsupported[-1]}"
            )
        for path in sorted(self.sql_directory.glob("[0-9][0-9][0-9][0-9]_*.sql")):
            version = self._version(path)
            checksum = self._checksum(path)
            if version in applied:
                if applied[version]["checksum"] != checksum:
                    raise MigrationError(f"migration checksum mismatch: {path.name}")
                continue
            script = path.read_text(encoding="utf-8")
            connection.executescript(
                "BEGIN IMMEDIATE;\n"
                + script
                + "\nINSERT INTO schema_migrations(version, name, checksum, applied_at_utc) "
                + f"VALUES ({version}, '{path.name}', '{checksum}', "
                + "strftime('%Y-%m-%dT%H:%M:%fZ','now'));\nCOMMIT;"
            )

    def _apply_first(self, connection: sqlite3.Connection, path: Path) -> None:
        version = self._version(path)
        checksum = self._checksum(path)
        script = path.read_text(encoding="utf-8")
        connection.executescript(
            "BEGIN IMMEDIATE;\n"
            + script
            + "\nINSERT INTO schema_migrations(version, name, checksum, applied_at_utc) "
            + f"VALUES ({version}, '{path.name}', '{checksum}', "
            + "strftime('%Y-%m-%dT%H:%M:%fZ','now'));\nCOMMIT;"
        )

    def _version(self, path: Path) -> int:
        return int(path.name.split("_", 1)[0])

    def _checksum(self, path: Path) -> str:
        return hashlib.sha256(path.read_bytes()).hexdigest()
