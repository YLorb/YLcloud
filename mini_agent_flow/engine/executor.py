from __future__ import annotations

from dataclasses import dataclass
from time import sleep
from typing import Any

from mini_agent_flow.engine.context import WorkflowContext
from mini_agent_flow.engine.models import EndNode, LLMNode, StartNode, ToolNode, Workflow, WorkflowNode
from mini_agent_flow.engine.resolver import VariableResolver
from mini_agent_flow.engine.trace import TraceRecorder
from mini_agent_flow.llm.base import LLMClient
from mini_agent_flow.tools.registry import ToolRegistry


class WorkflowExecutionError(RuntimeError):
    """workflow 执行失败时抛出的异常。

    结构校验由 Validator 负责；Executor 仍保留防御性检查，用于捕获运行时错误、
    未支持节点类型、工具/LLM 调用失败和意外循环。
    """

    def __init__(self, message: str, trace: list[dict[str, Any]] | None = None) -> None:
        """创建执行异常，并可携带已记录的 trace。"""

        super().__init__(message)
        self.trace = trace or []


@dataclass(frozen=True)
class WorkflowRunResult:
    """workflow 执行结果快照。"""

    workflow_name: str
    context: dict[str, Any]
    final_output: Any | None
    executed_nodes: list[str]
    trace: list[dict[str, Any]]


