from __future__ import annotations

import time
import warnings
from dataclasses import dataclass, replace
from time import sleep
from typing import Any, Literal
from uuid import uuid4

from mini_agent_flow.engine.context import WorkflowContext
from mini_agent_flow.engine.activation import ActivationTracker
from mini_agent_flow.engine.budget import BudgetExceededError, BudgetGuard
from mini_agent_flow.engine.executor import (
    NodeCancelledError,
    NodeContractError,
    NodeTimeoutError,
    WorkflowExecutionError,
    WorkflowRunResult,
    _validate_value_against_schema,
)
from mini_agent_flow.engine.graph_analyzer import CompiledGraph, LoopRegion, NetworkXGraphAnalyzer
from mini_agent_flow.engine.graph_compiler import V1ToV2Compiler
from mini_agent_flow.engine.execution_plan import ExecutionPlanCompiler
from mini_agent_flow.engine.dataflow_validator import StaticDataflowValidator
from mini_agent_flow.engine.schema_normalizer import SchemaNormalizer
from mini_agent_flow.engine.handlers import (
    NodeExecutionResult,
    NodeHandlerRegistry,
    NodeRuntimeContext,
)
from mini_agent_flow.engine.outcome import OutcomeEvent, OutcomeRouter
from mini_agent_flow.engine.loop_controller import LoopController, LoopReturnEvent
from mini_agent_flow.engine.run_controller import RunController
from mini_agent_flow.engine.runtime_config import RuntimeConfig
from mini_agent_flow.engine.graph_models import (
    EdgeCondition,
    GraphEdge,
    GraphEndNode,
    GraphLLMNode,
    GraphLoopNode,
    GraphMergeNode,
    GraphNode,
    GraphStartNode,
    GraphToolNode,
    GraphWorkflow,
)
from mini_agent_flow.engine.resolver import VariableResolver
from mini_agent_flow.engine.models import Workflow
from mini_agent_flow.engine.trace import TraceRecorder, V1TraceAdapter
from mini_agent_flow.llm.base import LLMClient
from mini_agent_flow.tools.registry import ToolRegistry
from mini_agent_flow.tools.secrets import SecretProvider
from mini_agent_flow.workers.isolated_process import IsolatedProcessRunner
from mini_agent_flow.persistence.sqlite_store import SQLiteRunControlStore


NodeStatus = Literal[
    "pending",
    "active",
    "inactive",
    "skipped",
    "running",
    "success",
    "failed",
    "timed_out",
    "cancelled",
]


@dataclass
class LoopFrame:
    loop_id: str
    iteration: int
    current_item: Any
    internal_node_states: dict[str, NodeStatus]
    internal_topological_order: tuple[str, ...]
    active_edges: list[tuple[str, str]]
    collected_outputs: list[Any]


