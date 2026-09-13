from __future__ import annotations

import re

from mini_agent_flow.engine.execution_plan import ExecutionPlan, ExecutionPlanCompiler
from mini_agent_flow.engine.graph_models import GraphWorkflow
from mini_agent_flow.intent.planner import ResolvedIntentPlan


class IntentTaskGraphCompiler:
    """将已验证的意图依赖 DAG 确定性编译为现有 Workflow v2 IR。"""

    def __init__(self, compiler: ExecutionPlanCompiler | None = None) -> None:
        self.compiler = compiler or ExecutionPlanCompiler()

    def compile(self, resolved: ResolvedIntentPlan) -> ExecutionPlan:
        active = [task for task in resolved.plan.tasks if task.disposition == "ACTIVE"]
        active_ids = {task.task_id for task in active}
        nodes: list[dict] = [
            {"id": "start", "type": "start"},
            {"id": "end", "type": "end"},
        ]
        node_id_by_task: dict[str, str] = {}
        for index, task in enumerate(active, start=1):
            node_id = f"intent_{index}_{self._safe(task.task_id)}"
            node_id_by_task[task.task_id] = node_id
            nodes.append(
                {
                    "id": node_id,
                    "type": "llm",
                    "description": f"{task.role}:{task.intent.value}",
                    "prompt": (
                        "仅生成该任务的结构化中间 Context，不生成最终用户回答。\n"
                        f"任务：{task.instruction}\n原问题：{{{{ question }}}}"
                    ),
                    "output": f"intent_result_{index}",
                    "publish": False,
                    "output_schema": {"type": "object"},
                    "metadata": {
                        "task_id": task.task_id,
                        "intent": task.intent.value,
                        "context_sufficiency": task.context_sufficiency,
                        "confidence": task.confidence,
                    },
                }
            )
        edges: list[dict] = []
        if not active:
            edges.append({"from": "start", "to": "end"})
        else:
            dependents: dict[str, set[str]] = {task.task_id: set() for task in active}
            for task in active:
                dependencies = [item for item in task.dependencies if item in active_ids]
                if not dependencies:
                    edges.append({"from": "start", "to": node_id_by_task[task.task_id]})
                for dependency in dependencies:
                    dependents[dependency].add(task.task_id)
                    edges.append(
                        {
                            "from": node_id_by_task[dependency],
                            "to": node_id_by_task[task.task_id],
                        }
                    )
            for task_id, downstream in dependents.items():
                if not downstream:
                    edges.append({"from": node_id_by_task[task_id], "to": "end"})
        workflow = GraphWorkflow.model_validate(
            {
                "version": "2.0",
                "name": f"intent_plan_{self._safe(resolved.plan.primary_task_id)}",
                "description": "TASK-007 compiled structured intent plan",
                "inputs": {"question": ""},
                "metadata": {
                    "schema_version": "intent-plan/1.0",
                    "prompt_version": "intent-plan-prompt/1.0",
                    "complexity": resolved.plan.complexity,
                    "degraded": resolved.degraded,
                    "degraded_reasons": list(resolved.degraded_reasons),
                    "dropped_task_ids": list(resolved.dropped_task_ids),
                    "skipped_task_ids": [
                        task.task_id
                        for task in resolved.plan.tasks
                        if task.disposition == "SKIPPED_UNCERTAIN"
                    ],
                },
                "nodes": nodes,
                "edges": edges,
            }
        )
        return self.compiler.compile(workflow)

    @staticmethod
    def _safe(value: str) -> str:
        return re.sub(r"[^A-Za-z0-9_]", "_", value)