class SequentialWorkflowExecutor:
    """Level 1 顺序执行器。

    执行器从 start 节点开始，按每个节点的 next 字段顺序执行，直到遇到 end。
    当前只支持 start / llm / tool / end；llm/tool 支持节点级 retry，
    暂不处理 condition、loop 或并发。
    """

    def __init__(
        self,
        llm: LLMClient,
        tool_registry: ToolRegistry,
        resolver: VariableResolver | None = None,
        max_steps: int = 100,
    ) -> None:
        """创建顺序执行器。"""

        if max_steps <= 0:
            raise WorkflowExecutionError("max_steps must be greater than 0")

        self.llm = llm
        self.tool_registry = tool_registry
        self.resolver = resolver or VariableResolver()
        self.max_steps = max_steps

    def run(self, workflow: Workflow) -> WorkflowRunResult:
        """执行 workflow，并返回最终 Context 快照。"""

        context = WorkflowContext.from_workflow(workflow)
        node_by_id = self._index_nodes(workflow)
        current_node_id = self._find_start_node(workflow).id
        executed_nodes: list[str] = []
        trace_recorder = TraceRecorder()

        for _ in range(self.max_steps):
            node = self._get_node(node_by_id, current_node_id)
            executed_nodes.append(node.id)

            if isinstance(node, StartNode):
                current_node_id = self._execute_start_node(node, context, trace_recorder)
                continue

            if isinstance(node, (LLMNode, ToolNode)):
                current_node_id = self._execute_with_retry(node, context, trace_recorder)
                continue

            if isinstance(node, EndNode):
                self._execute_end_node(node, context, trace_recorder)
                return WorkflowRunResult(
                    workflow_name=workflow.name,
                    context=context.to_dict(),
                    final_output=context.get("final_answer", None),
                    executed_nodes=executed_nodes,
                    trace=trace_recorder.to_list(),
                )

            self._record_unsupported_node_failure(node, context, trace_recorder)

        raise WorkflowExecutionError(
            f"workflow exceeded max steps: {self.max_steps}",
            trace=trace_recorder.to_list(),
        )

    def _execute_start_node(
        self,
        node: StartNode,
        context: WorkflowContext,
        trace_recorder: TraceRecorder,
    ) -> str:
        """执行 start 节点并记录 trace。"""

        context_before = context.to_dict()
        span = trace_recorder.start_span()
        trace_recorder.record_success(
            node_id=node.id,
            node_type=node.type,
            input_data={"next": node.next},
            output_data={"next": node.next},
            context_before=context_before,
            context_after=context.to_dict(),
            span=span,
        )
        return node.next

    def _execute_end_node(
        self,
        node: EndNode,
        context: WorkflowContext,
        trace_recorder: TraceRecorder,
    ) -> None:
        """执行 end 节点并记录 trace。"""

        context_before = context.to_dict()
        span = trace_recorder.start_span()
        trace_recorder.record_success(
            node_id=node.id,
            node_type=node.type,
            input_data={},
            output_data={},
            context_before=context_before,
            context_after=context.to_dict(),
            span=span,
        )

    def _execute_with_retry(
        self,
        node: LLMNode | ToolNode,
        context: WorkflowContext,
        trace_recorder: TraceRecorder,
    ) -> str:
        """按节点 retry 策略执行 LLM / Tool 节点。"""

        max_attempts = node.retry.max_attempts if node.retry else 1
        backoff_seconds = node.retry.backoff_seconds if node.retry else 0

        for attempt in range(1, max_attempts + 1):
            context_before = context.to_dict()
            span = trace_recorder.start_span()

            try:
                next_node_id, input_data, output_data = self._execute_retryable_node(node, context)
            except Exception as exc:
                error = exc if isinstance(exc, WorkflowExecutionError) else WorkflowExecutionError(str(exc))
                trace_recorder.record_failure(
                    node_id=node.id,
                    node_type=node.type,
                    input_data=self._failure_input_for_node(node),
                    error=error,
                    context_before=context_before,
                    context_after=context.to_dict(),
                    span=span,
                    attempt=attempt,
                )
                if attempt < max_attempts:
                    if backoff_seconds > 0:
                        sleep(backoff_seconds)
                    continue

                if isinstance(error, WorkflowExecutionError):
                    error.trace = trace_recorder.to_list()
                    raise error
                raise WorkflowExecutionError(
                    f"failed to execute node: {node.id}",
                    trace=trace_recorder.to_list(),
                ) from exc

            trace_recorder.record_success(
                node_id=node.id,
                node_type=node.type,
                input_data=input_data,
                output_data=output_data,
                context_before=context_before,
                context_after=context.to_dict(),
                span=span,
                attempt=attempt,
            )
            return next_node_id

        raise WorkflowExecutionError(
            f"failed to execute node after retry: {node.id}",
            trace=trace_recorder.to_list(),
        )

    def _execute_retryable_node(
        self,
        node: LLMNode | ToolNode,
        context: WorkflowContext,
    ) -> tuple[str, dict[str, Any], dict[str, Any]]:
        """执行支持 retry 的节点。"""

        if isinstance(node, LLMNode):
            return self._execute_llm_node(node, context)
        return self._execute_tool_node(node, context)

    def _execute_llm_node(
        self,
        node: LLMNode,
        context: WorkflowContext,
    ) -> tuple[str, dict[str, Any], dict[str, Any]]:
        """执行 LLM 节点：渲染 prompt、调用 llm、写入 output。"""

        try:
            prompt = self.resolver.resolve_template(node.prompt, context)
            result = self.llm.generate(prompt)
            context.set(node.output, result)
        except Exception as exc:
            raise WorkflowExecutionError(f"failed to execute llm node: {node.id}") from exc

        return node.next, {"prompt": prompt}, {node.output: result}

    def _execute_tool_node(
        self,
        node: ToolNode,
        context: WorkflowContext,
    ) -> tuple[str, dict[str, Any], dict[str, Any]]:
        """执行 Tool 节点：解析 input、获取工具、调用工具、写入 output。"""

        try:
            tool_input = self.resolver.resolve_value(node.input, context)
            tool = self.tool_registry.get(node.tool)
            result = tool(tool_input)
            context.set(node.output, result)
        except Exception as exc:
            raise WorkflowExecutionError(f"failed to execute tool node: {node.id}") from exc

        return (
            node.next,
            {"tool": node.tool, "tool_input": tool_input},
            {node.output: result},
        )

    def _index_nodes(self, workflow: Workflow) -> dict[str, WorkflowNode]:
        """建立 node_id 到节点对象的映射。"""

        return {node.id: node for node in workflow.nodes}

    def _find_start_node(self, workflow: Workflow) -> StartNode:
        """获取 start 节点。"""

        for node in workflow.nodes:
            if isinstance(node, StartNode):
                return node
        raise WorkflowExecutionError("workflow start node is missing")

    def _get_node(self, node_by_id: dict[str, WorkflowNode], node_id: str) -> WorkflowNode:
        """按 id 获取节点；找不到时抛运行时错误。"""

        try:
            return node_by_id[node_id]
        except KeyError as exc:
            raise WorkflowExecutionError(f"workflow points to unknown node: {node_id}") from exc

    def _record_unsupported_node_failure(
        self,
        node: WorkflowNode,
        context: WorkflowContext,
        trace_recorder: TraceRecorder,
    ) -> None:
        """记录未支持节点类型的失败 trace，并抛出执行错误。"""

        context_before = context.to_dict()
        span = trace_recorder.start_span()
        error = WorkflowExecutionError(f"unsupported node type: {node.type}")
        trace_recorder.record_failure(
            node_id=node.id,
            node_type=node.type,
            input_data=self._failure_input_for_node(node),
            error=error,
            context_before=context_before,
            context_after=context.to_dict(),
            span=span,
        )
        error.trace = trace_recorder.to_list()
        raise error

    def _failure_input_for_node(self, node: WorkflowNode) -> dict[str, Any]:
        """为失败 trace 生成可读的原始输入信息。"""

        if isinstance(node, StartNode):
            return {"next": node.next}
        if isinstance(node, LLMNode):
            return {"prompt_template": node.prompt}
        if isinstance(node, ToolNode):
            return {"tool": node.tool, "raw_input": node.input}
        return {}
