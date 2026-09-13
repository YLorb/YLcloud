from __future__ import annotations

import pytest

from mini_agent_flow.engine.context import WorkflowContext, WorkflowContextError
from mini_agent_flow.engine.loader import JsonWorkflowLoader


def test_empty_context_can_be_created() -> None:
    """空 Context 应能创建，并导出为空 dict。"""

    context = WorkflowContext()

    assert context.to_dict() == {}


def test_initial_values_are_copied() -> None:
    """初始化数据应被复制，外部修改不应影响 Context。"""

    initial_values = {"items": ["a"]}
    context = WorkflowContext(initial_values)
    initial_values["items"].append("b")

    assert context.require("items") == ["a"]


def test_from_workflow_reads_workflow_inputs() -> None:
    """Context 可以从 Workflow.inputs 初始化。"""

    workflow = JsonWorkflowLoader().load("examples/level1_manual_workflow.json")
    context = WorkflowContext.from_workflow(workflow)

    assert context.require("goal") == "总结 AI Agent 工作流系统的核心组成"


def test_get_existing_key() -> None:
    """get 可以读取已存在变量。"""

    context = WorkflowContext({"goal": "demo"})

    assert context.get("goal") == "demo"


def test_get_missing_key_returns_default() -> None:
    """get 读取不存在变量时返回 default。"""

    context = WorkflowContext()

    assert context.get("missing", default="fallback") == "fallback"


def test_require_missing_key_fails() -> None:
    """require 读取不存在变量时必须抛错，避免节点静默拿到 None。"""

    context = WorkflowContext()

    with pytest.raises(WorkflowContextError, match="missing"):
        context.require("missing")


def test_set_valid_key() -> None:
    """set 可以写入合法变量名。"""

    context = WorkflowContext()
    context.set("keywords", ["agent", "workflow"])

    assert context.require("keywords") == ["agent", "workflow"]


@pytest.mark.parametrize("invalid_key", ["", "1abc", "user-name", "foo.bar", 123])
def test_set_invalid_key_fails(invalid_key: object) -> None:
    """非法变量名应被拒绝，避免模板变量引用变得不可预测。"""

    context = WorkflowContext()

    with pytest.raises(WorkflowContextError, match="invalid context key"):
        context.set(invalid_key, "value")  # type: ignore[arg-type]


def test_update_valid_values() -> None:
    """update 可以批量写入多个合法变量。"""

    context = WorkflowContext()
    context.update({"keywords": ["agent"], "final_answer": "done"})

    assert context.require("keywords") == ["agent"]
    assert context.require("final_answer") == "done"


def test_update_rejects_non_dict() -> None:
    """update 只接受 dict，保持 Context 的 key-value 语义清晰。"""

    context = WorkflowContext()

    with pytest.raises(WorkflowContextError, match="must be a dict"):
        context.update(["not", "a", "dict"])  # type: ignore[arg-type]


def test_update_invalid_key_does_not_partially_update() -> None:
    """批量更新中出现非法 key 时，不应写入前面的合法 key。"""

    context = WorkflowContext()

    with pytest.raises(WorkflowContextError):
        context.update({"valid_key": "ok", "invalid-key": "bad"})

    assert not context.has("valid_key")


def test_has_checks_existing_key() -> None:
    """has 用于判断变量是否存在。"""

    context = WorkflowContext({"goal": "demo"})

    assert context.has("goal") is True
    assert context.has("missing") is False


def test_to_dict_returns_copy() -> None:
    """to_dict 返回副本，外部修改不应影响 Context。"""

    context = WorkflowContext({"items": ["a"]})
    exported = context.to_dict()
    exported["items"].append("b")

    assert context.require("items") == ["a"]


def test_snapshot_returns_copy_for_trace() -> None:
    """snapshot 返回某一时刻的副本，适合后续 Trace Recorder 使用。"""

    context = WorkflowContext({"items": ["a"]})
    snapshot = context.snapshot()
    context.set("items", ["changed"])

    assert snapshot == {"items": ["a"]}
