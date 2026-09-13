from __future__ import annotations

from collections.abc import Mapping
from typing import Iterator

import pytest

from mini_agent_flow.engine.executor import SequentialWorkflowExecutor
from mini_agent_flow.engine.loader import JsonWorkflowLoader
from mini_agent_flow.engine.validator import WorkflowValidator
from mini_agent_flow.llm.mock import MockLLM
from mini_agent_flow.tools.mcp_provider import FakeMCPToolProvider
from mini_agent_flow.tools.registry import ToolCallable, ToolRegistry, ToolRegistryError


def fake_mcp_upper(input_value: object) -> str:
    """模拟 MCP 工具：把输入转成大写字符串。"""

    return str(input_value).upper()


def fake_mcp_pack(input_value: object) -> dict[str, object]:
    """模拟 MCP 工具：把输入包装成结构化结果。"""

    return {"source": "fake_mcp", "value": input_value}


class InvalidToolMapping(Mapping[str, ToolCallable]):
    """用于构造半注册测试的 Mapping。

    普通 dict 不能保留重复 key，也不方便表达“第一个合法、第二个非法”的校验顺序。
    自定义 Mapping 可以稳定模拟 Provider 返回坏数据的情况。
    """

    def __iter__(self) -> Iterator[str]:
        return iter(["valid_tool", "bad name"])

    def __len__(self) -> int:
        return 2

    def __getitem__(self, key: str) -> ToolCallable:
        if key == "valid_tool":
            return fake_mcp_upper
        if key == "bad name":
            return fake_mcp_pack
        raise KeyError(key)


def test_fake_mcp_provider_returns_tools_copy() -> None:
    """FakeMCPToolProvider 应返回工具映射副本。"""

    provider = FakeMCPToolProvider({"mcp_upper": fake_mcp_upper})
    tools = provider.load_tools()
    tools["extra_tool"] = fake_mcp_pack

    assert provider.load_tools() == {"mcp_upper": fake_mcp_upper}


def test_register_many_registers_provider_tools() -> None:
    """ToolRegistry 可以批量注册 Provider 返回的工具。"""

    registry = ToolRegistry()
    provider = FakeMCPToolProvider(
        {
            "mcp_upper": fake_mcp_upper,
            "mcp_pack": fake_mcp_pack,
        }
    )

    registry.register_many(provider.load_tools())

    assert registry.names() == {"mcp_upper", "mcp_pack"}
    assert registry.get("mcp_upper")("hello") == "HELLO"
    assert registry.get("mcp_pack")("hello") == {
        "source": "fake_mcp",
        "value": "hello",
    }


def test_register_many_rejects_non_mapping() -> None:
    """批量注册必须接收 mapping，避免导入不确定结构。"""

    registry = ToolRegistry()

    with pytest.raises(ToolRegistryError, match="mapping"):
        registry.register_many(["mcp_upper"])  # type: ignore[arg-type]


def test_register_many_rejects_invalid_tool_name() -> None:
    """Provider 返回非法工具名时，批量注册应失败。"""

    registry = ToolRegistry()

    with pytest.raises(ToolRegistryError, match="invalid tool name"):
        registry.register_many({"bad name": fake_mcp_upper})


def test_register_many_rejects_non_callable() -> None:
    """Provider 返回非 callable 工具时，批量注册应失败。"""

    registry = ToolRegistry()

    with pytest.raises(ToolRegistryError, match="callable"):
        registry.register_many({"mcp_upper": "not callable"})  # type: ignore[dict-item]


def test_register_many_rejects_existing_tool_name() -> None:
    """批量导入不允许覆盖已有工具。"""

    registry = ToolRegistry()
    registry.register("mcp_upper", fake_mcp_upper)

    with pytest.raises(ToolRegistryError, match="already registered"):
        registry.register_many({"mcp_upper": fake_mcp_pack})


def test_register_many_failure_does_not_partially_register_tools() -> None:
    """批量注册失败时，不应留下已经处理过的部分工具。"""

    registry = ToolRegistry()

    with pytest.raises(ToolRegistryError, match="invalid tool name"):
        registry.register_many(InvalidToolMapping())

    assert registry.names() == set()


def test_provider_imported_tool_runs_through_executor() -> None:
    """Provider 导入的工具应能被 workflow tool 节点调用。"""

    registry = ToolRegistry()
    provider = FakeMCPToolProvider({"mcp_upper": fake_mcp_upper})
    registry.register_many(provider.load_tools())
    workflow = JsonWorkflowLoader(
        validator=WorkflowValidator(allowed_tools=registry.names())
    ).load_data(
        {
            "version": "1.0",
            "name": "fake_mcp_workflow",
            "inputs": {"message": "hello mcp"},
            "nodes": [
                {"id": "start", "type": "start", "next": "call_mcp"},
                {
                    "id": "call_mcp",
                    "type": "tool",
                    "tool": "mcp_upper",
                    "input": "{{ message }}",
                    "output": "mcp_result",
                    "next": "end",
                },
                {"id": "end", "type": "end"},
            ],
        }
    )
    executor = SequentialWorkflowExecutor(llm=MockLLM(), tool_registry=registry)

    result = executor.run(workflow)

    assert result.executed_nodes == ["start", "call_mcp", "end"]
    assert result.context["mcp_result"] == "HELLO MCP"
