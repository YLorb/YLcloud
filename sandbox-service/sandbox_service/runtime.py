from __future__ import annotations

import json
import os
import subprocess
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Protocol

from sandbox_service.catalog import SandboxToolDefinition


@dataclass(frozen=True, slots=True)
class RuntimeResult:
    result: Any
    exit_code: int
    resource_usage: dict[str, int | float]


class RuntimeExecutionError(RuntimeError):
    def __init__(self, message: str, *, exit_code: int | None = None) -> None:
        super().__init__(message)
        self.exit_code = exit_code


class RuntimeTimeoutError(RuntimeExecutionError):
    pass


class RuntimeResourceError(RuntimeExecutionError):
    pass


class SandboxRuntime(Protocol):
    def execute(self, invocation_id: str, tool: SandboxToolDefinition, arguments: dict[str, Any], timeout: int) -> RuntimeResult: ...
    def cancel(self, invocation_id: str) -> None: ...


class DockerRuntime:
    """Executes fixed images without a shell; request data is passed by a mounted JSON file."""

    def __init__(self, docker_binary: str = "docker", work_root: str | None = None,
                 work_volume: str | None = None) -> None:
        self.docker_binary = docker_binary
        self.work_root = work_root or os.getenv("SANDBOX_WORK_ROOT")
        self.work_volume = work_volume or os.getenv("SANDBOX_WORK_VOLUME")
        if self.work_volume and not self.work_root:
            raise ValueError("SANDBOX_WORK_ROOT is required when SANDBOX_WORK_VOLUME is configured")

    def build_command(self, invocation_id: str, tool: SandboxToolDefinition, root: Path) -> list[str]:
        limits = tool.limits
        container_name = f"ylcloud-sbx-{invocation_id.lower()}"
        command = [
            self.docker_binary, "run", "--rm", "--name", container_name, "--network", "none",
            "--read-only", "--user", "65532:65532", "--cap-drop", "ALL",
            "--security-opt", "no-new-privileges=true", "--pids-limit", str(limits.pids),
            "--cpus", str(limits.cpus), "--memory", str(limits.memory_bytes),
            "--memory-swap", str(limits.memory_bytes),
            "--tmpfs", f"/tmp:rw,noexec,nosuid,nodev,size={limits.tmpfs_bytes}",
        ]
        if self.work_volume:
            command.extend(["--mount", f"type=volume,src={self.work_volume},dst=/sandbox,volume-subpath={root.name}"])
        else:
            command.extend([
                "--mount", f"type=bind,src={root / 'request'},dst=/sandbox/request,readonly",
                "--mount", f"type=bind,src={root / 'output'},dst=/sandbox/output",
            ])
        command.extend(["--entrypoint", tool.entrypoint[0], tool.image, *tool.entrypoint[1:]])
        return command

    def execute(self, invocation_id: str, tool: SandboxToolDefinition, arguments: dict[str, Any], timeout: int) -> RuntimeResult:
        with tempfile.TemporaryDirectory(prefix="ylcloud-sandbox-", dir=self.work_root) as temp:
            root = Path(temp).resolve()
            request_dir, output_dir = root / "request", root / "output"
            request_dir.mkdir(mode=0o700)
            output_dir.mkdir(mode=0o700)
            request_file = request_dir / "request.json"
            request_file.write_text(json.dumps(arguments), encoding="utf-8")
            # Random parent remains unlistable; the container can only traverse to its two bind mounts.
            root.chmod(0o711)
            request_dir.chmod(0o555)
            request_file.chmod(0o444)
            output_dir.chmod(0o733)
            command = self.build_command(invocation_id, tool, root)
            try:
                completed = subprocess.run(command, capture_output=True, text=True, timeout=timeout, shell=False)
            except subprocess.TimeoutExpired as exc:
                self.cancel(invocation_id)
                raise RuntimeTimeoutError("sandbox tool exceeded its deadline") from exc
            if completed.returncode != 0:
                error_type = RuntimeResourceError if completed.returncode in {137, 143} else RuntimeExecutionError
                raise error_type("sandbox tool failed", exit_code=completed.returncode)
            result_file = output_dir / "result.json"
            if not result_file.is_file() or result_file.is_symlink():
                raise RuntimeExecutionError("sandbox tool did not produce a regular result.json")
            if result_file.stat().st_size > tool.limits.max_result_bytes:
                raise RuntimeResourceError("sandbox result exceeds the configured byte limit")
            result = json.loads(result_file.read_text(encoding="utf-8"))
            return RuntimeResult(result=result, exit_code=completed.returncode, resource_usage={})

    def cancel(self, invocation_id: str) -> None:
        subprocess.run(
            [self.docker_binary, "rm", "--force", f"ylcloud-sbx-{invocation_id.lower()}"],
            capture_output=True, text=True, timeout=10, shell=False, check=False,
        )


class InMemoryRuntime:
    def __init__(self, handlers: dict[tuple[str, str], Any]) -> None:
        self.handlers = handlers
        self.calls = 0

    def execute(self, invocation_id: str, tool: SandboxToolDefinition, arguments: dict[str, Any], timeout: int) -> RuntimeResult:
        self.calls += 1
        handler = self.handlers[(tool.name, tool.version)]
        return RuntimeResult(result=handler(arguments), exit_code=0, resource_usage={})

    def cancel(self, invocation_id: str) -> None:
        return None
