from __future__ import annotations

import re
from typing import Any, Callable


TOOL_NAME_PATTERN = re.compile(r"^[A-Za-z_][A-Za-z0-9_-]*$")
ToolCallable = Callable[[Any], Any]


class ToolRegistryError(ValueError):
    """工具注册表操作失败时抛出的异常。

    Tool Registry 是 workflow tool 节点的安全边界。非法工具名、非 callable
    对象、重复注册、读取未注册工具，都应该显式失败。
    """


class ToolRegistry:
    """本地工具注册表。

    注册表只保存“工具名 -> Python callable”的内存映射，不做动态 import，
    不从 workflow JSON 中加载任意函数，从而避免工具调用边界失控。
    """

    def __init__(self) -> None:
        """创建空工具注册表。"""

        self._tools: dict[str, ToolCallable] = {}

    def register(self, name: str, tool: ToolCallable) -> None:
        """注册一个工具。

        第一版不允许重复注册同名工具，避免误覆盖已存在的安全工具实现。
        """

        self._validate_name(name)
        if not callable(tool):
            raise ToolRegistryError(f"tool must be callable: {name}")
        if name in self._tools:
            raise ToolRegistryError(f"tool is already registered: {name}")

        self._tools[name] = tool

    def get(self, name: str) -> ToolCallable:
        """按工具名获取 callable。"""

        self._validate_name(name)
        if name not in self._tools:
            raise ToolRegistryError(f"tool is not registered: {name}")
        return self._tools[name]

    def has(self, name: str) -> bool:
        """判断工具是否已注册。"""

        self._validate_name(name)
        return name in self._tools

    def names(self) -> set[str]:
        """返回已注册工具名集合。

        返回 set 副本，调用方可以把它传给 WorkflowValidator.allowed_tools，
        但不能直接修改注册表内部状态。
        """

        return set(self._tools)

    def unregister(self, name: str) -> None:
        """移除已注册工具。"""

        self._validate_name(name)
        if name not in self._tools:
            raise ToolRegistryError(f"tool is not registered: {name}")
        del self._tools[name]

    def _validate_name(self, name: str) -> None:
        """校验工具名与 workflow ToolNode.tool 字段规则一致。"""

        if not isinstance(name, str) or not TOOL_NAME_PATTERN.fullmatch(name):
            raise ToolRegistryError(f"invalid tool name: {name!r}")
