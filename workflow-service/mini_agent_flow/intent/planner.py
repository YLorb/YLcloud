from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol
from uuid import UUID

from mini_agent_flow.contracts.models import ShortTermMessage
from mini_agent_flow.intent.models import (
    IntentPlanRequest,
    IntentPlanResponse,
    IntentTask,
    StructuredIntentPlan,
)
from mini_agent_flow.intent.validator import TaskGraphValidator


class IntentPlanModel(Protocol):
    def plan(self, run_id: UUID, request: IntentPlanRequest) -> IntentPlanResponse: ...


@dataclass(frozen=True, slots=True)
class IntentPlanningSettings:
    confidence_threshold: float = 0.7
    max_attempts: int = 4

    def __post_init__(self) -> None:
        if self.confidence_threshold != 0.7:
            raise ValueError("TASK-007 confidence threshold is fixed at 0.7")
        if not 1 <= self.max_attempts <= 10:
            raise ValueError("intent plan attempts must be between 1 and 10")


@dataclass(frozen=True, slots=True)
class ResolvedIntentPlan:
    plan: StructuredIntentPlan
    attempts: int
    degraded: bool
    degraded_reasons: tuple[str, ...]
    dropped_task_ids: tuple[str, ...]


class IntentPlanner:
    """执行语义重试；无关低置信子任务直接丢弃，安全不确定绝不降级执行。"""

    def __init__(
        self,
        model: IntentPlanModel,
        *,
        settings: IntentPlanningSettings | None = None,
        validator: TaskGraphValidator | None = None,
    ) -> None:
        self.model = model
        self.settings = settings or IntentPlanningSettings()
        self.validator = validator or TaskGraphValidator()

    def resolve(
        self,
        run_id: UUID,
        question: str,
        short_term_context: list[ShortTermMessage],
    ) -> ResolvedIntentPlan:
        dropped: set[str] = set()
        for attempt in range(1, self.settings.max_attempts + 1):
            try:
                response = self.model.plan(
                    run_id,
                    IntentPlanRequest(
                        contract_version="1.0",
                        schema_version="intent-plan/1.0",
                        prompt_version="intent-plan-prompt/1.0",
                        question=question,
                        short_term_context=short_term_context,
                        attempt=attempt,
                        confidence_threshold=0.7,
                    ),
                )
            except Exception as exc:
                if not getattr(exc, "retryable", False):
                    raise
                if attempt < self.settings.max_attempts:
                    continue
                return ResolvedIntentPlan(
                    self._fallback_plan(question),
                    attempt,
                    True,
                    ("RETRY_EXHAUSTED:INVALID_PLAN_RESPONSE",),
                    tuple(sorted(dropped)),
                )
            plan, newly_dropped = self._drop_irrelevant(response.plan)
            dropped.update(newly_dropped)
            self.validator.validate(plan)
            critical = self._critical_uncertainty(plan)
            optional = self._optional_uncertainty(plan, critical)
            if not critical:
                resolved = self._skip_tasks(plan, optional)
                self.validator.validate(resolved)
                return ResolvedIntentPlan(
                    resolved,
                    attempt,
                    bool(optional),
                    tuple(f"SKIPPED_UNCERTAIN:{task_id}" for task_id in sorted(optional)),
                    tuple(sorted(dropped)),
                )
            if attempt < self.settings.max_attempts:
                continue

            # 安全不确定的主任务直接跳过；路由不确定主任务仍可保留为纯 Context 任务，
            # 编译器只产生 LLM 中间节点，不会据此调用高风险 Tool。
            skipped = {
                task.task_id
                for task in plan.tasks
                if task.role == "SUBTASK" and task.task_id in critical
            }
            skipped = self._cascade_dependents(plan, skipped)
            if any(
                task.role == "PRIMARY" and task.uncertainty == "SECURITY"
                for task in plan.tasks
            ):
                # 主任务安全性无法确认时，所有前置子任务都失去合法执行目的。
                skipped.update(task.task_id for task in plan.tasks)
            skipped.update(optional)
            resolved = self._skip_tasks(plan, skipped)
            self.validator.validate(resolved, allow_skipped_primary=True)
            reasons = tuple(
                f"RETRY_EXHAUSTED:{task_id}" for task_id in sorted(critical)
            ) + tuple(f"SKIPPED_UNCERTAIN:{task_id}" for task_id in sorted(optional))
            return ResolvedIntentPlan(
                resolved, attempt, True, reasons, tuple(sorted(dropped))
            )
        raise AssertionError("intent planning loop must return")

    def _drop_irrelevant(
        self, plan: StructuredIntentPlan
    ) -> tuple[StructuredIntentPlan, set[str]]:
        dropped = {
            task.task_id
            for task in plan.tasks
            if task.role == "SUBTASK"
            and task.relevance == "IRRELEVANT"
            and task.confidence < self.settings.confidence_threshold
        }
        if not dropped:
            return plan, set()
        tasks = [
            task.model_copy(
                update={
                    "dependencies": [
                        dependency
                        for dependency in task.dependencies
                        if dependency not in dropped
                    ]
                }
            )
            for task in plan.tasks
            if task.task_id not in dropped
        ]
        return plan.model_copy(update={"tasks": tasks}), dropped

    def _critical_uncertainty(self, plan: StructuredIntentPlan) -> set[str]:
        return {
            task.task_id
            for task in plan.tasks
            if task.uncertainty in {"ROUTING", "SECURITY"}
            or (
                task.role == "PRIMARY"
                and (
                    task.confidence < self.settings.confidence_threshold
                    or task.intent.value == "UNKNOWN"
                )
            )
        }

    def _optional_uncertainty(
        self, plan: StructuredIntentPlan, critical: set[str]
    ) -> set[str]:
        return {
            task.task_id
            for task in plan.tasks
            if task.role == "SUBTASK"
            and task.task_id not in critical
            and task.confidence < self.settings.confidence_threshold
        }

    @staticmethod
    def _skip_tasks(
        plan: StructuredIntentPlan, skipped: set[str]
    ) -> StructuredIntentPlan:
        if not skipped:
            return plan
        tasks: list[IntentTask] = []
        for task in plan.tasks:
            updates: dict = {
                "dependencies": [
                    dependency for dependency in task.dependencies if dependency not in skipped
                ]
            }
            if task.task_id in skipped:
                updates["disposition"] = "SKIPPED_UNCERTAIN"
            tasks.append(task.model_copy(update=updates))
        return plan.model_copy(update={"tasks": tasks})

    @staticmethod
    def _cascade_dependents(
        plan: StructuredIntentPlan, initially_skipped: set[str]
    ) -> set[str]:
        skipped = set(initially_skipped)
        changed = True
        while changed:
            changed = False
            for task in plan.tasks:
                if task.task_id not in skipped and set(task.dependencies) & skipped:
                    skipped.add(task.task_id)
                    changed = True
        return skipped

    @staticmethod
    def _fallback_plan(question: str) -> StructuredIntentPlan:
        """模型持续不可用时只保留当前 Context 路径，不生成 Tool 路由。"""
        return StructuredIntentPlan(
            primary_task_id="primary",
            complexity="SIMPLE",
            tasks=[
                IntentTask(
                    task_id="primary",
                    role="PRIMARY",
                    intent="UNKNOWN",
                    instruction=question,
                    dependencies=[],
                    confidence=0.0,
                    context_sufficiency="UNKNOWN",
                    relevance="REQUIRED",
                    uncertainty="ROUTING",
                    disposition="ACTIVE",
                )
            ],
        )
