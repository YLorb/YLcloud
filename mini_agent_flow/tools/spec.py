from __future__ import annotations

from dataclasses import dataclass
from typing import Any


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
    permission: str = "public"
    risk_level: int = 0

    def __post_init__(self) -> None:
        """校验 risk_level 和 permission 的合法性。"""

        if not 0 <= self.risk_level <= 10:
            raise ValueError(f"risk_level must be between 0 and 10, got {self.risk_level}")
        if not self.permission:
            raise ValueError("permission must not be empty")
