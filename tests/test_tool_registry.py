from __future__ import annotations

import pytest

from mini_agent_flow.tools.builtin import create_default_tool_registry, echo, mock_search
from mini_agent_flow.tools.registry import ToolRegistry, ToolRegistryError
from mini_agent_flow.tools.spec import ToolSpec


def test_register_and_get_tool() -> None:
    """可以注册工具，并通过工具名取回 callable。"""

    registry = ToolRegistry()
    registry.register("echo", echo)

    assert registry.get("echo")("hello") == "hello"


def test_get_unregistered_tool_fails() -> None:
    """读取未注册工具应失败，避免 workflow 调用未知函数。"""

    registry = ToolRegistry()

    with pytest.raises(ToolRegistryError, match="not registered"):
        registry.get("missing_tool")


@pytest.mark.parametrize("name", ["", "1tool", "bad name", "tool.name", 123])
def test_invalid_tool_name_fails(name: object) -> None:
    """工具名必须符合受控标识符规则。"""

    registry = ToolRegistry()

    with pytest.raises(ToolRegistryError, match="invalid tool name"):
        registry.register(name, echo)  # type: ignore[arg-type]


def test_register_non_callable_fails() -> None:
    """注册对象必须是 callable。"""

    registry = ToolRegistry()

    with pytest.raises(ToolRegistryError, match="callable"):
        registry.register("not_callable", "hello")  # type: ignore[arg-type]


def test_duplicate_tool_registration_fails() -> None:
    """重复注册同名工具会失败，避免误覆盖已有工具。"""

    registry = ToolRegistry()
    registry.register("echo", echo)

    with pytest.raises(ToolRegistryError, match="already registered"):
        registry.register("echo", echo)


def test_has_checks_tool_existence() -> None:
    """has 可以判断工具是否存在。"""

    registry = ToolRegistry()
    registry.register("echo", echo)

    assert registry.has("echo") is True
    assert registry.has("missing_tool") is False


def test_names_returns_registered_tool_names_copy() -> None:
    """names 返回工具名集合副本，不暴露内部 dict。"""

    registry = ToolRegistry()
    registry.register("echo", echo)
    names = registry.names()
    names.add("fake_tool")

    assert registry.names() == {"echo"}


def test_unregister_removes_tool() -> None:
    """unregister 可以移除已注册工具。"""

    registry = ToolRegistry()
    registry.register("echo", echo)
    registry.unregister("echo")

    assert registry.has("echo") is False


def test_unregister_missing_tool_fails() -> None:
    """移除未注册工具应失败，避免调用方误以为删除成功。"""

    registry = ToolRegistry()

    with pytest.raises(ToolRegistryError, match="not registered"):
        registry.unregister("missing_tool")


def test_default_registry_contains_builtin_tools() -> None:
    """默认注册表应包含当前内置安全工具。"""

    registry = create_default_tool_registry()

    assert registry.names() == {"mock_search", "echo"}


def test_echo_returns_input_value() -> None:
    """echo 返回输入原值。"""

    input_value = {"message": "hello"}

    assert echo(input_value) is input_value


def test_mock_search_accepts_string_input() -> None:
    """mock_search 接受字符串输入并返回本地假结果。"""

    result = mock_search("LangGraph")

    assert result == [{"title": "Mock result for LangGraph", "source": "mock_search"}]


def test_mock_search_accepts_list_input() -> None:
    """mock_search 接受 list 输入并为每个元素生成本地假结果。"""

    result = mock_search(["LangGraph", "Dify"])

    assert result == [
        {"title": "Mock result for LangGraph", "source": "mock_search"},
        {"title": "Mock result for Dify", "source": "mock_search"},
    ]


def test_register_with_spec() -> None:
    """注册工具时可以附带 ToolSpec，后续可通过 get_spec 取回。"""

    registry = ToolRegistry()
    spec = ToolSpec(name="echo", description="echo spec", permission="public", risk_level=0)
    registry.register("echo", echo, spec=spec)

    assert registry.get_spec("echo") == spec


def test_get_spec_returns_none_when_no_spec() -> None:
    """未提供 ToolSpec 时，get_spec 返回 None 而不是报错。"""

    registry = ToolRegistry()
    registry.register("echo", echo)

    assert registry.get_spec("echo") is None


def test_unregister_removes_spec() -> None:
    """unregister 工具时应同时移除对应的 ToolSpec。"""

    registry = ToolRegistry()
    registry.register("echo", echo, spec=ToolSpec(name="echo"))
    registry.unregister("echo")

    assert registry.has("echo") is False
    assert registry.get_spec("echo") is None


def test_register_many_with_specs() -> None:
    """批量注册工具时可以同时批量注册 ToolSpec。"""

    registry = ToolRegistry()
    tools = {"echo": echo, "mock_search": mock_search}
    specs = {
        "echo": ToolSpec(name="echo", permission="public"),
        "mock_search": ToolSpec(name="mock_search", permission="public"),
    }
    registry.register_many(tools, specs=specs)

    assert registry.get_spec("echo") == specs["echo"]
    assert registry.get_spec("mock_search") == specs["mock_search"]
