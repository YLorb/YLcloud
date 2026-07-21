from __future__ import annotations

import json
import re
from typing import Any

from mini_agent_flow.engine.context import VARIABLE_NAME_PATTERN, WorkflowContext, WorkflowContextError


VARIABLE_PATH = r"[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*"
VARIABLE_PATTERN = re.compile(rf"\{{\{{\s*({VARIABLE_PATH})\s*\}}\}}")
FULL_VARIABLE_PATTERN = re.compile(rf"^\s*\{{\{{\s*({VARIABLE_PATH})\s*\}}\}}\s*$")
UNRESOLVED_BRACES_PATTERN = re.compile(r"\{\{|\}\}")


class VariableResolveError(ValueError):
    """变量解析失败时抛出的异常。

    Resolver 只允许 `{{ key }}` 这种简单变量引用。缺失变量、非法变量语法、
    或未闭合的模板括号都应该显式失败，避免 workflow 静默使用错误输入。
    """


class VariableResolver:
    """解析 workflow 配置中的变量引用。

    Resolver 负责把 `{{ key }}` 映射到 WorkflowContext 中已有的真实值。
    它不执行表达式、不调用函数、不访问对象属性，只做可控的变量替换。
    """

    def resolve_template(self, template: str, context: WorkflowContext) -> str:
        """解析字符串模板，并始终返回字符串。

        该方法主要用于 LLM prompt。即使模板整体是 `{{ key }}`，也会把 key
        对应的值格式化为字符串，因为 prompt 最终必须是文本。
        """

        if not isinstance(template, str):
            raise VariableResolveError("template must be a string")

        return self._resolve_embedded_template(template, context)

    def resolve_value(self, value: Any, context: WorkflowContext) -> Any:
        """解析任意 JSON 字段值。

        如果整个字符串就是 `{{ key }}`，返回 Context 中的原始对象，保留 list、
        dict、int、bool 等类型；如果变量嵌在字符串中，则替换为字符串。
        list 和 dict 会递归解析，方便 tool input 使用结构化参数。
        """

        if isinstance(value, str):
            full_match = FULL_VARIABLE_PATTERN.fullmatch(value)
            if full_match:
                return self._require_context_value(full_match.group(1), context)
            return self._resolve_embedded_template(value, context)

        if isinstance(value, list):
            return [self.resolve_value(item, context) for item in value]

        if isinstance(value, dict):
            return {
                key: self.resolve_value(item, context)
                for key, item in value.items()
            }

        return value

    def extract_variables(self, template: str) -> set[str]:
        """提取模板中引用的变量名。

        提取前会检查模板是否存在未识别的 `{{` 或 `}}`，防止非法模板被误认为
        “没有变量”，从而延迟到执行阶段才暴露问题。
        """

        if not isinstance(template, str):
            raise VariableResolveError("template must be a string")

        variables = {match.group(1) for match in VARIABLE_PATTERN.finditer(template)}
        self._ensure_no_unresolved_braces(VARIABLE_PATTERN.sub("", template))
        return variables

    def _resolve_embedded_template(self, template: str, context: WorkflowContext) -> str:
        """解析字符串中的所有变量，并把变量值格式化后替换进去。"""

        def replace(match: re.Match[str]) -> str:
            value = self._require_context_value(match.group(1), context)
            return self._stringify_for_template(value)

        resolved = VARIABLE_PATTERN.sub(replace, template)
        self._ensure_no_unresolved_braces(resolved)
        return resolved

    def _require_context_value(self, key: str, context: WorkflowContext) -> Any:
        """从 Context 读取变量，并把 Context 错误转换成 Resolver 错误。"""

        parts = key.split(".")
        if not parts or any(not VARIABLE_NAME_PATTERN.fullmatch(part) for part in parts):
            raise VariableResolveError(f"invalid variable name: {key!r}")

        try:
            value = context.require(parts[0])
        except WorkflowContextError as exc:
            raise VariableResolveError(f"missing variable: {key}") from exc

        for part in parts[1:]:
            if not isinstance(value, dict) or part not in value:
                raise VariableResolveError(f"missing variable: {key}")
            value = value[part]
        return value

    def _stringify_for_template(self, value: Any) -> str:
        """把变量值转换成适合拼入字符串模板的文本。

        list/dict 使用 JSON 字符串，方便 LLM prompt 阅读结构化数据；None 转为空
        字符串，避免 prompt 中出现 Python 的 `None` 字面量。
        """

        if value is None:
            return ""
        if isinstance(value, (list, dict)):
            return json.dumps(value, ensure_ascii=False)
        return str(value)

    def _ensure_no_unresolved_braces(self, text: str) -> None:
        """拒绝残留的模板括号，避免非法语法静默通过。"""

        if UNRESOLVED_BRACES_PATTERN.search(text):
            raise VariableResolveError("invalid variable template syntax")
