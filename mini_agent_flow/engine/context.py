from __future__ import annotations

import copy
import re
from typing import Any

from mini_agent_flow.engine.models import Workflow


VARIABLE_NAME_PATTERN = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")


class WorkflowContextError(KeyError):
    """Context 读写非法时抛出的异常。

    Context 是 workflow 执行时的共享变量表。变量名非法、必需变量缺失、
    或批量更新输入类型不正确，都属于 Context 层面的错误。
    """


class WorkflowContext:
    """workflow 运行时共享上下文。

    每个节点不直接把数据传给下一个节点，而是把结果写入 Context 的某个 key。
    后续节点再按 workflow 配置从 Context 读取所需 key。这个设计让数据流显式、
    可追踪，也方便后续 Trace Recorder 记录每一步的输入输出快照。
    """

    def __init__(self, initial_values: dict[str, Any] | None = None) -> None:
        """创建 Context。

        initial_values 通常来自 workflow.inputs。这里会深拷贝输入，避免外部 dict
        在 Context 创建后继续修改，导致运行时状态被意外污染。
        """

        self._values: dict[str, Any] = {}
        if initial_values is not None:
            self.update(initial_values)

    @classmethod
    def from_workflow(cls, workflow: Workflow) -> "WorkflowContext":
        """从已校验的 Workflow 对象初始化 Context。"""

        return cls(workflow.inputs)

    def get(self, key: str, default: Any = None) -> Any:
        """读取变量；不存在时返回 default。"""

        self._validate_key(key)
        return self._values.get(key, default)

    def require(self, key: str) -> Any:
        """读取必需变量；不存在时抛出 WorkflowContextError。"""

        self._validate_key(key)
        if key not in self._values:
            raise WorkflowContextError(f"required context key is missing: {key}")
        return self._values[key]

    def set(self, key: str, value: Any) -> None:
        """写入单个变量。"""

        self._validate_key(key)
        self._values[key] = copy.deepcopy(value)

    def update(self, values: dict[str, Any]) -> None:
        """批量写入变量。

        先完整校验所有 key，再统一写入。这样如果某个 key 非法，Context 不会处于
        “前几个变量已写入、后一个变量失败”的半更新状态。
        """

        if not isinstance(values, dict):
            raise WorkflowContextError("context update values must be a dict")

        for key in values:
            self._validate_key(key)

        for key, value in values.items():
            self._values[key] = copy.deepcopy(value)

    def has(self, key: str) -> bool:
        """判断变量是否存在。"""

        self._validate_key(key)
        return key in self._values

    def delete(self, key: str) -> None:
        """删除临时变量；不存在时保持幂等。"""

        self._validate_key(key)
        self._values.pop(key, None)

    def to_dict(self) -> dict[str, Any]:
        """导出完整 Context 副本。"""

        return copy.deepcopy(self._values)

    def snapshot(self) -> dict[str, Any]:
        """返回当前 Context 快照。

        snapshot 语义上服务于后续 trace，即记录某一时刻的运行状态。
        当前实现与 to_dict 一样返回深拷贝，避免快照被后续写入影响。
        """

        return self.to_dict()

    def _validate_key(self, key: str) -> None:
        """校验 Context 变量名与 workflow output 变量名规则一致。"""

        if not isinstance(key, str) or not VARIABLE_NAME_PATTERN.fullmatch(key):
            raise WorkflowContextError(f"invalid context key: {key!r}")
