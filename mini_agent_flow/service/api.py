from __future__ import annotations

import asyncio
import json
import os
from contextlib import asynccontextmanager
from typing import Any, AsyncIterator
from uuid import UUID

from fastapi import FastAPI, Header, Request, Response, status
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.types import ASGIApp, Message, Receive, Scope, Send

from mini_agent_flow.contracts.models import WorkflowRunCreateRequest
from mini_agent_flow.security.service_jwt import (
    HmacJwtVerifier,
    ServiceAuthError,
    ServiceAuthenticator,
    ServiceJwtSettings,
    UnconfiguredServiceAuthenticator,
    require_bindings,
)
from mini_agent_flow.service.run_service import (
    RequestMetadata,
    RunApplicationService,
    RunServiceError,
    UnconfiguredRunApplicationService,
)
from mini_agent_flow.service.settings import ServiceSettings


def _error_body(code: str, message: str, *, retryable: bool) -> dict[str, Any]:
    return {
        "contractVersion": "1.0",
        "code": code,
        "message": message,
        "retryable": retryable,
    }


class _RequestBodyTooLarge(Exception):
    pass


class RequestSizeLimitMiddleware:
    """在 ASGI receive 流上计数，避免先把超大 chunked 请求完整读入内存。"""

    def __init__(self, app: ASGIApp, max_bytes: int) -> None:
        self.app = app
        self.max_bytes = max_bytes

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        headers = {key.lower(): value for key, value in scope.get("headers", [])}
        content_length = headers.get(b"content-length")
        if content_length is not None:
            try:
                if int(content_length) > self.max_bytes:
                    await self._send_rejection(send)
                    return
            except ValueError:
                pass

        consumed = 0

        async def limited_receive() -> Message:
            nonlocal consumed
            message = await receive()
            if message["type"] == "http.request":
                consumed += len(message.get("body", b""))
                if consumed > self.max_bytes:
                    raise _RequestBodyTooLarge
            return message

        try:
            await self.app(scope, limited_receive, send)
        except _RequestBodyTooLarge:
            await self._send_rejection(send)

    @staticmethod
    async def _send_rejection(send: Send) -> None:
        body = json.dumps(
            _error_body(
                "REQUEST_TOO_LARGE",
                "request body exceeds configured limit",
                retryable=False,
            ),
            separators=(",", ":"),
        ).encode("utf-8")
        await send(
            {
                "type": "http.response.start",
                "status": 413,
                "headers": [
                    (b"content-type", b"application/json"),
                    (b"content-length", str(len(body)).encode("ascii")),
                ],
            }
        )
        await send({"type": "http.response.body", "body": body})


