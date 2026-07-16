from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any

import yaml

from mini_agent_flow.engine.models import Workflow
from mini_agent_flow.engine.validator import WorkflowValidator


class WorkflowLoadError(ValueError):
    """workflow 文件加载失败时抛出的异常。

    Loader 只负责“文件与格式层面”的错误，例如文件不存在、路径不是文件、
    后缀不符合 Loader 要求、配置语法错误，或顶层不是 object。
    """


class WorkflowLoader:
    """统一 Workflow Loader。

    这个类是调用方优先使用的统一入口：它根据文件后缀选择 JSON 或 YAML Loader，
    让 CLI、Planner 和测试代码不需要散落一堆格式判断。
    """

    def __init__(self, validator: WorkflowValidator | None = None) -> None:
        """创建统一 Loader，并复用同一个 Validator 配置。"""

        self.validator = validator or WorkflowValidator()

    def load(self, path: str | Path) -> Workflow:
        """根据文件后缀自动加载 .json / .yaml / .yml workflow。"""

        workflow_path = Path(path)
        suffix = workflow_path.suffix.lower()

        if suffix == ".json":
            return JsonWorkflowLoader(validator=self.validator).load(workflow_path)
        if suffix in {".yaml", ".yml"}:
            return YamlWorkflowLoader(validator=self.validator).load(workflow_path)
        if suffix == ".md":
            return MarkdownWorkflowLoader(validator=self.validator).load(workflow_path)

        raise WorkflowLoadError(
            f"workflow file must use .json, .yaml, .yml or .md extension: {workflow_path}"
        )

    def load_data(self, data: Any) -> Workflow:
        """加载已解析的 dict 数据，供 AI 生成 workflow 后复用同一套校验。"""

        if not isinstance(data, dict):
            raise WorkflowLoadError("workflow data root must be an object")

        return self.validator.validate_data(data)


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


class YamlWorkflowLoader:
    """YAML Workflow Loader。

    YAML Loader 与 JSON Loader 的输出保持一致：只负责把 .yaml / .yml 文件解析成
    dict，再交给 WorkflowValidator 校验，最后返回标准 Workflow 对象。
    """

    def __init__(self, validator: WorkflowValidator | None = None) -> None:
        """创建 YAML Loader。"""

        self.validator = validator or WorkflowValidator()

    def load(self, path: str | Path) -> Workflow:
        """从 .yaml / .yml 文件加载并校验 workflow。"""

        workflow_path = Path(path)
        self._validate_path(workflow_path)
        data = self._read_yaml(workflow_path)
        return self.load_data(data)

    def load_data(self, data: Any) -> Workflow:
        """加载已解析的 YAML 数据。"""

        if not isinstance(data, dict):
            raise WorkflowLoadError("workflow YAML root must be an object")

        return self.validator.validate_data(data)

    def _validate_path(self, path: Path) -> None:
        """校验输入路径是一个存在的 YAML 文件。"""

        if not path.exists():
            raise WorkflowLoadError(f"workflow file does not exist: {path}")
        if not path.is_file():
            raise WorkflowLoadError(f"workflow path is not a file: {path}")
        if path.suffix.lower() not in {".yaml", ".yml"}:
            raise WorkflowLoadError(f"workflow file must use .yaml or .yml extension: {path}")

    def _read_yaml(self, path: Path) -> dict[str, Any]:
        """读取 YAML 文件，并确保顶层结构是 object。"""

        try:
            data = yaml.safe_load(path.read_text(encoding="utf-8"))
        except yaml.YAMLError as exc:
            raise WorkflowLoadError(f"invalid YAML workflow: {exc}") from exc
        except OSError as exc:
            raise WorkflowLoadError(f"cannot read workflow file: {path}") from exc

        return self._ensure_yaml_object(data)

    def _ensure_yaml_object(self, data: Any) -> dict[str, Any]:
        """确保 YAML 顶层是 object/mapping，并返回可交给 Validator 的 dict。"""

        if not isinstance(data, dict):
            raise WorkflowLoadError("workflow YAML root must be an object")

        return data

class MarkdownWorkflowLoader:
    """Markdown Workflow Loader。

    Markdown Loader 与 JSON Loader 的输出保持一致：只负责把 .md 文件解析成
    dict，再交给 WorkflowValidator 校验，最后返回标准 Workflow 对象。
    """

    def __init__(self, validator: WorkflowValidator | None = None) -> None:
        """创建 Markdown Loader。"""

        self.validator = validator or WorkflowValidator()

    def load(self, path: str | Path) -> Workflow:
        """从 .md 文件加载并校验 workflow。"""

        workflow_path = Path(path)
        self._validate_path(workflow_path)
        data = self._read_markdown(workflow_path)
        return self.load_data(data)

    def load_data(self, data: Any) -> Workflow:
        """加载已解析的 YAML 数据。"""

        if not isinstance(data, dict):
            raise WorkflowLoadError("workflow Markdown root must be an object")

        return self.validator.validate_data(data)

    def _validate_path(self, path: Path) -> None:
        """校验输入路径是一个存在的 Markdown 文件。"""

        if not path.exists():
            raise WorkflowLoadError(f"workflow file does not exist: {path}")
        if not path.is_file():
            raise WorkflowLoadError(f"workflow path is not a file: {path}")
        if path.suffix.lower() not in {".md"}:
            raise WorkflowLoadError(f"workflow file must use .md extension: {path}")

    # (self, path: Path) -> dict[str, Any]:
    def _read_markdown(self, path: Path) -> dict[str, Any]:
        text = path.read_text(encoding="utf-8")
        # 1. 提取 frontmatter → workflow 级元数据
        fm_match = re.match(r"^---\s*\n(.*?)\n---\s*\n", text, re.DOTALL)
        data = yaml.safe_load(fm_match.group(1))

        # 2. 按 ## 切分，每段解析为节点 dict
        body = text[fm_match.end():]
        matches = list(re.finditer(r"^## (\S+)\s*\n", body, re.MULTILINE))

        nodes = []
        for i, m in enumerate(matches):
            end = matches[i + 1].start() if i + 1 < len(matches) else len(body)
            node_data = yaml.safe_load(body[m.end():end].strip()) or {}
            node_data["id"] = m.group(1)
            nodes.append(node_data)

        data["nodes"] = nodes
        return data
    
    def _ensure_md_object(self, data: Any) -> dict[str, Any]:
        """确保 markdown 顶层是 object/mapping，并返回可交给 Validator 的 dict。"""

        if not isinstance(data, dict):
            raise WorkflowLoadError("workflow Markdown root must be an object")

        return data