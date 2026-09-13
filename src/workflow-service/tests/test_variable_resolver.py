from __future__ import annotations

import pytest

from mini_agent_flow.engine.context import WorkflowContext
from mini_agent_flow.engine.resolver import VariableResolveError, VariableResolver


@pytest.fixture()
def context() -> WorkflowContext:
    """提供一份包含多类型变量的 Context。"""

    return WorkflowContext(
        {
            "goal": "总结 AI Agent",
            "limit": 5,
            "enabled": True,
            "keywords": ["agent", "workflow"],
            "search_results": [{"title": "A"}, {"title": "B"}],
            "empty_value": None,
        }
    )


def test_plain_string_without_variables_is_unchanged(context: WorkflowContext) -> None:
    """没有变量的普通字符串应原样返回。"""

    assert VariableResolver().resolve_template("hello", context) == "hello"


def test_string_template_replaces_variable(context: WorkflowContext) -> None:
    """嵌入字符串中的变量会被替换为文本。"""

    result = VariableResolver().resolve_template("目标：{{ goal }}", context)

    assert result == "目标：总结 AI Agent"


def test_full_variable_reference_preserves_original_type(context: WorkflowContext) -> None:
    """整个字段是变量引用时，应保留原始对象类型。"""

    result = VariableResolver().resolve_value("{{ keywords }}", context)

    assert result == ["agent", "workflow"]


def test_full_number_variable_preserves_number_type(context: WorkflowContext) -> None:
    """完整变量引用数字时，应返回 int 而不是字符串。"""

    result = VariableResolver().resolve_value("{{ limit }}", context)

    assert result == 5
    assert isinstance(result, int)


def test_embedded_number_variable_becomes_string(context: WorkflowContext) -> None:
    """变量嵌在字符串中时，数字会被格式化进字符串。"""

    result = VariableResolver().resolve_value("搜索 {{ limit }} 条", context)

    assert result == "搜索 5 条"


def test_list_values_are_resolved_recursively(context: WorkflowContext) -> None:
    """list 中的模板值会递归解析。"""

    result = VariableResolver().resolve_value(["{{ goal }}", "{{ limit }}"], context)

    assert result == ["总结 AI Agent", 5]


def test_dict_values_are_resolved_recursively(context: WorkflowContext) -> None:
    """dict 中的模板值会递归解析。"""

    result = VariableResolver().resolve_value(
        {"query": "{{ keywords }}", "limit": "{{ limit }}"},
        context,
    )

    assert result == {"query": ["agent", "workflow"], "limit": 5}


def test_nested_dict_and_list_are_resolved(context: WorkflowContext) -> None:
    """嵌套 dict/list 也会递归解析，方便复杂 tool input。"""

    result = VariableResolver().resolve_value(
        {
            "payload": {
                "keywords": "{{ keywords }}",
                "messages": ["目标：{{ goal }}", {"limit": "{{ limit }}"}],
            }
        },
        context,
    )

    assert result == {
        "payload": {
            "keywords": ["agent", "workflow"],
            "messages": ["目标：总结 AI Agent", {"limit": 5}],
        }
    }


def test_missing_variable_fails(context: WorkflowContext) -> None:
    """引用不存在变量时必须失败，避免节点静默使用错误输入。"""

    with pytest.raises(VariableResolveError, match="missing variable"):
        VariableResolver().resolve_value("{{ missing_key }}", context)


@pytest.mark.parametrize(
    "template",
    ["{{ user-name }}", "{{ 1abc }}", "{{ foo.bar }}", "{{ goal"],
)
def test_invalid_variable_syntax_fails(template: str, context: WorkflowContext) -> None:
    """只支持简单变量名，非法模板语法应被拒绝。"""

    with pytest.raises(VariableResolveError):
        VariableResolver().resolve_value(template, context)


def test_multiple_variables_in_same_string(context: WorkflowContext) -> None:
    """同一字符串中可以替换多个变量。"""

    result = VariableResolver().resolve_template(
        "目标：{{ goal }}，数量：{{ limit }}",
        context,
    )

    assert result == "目标：总结 AI Agent，数量：5"


def test_non_string_scalar_values_are_unchanged(context: WorkflowContext) -> None:
    """非字符串标量不需要解析，应原样返回。"""

    assert VariableResolver().resolve_value(42, context) == 42
    assert VariableResolver().resolve_value(False, context) is False


def test_dict_list_are_json_stringified_inside_template(context: WorkflowContext) -> None:
    """list/dict 嵌入 prompt 时使用 JSON 文本，而不是 Python repr。"""

    result = VariableResolver().resolve_template("资料：{{ search_results }}", context)

    assert result == '资料：[{"title": "A"}, {"title": "B"}]'


def test_none_becomes_empty_string_inside_template(context: WorkflowContext) -> None:
    """None 嵌入字符串模板时转换为空字符串。"""

    result = VariableResolver().resolve_template("值：{{ empty_value }}", context)

    assert result == "值："


def test_extract_variables(context: WorkflowContext) -> None:
    """可以提取模板中引用的变量名，后续 Validator 可复用该能力做静态检查。"""

    variables = VariableResolver().extract_variables("{{ goal }} - {{ search_results }}")

    assert variables == {"goal", "search_results"}
