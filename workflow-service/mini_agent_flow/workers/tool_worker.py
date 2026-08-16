from __future__ import annotations

import importlib
import os
import sys
import traceback
from typing import Any

from mini_agent_flow.workers.protocol import PROTOCOL_VERSION, decode_message, encode_message


def tool_worker_main(
    request_connection: Any,
    result_connection: Any,
    cancel_event: Any,
    ready_event: Any,
    start_gate: Any,
    max_bytes: int,
    debug_worker_output: bool,
    max_debug_output_bytes: int,
) -> None:
    try:
        # Tool stdout/stderr 默认及 debug 模式均不继承父终端，避免旁路泄密。
        sys.stdout = open(os.devnull, "w", encoding="utf-8")
        sys.stderr = open(os.devnull, "w", encoding="utf-8")
        if sys.platform != "win32":
            os.setsid()
        ready_event.set()
        if not start_gate.wait(10):
            return
        request = decode_message(request_connection.recv_bytes(), max_bytes=max_bytes)
        invocation_id = request["invocation_id"]
        if cancel_event.is_set():
            response = {
                "protocol_version": PROTOCOL_VERSION,
                "invocation_id": invocation_id,
                "status": "cancelled",
            }
        else:
            loader = request.get("loader", {})
            if loader.get("kind") == "importable":
                module = importlib.import_module(loader["module"])
                tool = getattr(module, loader["function"])
            elif loader.get("kind") == "provider":
                module = importlib.import_module(loader["provider_module"])
                factory = getattr(module, loader["provider_factory"])
                tool = factory(loader["provider_config_ref"], loader["tool_name"])
            else:
                raise ValueError("unknown isolated tool loader kind")
            if not callable(tool):
                raise TypeError("isolated tool loader did not return a callable")
            secrets = request.get("secrets", {})
            if request.get("pass_idempotency_key"):
                output = tool(
                    request.get("input"),
                    secrets,
                    request.get("idempotency_key"),
                )
            elif secrets:
                output = tool(request.get("input"), secrets)
            else:
                output = tool(request.get("input"))
            response = {
                "protocol_version": PROTOCOL_VERSION,
                "invocation_id": invocation_id,
                "status": "success",
                "output": output,
            }
    except BaseException as exc:
        safe_message = str(exc)
        for secret_value in (locals().get("request", {}).get("secrets", {}) or {}).values():
            if secret_value:
                safe_message = safe_message.replace(str(secret_value), "[REDACTED]")
        response = {
            "protocol_version": PROTOCOL_VERSION,
            "invocation_id": locals().get("invocation_id", "unknown"),
            "status": "error",
            "safe_error_type": type(exc).__name__,
            "safe_error_message": safe_message,
        }
        if debug_worker_output:
            debug_traceback = traceback.format_exc(limit=5)
            response["debug_traceback"] = debug_traceback.encode("utf-8")[
                :max_debug_output_bytes
            ].decode("utf-8", errors="ignore")
    try:
        result_connection.send_bytes(encode_message(response, max_bytes=max_bytes))
    except BaseException:
        return
    finally:
        request_connection.close()
        result_connection.close()
