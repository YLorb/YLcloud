from __future__ import annotations

import time
from dataclasses import dataclass, field
from time import sleep
from typing import Any

from mini_agent_flow.engine.context import WorkflowContext
from mini_agent_flow.engine.models import (
    ConditionNode,
    EndNode,
    LLMNode,
    StartNode,
    ToolNode,
    Workflow,
    WorkflowNode,
)
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


class NodeContractError(WorkflowExecutionError):
    """节点或 Tool 输出违反已声明 Schema；不得进入业务 Error Edge。"""

class NodeTimeoutError(Exception):
    """节点执行超时。"""     


class NodeCancelledError(Exception):
    """节点收到显式取消请求。"""


@dataclass(frozen=True)
class WorkflowRunResult:
    """workflow 执行结果快照。"""

    workflow_name: str
    context: dict[str, Any]
    final_output: Any | None
    executed_nodes: list[str]
    trace: list[dict[str, Any]]
    status: str = "completed"
    run_id: str | None = None
    execution_id: str | None = None
    active_end_nodes: list[str] = field(default_factory=list)
    handled_outcomes: list[dict[str, Any]] = field(default_factory=list)
    cleanup_errors: list[Any] = field(default_factory=list)


class SequentialWorkflowExecutor:
    """Level 1 顺序执行器。

    执行器从 start 节点开始，按每个节点的 next 字段顺序执行，直到遇到 end。
    当前支持 start / llm / tool / condition / end；llm/tool 支持节点级 retry，
    暂不处理 loop 或并发。
    """

    def __init__(
        self,
        llm: LLMClient,
        tool_registry: ToolRegistry,
        resolver: VariableResolver | None = None,
        max_steps: int = 100,
        allowed_permissions: set[str] | None = None,
        max_risk_level: int | None = None,
    ) -> None:
        """创建顺序执行器。"""

        if max_steps <= 0:
            raise WorkflowExecutionError("max_steps must be greater than 0")

        self.llm = llm
        self.tool_registry = tool_registry
        self.resolver = resolver or VariableResolver()
        self.max_steps = max_steps
        self.allowed_permissions = allowed_permissions
        self.max_risk_level = max_risk_level

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

            if isinstance(node, ConditionNode):
                current_node_id = self._execute_condition_node(node, context, trace_recorder)
                continue

            if isinstance(node, EndNode):
                final_output = self._execute_end_node(node, context, trace_recorder, workflow)
                return WorkflowRunResult(
                    workflow_name=workflow.name,
                    context=context.to_dict(),
                    final_output=final_output,
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

    def _execute_condition_node(
        self,
        node: ConditionNode,
        context: WorkflowContext,
        trace_recorder: TraceRecorder,
    ) -> str:
        """执行 condition 节点：解析表达式、判断真假、选择分支。"""

        context_before = context.to_dict()
        span = trace_recorder.start_span()

        try:
            resolved_value = self.resolver.resolve_value(node.expression, context)
            condition_result = self._evaluate_condition_value(resolved_value)
            selected_branch = "if_true" if condition_result else "if_false"
            next_node_id = node.if_true if condition_result else node.if_false
        except Exception as exc:
            error = WorkflowExecutionError(f"failed to execute condition node: {node.id}")
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
            raise error from exc

        trace_recorder.record_success(
            node_id=node.id,
            node_type=node.type,
            input_data={
                "expression": node.expression,
                "resolved_value": resolved_value,
                "if_true": node.if_true,
                "if_false": node.if_false,
            },
            output_data={
                "condition_result": condition_result,
                "selected_branch": selected_branch,
                "next": next_node_id,
            },
            context_before=context_before,
            context_after=context.to_dict(),
            span=span,
        )
        return next_node_id

    def _execute_end_node(
        self,
        node: EndNode,
        context: WorkflowContext,
        trace_recorder: TraceRecorder,
        workflow: Workflow,
    ) -> Any | None:
        """执行 end 节点、组装最终输出并记录 trace。"""

        context_before = context.to_dict()
        span = trace_recorder.start_span()

        try:
            final_output = self._build_final_output(workflow, context)
        except Exception as exc:
            error = WorkflowExecutionError(f"failed to build final output at end node: {node.id}")
            trace_recorder.record_failure(
                node_id=node.id,
                node_type=node.type,
                input_data={"outputs": workflow.outputs},
                error=error,
                context_before=context_before,
                context_after=context.to_dict(),
                span=span,
            )
            error.trace = trace_recorder.to_list()
            raise error from exc

        trace_recorder.record_success(
            node_id=node.id,
            node_type=node.type,
            input_data={"outputs": workflow.outputs},
            output_data={"final_output": final_output} if workflow.outputs else {},
            context_before=context_before,
            context_after=context.to_dict(),
            span=span,
        )
        return final_output

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
        
        start = time.monotonic()
        try:
            prompt = self.resolver.resolve_template(node.prompt, context)
            result = self.llm.generate(prompt)
            context.set(node.output, result)
        except Exception as exc:
            raise WorkflowExecutionError(f"failed to execute llm node: {node.id}") from exc

        elapsed = time.monotonic() - start
        if node.timeout_seconds and elapsed > node.timeout_seconds:
            raise NodeTimeoutError(f"timeout to execute llm node: {node.id}")
        return node.next, {"prompt": prompt}, {node.output: result}

    def _execute_tool_node(
        self,
        node: ToolNode,
        context: WorkflowContext,
    ) -> tuple[str, dict[str, Any], dict[str, Any]]:
        """执行 Tool 节点：解析 input、校验 spec、调用工具、写入 output。"""

        try:
            tool_input = self.resolver.resolve_value(node.input, context)
            tool = self.tool_registry.get(node.tool)
            self._validate_tool_spec(node.tool, tool_input)
            result = tool(tool_input)
            context.set(node.output, result)
        except WorkflowExecutionError:
            raise
        except Exception as exc:
            raise WorkflowExecutionError(f"failed to execute tool node: {node.id}") from exc

        return (
            node.next,
            {"tool": node.tool, "tool_input": tool_input},
            {node.output: result},
        )

    def _validate_tool_spec(self, tool_name: str, tool_input: Any) -> None:
        """基于 ToolSpec 进行运行时权限、风险等级和输入 schema 校验。"""

        spec = self.tool_registry.get_spec(tool_name)
        if spec is None:
            return

        if self.allowed_permissions is not None and spec.permission not in self.allowed_permissions:
            raise WorkflowExecutionError(
                f"tool {tool_name!r} permission {spec.permission!r} is not allowed"
            )
        if self.max_risk_level is not None and spec.risk_level > self.max_risk_level:
            raise WorkflowExecutionError(
                f"tool {tool_name!r} risk level {spec.risk_level} exceeds max {self.max_risk_level}"
            )
        if spec.execution_mode == "sandbox":
            raise WorkflowExecutionError(
                f"tool {tool_name!r} requires the graph sandbox executor"
            )

        self._validate_tool_input(tool_name, tool_input, spec.input_schema)

    def _validate_tool_input(
        self,
        tool_name: str,
        tool_input: Any,
        schema: dict[str, Any] | None,
    ) -> None:
        """校验工具输入是否符合简化的 JSON Schema 子集。

        支持的校验项包括：type、anyOf、items。使用内置实现避免引入额外
        编译依赖，同时覆盖当前内置工具所需的 schema 场景。
        """

        if schema is None:
            return

        if schema == {"type": "any"}:
            return

        errors = _validate_value_against_schema(tool_input, schema)
        if errors:
            message = "; ".join(errors)
            raise WorkflowExecutionError(
                f"tool {tool_name!r} input does not match schema: {message}"
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
        if isinstance(node, ConditionNode):
            return {
                "expression": node.expression,
                "if_true": node.if_true,
                "if_false": node.if_false,
            }
        return {}

    def _build_final_output(self, workflow: Workflow, context: WorkflowContext) -> Any | None:
        """根据 workflow.outputs 从 Context 中组装最终输出。"""

        if not workflow.outputs:
            return None
        if len(workflow.outputs) == 1:
            return context.require(workflow.outputs[0])
        return {output_name: context.require(output_name) for output_name in workflow.outputs}

    def _evaluate_condition_value(self, value: Any) -> bool:
        """把解析后的 condition 值转换为 bool。

        这里不执行表达式语言，只做 Python 基础类型的安全 truthy 判断。
        """

        if isinstance(value, bool):
            return value
        if value is None:
            return False
        if isinstance(value, (int, float)):
            return value != 0
        if isinstance(value, str):
            normalized_value = value.strip().lower()
            if normalized_value in {"", "false", "no", "0"}:
                return False
            if normalized_value in {"true", "yes", "1"}:
                return True
            return True
        return bool(value)


def _validate_value_against_schema(value: Any, schema: dict[str, Any]) -> list[str]:
    """递归校验值是否符合 schema，返回所有错误信息。"""

    errors: list[str] = []

    if "anyOf" in schema:
        sub_schemas = schema["anyOf"]
        if not isinstance(sub_schemas, list):
            return ["anyOf must be a list"]
        if not sub_schemas:
            return []
        sub_errors: list[str] = []
        for sub_schema in sub_schemas:
            local_errors = _validate_value_against_schema(value, sub_schema)
            if not local_errors:
                break
            sub_errors.append(f"({local_errors[0]})")
        else:
            errors.append(f"value does not match anyOf: {', '.join(sub_errors)}")
        return errors

    schema_type = schema.get("type")
    if schema_type is None:
        return errors

    if schema_type == "string" and not isinstance(value, str):
        errors.append(f"expected string, got {type(value).__name__}")
    elif schema_type == "integer" and (
        not isinstance(value, int) or isinstance(value, bool)
    ):
        errors.append(f"expected integer, got {type(value).__name__}")
    elif schema_type == "number" and (
        not isinstance(value, (int, float)) or isinstance(value, bool)
    ):
        errors.append(f"expected number, got {type(value).__name__}")
    elif schema_type == "boolean" and not isinstance(value, bool):
        errors.append(f"expected boolean, got {type(value).__name__}")
    elif schema_type == "array" and not isinstance(value, list):
        errors.append(f"expected array, got {type(value).__name__}")
    elif schema_type == "object" and not isinstance(value, dict):
        errors.append(f"expected object, got {type(value).__name__}")
    elif schema_type == "null" and value is not None:
        errors.append(f"expected null, got {type(value).__name__}")
    elif schema_type not in {"string", "integer", "number", "boolean", "array", "object", "null", "any"}:
        errors.append(f"unsupported schema type: {schema_type}")

    if schema_type == "array":
        items_schema = schema.get("items")
        if items_schema is not None and isinstance(value, list):
            for index, item in enumerate(value):
                item_errors = _validate_value_against_schema(item, items_schema)
                for item_error in item_errors:
                    errors.append(f"item {index}: {item_error}")

    if schema_type == "object" and isinstance(value, dict):
        properties = schema.get("properties", {})
        if not isinstance(properties, dict):
            errors.append("object properties must be an object")
            properties = {}
        required = schema.get("required", [])
        if not isinstance(required, list):
            errors.append("object required must be an array")
            required = []
        for name in required:
            if name not in value:
                errors.append(f"missing required property: {name}")
        for name, item in value.items():
            if name in properties:
                item_schema = properties[name]
            else:
                additional = schema.get("additionalProperties", True)
                if additional is False:
                    errors.append(f"unexpected property: {name}")
                    continue
                if additional is True:
                    continue
                item_schema = additional
            if isinstance(item_schema, dict):
                item_errors = _validate_value_against_schema(item, item_schema)
                for item_error in item_errors:
                    errors.append(f"property {name}: {item_error}")

    return errors
