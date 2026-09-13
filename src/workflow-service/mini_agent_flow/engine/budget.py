from __future__ import annotations

import json
import time
from dataclasses import dataclass
from typing import Any, Literal

from mini_agent_flow.engine.runtime_config import RuntimeConfig


class BudgetExceededError(RuntimeError):
    """工作流定义或运行时超过固定预算。"""


@dataclass
class BudgetGuard:
    config: RuntimeConfig

    def __post_init__(self) -> None:
        self.started_at = time.perf_counter()
        self.steps = 0
        self.loop_iterations = 0
        self.llm_calls = 0
        self.tool_calls = 0

    def validate_definition(self, *, node_count: int, edge_count: int) -> None:
        self._require_at_most("nodes", node_count, self.config.max_nodes)
        self._require_at_most("edges", edge_count, self.config.max_edges)

    def consume_step(self, node_type: Literal["start", "end", "llm", "tool", "merge", "loop"]) -> None:
        self.ensure_duration()
        self.steps += 1
        self._require_at_most("steps", self.steps, self.config.max_steps)
        if node_type == "llm":
            self.llm_calls += 1
            self._require_at_most("LLM calls", self.llm_calls, self.config.max_llm_calls)
        elif node_type == "tool":
            self.tool_calls += 1
            self._require_at_most("Tool calls", self.tool_calls, self.config.max_tool_calls)

    def consume_loop_iteration(self) -> None:
        self.ensure_duration()
        self.loop_iterations += 1
        self._require_at_most(
            "loop iterations", self.loop_iterations, self.config.max_loop_iterations
        )

    def ensure_duration(self) -> None:
        elapsed = time.perf_counter() - self.started_at
        if elapsed > self.config.max_duration_seconds:
            raise BudgetExceededError(
                f"workflow duration exceeds limit: {elapsed:.3f}s > "
                f"{self.config.max_duration_seconds:.3f}s"
            )

    def ensure_context(self, context: dict[str, Any]) -> int:
        try:
            size = len(
                json.dumps(
                    context,
                    ensure_ascii=False,
                    sort_keys=True,
                    separators=(",", ":"),
                ).encode("utf-8")
            )
        except (TypeError, ValueError) as exc:
            raise BudgetExceededError("workflow context must remain JSON-compatible") from exc
        self._require_at_most("context bytes", size, self.config.max_context_bytes)
        return size

    def truncate_trace(self, trace: list[dict[str, Any]]) -> list[dict[str, Any]]:
        kept: list[dict[str, Any]] = []
        used = 0
        for event in trace:
            encoded = json.dumps(
                event,
                ensure_ascii=False,
                sort_keys=True,
                default=str,
                separators=(",", ":"),
            ).encode("utf-8")
            if used + len(encoded) > self.config.max_trace_bytes:
                kept.append(
                    {
                        "schema_version": "2.0",
                        "status": "warning",
                        "trace_truncated": True,
                        "message": "trace truncated by runtime byte budget",
                    }
                )
                break
            kept.append(event)
            used += len(encoded)
        return kept

    def _require_at_most(self, name: str, value: int, limit: int) -> None:
        if value > limit:
            raise BudgetExceededError(f"workflow exceeds {name} budget: {value} > {limit}")
