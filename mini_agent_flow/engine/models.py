from __future__ import annotations

from typing import Annotated, Any, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator


class RetryPolicy(BaseModel):
    """节点级重试策略。

    当前阶段只定义数据结构，不实际执行 retry。后续 Executor 可以读取该配置，
    在节点失败时根据 max_attempts 和 backoff_seconds 决定是否重试。
    """

    model_config = ConfigDict(extra="forbid")

    max_attempts: int = Field(default=1, ge=1, le=10)
    backoff_seconds: float = Field(default=0, ge=0)


class BaseNode(BaseModel):
    """所有 workflow 节点共享的基础字段。

    extra="forbid" 用来固定 JSON 格式，避免 AI 生成 workflow 时塞入未知字段，
    也方便后续 Validator 和 Executor 明确知道每个节点的结构边界。
    """

    model_config = ConfigDict(extra="forbid")

    id: str = Field(min_length=1, pattern=r"^[A-Za-z_][A-Za-z0-9_-]*$")
    type: str
    description: str | None = None
    metadata: dict[str, Any] = Field(default_factory=dict)
    retry: RetryPolicy | None = None


class StartNode(BaseNode):
    """workflow 的唯一入口节点。

    start 节点不执行业务逻辑，只负责把执行流转交给 next 指向的第一个真实节点。
    """

    type: Literal["start"]
    next: str = Field(min_length=1)


class EndNode(BaseNode):
    """workflow 的结束节点。

    end 节点没有 next，表示执行流在这里终止。一个 workflow 可以保留多个 end，
    方便 condition 分支在不同路径上结束。
    """

    type: Literal["end"]


class LLMNode(BaseNode):
    """LLM 调用节点。

    prompt 保存待渲染的 Prompt Template，output 表示 LLM 结果写回 context 的变量名。
    真正的模板渲染和模型调用会在后续 Executor 中完成。
    """

    type: Literal["llm"]
    prompt: str = Field(min_length=1)
    output: str = Field(min_length=1, pattern=r"^[A-Za-z_][A-Za-z0-9_]*$")
    next: str = Field(min_length=1)


class ToolNode(BaseNode):
    """工具调用节点。

    tool 是工具注册表中的工具名，input 是传给工具的输入，output 是写回 context
    的变量名。当前模型只描述结构，是否允许调用某个工具由 Validator 的白名单控制。
    """

    type: Literal["tool"]
    tool: str = Field(min_length=1, pattern=r"^[A-Za-z_][A-Za-z0-9_-]*$")
    input: Any
    output: str = Field(min_length=1, pattern=r"^[A-Za-z_][A-Za-z0-9_]*$")
    next: str = Field(min_length=1)


class ConditionNode(BaseNode):
    """条件分支节点。

    expression 只保存条件表达式文本，当前阶段不执行表达式。后续 Executor 需要在
    受控环境中解释它，并根据结果跳转到 if_true 或 if_false。
    """

    type: Literal["condition"]
    expression: str = Field(min_length=1)
    if_true: str = Field(min_length=1)
    if_false: str = Field(min_length=1)


class LoopNode(BaseNode):
    """循环节点。

    items 表示从 context 取出的可迭代对象，item_var 表示每轮循环写入 context
    的变量名，body 保存循环体节点 id。具体循环执行策略由后续 Executor 实现。
    """

    type: Literal["loop"]
    items: str = Field(min_length=1)
    item_var: str = Field(min_length=1, pattern=r"^[A-Za-z_][A-Za-z0-9_]*$")
    body: list[str] = Field(min_length=1)
    next: str = Field(min_length=1)


WorkflowNode = Annotated[
    StartNode | EndNode | LLMNode | ToolNode | ConditionNode | LoopNode,
    Field(discriminator="type"),
]
"""按 type 字段区分不同节点模型。

Pydantic 会根据 JSON 中的 type 自动选择 StartNode、LLMNode 等具体模型，
这样可以把不同节点类型的必填字段约束放在各自类里。
"""


class Workflow(BaseModel):
    """完整 workflow 定义。

    Workflow 是固定 JSON 格式的顶层模型。它只负责结构层面的基础约束；
    跨节点引用、可达性、tool 白名单等语义规则由 WorkflowValidator 继续校验。
    """

    model_config = ConfigDict(extra="forbid")

    version: Literal["1.0"]
    name: str = Field(min_length=1, pattern=r"^[A-Za-z_][A-Za-z0-9_-]*$")
    description: str | None = None
    inputs: dict[str, Any] = Field(default_factory=dict)
    metadata: dict[str, Any] = Field(default_factory=dict)
    nodes: list[WorkflowNode] = Field(min_length=2)

    @model_validator(mode="after")
    def validate_required_node_counts(self) -> "Workflow":
        """校验 workflow 最基础的入口、出口和节点唯一性。

        这些规则与具体执行器无关，因此放在模型层可以尽早拒绝明显非法的输入。
        """

        start_nodes = [node for node in self.nodes if node.type == "start"]
        end_nodes = [node for node in self.nodes if node.type == "end"]

        if len(start_nodes) != 1:
            raise ValueError("workflow must contain exactly one start node")
        if not end_nodes:
            raise ValueError("workflow must contain at least one end node")

        node_ids = [node.id for node in self.nodes]
        if len(node_ids) != len(set(node_ids)):
            raise ValueError("workflow node ids must be unique")

        return self
