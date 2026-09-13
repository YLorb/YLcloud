from __future__ import annotations

import re
import json
import importlib
from collections.abc import Mapping
from typing import Any, Callable

from mini_agent_flow.tools.spec import (
    ImportableToolEntrypoint,
    ProcessToolProviderDescriptor,
    ToolSpec,
)


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
        self._specs: dict[str, ToolSpec] = {}

    def register(
        self,
        name: str,
        tool: ToolCallable,
        *,
        spec: ToolSpec | None = None,
    ) -> None:
        """注册一个工具，可选附带 ToolSpec 元数据。"""

        self._validate_name(name)
        if not callable(tool):
            raise ToolRegistryError(f"tool must be callable: {name}")
        if name in self._tools:
            raise ToolRegistryError(f"tool is already registered: {name}")
        if spec is not None:
            self._validate_spec(name, spec)

        self._tools[name] = tool
        if spec is not None:
            self._specs[name] = spec

    def register_many(
        self,
        tools: Mapping[str, ToolCallable],
        *,
        specs: Mapping[str, ToolSpec] | None = None,
    ) -> None:
        """批量注册工具。

        Provider 导入外部工具时会一次返回多个 callable。这里先完整校验所有工具，
        再统一写入注册表，避免中途失败造成“只注册了一半”的不一致状态。
        """

        if not isinstance(tools, Mapping):
            raise ToolRegistryError("tools must be a mapping")

        specs = specs or {}
        validated_tools: dict[str, ToolCallable] = {}
        validated_specs: dict[str, ToolSpec] = {}
        for name, tool in tools.items():
            self._validate_name(name)
            if not callable(tool):
                raise ToolRegistryError(f"tool must be callable: {name}")
            if name in self._tools:
                raise ToolRegistryError(f"tool is already registered: {name}")
            if name in specs:
                self._validate_spec(name, specs[name])
            validated_tools[name] = tool
            if name in specs:
                validated_specs[name] = specs[name]

        self._tools.update(validated_tools)
        self._specs.update(validated_specs)

    def get(self, name: str) -> ToolCallable:
        """按工具名获取 callable。"""

        self._validate_name(name)
        if name not in self._tools:
            raise ToolRegistryError(f"tool is not registered: {name}")
        return self._tools[name]

    def get_spec(self, name: str) -> ToolSpec | None:
        """按工具名获取 ToolSpec，未注册工具或 spec 时返回 None。"""

        self._validate_name(name)
        return self._specs.get(name)

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
        self._specs.pop(name, None)

    def _validate_name(self, name: str) -> None:
        """校验工具名与 workflow ToolNode.tool 字段规则一致。"""

        if not isinstance(name, str) or not TOOL_NAME_PATTERN.fullmatch(name):
            raise ToolRegistryError(f"invalid tool name: {name!r}")

    def _validate_spec(self, name: str, spec: ToolSpec) -> None:
        if spec.name != name:
            raise ToolRegistryError(
                f"ToolSpec name must match registry name: {spec.name!r} != {name!r}"
            )
        for label, schema in (
            ("input_schema", spec.input_schema),
            ("output_schema", spec.output_schema),
        ):
            if schema is not None and not isinstance(schema, dict):
                raise ToolRegistryError(f"tool {label} must be a JSON object: {name}")
            try:
                json.dumps(schema)
            except (TypeError, ValueError) as exc:
                raise ToolRegistryError(
                    f"tool {label} must be JSON-compatible: {name}"
                ) from exc
        if spec.execution_mode != "isolated_process":
            return
        loader = spec.loader
        try:
            if isinstance(loader, ImportableToolEntrypoint):
                target = getattr(importlib.import_module(loader.module), loader.function)
            elif isinstance(loader, ProcessToolProviderDescriptor):
                module_name, _, factory_name = loader.provider_type.partition(":")
                target = getattr(importlib.import_module(module_name), factory_name)
            else:
                raise TypeError("missing isolated loader")
        except (ImportError, AttributeError, TypeError) as exc:
            raise ToolRegistryError(
                f"isolated tool loader cannot be reconstructed: {name}"
            ) from exc
        if not callable(target):
            raise ToolRegistryError(
                f"isolated tool loader target must be callable: {name}"
            )
