from __future__ import annotations

from typing import Any

from mini_agent_flow.tools.registry import ToolRegistry
from mini_agent_flow.tools.spec import ToolSpec


def echo(input_value: Any) -> Any:
    """返回输入原值。

    echo 是最小、最安全的测试工具，适合验证 Tool Registry 和后续 Executor
    的输入输出链路，不产生任何外部副作用。
    """

    return input_value


def mock_search(query: Any) -> list[dict[str, str]]:
    """本地 mock 搜索工具。

    该工具不联网、不读取文件，只根据输入构造假搜索结果。它用于 Level 1 演示
    tool 节点的数据流，而不是提供真实搜索能力。
    """

    queries = _normalize_queries(query)
    return [
        {
            "title": f"Mock result for {item}",
            "source": "mock_search",
        }
        for item in queries
    ]


def _echo_spec() -> ToolSpec:
    return ToolSpec(
        name="echo",
        description="返回输入原值，无任何副作用。",
        input_schema={"type": "any"},
        permission="public",
        risk_level=0,
        idempotent=True,
    )


def _mock_search_spec() -> ToolSpec:
    return ToolSpec(
        name="mock_search",
        description="根据查询返回本地 mock 搜索结果，不联网。",
        input_schema={
            "anyOf": [
                {"type": "string"},
                {"type": "array", "items": {"type": "string"}},
            ]
        },
        permission="public",
        risk_level=0,
        idempotent=True,
    )


def create_default_tool_registry() -> ToolRegistry:
    """创建默认本地工具注册表。"""

    registry = ToolRegistry()
    registry.register("mock_search", mock_search, spec=_mock_search_spec())
    registry.register("echo", echo, spec=_echo_spec())
    return registry


def _normalize_queries(query: Any) -> list[str]:
    """把 str/list 等输入归一化为字符串查询列表。"""

    if isinstance(query, list):
        return [str(item) for item in query]
    if query is None:
        return []
    return [str(query)]
