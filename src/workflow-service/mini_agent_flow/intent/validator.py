from __future__ import annotations

import networkx as nx

from mini_agent_flow.intent.models import StructuredIntentPlan


class TaskGraphValidationError(ValueError):
    """结构化 Plan 不能安全编译时抛出。"""


class TaskGraphValidator:
    """验证无环依赖以及每个活动子任务都能到达唯一主任务。"""

    def validate(
        self, plan: StructuredIntentPlan, *, allow_skipped_primary: bool = False
    ) -> StructuredIntentPlan:
        graph = nx.DiGraph()
        graph.add_nodes_from(task.task_id for task in plan.tasks)
        for task in plan.tasks:
            for dependency in task.dependencies:
                graph.add_edge(dependency, task.task_id)
        if not nx.is_directed_acyclic_graph(graph):
            raise TaskGraphValidationError("task graph must be acyclic")
        primary = next(task for task in plan.tasks if task.task_id == plan.primary_task_id)
        if primary.disposition != "ACTIVE" and not allow_skipped_primary:
            raise TaskGraphValidationError("primary task must be active")
        for task in plan.tasks:
            if task.role != "SUBTASK" or task.disposition != "ACTIVE":
                continue
            if plan.primary_task_id not in nx.descendants(graph, task.task_id):
                raise TaskGraphValidationError(
                    f"active subtask does not contribute to primary task: {task.task_id}"
                )
        return plan
