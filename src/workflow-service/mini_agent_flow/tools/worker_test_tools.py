from __future__ import annotations

import time
from typing import Any


def isolated_echo(value: Any) -> Any:
    return value


def isolated_sleep(seconds: Any) -> str:
    time.sleep(float(seconds))
    return "done"


def isolated_error(value: Any) -> Any:
    raise RuntimeError(f"worker failure: {value}")


def provider_tool_factory(config_ref: str, tool_name: str):
    """测试用可重建 Provider；生产 Provider 使用相同工厂协议。"""

    if tool_name != "prefixed_echo":
        raise KeyError(tool_name)

    def invoke(value: Any) -> Any:
        return {"config_ref": config_ref, "value": value}

    return invoke


def isolated_secret_names(value: Any, secrets: dict[str, str]) -> Any:
    return {"value": value, "secret_names": sorted(secrets)}


def isolated_secret_error(value: Any, secrets: dict[str, str]) -> Any:
    raise RuntimeError(f"failed with {secrets['API_KEY']}: {value}")
