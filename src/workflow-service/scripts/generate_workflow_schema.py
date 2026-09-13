from __future__ import annotations

import json
from pathlib import Path

from mini_agent_flow.engine.graph_models import GraphWorkflow


def main() -> None:
    schema = GraphWorkflow.model_json_schema(by_alias=True, mode="validation")
    schema["$schema"] = "https://json-schema.org/draft/2020-12/schema"
    schema["$id"] = "https://example.local/schemas/workflow-v2.schema.json"
    target = Path(__file__).resolve().parents[1] / "schemas" / "workflow-v2.schema.json"
    target.write_text(
        json.dumps(schema, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
