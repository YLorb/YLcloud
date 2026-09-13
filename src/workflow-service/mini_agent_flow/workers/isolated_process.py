from __future__ import annotations

import multiprocessing
import threading
import time
from typing import Any, Mapping
from uuid import uuid4

from mini_agent_flow.tools.spec import (
    ImportableToolEntrypoint,
    ProcessToolProviderDescriptor,
    ToolSpec,
)
from mini_agent_flow.workers.process_tree import PosixProcessGroup, ProcessTreeError, attach_process_tree
from mini_agent_flow.workers.protocol import PROTOCOL_VERSION, decode_message, encode_message
from mini_agent_flow.workers.tool_worker import tool_worker_main


class IsolatedToolError(RuntimeError):
    pass


class IsolatedToolTimeoutError(IsolatedToolError):
    pass


class IsolatedToolCancelledError(IsolatedToolError):
    pass


class IsolatedProcessRunner:
    def __init__(
        self,
        *,
        worker_limit: int = 4,
        max_ipc_bytes: int = 1_048_576,
        cancel_grace_seconds: float = 2,
        debug_worker_output: bool = False,
        max_debug_output_bytes: int = 65_536,
    ) -> None:
        if (
            worker_limit < 1
            or max_ipc_bytes < 1
            or max_debug_output_bytes < 1
            or cancel_grace_seconds <= 0
        ):
            raise ValueError("isolated worker limits must be positive")
        self.max_ipc_bytes = max_ipc_bytes
        self.cancel_grace_seconds = cancel_grace_seconds
        self.debug_worker_output = debug_worker_output
        self.max_debug_output_bytes = max_debug_output_bytes
        self._slots = threading.BoundedSemaphore(worker_limit)
        self._context = multiprocessing.get_context("spawn")

    def invoke(
        self,
        spec: ToolSpec,
        input_value: Any,
        *,
        timeout_seconds: float,
        secrets: Mapping[str, str] | None = None,
        invocation_id: str | None = None,
        cancel_check: Any | None = None,
        idempotency_key: str | None = None,
        pass_idempotency_key: bool = False,
    ) -> Any:
        if spec.execution_mode != "isolated_process":
            raise IsolatedToolError(f"tool is not configured for process isolation: {spec.name}")
        if isinstance(spec.loader, ImportableToolEntrypoint):
            loader = {
                "kind": "importable",
                "module": spec.loader.module,
                "function": spec.loader.function,
            }
        elif isinstance(spec.loader, ProcessToolProviderDescriptor):
            provider_module, _, provider_factory = spec.loader.provider_type.partition(":")
            loader = {
                "kind": "provider",
                "provider_module": provider_module,
                "provider_factory": provider_factory,
                "provider_config_ref": spec.loader.provider_config_ref,
                "tool_name": spec.loader.tool_name,
            }
        else:
            raise IsolatedToolError("isolated tool loader descriptor is invalid")
        invocation_id = invocation_id or str(uuid4())
        request = {
            "protocol_version": PROTOCOL_VERSION,
            "invocation_id": invocation_id,
            "tool_id": spec.name,
            "loader": loader,
            "input": input_value,
            "secrets": dict(secrets or {}),
            "idempotency_key": idempotency_key,
            "pass_idempotency_key": pass_idempotency_key,
        }
        request_bytes = encode_message(request, max_bytes=self.max_ipc_bytes)
        if not self._slots.acquire(timeout=timeout_seconds):
            raise IsolatedToolTimeoutError("isolated worker slot deadline exceeded")
        process = None
        tree = None
        request_recv = request_send = result_recv = result_send = None
        try:
            request_recv, request_send = self._context.Pipe(duplex=False)
            result_recv, result_send = self._context.Pipe(duplex=False)
            cancel_event = self._context.Event()
            ready_event = self._context.Event()
            start_gate = self._context.Event()
            process = self._context.Process(
                target=tool_worker_main,
                args=(
                    request_recv,
                    result_send,
                    cancel_event,
                    ready_event,
                    start_gate,
                    self.max_ipc_bytes,
                    self.debug_worker_output,
                    self.max_debug_output_bytes,
                ),
                daemon=False,
            )
            process.start()
            tree = attach_process_tree(
                process,
                ready_event=ready_event,
                start_gate=start_gate,
            )
            request_recv.close()
            result_send.close()
            request_send.send_bytes(request_bytes)
            deadline = time.perf_counter() + timeout_seconds
            while True:
                if cancel_check is not None and bool(cancel_check()):
                    cancel_event.set()
                    process.join(timeout=self.cancel_grace_seconds)
                    if process.is_alive():
                        tree.terminate()
                        process.join(timeout=self.cancel_grace_seconds)
                    raise IsolatedToolCancelledError(
                        f"isolated tool was cancelled: {spec.name}"
                    )
                remaining = deadline - time.perf_counter()
                if remaining <= 0:
                    cancel_event.set()
                    process.join(timeout=self.cancel_grace_seconds)
                    if process.is_alive():
                        tree.terminate()
                        process.join(timeout=self.cancel_grace_seconds)
                    if process.is_alive():
                        if isinstance(tree, PosixProcessGroup):
                            tree.kill()
                        else:
                            process.kill()
                        process.join(timeout=self.cancel_grace_seconds)
                    raise IsolatedToolTimeoutError(
                        f"isolated tool exceeded timeout: {spec.name}"
                    )
                if result_recv.poll(min(0.05, remaining)):
                    response = decode_message(
                        result_recv.recv_bytes(), max_bytes=self.max_ipc_bytes
                    )
                    if response["invocation_id"] != invocation_id:
                        raise IsolatedToolError("worker response invocation_id mismatch")
                    status = response.get("status")
                    if status == "success":
                        process.join(timeout=self.cancel_grace_seconds)
                        return response.get("output")
                    if status == "cancelled":
                        raise IsolatedToolError("isolated tool was cancelled")
                    raise IsolatedToolError(
                        f"{response.get('safe_error_type', 'WorkerError')}: "
                        f"{response.get('safe_error_message', 'isolated tool failed')}"
                    )
                if not process.is_alive():
                    raise IsolatedToolError(
                        f"isolated tool exited without a response: {process.exitcode}"
                    )
        except ProcessTreeError as exc:
            raise IsolatedToolError(str(exc)) from exc
        finally:
            for connection in (request_recv, request_send, result_recv, result_send):
                if connection is not None:
                    try:
                        connection.close()
                    except OSError:
                        pass
            if tree is not None:
                tree.close()
            if process is not None and process.is_alive():
                process.terminate()
                process.join(timeout=self.cancel_grace_seconds)
            self._slots.release()
