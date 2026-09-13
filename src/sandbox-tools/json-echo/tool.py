from __future__ import annotations

import json
from pathlib import Path


request = json.loads(Path("/sandbox/request/request.json").read_text(encoding="utf-8"))
if not isinstance(request, dict):
    raise SystemExit(2)
Path("/sandbox/output/result.json").write_text(
    json.dumps({"value": request.get("value")}, ensure_ascii=False), encoding="utf-8"
)

