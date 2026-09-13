from __future__ import annotations

from typing import Any, Protocol

from mini_agent_flow.engine.executor import WorkflowRunResult
from mini_agent_flow.engine.validator import WorkflowValidator
from mini_agent_flow.planner.catalog import WorkflowTemplateCatalog
from mini_agent_flow.planner.models import Level2RunResult
from mini_agent_flow.planner.selector import TemplateSelector


class Level2ExecutionError(RuntimeError):
    """模板输入无法填充或执行准备失败时抛出的异常。"""


class WorkflowExecutor(Protocol):
    def run(self, workflow: Any) -> WorkflowRunResult: ...


class Level2WorkflowService:
    """串联模板加载、选择、输入填充、校验和 Level 1 执行。"""

    def __init__(
        self,
        catalog: WorkflowTemplateCatalog,
        selector: TemplateSelector,
        validator: WorkflowValidator,
        executor: WorkflowExecutor,
    ) -> None:
        self.catalog = catalog
        self.selector = selector
        self.validator = validator
        self.executor = executor

    def run(self, goal: str) -> Level2RunResult:
        """根据 Goal 自动选择并执行一个 Workflow 模板。"""

        normalized_goal = goal.strip()
        if not normalized_goal:
            raise Level2ExecutionError("goal must not be empty")
        if len(normalized_goal) > 10_000:
            raise Level2ExecutionError("goal must not exceed 10000 characters")

        selection = self.selector.select(normalized_goal, self.catalog.load())
        workflow_data = selection.candidate.workflow.model_dump(mode="python")
        workflow_data["inputs"] = dict(workflow_data["inputs"])
        workflow_data["inputs"]["goal"] = normalized_goal

        missing_inputs = []
        for name in selection.candidate.metadata.required_inputs:
            value = workflow_data["inputs"].get(name)
            if value is None or value == "":
                missing_inputs.append(name)
        if missing_inputs:
            raise Level2ExecutionError(
                f"selected template requires inputs that cannot be filled: {', '.join(missing_inputs)}"
            )

        workflow = self.validator.validate_data(workflow_data)
        run_result = self.executor.run(workflow)
        return Level2RunResult(
            goal=normalized_goal,
            selected_workflow=selection.candidate.path.name,
            selection_reason=selection.selection_reason,
            matched_keywords=selection.matched_keywords,
            score=selection.score,
            final_output=run_result.final_output,
            context=run_result.context,
            executed_nodes=run_result.executed_nodes,
            trace=run_result.trace,
        )
