from __future__ import annotations

from typing import Any, Protocol


class RunControlStore(Protocol):
    def migrate(self) -> None: ...

    def create_run(self, record: dict[str, Any]) -> None: ...

    def get_run(self, run_id: str) -> dict[str, Any] | None: ...

    def compare_and_set_run(
        self,
        run_id: str,
        *,
        expected_version: int,
        from_statuses: set[str],
        to_status: str,
        updates: dict[str, Any] | None = None,
    ) -> bool: ...

    def create_execution(self, record: dict[str, Any]) -> None: ...

    def create_invocation(self, record: dict[str, Any]) -> None: ...

    def compare_and_set_invocation(
        self,
        invocation_id: str,
        *,
        expected_version: int,
        from_statuses: set[str],
        to_status: str,
        updates: dict[str, Any] | None = None,
    ) -> bool: ...

    def append_control_event(self, event: dict[str, Any]) -> bool: ...
