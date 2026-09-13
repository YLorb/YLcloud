from __future__ import annotations

from collections.abc import Mapping

from mini_agent_flow.tools.registry import ToolCallable
from mini_agent_flow.tools.spec import ToolSpec


class FakeMCPToolProvider:
    """用于测试 MCP Tool Provider 形态的本地假 Provider。

    它不连接真实 MCP Server，只模拟“外部工具来源返回一组工具”的过程。
    后续接真实 MCP Client 时，可以保持 ToolRegistry 和 Executor 不变。
    """

    def __init__(
        self,
        tools: Mapping[str, ToolCallable],
        specs: Mapping[str, ToolSpec] | None = None,
    ) -> None:
        """保存外部传入的工具映射副本和可选的 spec 映射副本。"""

        self._tools = dict(tools)
        self._specs = dict(specs) if specs else {}

    def load_tools(self) -> dict[str, ToolCallable]:
        """返回工具映射副本，避免调用方直接修改 Provider 内部状态。"""

        return dict(self._tools)

    def load_specs(self) -> dict[str, ToolSpec]:
        """返回 spec 映射副本。"""

        return dict(self._specs)
