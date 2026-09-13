from __future__ import annotations

import os
from dataclasses import asdict, dataclass, fields
from pathlib import Path
from typing import Any, Mapping

import yaml


@dataclass(frozen=True)
class RuntimeConfig:
    database_path: str = ".mini-agent-flow/runs.db"
    max_concurrent_runs: int = 4
    default_node_timeout_seconds: float = 60
    max_duration_seconds: float = 1800
    poll_interval_seconds: float = 0.5
    heartbeat_interval_seconds: float = 2
    lease_seconds: float = 6
    cancel_grace_seconds: float = 2
    cleanup_timeout_seconds: float = 30
    sqlite_busy_timeout_seconds: float = 5
    sqlite_store_retries: int = 3
    cleanup_interval_seconds: float = 3600
    success_retention_days: int = 7
    failure_retention_days: int = 30
    isolated_worker_limit: int = 4
    max_ipc_bytes: int = 1_048_576
    max_debug_output_bytes: int = 65_536
    debug_worker_output: bool = False
    isolation_risk_threshold: int = 8
    max_nodes: int = 200
    max_edges: int = 500
    max_steps: int = 1000
    max_loop_iterations: int = 100
    max_llm_calls: int = 100
    max_tool_calls: int = 200
    max_context_bytes: int = 10 * 1024 * 1024
    max_trace_bytes: int = 20 * 1024 * 1024

    def __post_init__(self) -> None:
        for item in fields(self):
            value = getattr(self, item.name)
            if isinstance(value, bool):
                continue
            if isinstance(value, (int, float)) and value <= 0:
                raise ValueError(f"runtime config must be positive: {item.name}")


class RuntimeConfigLoader:
    ENV_PREFIX = "MINI_AGENT_FLOW_"

    def load(
        self,
        *,
        yaml_path: str | Path | None = None,
        environ: Mapping[str, str] | None = None,
        cli_overrides: Mapping[str, Any] | None = None,
    ) -> RuntimeConfig:
        values = asdict(RuntimeConfig())
        if yaml_path is not None:
            loaded = yaml.safe_load(Path(yaml_path).read_text(encoding="utf-8")) or {}
            if not isinstance(loaded, dict):
                raise ValueError("runtime YAML config must be an object")
            runtime_values = loaded.get("runtime", loaded)
            if not isinstance(runtime_values, dict):
                raise ValueError("runtime YAML section must be an object")
            self._merge(values, runtime_values)
        environment = environ if environ is not None else os.environ
        field_by_env = {
            self.ENV_PREFIX + name.upper(): name for name in values
        }
        for env_name, field_name in field_by_env.items():
            if env_name in environment:
                values[field_name] = self._coerce(environment[env_name], values[field_name])
        if cli_overrides:
            self._merge(values, {key: value for key, value in cli_overrides.items() if value is not None})
        return RuntimeConfig(**values)

    def _merge(self, target: dict[str, Any], source: Mapping[str, Any]) -> None:
        unknown = set(source) - set(target)
        if unknown:
            raise ValueError(f"unknown runtime config fields: {', '.join(sorted(unknown))}")
        target.update(source)

    def _coerce(self, raw: str, default: Any) -> Any:
        if isinstance(default, bool):
            normalized = raw.strip().lower()
            if normalized in {"1", "true", "yes", "on"}:
                return True
            if normalized in {"0", "false", "no", "off"}:
                return False
            raise ValueError(f"invalid boolean environment value: {raw}")
        if isinstance(default, int):
            return int(raw)
        if isinstance(default, float):
            return float(raw)
        return raw