class GraphWorkflowExecutor:
    """同步执行 Workflow v2：稳定拓扑序 + 节点激活 + 受控 Loop SCC。"""

    def __init__(
        self,
        llm: LLMClient,
        tool_registry: ToolRegistry,
        *,
        resolver: VariableResolver | None = None,
        analyzer: NetworkXGraphAnalyzer | None = None,
        max_steps: int = 1000,
        allowed_permissions: set[str] | None = None,
        max_risk_level: int | None = None,
        handler_registry: NodeHandlerRegistry | None = None,
        isolated_runner: IsolatedProcessRunner | None = None,
        run_control_store: SQLiteRunControlStore | None = None,
        runtime_config: RuntimeConfig | None = None,
        secret_provider: SecretProvider | None = None,
    ) -> None:
        if max_steps <= 0:
            raise WorkflowExecutionError("max_steps must be greater than 0")
        self.llm = llm
        self.tool_registry = tool_registry
        self.resolver = resolver or VariableResolver()
        self.analyzer = analyzer or NetworkXGraphAnalyzer()
        self.max_steps = max_steps
        self.allowed_permissions = allowed_permissions
        self.max_risk_level = max_risk_level
        self.handler_registry = handler_registry or NodeHandlerRegistry()
        self.run_control_store = run_control_store
        self.runtime_config = runtime_config or RuntimeConfig(max_steps=max_steps)
        self.isolated_runner = isolated_runner or IsolatedProcessRunner(
            worker_limit=self.runtime_config.isolated_worker_limit,
            max_ipc_bytes=self.runtime_config.max_ipc_bytes,
            cancel_grace_seconds=self.runtime_config.cancel_grace_seconds,
            debug_worker_output=self.runtime_config.debug_worker_output,
            max_debug_output_bytes=self.runtime_config.max_debug_output_bytes,
        )
        self.secret_provider = secret_provider
        self._step_count = 0
        self._budget: BudgetGuard | None = None

    def run(
        self,
        workflow: Workflow | GraphWorkflow,
        *,
        run_id: str | None = None,
        epoch: int = 1,
        restart_existing: bool = False,
    ) -> WorkflowRunResult:
        is_v1 = isinstance(workflow, Workflow)
        if isinstance(workflow, Workflow):
            workflow = V1ToV2Compiler().compile(workflow)
        self._budget = BudgetGuard(self.runtime_config)
        try:
            self._budget.validate_definition(
                node_count=len(workflow.nodes), edge_count=len(workflow.edges)
            )
        except BudgetExceededError as exc:
            raise WorkflowExecutionError(str(exc)) from exc
        plan = ExecutionPlanCompiler(analyzer=self.analyzer).compile(workflow)
        StaticDataflowValidator().analyze(plan)
        self._validate_runtime_tool_policies(workflow)
        compiled = plan.compiled_graph
        context = self._create_context(workflow)
        try:
            self._budget.ensure_context(context.to_dict())
        except BudgetExceededError as exc:
            raise WorkflowExecutionError(str(exc)) from exc
        trace = TraceRecorder()
        node_by_id = dict(plan.nodes_by_id)
        activation_tracker = ActivationTracker()
        outcome_router = OutcomeRouter(plan.edge_index)
        controller = RunController(
            workflow=workflow,
            tool_registry=self.tool_registry,
            config=self.runtime_config,
            store=self.run_control_store,
            run_id=run_id,
            epoch=epoch,
            restart_existing=restart_existing,
        )
        controller.start()
        run_id = controller.run_id
        execution_id = controller.execution_id
        pending_outcomes: dict[str, OutcomeEvent] = {}
        handled_outcomes: list[OutcomeEvent] = []
        active_end_nodes: list[str] = []
        cancellation_mode = False
        cleanup_node_ids: set[str] = set()
        cleanup_errors: list[str] = []
        states: dict[str, NodeStatus] = {
            node_id: "pending" for node_id in compiled.condensed_graph.nodes
        }
        start_id = next(node.id for node in workflow.nodes if isinstance(node, GraphStartNode))
        states[compiled.representative_by_node[start_id]] = "active"
        executed: list[str] = []
        self._step_count = 0

        try:
            for representative in compiled.topological_order:
                node = node_by_id[representative]
                if cancellation_mode and representative not in cleanup_node_ids and not isinstance(
                    node, GraphEndNode
                ):
                    states[representative] = "inactive"
                    self._record_inactive(node, context, trace)
                    continue
                if states[representative] != "active":
                    states[representative] = "inactive"
                    self._record_inactive(node, context, trace)
                    continue

                cancel_request = controller.consume_cancel_request(node.id)
                if cancel_request is not None:
                    states[representative] = "cancelled"
                    cancellation_mode = True
                    cleanup_target = self._route_cancel_outcome(
                        node=node,
                        reason=str(cancel_request.get("reason") or "USER_CANCELLED"),
                        run_id=run_id,
                        execution_id=execution_id,
                        invocation_id=str(uuid4()),
                        router=outcome_router,
                        compiled=compiled,
                        states=states,
                        tracker=activation_tracker,
                        pending_outcomes=pending_outcomes,
                        handled_outcomes=handled_outcomes,
                    )
                    if cleanup_target is None:
                        break
                    cleanup_node_ids.add(cleanup_target)
                    continue

                states[representative] = "running"
                self._consume_step()
                self._consume_handler_budget(node)
                executed.append(representative)
                incoming_outcome = pending_outcomes.pop(node.id, None)
                routed_outcome = False
                if representative in compiled.loop_regions:
                    loop_before = context.to_dict()
                    loop_span = trace.start_span()
                    loop_handle = controller.begin_invocation(node, attempt=1)
                    try:
                        self._execute_loop(
                            node=self._require_loop(node),
                            region=compiled.loop_regions[representative],
                            compiled=compiled,
                            context=context,
                            trace=trace,
                            node_by_id=node_by_id,
                            executed=executed,
                            controller=controller,
                        )
                        if not controller.accept_invocation(loop_handle, status="success"):
                            raise WorkflowExecutionError(
                                f"loop result lost CAS race: {node.id}"
                            )
                        controller.persist_node_commit(
                            loop_handle,
                            node=node,
                            outputs={},
                            context=context.to_dict(),
                        )
                    except Exception as exc:
                        if isinstance(exc, NodeContractError):
                            controller.accept_invocation(
                                loop_handle, status="failed", error=exc
                            )
                            raise
                        if isinstance(exc, NodeCancelledError):
                            controller.accept_invocation(
                                loop_handle, status="cancelled", error=exc
                            )
                            setattr(exc, "invocation_id", loop_handle.invocation_id)
                            setattr(exc, "attempt", 1)
                            states[representative] = "cancelled"
                            cancellation_mode = True
                            cleanup_target = self._route_cancel_outcome(
                                node=node,
                                reason="USER_CANCELLED",
                                run_id=run_id,
                                execution_id=execution_id,
                                invocation_id=str(
                                    getattr(exc, "invocation_id", uuid4())
                                ),
                                router=outcome_router,
                                compiled=compiled,
                                states=states,
                                tracker=activation_tracker,
                                pending_outcomes=pending_outcomes,
                                handled_outcomes=handled_outcomes,
                            )
                            if cleanup_target is None:
                                break
                            cleanup_node_ids.add(cleanup_target)
                            continue
                        is_timeout = isinstance(exc, NodeTimeoutError)
                        states[representative] = "timed_out" if is_timeout else "failed"
                        if isinstance(
                            exc, (WorkflowExecutionError, NodeTimeoutError, NodeCancelledError)
                        ):
                            error: BaseException = exc
                        else:
                            error = WorkflowExecutionError(str(exc))
                        setattr(error, "invocation_id", loop_handle.invocation_id)
                        setattr(error, "attempt", 1)
                        trace.record_failure(
                            node_id=node.id,
                            node_type=node.type,
                            input_data={"items": node.items},
                            error=error,
                            context_before=loop_before,
                            context_after=context.to_dict(),
                            span=loop_span,
                            status="timed_out" if is_timeout else "failed",
                        )
                        controller.accept_invocation(
                            loop_handle,
                            status="timed_out" if is_timeout else "failed",
                            error=error,
                        )
                        if incoming_outcome is not None and incoming_outcome.outcome == "cancelled":
                            cleanup_errors.append(str(error))
                            break
                        if incoming_outcome is not None:
                            raise WorkflowExecutionError(
                                f"outcome handler failed: {node.id}"
                            ) from error
                        routed_outcome = self._route_failure_outcome(
                            node=node,
                            error=error,
                            is_timeout=is_timeout,
                            run_id=run_id,
                            execution_id=execution_id,
                            router=outcome_router,
                            compiled=compiled,
                            states=states,
                            tracker=activation_tracker,
                            pending_outcomes=pending_outcomes,
                            handled_outcomes=handled_outcomes,
                        )
                        if not routed_outcome:
                            raise error
                else:
                    try:
                        states[representative] = self._execute_node(
                            node,
                            context,
                            trace,
                            outcome_event=incoming_outcome,
                            controller=controller,
                        )
                    except Exception as exc:
                        if isinstance(exc, NodeContractError):
                            raise
                        if isinstance(exc, NodeCancelledError):
                            states[representative] = "cancelled"
                            cancellation_mode = True
                            cleanup_target = self._route_cancel_outcome(
                                node=node,
                                reason="USER_CANCELLED",
                                run_id=run_id,
                                execution_id=execution_id,
                                invocation_id=str(
                                    getattr(exc, "invocation_id", uuid4())
                                ),
                                router=outcome_router,
                                compiled=compiled,
                                states=states,
                                tracker=activation_tracker,
                                pending_outcomes=pending_outcomes,
                                handled_outcomes=handled_outcomes,
                            )
                            if cleanup_target is None:
                                break
                            cleanup_node_ids.add(cleanup_target)
                            continue
                        is_timeout = isinstance(exc, NodeTimeoutError)
                        states[representative] = "timed_out" if is_timeout else "failed"
                        if incoming_outcome is not None and incoming_outcome.outcome == "cancelled":
                            cleanup_errors.append(str(exc))
                            break
                        if incoming_outcome is not None:
                            raise WorkflowExecutionError(
                                f"outcome handler failed: {node.id}"
                            ) from exc
                        routed_outcome = self._route_failure_outcome(
                            node=node,
                            error=exc,
                            is_timeout=is_timeout,
                            run_id=run_id,
                            execution_id=execution_id,
                            router=outcome_router,
                            compiled=compiled,
                            states=states,
                            tracker=activation_tracker,
                            pending_outcomes=pending_outcomes,
                            handled_outcomes=handled_outcomes,
                        )
                        if not routed_outcome:
                            raise
                if routed_outcome:
                    continue
                if representative in compiled.loop_regions:
                    states[representative] = "success"
                if isinstance(node, GraphEndNode):
                    active_end_nodes.append(node.id)
                self._activate_outer_successors(
                    source=representative,
                    compiled=compiled,
                    states=states,
                    context=context,
                    tracker=activation_tracker,
                    router=outcome_router,
                    source_status="skipped" if states[representative] == "skipped" else "success",
                )
        except NodeTimeoutError as exc:
            wrapped = WorkflowExecutionError(
                str(exc), trace=self._render_trace(trace, is_v1=is_v1)
            )
            controller.fail(wrapped, wrapped.trace, status="timed_out")
            raise wrapped from exc
        except WorkflowExecutionError as exc:
            exc.trace = self._render_trace(trace, is_v1=is_v1)
            controller.fail(exc, exc.trace)
            raise
        except Exception as exc:
            wrapped = WorkflowExecutionError(
                str(exc), trace=self._render_trace(trace, is_v1=is_v1)
            )
            controller.fail(wrapped, wrapped.trace)
            raise wrapped from exc

        if not active_end_nodes and not cancellation_mode:
            error = WorkflowExecutionError(
                "workflow completed without reaching an active end node",
                trace=self._render_trace(trace, is_v1=is_v1),
            )
            controller.fail(error, error.trace)
            raise error
        final_output = None if cancellation_mode else self._build_final_output(workflow, context)
        result_status = (
            "cancelled"
            if cancellation_mode
            else "completed_with_recovery" if handled_outcomes else "completed"
        )
        result = WorkflowRunResult(
            workflow_name=workflow.name,
            context=context.to_dict(),
            final_output=final_output,
            executed_nodes=executed,
            trace=self._render_trace(trace, is_v1=is_v1),
            status=result_status,
            run_id=run_id,
            execution_id=execution_id,
            active_end_nodes=active_end_nodes,
            handled_outcomes=[event.to_safe_dict() for event in handled_outcomes],
            cleanup_errors=cleanup_errors,
        )
        controller.complete(
            status=result.status,
            result_summary={
                "final_output": result.final_output,
                "active_end_nodes": result.active_end_nodes,
                "handled_outcomes": result.handled_outcomes,
            },
            trace=result.trace,
        )
        return result

    def _route_cancel_outcome(
        self,
        *,
        node: GraphNode,
        reason: str,
        run_id: str,
        execution_id: str,
        invocation_id: str,
        router: OutcomeRouter,
        compiled: CompiledGraph,
        states: dict[str, NodeStatus],
        tracker: ActivationTracker,
        pending_outcomes: dict[str, OutcomeEvent],
        handled_outcomes: list[OutcomeEvent],
    ) -> str | None:
        event = OutcomeEvent.create(
            run_id=run_id,
            execution_id=execution_id,
            invocation_id=invocation_id,
            node_id=node.id,
            outcome="cancelled",
            reason_code=reason,
            attempt=0,
            elapsed_ms=0,
        )
        handled_outcomes.append(event)
        edge = router.cancel_edge(node.id)
        if edge is None:
            return None
        target_rep = compiled.representative_by_node[edge.target]
        states[target_rep] = "active"
        pending_outcomes[edge.target] = event
        tracker.activate(
            edge_id=edge.id or "cancel",
            source_node_id=edge.source,
            target_node_id=edge.target,
            source_status="cancelled",
        )
        return edge.target

    def _validate_runtime_tool_policies(self, workflow: GraphWorkflow) -> None:
        nodes = {node.id: node for node in workflow.nodes}
        for node in workflow.nodes:
            if not isinstance(node, GraphToolNode):
                continue
            if not self.tool_registry.has(node.tool):
                raise WorkflowExecutionError(f"tool is not registered: {node.tool}")
        for edge in workflow.edges:
            if edge.kind != "cancel":
                continue
            target = nodes.get(edge.target)
            if not isinstance(target, GraphToolNode):
                raise WorkflowExecutionError("cancel edge must target a cleanup tool node")
            spec = self.tool_registry.get_spec(target.tool)
            if spec is None or not spec.cleanup_allowed or not spec.idempotent:
                raise WorkflowExecutionError(
                    f"cancel edge target tool must be idempotent and cleanup_allowed: {target.tool}"
                )
            if target.timeout_seconds is None or target.timeout_seconds > self.runtime_config.cleanup_timeout_seconds:
                raise WorkflowExecutionError(
                    f"cleanup tool timeout must be explicit and <= "
                    f"{self.runtime_config.cleanup_timeout_seconds}s: {target.id}"
                )

    def _create_context(self, workflow: GraphWorkflow) -> WorkflowContext:
        initial = dict(workflow.inputs)
        for name, spec in workflow.state.items():
            if name not in initial and "default" in spec.model_fields_set:
                initial[name] = spec.default
            elif name not in initial and spec.nullable:
                initial[name] = None
        initial["node_outputs"] = {}
        return WorkflowContext(initial)

    def _execute_node(
        self,
        node: GraphNode,
        context: WorkflowContext,
        trace: TraceRecorder,
        *,
        outcome_event: OutcomeEvent | None = None,
        controller: RunController,
        loop_id: str | None = None,
        iteration: int | None = None,
    ) -> NodeStatus:
        if loop_id is not None:
            self._consume_handler_budget(node)
        if node.skip_if is not None and self._evaluate_condition(node.skip_if, context):
            handle = controller.begin_invocation(node, attempt=1, iteration=iteration)
            skipped_outputs: dict[str, Any] = {}
            prepared_context: dict[str, Any] | None = None
            if node.skip_output_attribution == "self":
                for output_name, reference in node.skip_output_mapping.items():
                    source_node, source_output = reference.split(".", 1)
                    skipped_outputs[output_name] = self._read_node_output(
                        source_node, source_output, context
                    )
                if isinstance(node, (GraphLLMNode, GraphToolNode, GraphMergeNode)):
                    if node.output not in skipped_outputs:
                        raise NodeContractError(
                            f"skip_output_mapping must provide declared output: "
                            f"{node.id}.{node.output}"
                        )
                    self._validate_node_output(node, skipped_outputs[node.output])
                prepared_context = context.to_dict()
                output_store = prepared_context.setdefault("node_outputs", {})
                output_store.setdefault(node.id, {}).update(skipped_outputs)
                try:
                    self._require_budget().ensure_context(prepared_context)
                except BudgetExceededError as exc:
                    raise NodeContractError(str(exc)) from exc
            if not controller.accept_invocation(handle, status="skipped"):
                raise WorkflowExecutionError(f"skip result lost CAS race: {node.id}")
            if prepared_context is not None:
                context.update(prepared_context)
            self._record_skipped(
                node,
                context,
                trace,
                reason="skip_if matched",
                loop_id=loop_id,
                iteration=iteration,
            )
            controller.persist_node_commit(
                handle,
                node=node,
                outputs=skipped_outputs,
                context=context.to_dict(),
            )
            return "skipped"
        if isinstance(node, (GraphStartNode, GraphEndNode)):
            return self._execute_handler_once(
                node,
                context,
                trace,
                outcome_event=outcome_event,
                controller=controller,
                loop_id=loop_id,
                iteration=iteration,
            )
        if isinstance(node, (GraphLLMNode, GraphToolNode)):
            return self._execute_retryable(
                node,
                context,
                trace,
                outcome_event=outcome_event,
                controller=controller,
                loop_id=loop_id,
                iteration=iteration,
            )
        if isinstance(node, GraphMergeNode):
            return self._execute_handler_once(
                node,
                context,
                trace,
                outcome_event=outcome_event,
                controller=controller,
                loop_id=loop_id,
                iteration=iteration,
            )
        if isinstance(node, GraphLoopNode):
            raise WorkflowExecutionError(f"loop node must execute as a compiled region: {node.id}")
        raise WorkflowExecutionError(f"unsupported graph node type: {node.type}")

    def _execute_retryable(
        self,
        node: GraphLLMNode | GraphToolNode,
        context: WorkflowContext,
        trace: TraceRecorder,
        *,
        outcome_event: OutcomeEvent | None = None,
        controller: RunController,
        loop_id: str | None = None,
        iteration: int | None = None,
    ) -> NodeStatus:
        attempts = node.retry.max_attempts if node.retry else 1
        backoff = node.retry.backoff_seconds if node.retry else 0
        ordinary_failures = 0
        timeout_retry_used = False
        attempt = 0
        while True:
            try:
                self._require_budget().ensure_duration()
            except BudgetExceededError as exc:
                deadline_error = NodeTimeoutError(str(exc))
                setattr(deadline_error, "global_deadline_exhausted", True)
                raise deadline_error from exc
            attempt += 1
            handle = controller.begin_invocation(node, attempt=attempt, iteration=iteration)
            before = context.to_dict()
            span = trace.start_span()
            started = time.perf_counter()
            try:
                result = self.handler_registry.get(node.type).execute(
                    node,
                    self._handler_runtime(
                        context,
                        node,
                        outcome_event,
                        controller,
                        idempotency_key=handle.idempotency_key,
                    ),
                )
                elapsed = time.perf_counter() - started
                timeout_seconds = (
                    node.timeout_seconds
                    or self.runtime_config.default_node_timeout_seconds
                )
                if elapsed > timeout_seconds:
                    raise NodeTimeoutError(
                        f"timeout to execute {node.type} node: {node.id}"
                    )
                try:
                    self._require_budget().ensure_duration()
                except BudgetExceededError as exc:
                    deadline_error = NodeTimeoutError(str(exc))
                    setattr(deadline_error, "global_deadline_exhausted", True)
                    raise deadline_error from exc
                self._validate_handler_result(node, result)
                prepared_context = self._prepare_handler_commit(node, result, context)
                if not controller.accept_invocation(handle, status=result.disposition):
                    raise NodeTimeoutError(f"late result rejected for node: {node.id}")
                self._commit_handler_result(prepared_context, context)
                controller.persist_node_commit(
                    handle,
                    node=node,
                    outputs=result.outputs,
                    context=context.to_dict(),
                )
                trace.record_success(
                    node_id=node.id,
                    node_type=node.type,
                    input_data=result.input_data,
                    output_data=result.outputs,
                    context_before=before,
                    context_after=context.to_dict(),
                    span=span,
                    attempt=attempt,
                    loop_id=loop_id,
                    iteration=iteration,
                )
                return result.disposition
            except Exception as exc:
                if isinstance(exc, (WorkflowExecutionError, NodeTimeoutError, NodeCancelledError)):
                    error: BaseException = exc
                else:
                    error = WorkflowExecutionError(str(exc))
                trace.record_failure(
                    node_id=node.id,
                    node_type=node.type,
                    input_data=self._raw_node_input(node),
                    error=error,
                    context_before=before,
                    context_after=context.to_dict(),
                    span=span,
                    attempt=attempt,
                    loop_id=loop_id,
                    iteration=iteration,
                    status=(
                        "cancelled"
                        if isinstance(error, NodeCancelledError)
                        else "timed_out" if isinstance(error, NodeTimeoutError) else "failed"
                    ),
                )
                controller.accept_invocation(
                    handle,
                    status=(
                        "cancelled"
                        if isinstance(error, NodeCancelledError)
                        else "timed_out" if isinstance(error, NodeTimeoutError) else "failed"
                    ),
                    error=error,
                )
                setattr(error, "invocation_id", handle.invocation_id)
                setattr(error, "attempt", attempt)
                if isinstance(error, NodeContractError):
                    raise error
                if isinstance(error, NodeCancelledError):
                    raise error
                if isinstance(error, NodeTimeoutError):
                    if (
                        not getattr(error, "global_deadline_exhausted", False)
                        and not timeout_retry_used
                        and self._can_timeout_retry(node)
                    ):
                        timeout_retry_used = True
                        continue
                    raise error
                ordinary_failures += 1
                if ordinary_failures >= attempts:
                    raise error
                if backoff:
                    sleep(backoff)

    def _can_timeout_retry(self, node: GraphLLMNode | GraphToolNode) -> bool:
        if isinstance(node, GraphLLMNode):
            return True
        spec = self.tool_registry.get_spec(node.tool)
        return bool(spec and spec.idempotent)

    def _execute_handler_once(
        self,
        node: GraphStartNode | GraphEndNode | GraphMergeNode,
        context: WorkflowContext,
        trace: TraceRecorder,
        *,
        outcome_event: OutcomeEvent | None = None,
        controller: RunController,
        loop_id: str | None = None,
        iteration: int | None = None,
    ) -> NodeStatus:
        before = context.to_dict()
        span = trace.start_span()
        handle = controller.begin_invocation(node, attempt=1, iteration=iteration)
        try:
            result = self.handler_registry.get(node.type).execute(
                node,
                self._handler_runtime(
                    context,
                    node,
                    outcome_event,
                    controller,
                    idempotency_key=handle.idempotency_key,
                ),
            )
            self._validate_handler_result(node, result)
            prepared_context = self._prepare_handler_commit(node, result, context)
            if not controller.accept_invocation(handle, status=result.disposition):
                raise WorkflowExecutionError(f"node result lost CAS race: {node.id}")
            self._commit_handler_result(prepared_context, context)
            controller.persist_node_commit(
                handle,
                node=node,
                outputs=result.outputs,
                context=context.to_dict(),
            )
        except Exception as exc:
            error = (
                exc
                if isinstance(exc, (WorkflowExecutionError, NodeCancelledError))
                else WorkflowExecutionError(str(exc))
            )
            trace.record_failure(
                node_id=node.id,
                node_type=node.type,
                input_data={},
                error=error,
                context_before=before,
                context_after=context.to_dict(),
                span=span,
                loop_id=loop_id,
                iteration=iteration,
                status="cancelled" if isinstance(error, NodeCancelledError) else "failed",
            )
            controller.accept_invocation(
                handle,
                status="cancelled" if isinstance(error, NodeCancelledError) else "failed",
                error=error,
            )
            setattr(error, "invocation_id", handle.invocation_id)
            setattr(error, "attempt", 1)
            raise error
        trace.record_success(
            node_id=node.id,
            node_type=node.type,
            input_data=result.input_data,
            output_data=result.outputs,
            context_before=before,
            context_after=context.to_dict(),
            span=span,
            loop_id=loop_id,
            iteration=iteration,
        )
        return result.disposition

    def _handler_runtime(
        self,
        context: WorkflowContext,
        node: GraphNode,
        outcome_event: OutcomeEvent | None,
        controller: RunController,
        *,
        idempotency_key: str | None,
    ) -> NodeRuntimeContext:
        resolution_context = context
        safe_outcome: dict[str, Any] | None = None
        if outcome_event is not None:
            safe_outcome = outcome_event.to_safe_dict()
            mapped: dict[str, Any] = {}
            for local_name, event_field in node.outcome_inputs.items():
                if event_field not in safe_outcome:
                    raise WorkflowExecutionError(
                        f"outcome input references unknown field: {event_field}"
                    )
                mapped[local_name] = safe_outcome[event_field]
            resolution_context = WorkflowContext(mapped)
        return NodeRuntimeContext(
            context=resolution_context,
            resolver=self.resolver,
            llm=self.llm,
            tool_registry=self.tool_registry,
            allowed_permissions=self.allowed_permissions,
            max_risk_level=self.max_risk_level,
            isolation_risk_threshold=self.runtime_config.isolation_risk_threshold,
            secret_provider=self.secret_provider,
            cancellation_check=lambda: controller.consume_cancel_request(node.id) is not None,
            idempotency_key=idempotency_key,
            outcome_event=safe_outcome,
            isolated_runner=self.isolated_runner,
        )

    def _prepare_handler_commit(
        self,
        node: GraphNode,
        result: NodeExecutionResult,
        context: WorkflowContext,
    ) -> dict[str, Any] | None:
        if result.disposition == "skipped":
            return None
        self._validate_handler_result(node, result)
        candidate = context.to_dict()
        node_outputs = candidate.setdefault("node_outputs", {})
        for output_name, value in result.outputs.items():
            node_outputs.setdefault(node.id, {})[output_name] = value
        publish_patch = dict(result.publish_patch)
        for output_name, state_name in node.publish_mapping.items():
            publish_patch[state_name] = result.outputs[output_name]
        candidate.update(publish_patch)
        try:
            self._require_budget().ensure_context(candidate)
        except BudgetExceededError as exc:
            raise NodeContractError(str(exc)) from exc
        return candidate

    def _commit_handler_result(
        self,
        prepared_context: dict[str, Any] | None,
        context: WorkflowContext,
    ) -> None:
        if prepared_context is not None:
            context.update(prepared_context)

    def _validate_handler_result(
        self,
        node: GraphNode,
        result: NodeExecutionResult,
    ) -> None:
        if result.disposition == "skipped":
            return
        if isinstance(node, (GraphLLMNode, GraphToolNode, GraphMergeNode)):
            if node.output not in result.outputs:
                raise NodeContractError(
                    f"handler did not produce declared output: {node.id}.{node.output}"
                )
            self._validate_node_output(node, result.outputs[node.output])
        for output_name in node.publish_mapping:
            if output_name not in result.outputs:
                raise NodeContractError(
                    f"publish mapping references missing output: {node.id}.{output_name}"
                )

    def _execute_loop(
        self,
        *,
        node: GraphLoopNode,
        region: LoopRegion,
        compiled: CompiledGraph,
        context: WorkflowContext,
        trace: TraceRecorder,
        node_by_id: dict[str, GraphNode],
        executed: list[str],
        controller: RunController,
    ) -> None:
        before = context.to_dict()
        span = trace.start_span()
        items = self.resolver.resolve_value(node.items, context)
        if not isinstance(items, list):
            raise WorkflowExecutionError(f"loop items must resolve to a list: {node.id}")
        if len(items) > node.max_iterations:
            raise WorkflowExecutionError(
                f"loop {node.id} exceeds max_iterations: {node.max_iterations}"
            )
        if not items:
            warnings.warn(
                f"loop {node.id} resolved to an empty items list",
                RuntimeWarning,
                stacklevel=2,
            )

        collected: list[Any] = []
        outer_node_outputs = context.require("node_outputs")
        loop_controller = LoopController(
            run_id=controller.run_id, loop_id=node.id, item_count=len(items)
        )
        return_edge = next(
            (
                edge
                for edge in compiled.workflow.edges
                if edge.source == node.body_exit and edge.target == node.id
            ),
            None,
        )
        if return_edge is None or return_edge.id is None:
            raise WorkflowExecutionError(f"loop body has no explicit return edge: {node.id}")
        previous_item = context.get(node.item_var) if context.has(node.item_var) else None
        had_item = context.has(node.item_var)
        had_index = bool(node.index_var and context.has(node.index_var))
        previous_index = context.get(node.index_var) if had_index and node.index_var else None
        try:
            for iteration, item in enumerate(items):
                loop_controller.begin_iteration(iteration)
                try:
                    self._require_budget().consume_loop_iteration()
                except BudgetExceededError as exc:
                    raise WorkflowExecutionError(str(exc)) from exc
                context.set(node.item_var, item)
                # 每轮使用独立输出命名空间；完成后只把 Loop 声明输出带回外层。
                context.set("node_outputs", {})
                if node.index_var:
                    context.set(node.index_var, iteration)
                frame = LoopFrame(
                    loop_id=node.id,
                    iteration=iteration,
                    current_item=item,
                    internal_node_states={member: "pending" for member in region.internal_order},
                    internal_topological_order=region.internal_order,
                    active_edges=[],
                    collected_outputs=collected,
                )
                frame.internal_node_states[node.body_entry] = "active"
                for internal_id in frame.internal_topological_order:
                    internal_node = node_by_id[internal_id]
                    status = frame.internal_node_states[internal_id]
                    if status != "active":
                        frame.internal_node_states[internal_id] = "inactive"
                        self._record_inactive(
                            internal_node,
                            context,
                            trace,
                            loop_id=node.id,
                            iteration=iteration,
                        )
                        continue
                    self._consume_step()
                    frame.internal_node_states[internal_id] = "running"
                    executed.append(internal_id)
                    frame.internal_node_states[internal_id] = self._execute_node(
                        internal_node,
                        context,
                        trace,
                        controller=controller,
                        loop_id=node.id,
                        iteration=iteration,
                    )
                    self._activate_internal_successors(
                        source=internal_id,
                        region=region,
                        compiled=compiled,
                        states=frame.internal_node_states,
                        active_edges=frame.active_edges,
                        context=context,
                    )
                if frame.internal_node_states.get(node.body_exit) != "success":
                    raise WorkflowExecutionError(
                        f"loop body_exit was not reached in iteration {iteration}: {node.id}"
                    )
                if node.collect:
                    collect_item = self._read_node_output(
                        node.collect.node, node.collect.output, context
                    )
                    collect_schema = SchemaNormalizer().normalize(
                        node.collect.output_schema
                    )
                    collect_errors = _validate_value_against_schema(
                        collect_item, collect_schema
                    )
                    if collect_errors:
                        raise NodeContractError(
                            f"loop {node.id!r} collect item does not match output_schema: "
                            f"{'; '.join(collect_errors)}"
                        )
                    collected.append(collect_item)
                else:
                    collect_item = None
                decision = loop_controller.accept(
                    LoopReturnEvent.create(
                        run_id=controller.run_id,
                        loop_id=node.id,
                        loop_invocation_id=loop_controller.loop_invocation_id,
                        iteration=iteration,
                        attempt=1,
                        source_node_id=node.body_exit,
                        source_edge_id=return_edge.id,
                        arrival_reason="BODY_COMPLETED",
                        collect_item=collect_item,
                        controller_version=loop_controller.version,
                    )
                )
                if decision not in {"NEXT_ITERATION", "COMPLETE"}:
                    raise WorkflowExecutionError(
                        f"unexpected loop controller decision: {decision}"
                    )
        except Exception as exc:
            if loop_controller.state.value == "BODY_RUNNING":
                reason = (
                    "CANCELLED" if isinstance(exc, NodeCancelledError) else "FAILED"
                )
                source_node_id = locals().get("internal_id", node.body_exit)
                loop_controller.accept(
                    LoopReturnEvent.create(
                        run_id=controller.run_id,
                        loop_id=node.id,
                        loop_invocation_id=loop_controller.loop_invocation_id,
                        iteration=int(locals().get("iteration", 0)),
                        attempt=1,
                        source_node_id=str(source_node_id),
                        source_edge_id=(
                            f"runtime_{node.id}_{source_node_id}_{reason.lower()}"
                        ),
                        arrival_reason=reason,
                        error=exc,
                        retryable=False,
                        controller_version=loop_controller.version,
                    )
                )
            raise
        finally:
            context.set("node_outputs", outer_node_outputs)
            if had_item:
                context.set(node.item_var, previous_item)
            else:
                context.delete(node.item_var)
            if node.index_var:
                if had_index:
                    context.set(node.index_var, previous_index)
                else:
                    context.delete(node.index_var)

        output_data: dict[str, Any] = {"iterations": len(items)}
        if node.collect:
            self._store_node_output(node.id, node.collect.target, collected, context)
            if node.collect.publish:
                context.set(node.collect.target, collected)
            output_data[node.collect.target] = collected
        trace.record_success(
            node_id=node.id,
            node_type=node.type,
            input_data={"items": items, "max_iterations": node.max_iterations},
            output_data=output_data,
            context_before=before,
            context_after=context.to_dict(),
            span=span,
        )

    def _activate_outer_successors(
        self,
        *,
        source: str,
        compiled: CompiledGraph,
        states: dict[str, NodeStatus],
        context: WorkflowContext,
        tracker: ActivationTracker,
        router: OutcomeRouter,
        source_status: Literal["success", "skipped"],
    ) -> None:
        edges = router.success_edges(
            source,
            lambda edge: bool(
                edge.condition is not None
                and self._evaluate_condition(edge.condition, context)
            ),
        )
        for edge in edges:
            if compiled.representative_by_node[edge.target] == source:
                continue
            target = compiled.representative_by_node[edge.target]
            if edge.id is None:
                raise WorkflowExecutionError("compiled edge is missing an id")
            tracker.activate(
                edge_id=edge.id,
                source_node_id=edge.source,
                target_node_id=edge.target,
                source_status=source_status,
            )
            if states[target] == "pending":
                states[target] = "active"

    def _route_failure_outcome(
        self,
        *,
        node: GraphNode,
        error: BaseException,
        is_timeout: bool,
        run_id: str,
        execution_id: str,
        router: OutcomeRouter,
        compiled: CompiledGraph,
        states: dict[str, NodeStatus],
        tracker: ActivationTracker,
        pending_outcomes: dict[str, OutcomeEvent],
        handled_outcomes: list[OutcomeEvent],
    ) -> bool:
        event = OutcomeEvent.create(
            run_id=run_id,
            execution_id=execution_id,
            invocation_id=str(getattr(error, "invocation_id", uuid4())),
            node_id=node.id,
            outcome="timeout" if is_timeout else "error",
            reason_code="NODE_TIMEOUT" if is_timeout else "NODE_EXECUTION_FAILED",
            attempt=int(getattr(error, "attempt", 1)),
            elapsed_ms=0,
            error=error,
            retryable=False,
        )
        if is_timeout:
            edge = router.timeout_edge(node.id)
        else:
            event_context = WorkflowContext(event.to_safe_dict())
            edge = router.error_edge(
                node.id,
                lambda candidate: bool(
                    candidate.condition is not None
                    and self._evaluate_condition(candidate.condition, event_context)
                ),
            )
        if edge is None:
            return False
        if edge.id is None:
            raise WorkflowExecutionError("compiled outcome edge is missing an id")
        target = compiled.representative_by_node[edge.target]
        tracker.activate(
            edge_id=edge.id,
            source_node_id=edge.source,
            target_node_id=edge.target,
            source_status="recovered",
        )
        if states[target] == "pending":
            states[target] = "active"
        handled = replace(event, handled=True)
        pending_outcomes[edge.target] = handled
        handled_outcomes.append(handled)
        return True

    def _activate_internal_successors(
        self,
        *,
        source: str,
        region: LoopRegion,
        compiled: CompiledGraph,
        states: dict[str, NodeStatus],
        active_edges: list[tuple[str, str]],
        context: WorkflowContext,
    ) -> None:
        edges = [
            edge
            for edge in compiled.workflow.edges
            if edge.source == source
            and edge.target in region.member_node_ids
            and edge.target != region.loop_node_id
        ]
        for edge in self._select_edges(edges, context):
            active_edges.append((edge.source, edge.target))
            if states[edge.target] == "pending":
                states[edge.target] = "active"

    def _select_edges(self, edges: list[GraphEdge], context: WorkflowContext) -> list[GraphEdge]:
        selected = [
            edge
            for edge in edges
            if edge.kind in {"flow", "end", "loop_body_start"}
            and (edge.condition is None or self._evaluate_condition(edge.condition, context))
        ]
        if selected:
            return selected
        return [edge for edge in edges if edge.kind == "default"]

    def _evaluate_condition(self, condition: EdgeCondition, context: WorkflowContext) -> bool:
        left = self.resolver.resolve_value(condition.source, context)
        right = self.resolver.resolve_value(condition.value, context)
        operator = condition.operator
        if operator == "truthy":
            return self._truthy(left)
        if operator == "falsy":
            return not self._truthy(left)
        if operator == "equals":
            return left == right
        if operator == "not_equals":
            return left != right
        if operator == "greater_than":
            return left > right
        if operator == "greater_or_equal":
            return left >= right
        if operator == "less_than":
            return left < right
        if operator == "less_or_equal":
            return left <= right
        if operator == "contains":
            return right in left
        if operator == "in":
            return left in right
        raise WorkflowExecutionError(f"unsupported edge condition operator: {operator}")

    def _truthy(self, value: Any) -> bool:
        if isinstance(value, str):
            normalized = value.strip().lower()
            if normalized in {"", "false", "no", "0"}:
                return False
            if normalized in {"true", "yes", "1"}:
                return True
        return bool(value)

    def _store_node_output(
        self,
        node_id: str,
        output: str,
        value: Any,
        context: WorkflowContext,
    ) -> None:
        node_outputs = context.get("node_outputs", {})
        node_outputs.setdefault(node_id, {})[output] = value
        context.set("node_outputs", node_outputs)

    def _read_node_output(self, node_id: str, output: str, context: WorkflowContext) -> Any:
        node_outputs = context.require("node_outputs")
        try:
            return node_outputs[node_id][output]
        except (KeyError, TypeError) as exc:
            raise WorkflowExecutionError(
                f"node output is unavailable: {node_id}.{output}"
            ) from exc

    def _validate_node_output(
        self,
        node: GraphLLMNode | GraphToolNode | GraphMergeNode,
        value: Any,
    ) -> None:
        schema = SchemaNormalizer().normalize(node.output_schema)
        if not schema:
            return
        errors = _validate_value_against_schema(value, schema)
        if errors:
            raise NodeContractError(
                f"node {node.id!r} output does not match output_schema: {'; '.join(errors)}"
            )

    def _record_skipped(
        self,
        node: GraphNode,
        context: WorkflowContext,
        trace: TraceRecorder,
        *,
        reason: str = "node handler returned SkipResult",
        loop_id: str | None = None,
        iteration: int | None = None,
    ) -> None:
        before = context.to_dict()
        span = trace.start_span()
        data: dict[str, Any] = {"reason": reason}
        if loop_id is not None:
            data.update({"loop_id": loop_id, "iteration": iteration})
        trace.record_skipped(
            node_id=node.id,
            node_type=node.type,
            input_data=data,
            context_before=before,
            context_after=context.to_dict(),
            span=span,
            loop_id=loop_id,
            iteration=iteration,
        )

    def _record_inactive(
        self,
        node: GraphNode,
        context: WorkflowContext,
        trace: TraceRecorder,
        *,
        loop_id: str | None = None,
        iteration: int | None = None,
    ) -> None:
        before = context.to_dict()
        span = trace.start_span()
        trace.record_inactive(
            node_id=node.id,
            node_type=node.type,
            context_before=before,
            context_after=context.to_dict(),
            span=span,
            loop_id=loop_id,
            iteration=iteration,
        )

    def _raw_node_input(self, node: GraphLLMNode | GraphToolNode) -> dict[str, Any]:
        if isinstance(node, GraphLLMNode):
            return {"prompt_template": node.prompt}
        return {"tool": node.tool, "raw_input": node.input}

    def _build_final_output(self, workflow: GraphWorkflow, context: WorkflowContext) -> Any | None:
        if not workflow.outputs:
            return None
        if len(workflow.outputs) == 1:
            return context.require(workflow.outputs[0])
        return {name: context.require(name) for name in workflow.outputs}

    def _consume_step(self) -> None:
        self._step_count += 1
        if self._step_count > self.max_steps:
            raise WorkflowExecutionError(f"workflow exceeded max steps: {self.max_steps}")
        # node-type counters are consumed at the actual Handler boundary.

    def _consume_handler_budget(self, node: GraphNode) -> None:
        try:
            self._require_budget().consume_step(node.type)  # type: ignore[arg-type]
        except BudgetExceededError as exc:
            raise WorkflowExecutionError(str(exc)) from exc

    def _require_budget(self) -> BudgetGuard:
        if self._budget is None:
            raise WorkflowExecutionError("runtime budget was not initialized")
        return self._budget

    def _require_loop(self, node: GraphNode) -> GraphLoopNode:
        if not isinstance(node, GraphLoopNode):
            raise WorkflowExecutionError(f"compiled loop representative is invalid: {node.id}")
        return node

    def _render_trace(self, trace: TraceRecorder, *, is_v1: bool) -> list[dict[str, Any]]:
        events = self._require_budget().truncate_trace(trace.to_v2_list())
        if is_v1:
            return V1TraceAdapter().project(events)
        return events
