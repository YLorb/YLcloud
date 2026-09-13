from __future__ import annotations

from typing import Protocol

from mini_agent_flow.tools.registry import ToolCallable
from mini_agent_flow.tools.spec import ToolSpec


class ToolProvider(Protocol):
    """工具来源协议。

    本地工具、Fake MCP 工具、真实 MCP Server 工具都可以通过这个协议向
    ToolRegistry 提供“工具名 -> callable”的映射。
    """

    def load_tools(self) -> dict[str, ToolCallable]:
        """加载当前 Provider 提供的工具。"""

        ...

    def load_specs(self) -> dict[str, ToolSpec]:
        """加载当前 Provider 提供的工具元数据。"""

        ...
