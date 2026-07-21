from mini_agent_flow.engine.executor import (
    NodeCancelledError,
    NodeContractError,
    NodeTimeoutError,
    WorkflowExecutionError,
    _validate_value_against_schema,
)
from mini_agent_flow.engine.graph_models import GraphNode, GraphToolNode
from mini_agent_flow.engine.handlers.base import NodeExecutionResult, NodeRuntimeContext
from mini_agent_flow.workers.isolated_process import (
    IsolatedToolCancelledError,
    IsolatedToolError,
    IsolatedToolTimeoutError,
)


class ToolNodeHandler:
    def execute(self, node: GraphNode, runtime: NodeRuntimeContext) -> NodeExecutionResult:
        if not isinstance(node, GraphToolNode):
            raise WorkflowExecutionError(f"tool handler cannot execute node: {node.id}")
        tool_input = runtime.resolver.resolve_value(node.input, runtime.context)
        spec = runtime.tool_registry.get_spec(node.tool)
        if spec is not None:
            if runtime.allowed_permissions is not None and spec.permission not in runtime.allowed_permissions:
                raise WorkflowExecutionError(
                    f"tool {node.tool!r} permission {spec.permission!r} is not allowed"
                )
            if runtime.max_risk_level is not None and spec.risk_level > runtime.max_risk_level:
                raise WorkflowExecutionError(
                    f"tool {node.tool!r} risk level {spec.risk_level} exceeds max "
                    f"{runtime.max_risk_level}"
                )
            if spec.risk_level >= runtime.isolation_risk_threshold and spec.execution_mode != "isolated_process":
                raise WorkflowExecutionError(
                    f"tool {node.tool!r} risk level {spec.risk_level} requires "
                    "isolated_process execution"
                )
            if spec.input_schema and spec.input_schema != {"type": "any"}:
                errors = _validate_value_against_schema(tool_input, spec.input_schema)
                if errors:
                    raise WorkflowExecutionError(
                        f"tool {node.tool!r} input does not match schema: {'; '.join(errors)}"
                    )

        secrets = self._resolve_secrets(
            spec.required_secret_names if spec is not None else (), runtime
        )
        if spec is not None and spec.execution_mode == "isolated_process":
            if runtime.isolated_runner is None:
                raise WorkflowExecutionError("isolated process runner is unavailable")
            try:
                value = runtime.isolated_runner.invoke(
                    spec,
                    tool_input,
                    timeout_seconds=node.timeout_seconds or 60,
                    secrets=secrets,
                    cancel_check=runtime.cancellation_check,
                    idempotency_key=runtime.idempotency_key,
                    pass_idempotency_key=spec.accepts_idempotency_key,
                )
            except IsolatedToolCancelledError as exc:
                raise NodeCancelledError(str(exc)) from exc
            except IsolatedToolTimeoutError as exc:
                raise NodeTimeoutError(str(exc)) from exc
            except IsolatedToolError as exc:
                raise WorkflowExecutionError(str(exc)) from exc
        else:
            tool = runtime.tool_registry.get(node.tool)
            try:
                if spec is not None and spec.accepts_idempotency_key:
                    value = tool(tool_input, secrets, runtime.idempotency_key)
                else:
                    value = tool(tool_input, secrets) if secrets else tool(tool_input)
            except Exception as exc:
                safe_message = str(exc)
                for secret_value in secrets.values():
                    if secret_value:
                        safe_message = safe_message.replace(secret_value, "[REDACTED]")
                raise WorkflowExecutionError(safe_message) from exc
        if spec is not None and spec.output_schema and spec.output_schema != {"type": "any"}:
            errors = _validate_value_against_schema(value, spec.output_schema)
            if errors:
                raise NodeContractError(
                    f"tool {node.tool!r} output does not match schema: {'; '.join(errors)}"
                )
        return NodeExecutionResult(
            input_data={"tool": node.tool, "tool_input": tool_input},
            outputs={node.output: value},
            publish_patch={node.output: value} if node.publish else {},
        )

    def _resolve_secrets(
        self, names: tuple[str, ...], runtime: NodeRuntimeContext
    ) -> dict[str, str]:
        if not names:
            return {}
        if runtime.secret_provider is None:
            raise WorkflowExecutionError("tool requires a configured SecretProvider")
        try:
            return {name: runtime.secret_provider.get_secret(name) for name in names}
        except KeyError as exc:
            raise WorkflowExecutionError(str(exc)) from exc
