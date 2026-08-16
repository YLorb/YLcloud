from __future__ import annotations

from dataclasses import dataclass
import json
from pathlib import Path
from typing import Any


@dataclass(frozen=True, slots=True)
class ResourceLimits:
    cpus: float = 1.0
    memory_bytes: int = 512 * 1024 * 1024
    pids: int = 64
    tmpfs_bytes: int = 1024 * 1024 * 1024
    timeout_seconds: int = 60
    max_result_bytes: int = 1024 * 1024


@dataclass(frozen=True, slots=True)
class SandboxToolDefinition:
    name: str
    version: str
    image: str
    entrypoint: tuple[str, ...]
    input_schema: dict[str, Any]
    output_schema: dict[str, Any]
    limits: ResourceLimits = ResourceLimits()
    network_policy: str = "none"

    def __post_init__(self) -> None:
        if "@sha256:" not in self.image and not self.image.startswith("sha256:"):
            raise ValueError("sandbox tool image must be pinned by sha256 digest")
        if not self.entrypoint or any(not part for part in self.entrypoint):
            raise ValueError("sandbox tool requires a fixed entrypoint")
        if self.network_policy != "none":
            raise ValueError("first sandbox release only permits network=none")


class ToolCatalog:
    def __init__(self, tools: list[SandboxToolDefinition] | None = None) -> None:
        self._tools = {(tool.name, tool.version): tool for tool in tools or []}

    def get(self, name: str, version: str) -> SandboxToolDefinition:
        try:
            return self._tools[(name, version)]
        except KeyError as exc:
            raise KeyError(f"sandbox tool is not registered: {name}@{version}") from exc

    def list(self) -> list[SandboxToolDefinition]:
        return list(self._tools.values())

    @classmethod
    def from_json_file(cls, path: str | Path) -> "ToolCatalog":
        raw = json.loads(Path(path).read_text(encoding="utf-8-sig"))
        if not isinstance(raw, list):
            raise ValueError("sandbox tool catalog must be an array")
        tools: list[SandboxToolDefinition] = []
        for item in raw:
            limits = ResourceLimits(**item.pop("limits", {}))
            item["entrypoint"] = tuple(item["entrypoint"])
            tools.append(SandboxToolDefinition(**item, limits=limits))
        return cls(tools)
