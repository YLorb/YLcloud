from __future__ import annotations

import os
from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class ServiceSettings:
    """HTTP 层资源门禁；所有数值在应用创建前完成校验。"""

    max_request_bytes: int = 1_048_576
    request_timeout_seconds: float = 30.0
    max_concurrent_requests: int = 32
    expose_docs: bool = False

    def __post_init__(self) -> None:
        if self.max_request_bytes < 1:
            raise ValueError("max_request_bytes must be positive")
        if self.request_timeout_seconds <= 0:
            raise ValueError("request_timeout_seconds must be positive")
        if self.max_concurrent_requests < 1:
            raise ValueError("max_concurrent_requests must be positive")

    @classmethod
    def from_env(cls) -> "ServiceSettings":
        return cls(
            max_request_bytes=int(
                os.getenv("WORKFLOW_HTTP_MAX_REQUEST_BYTES", "1048576")
            ),
            request_timeout_seconds=float(
                os.getenv("WORKFLOW_HTTP_REQUEST_TIMEOUT_SECONDS", "30")
            ),
            max_concurrent_requests=int(
                os.getenv("WORKFLOW_HTTP_MAX_CONCURRENT_REQUESTS", "32")
            ),
            expose_docs=os.getenv("WORKFLOW_HTTP_EXPOSE_DOCS", "false").lower()
            in {"1", "true", "yes"},
        )
