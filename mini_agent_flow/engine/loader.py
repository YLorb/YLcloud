from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from mini_agent_flow.engine.models import Workflow
from mini_agent_flow.engine.validator import WorkflowValidator


class WorkflowLoadError(ValueError):
    """workflow 文件加载失败时抛出的异常。

    Loader 只负责“文件与 JSON 层面”的错误，例如文件不存在、路径不是文件、
    后缀不是 .json、JSON 语法错误，或 JSON 顶层不是 object。
    """


class JsonWorkflowLoader:
    """JSON Workflow Loader。

    Loader 的职责是把 workflow.json 安全加载成 Workflow 对象。它不执行 workflow，
    也不直接判断节点语义是否合法，而是把已解析的 dict 交给 WorkflowValidator。
    """

    def __init__(self, validator: WorkflowValidator | None = None) -> None:
        """创建 Loader。

        validator 可以由调用方传入，用于配置 allowed_tools 等校验策略；
        不传时使用默认 WorkflowValidator。
        """

        self.validator = validator or WorkflowValidator()

    def load(self, path: str | Path) -> Workflow:
        """从 .json 文件加载并校验 workflow，返回标准 Workflow 对象。"""

        workflow_path = Path(path)
        self._validate_path(workflow_path)
        data = self._read_json(workflow_path)
        return self.load_data(data)

    def load_data(self, data: Any) -> Workflow:
        """加载已解析的 dict 数据。

        这个方法主要服务于测试和后续 Planner：AI 生成 JSON 后可以先转成 dict，
        再走同一套 Validator 校验，最终得到 Workflow 对象。
        """

        if not isinstance(data, dict):
            raise WorkflowLoadError("workflow data root must be an object")

        return self.validator.validate_data(data)

    def _validate_path(self, path: Path) -> None:
        """校验输入路径是一个存在的 JSON 文件。"""

        if not path.exists():
            raise WorkflowLoadError(f"workflow file does not exist: {path}")
        if not path.is_file():
            raise WorkflowLoadError(f"workflow path is not a file: {path}")
        if path.suffix.lower() != ".json":
            raise WorkflowLoadError(f"workflow file must use .json extension: {path}")

    def _read_json(self, path: Path) -> dict[str, Any]:
        """读取 JSON 文件，并确保顶层结构是 object。"""

        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as exc:
            raise WorkflowLoadError(f"invalid JSON workflow: {exc}") from exc
        except OSError as exc:
            raise WorkflowLoadError(f"cannot read workflow file: {path}") from exc

        # 检查data是否为字典
        return self._ensure_json_object(data)

    def _ensure_json_object(self, data: Any) -> dict[str, Any]:
        """确保 JSON 顶层是 object，并返回可交给 Validator 的 dict。"""
        # 在 json 中，object 表示字典，这里只是将 json 的字典转化为了 python 的字典

        if not isinstance(data, dict):
            raise WorkflowLoadError("workflow JSON root must be an object")

        return data
