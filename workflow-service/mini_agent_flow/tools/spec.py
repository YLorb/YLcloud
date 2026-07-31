from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Literal


@dataclass(frozen=True)
class ImportableToolEntrypoint:
    module: str
    function: str

    def __post_init__(self) -> None:
        if not self.module or not self.function:
            raise ValueError("importable tool entrypoint requires module and function")


@dataclass(frozen=True)
class ProcessToolProviderDescriptor:
    provider_type: str
    provider_config_ref: str
    tool_name: str

    def __post_init__(self) -> None:
        if not self.provider_type or not self.provider_config_ref or not self.tool_name:
            raise ValueError("process tool provider descriptor fields must not be empty")
        module, separator, factory = self.provider_type.partition(":")
        if not separator or not module or not factory:
            raise ValueError(
                "provider_type must be an importable 'module:factory' reference"
            )


ToolLoader = ImportableToolEntrypoint | ProcessToolProviderDescriptor


@dataclass(frozen=True)
class ToolSpec:
    """工具元数据描述。

    ToolSpec 描述工具的名称、描述、输入 schema、权限等级和风险等级。
    Registry、Validator 和 Executor 会基于这些信息做工具发现、权限控制和
    运行时输入校验。
    """

    name: str
    description: str = ""
    input_schema: dict[str, Any] | None = None
    output_schema: dict[str, Any] | None = None
    permission: str = "public"
    risk_level: int = 0
    execution_mode: Literal["inline", "isolated_process"] = "inline"
    loader: ToolLoader | None = None
    idempotent: bool = False
    supports_cancellation: bool = False
    required_secret_names: tuple[str, ...] = ()
    cleanup_allowed: bool = False
    side_effecting: bool = False
    accepts_idempotency_key: bool = False

    def __post_init__(self) -> None:
        """校验 risk_level 和 permission 的合法性。"""

        if not 0 <= self.risk_level <= 10:
            raise ValueError(f"risk_level must be between 0 and 10, got {self.risk_level}")
        if not self.permission:
            raise ValueError("permission must not be empty")
        if self.execution_mode == "isolated_process" and self.loader is None:
            raise ValueError("isolated_process tool requires a loader descriptor")
        if any(not name for name in self.required_secret_names):
            raise ValueError("required secret names must not be empty")
        if self.cleanup_allowed and not self.idempotent:
            raise ValueError("cleanup tool must be idempotent")
        if self.side_effecting and self.idempotent and not self.accepts_idempotency_key:
            raise ValueError(
                "idempotent side-effect tool must accept the runtime idempotency key"
            )
        if self.cleanup_allowed and not self.accepts_idempotency_key:
            raise ValueError("cleanup tool must accept the runtime idempotency key")
