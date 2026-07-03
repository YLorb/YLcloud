from __future__ import annotations

from pathlib import Path

from pydantic import ValidationError

from mini_agent_flow.engine.loader import WorkflowLoadError, WorkflowLoader
from mini_agent_flow.engine.validator import WorkflowValidationError
from mini_agent_flow.planner.models import TemplateCandidate, TemplateMetadata


class TemplateCatalogError(ValueError):
    """模板目录或模板内容无法安全加载时抛出的异常。"""


class WorkflowTemplateCatalog:
    """从受约束目录加载 JSON/YAML Workflow 模板。"""

    SUPPORTED_SUFFIXES = {".json", ".yaml", ".yml"}

    def __init__(self, template_dir: str | Path, loader: WorkflowLoader) -> None:
        self.template_dir = Path(template_dir)
        self.loader = loader

    def load(self) -> list[TemplateCandidate]:
        """加载目录中的模板，并严格校验每个模板的 metadata。"""

        root = self.template_dir.resolve()
        if not root.exists():
            raise TemplateCatalogError(f"template directory does not exist: {self.template_dir}")
        if not root.is_dir():
            raise TemplateCatalogError(f"template path is not a directory: {self.template_dir}")

        paths = sorted(
            path
            for path in root.iterdir()
            if path.is_file() and path.suffix.lower() in self.SUPPORTED_SUFFIXES
        )
        if not paths:
            raise TemplateCatalogError(f"template directory contains no workflow files: {root}")

        candidates = [self._load_candidate(root, path) for path in paths]
        self._validate_unique_names(candidates)
        return candidates

    def _load_candidate(self, root: Path, path: Path) -> TemplateCandidate:
        """加载单个模板，并阻止符号链接逃逸出模板目录。"""

        resolved_path = path.resolve()
        if not resolved_path.is_relative_to(root):
            raise TemplateCatalogError(f"template resolves outside template directory: {path}")

        try:
            workflow = self.loader.load(resolved_path)
            metadata = TemplateMetadata.model_validate(workflow.metadata)
        except (WorkflowLoadError, WorkflowValidationError, ValidationError) as exc:
            raise TemplateCatalogError(f"invalid workflow template {path.name}: {exc}") from exc

        undeclared_inputs = sorted(set(metadata.required_inputs) - set(workflow.inputs))
        if undeclared_inputs:
            raise TemplateCatalogError(
                f"template {path.name} does not declare required inputs: "
                f"{', '.join(undeclared_inputs)}"
            )

        return TemplateCandidate(path=resolved_path, workflow=workflow, metadata=metadata)

    def _validate_unique_names(self, candidates: list[TemplateCandidate]) -> None:
        """模板名是 Level 2 的稳定标识，目录内不允许重复。"""

        names = [candidate.workflow.name for candidate in candidates]
        duplicates = sorted(name for name in set(names) if names.count(name) > 1)
        if duplicates:
            raise TemplateCatalogError(
                f"template workflow names must be unique: {', '.join(duplicates)}"
            )
