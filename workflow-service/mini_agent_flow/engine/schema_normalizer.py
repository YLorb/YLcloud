from __future__ import annotations

from copy import deepcopy
from typing import Any

from mini_agent_flow.engine.graph_models import GraphWorkflow, StateFieldSpec


class SchemaNormalizationError(ValueError):
    """外部简化 Schema 无法转换为内部 JSON Schema。"""


class SchemaNormalizer:
    """把项目简化类型标准化为 JSON Schema 子集。"""

    _SUPPORTED_TYPES = {
        "any",
        "string",
        "integer",
        "number",
        "boolean",
        "array",
        "object",
        "null",
    }

    def normalize_workflow_state(self, workflow: GraphWorkflow) -> dict[str, dict[str, Any]]:
        return {
            name: self.normalize_state_field(spec)
            for name, spec in workflow.state.items()
        }

    def normalize_state_field(self, spec: StateFieldSpec) -> dict[str, Any]:
        schema: dict[str, Any] = {} if spec.type == "any" else {"type": spec.type}
        if spec.nullable:
            schema = {"anyOf": [schema, {"type": "null"}]}
        if "default" in spec.model_fields_set:
            schema["default"] = deepcopy(spec.default)
        return schema

    def normalize(self, schema: dict[str, Any] | None) -> dict[str, Any]:
        if schema is None:
            return {}
        if not isinstance(schema, dict):
            raise SchemaNormalizationError("schema must be an object")
        normalized = deepcopy(schema)
        schema_type = normalized.get("type")
        if schema_type is None:
            return normalized
        if schema_type not in self._SUPPORTED_TYPES:
            raise SchemaNormalizationError(f"unsupported schema type: {schema_type}")
        if schema_type == "any":
            normalized.pop("type", None)
        if schema_type == "array" and "items" in normalized:
            normalized["items"] = self.normalize(normalized["items"])
        if schema_type == "object":
            properties = normalized.get("properties", {})
            if not isinstance(properties, dict):
                raise SchemaNormalizationError("object schema properties must be an object")
            normalized["properties"] = {
                name: self.normalize(child) for name, child in properties.items()
            }
            additional = normalized.get("additionalProperties")
            if isinstance(additional, dict):
                normalized["additionalProperties"] = self.normalize(additional)
        if "anyOf" in normalized:
            if not isinstance(normalized["anyOf"], list):
                raise SchemaNormalizationError("anyOf must be an array")
            normalized["anyOf"] = [self.normalize(item) for item in normalized["anyOf"]]
        return normalized