def create_app(
    run_service: RunApplicationService | None = None,
    settings: ServiceSettings | None = None,
    authenticator: ServiceAuthenticator | None = None,
) -> FastAPI:
    service = run_service or _default_run_service()
    auth = authenticator or _default_authenticator()
    config = settings or ServiceSettings.from_env()
    limiter = asyncio.Semaphore(config.max_concurrent_requests)

    @asynccontextmanager
    async def lifespan(_: FastAPI) -> AsyncIterator[None]:
        await service.start()
        try:
            yield
        finally:
            await service.stop()

    application = FastAPI(
        title="YLcloud Workflow Service",
        version="1.0",
        docs_url="/docs" if config.expose_docs else None,
        redoc_url=None,
        openapi_url="/openapi.json" if config.expose_docs else None,
        lifespan=lifespan,
    )
    application.add_middleware(RequestSizeLimitMiddleware, max_bytes=config.max_request_bytes)

    @application.middleware("http")
    async def resource_guard(request: Request, call_next):
        if request.url.path in {"/health", "/ready"}:
            return await call_next(request)
        content_length = request.headers.get("content-length")
        if content_length is not None:
            try:
                if int(content_length) > config.max_request_bytes:
                    return JSONResponse(
                        status_code=413,
                        content=_error_body(
                            "REQUEST_TOO_LARGE",
                            "request body exceeds configured limit",
                            retryable=False,
                        ),
                    )
            except ValueError:
                pass
        acquired = False
        try:
            async with asyncio.timeout(config.request_timeout_seconds):
                await limiter.acquire()
                acquired = True
                return await call_next(request)
        except TimeoutError:
            return JSONResponse(
                status_code=status.HTTP_504_GATEWAY_TIMEOUT,
                content=_error_body(
                    "REQUEST_TIMED_OUT", "request deadline exceeded", retryable=True
                ),
            )
        finally:
            if acquired:
                limiter.release()

    @application.exception_handler(RunServiceError)
    async def handle_service_error(_: Request, exc: RunServiceError) -> JSONResponse:
        return JSONResponse(
            status_code=exc.status_code,
            content=_error_body(exc.code, str(exc), retryable=exc.retryable),
        )

    @application.exception_handler(ServiceAuthError)
    async def handle_auth_error(_: Request, exc: ServiceAuthError) -> JSONResponse:
        return JSONResponse(
            status_code=exc.status_code,
            content=_error_body(exc.code, str(exc), retryable=exc.status_code == 503),
        )

    @application.exception_handler(RequestValidationError)
    async def handle_validation_error(
        _: Request, exc: RequestValidationError
    ) -> JSONResponse:
        return JSONResponse(
            status_code=422,
            content={
                **_error_body(
                    "INVALID_REQUEST", "request does not match contract", retryable=False
                ),
                "details": {
                    "errors": [
                        {
                            "location": list(error.get("loc", ())),
                            "type": error.get("type", "validation_error"),
                            "message": error.get("msg", "invalid value"),
                        }
                        for error in exc.errors()
                    ]
                },
            },
        )

    @application.exception_handler(Exception)
    async def handle_unexpected_error(_: Request, __: Exception) -> JSONResponse:
        # 内部异常不向调用方回显堆栈、数据库信息或用户上下文。
        return JSONResponse(
            status_code=500,
            content=_error_body(
                "INTERNAL_ERROR", "unexpected workflow service error", retryable=True
            ),
        )

    @application.get("/health")
    async def health() -> dict[str, str]:
        return {"status": "UP"}

    @application.get("/ready")
    async def ready(response: Response) -> dict[str, str]:
        if not auth.is_ready() or not await service.is_ready():
            response.status_code = status.HTTP_503_SERVICE_UNAVAILABLE
            return {"status": "NOT_READY"}
        return {"status": "READY"}

    @application.post(
        "/internal/v1/workflow-runs",
        status_code=status.HTTP_202_ACCEPTED,
    )
    async def create_run(
        payload: WorkflowRunCreateRequest,
        idempotency_key: str = Header(alias="Idempotency-Key", min_length=1, max_length=256),
        request_id: str = Header(alias="X-Request-Id", min_length=1, max_length=128),
        traceparent: str | None = Header(default=None, max_length=128),
        authorization: str | None = Header(default=None, alias="Authorization"),
    ) -> dict[str, Any]:
        identity = auth.authenticate(authorization, {"workflow.run.create"})
        require_bindings(
            identity,
            user_id=payload.user_id,
            session_id=payload.session_id,
            message_id=payload.assistant_message_id,
        )
        accepted = await service.create_run(
            payload,
            RequestMetadata(
                idempotency_key=idempotency_key,
                request_id=request_id,
                traceparent=traceparent,
            ),
        )
        return accepted.model_dump(by_alias=True, mode="json")

    @application.get("/internal/v1/workflow-runs/{run_id}")
    async def get_run(
        run_id: UUID, authorization: str | None = Header(default=None, alias="Authorization")
    ) -> dict[str, Any]:
        identity = auth.authenticate(authorization, {"workflow.run.read"})
        require_bindings(identity, run_id=str(run_id))
        return await service.get_run(str(run_id))

    @application.get("/internal/v1/workflow-runs/{run_id}/result")
    async def get_result(
        run_id: UUID, authorization: str | None = Header(default=None, alias="Authorization")
    ) -> dict[str, Any]:
        identity = auth.authenticate(authorization, {"workflow.run.read"})
        require_bindings(identity, run_id=str(run_id))
        result = await service.get_result(str(run_id))
        return result.model_dump(by_alias=True, mode="json")

    @application.post("/internal/v1/workflow-runs/{run_id}/cancel")
    async def cancel_run(
        run_id: UUID, authorization: str | None = Header(default=None, alias="Authorization")
    ) -> dict[str, Any]:
        identity = auth.authenticate(authorization, {"workflow.run.cancel"})
        require_bindings(identity, run_id=str(run_id))
        return await service.cancel_run(str(run_id))

    @application.post(
        "/internal/v1/workflow-runs/{run_id}/retry",
        status_code=status.HTTP_202_ACCEPTED,
    )
    async def retry_run(
        run_id: UUID,
        idempotency_key: str = Header(alias="Idempotency-Key", min_length=1, max_length=256),
        request_id: str = Header(alias="X-Request-Id", min_length=1, max_length=128),
        traceparent: str | None = Header(default=None, max_length=128),
        authorization: str | None = Header(default=None, alias="Authorization"),
    ) -> dict[str, Any]:
        identity = auth.authenticate(authorization, {"workflow.run.retry"})
        require_bindings(identity, run_id=str(run_id))
        accepted = await service.retry_run(
            str(run_id),
            RequestMetadata(idempotency_key, request_id, traceparent),
        )
        return accepted.model_dump(by_alias=True, mode="json")

    return application


def _default_run_service() -> RunApplicationService:
    # Lazy imports keep contract-only consumers free from database setup side effects.
    if os.getenv("WORKFLOW_MYSQL_ENABLED", "false").lower() not in {"1", "true", "yes"}:
        return UnconfiguredRunApplicationService()
    from mini_agent_flow.service.mysql_run_service import MySQLRunApplicationService
    from mini_agent_flow.persistence.mysql_store import MySQLSettings, MySQLWorkflowStore

    return MySQLRunApplicationService(MySQLWorkflowStore(MySQLSettings.from_env()))


def _default_authenticator() -> ServiceAuthenticator:
    settings = ServiceJwtSettings.workflow_from_env()
    return HmacJwtVerifier(settings) if settings else UnconfiguredServiceAuthenticator()


app = create_app()
