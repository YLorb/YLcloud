from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Any


PROTOCOL_VERSION = 1


class WorkerProtocolError(ValueError):
    pass


def encode_message(message: dict[str, Any], *, max_bytes: int) -> bytes:
    try:
        payload = json.dumps(
            message,
            ensure_ascii=False,
            separators=(",", ":"),
            sort_keys=True,
        ).encode("utf-8")
    except (TypeError, ValueError) as exc:
        raise WorkerProtocolError("worker message must contain JSON-compatible data") from exc
    if len(payload) > max_bytes:
        raise WorkerProtocolError(
            f"worker message exceeds byte limit: {len(payload)} > {max_bytes}"
        )
    return payload


def decode_message(payload: bytes, *, max_bytes: int) -> dict[str, Any]:
    if len(payload) > max_bytes:
        raise WorkerProtocolError(
            f"worker message exceeds byte limit: {len(payload)} > {max_bytes}"
        )
    try:
        message = json.loads(payload.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise WorkerProtocolError("worker message is not valid UTF-8 JSON") from exc
    if not isinstance(message, dict):
        raise WorkerProtocolError("worker message root must be an object")
    if message.get("protocol_version") != PROTOCOL_VERSION:
        raise WorkerProtocolError("worker protocol version mismatch")
    if not isinstance(message.get("invocation_id"), str):
        raise WorkerProtocolError("worker message requires invocation_id")
    return message


@dataclass(frozen=True)
class WorkerResponse:
    invocation_id: str
    status: str
    output: Any = None
    safe_error_type: str | None = None
    safe_error_message: str | None = None
