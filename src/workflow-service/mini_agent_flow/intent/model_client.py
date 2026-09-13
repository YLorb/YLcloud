from __future__ import annotations

import json
import os
from dataclasses import dataclass
from urllib.parse import urlsplit
from uuid import UUID

import httpx

from mini_agent_flow.intent.models import IntentPlanRequest, IntentPlanResponse
from mini_agent_flow.security.service_jwt import (
    MODEL_SERVICE_AUDIENCE,
    WorkflowServiceTokenIssuer,
)


@dataclass(frozen=True, slots=True)
class IntentPlanClientSettings:
    base_url: str
    timeout_seconds: float = 120.0
    max_request_bytes: int = 2_000_000
    max_response_bytes: int = 1_048_576

    def __post_init__(self) -> None:
        parsed = urlsplit(self.base_url)
        if (
            parsed.scheme not in {"http", "https"}
            or not parsed.hostname
            or parsed.username is not None
            or parsed.password is not None
            or parsed.query
            or parsed.fragment
            or parsed.path not in {"", "/"}
        ):
            raise ValueError("model service base_url must be an HTTP(S) service root")
        if self.timeout_seconds <= 0 or min(self.max_request_bytes, self.max_response_bytes) < 1:
            raise ValueError("model service resource limits must be positive")

    @classmethod
    def from_env(cls) -> "IntentPlanClientSettings | None":
        base_url = os.getenv("WORKFLOW_MODEL_SERVICE_URL", "").strip()
        if not base_url:
            return None
        return cls(
            base_url,
            float(os.getenv("WORKFLOW_MODEL_PLAN_TIMEOUT_SECONDS", "120")),
            int(os.getenv("WORKFLOW_MODEL_PLAN_MAX_REQUEST_BYTES", "2000000")),
            int(os.getenv("WORKFLOW_MODEL_PLAN_MAX_RESPONSE_BYTES", "1048576")),
        )


class IntentPlanClientError(RuntimeError):
    def __init__(self, code: str, message: str, *, retryable: bool) -> None:
        super().__init__(message)
        self.code = code
        self.retryable = retryable


class ModelServiceIntentPlanClient:
    """固定调用 model-service /plan；语义重试由 IntentPlanner 控制。"""

    def __init__(
        self,
        settings: IntentPlanClientSettings,
        token_issuer: WorkflowServiceTokenIssuer,
        *,
        transport: httpx.BaseTransport | None = None,
    ) -> None:
        self.settings = settings
        self.token_issuer = token_issuer
        self.transport = transport
        self.endpoint = f"{settings.base_url.rstrip('/')}/plan"

    def plan(self, run_id: UUID, request: IntentPlanRequest) -> IntentPlanResponse:
        body = json.dumps(
            request.model_dump(mode="json", by_alias=True),
            ensure_ascii=False,
            separators=(",", ":"),
        ).encode("utf-8")
        if len(body) > self.settings.max_request_bytes:
            raise IntentPlanClientError(
                "PLAN_REQUEST_TOO_LARGE", "intent plan request is too large", retryable=False
            )
        token = self.token_issuer.issue(
            MODEL_SERVICE_AUDIENCE,
            ["model.plan"],
            {"runId": str(run_id)},
        )
        try:
            with httpx.Client(
                timeout=self.settings.timeout_seconds,
                follow_redirects=False,
                transport=self.transport,
            ) as client:
                with client.stream(
                    "POST",
                    self.endpoint,
                    content=body,
                    headers={
                        "Authorization": f"Bearer {token}",
                        "Content-Type": "application/json",
                        "Accept": "application/json",
                    },
                ) as response:
                    if response.status_code != 200:
                        retryable = response.status_code in {500, 502, 503, 504}
                        raise IntentPlanClientError(
                            "MODEL_PLAN_UNAVAILABLE" if retryable else "MODEL_PLAN_REJECTED",
                            f"model service returned HTTP {response.status_code}",
                            retryable=retryable,
                        )
                    content_type = response.headers.get("content-type", "").split(";", 1)[0].lower()
                    if content_type != "application/json":
                        raise IntentPlanClientError(
                            "INVALID_PLAN_RESPONSE", "model service response must be JSON", retryable=True
                        )
                    chunks: list[bytes] = []
                    size = 0
                    for chunk in response.iter_bytes():
                        size += len(chunk)
                        if size > self.settings.max_response_bytes:
                            raise IntentPlanClientError(
                                "PLAN_RESPONSE_TOO_LARGE",
                                "model service response is too large",
                                retryable=False,
                            )
                        chunks.append(chunk)
        except httpx.TransportError as exc:
            raise IntentPlanClientError(
                "MODEL_PLAN_UNAVAILABLE", "model service transport failed", retryable=True
            ) from exc
        try:
            payload = json.loads(b"".join(chunks))
            return IntentPlanResponse.model_validate(payload)
        except Exception as exc:
            raise IntentPlanClientError(
                "INVALID_PLAN_RESPONSE",
                "model service returned an invalid intent plan contract",
                retryable=True,
            ) from exc
