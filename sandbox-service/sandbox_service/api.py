from __future__ import annotations

import os
import hmac

from fastapi import FastAPI, Header, HTTPException, Query

from sandbox_service.catalog import ToolCatalog
from sandbox_service.models import InvocationRequest, InvocationResponse
from sandbox_service.runtime import DockerRuntime
from sandbox_service.service import SandboxService


def create_app(service: SandboxService | None = None) -> FastAPI:
    app = FastAPI(title="YLcloud Sandbox Service", docs_url=None, redoc_url=None)
    catalog_path = os.getenv("SANDBOX_TOOL_CATALOG_FILE")
    catalog = ToolCatalog.from_json_file(catalog_path) if catalog_path else ToolCatalog()
    active_service = service or SandboxService(catalog, DockerRuntime())

    def authorize(authorization: str | None) -> None:
        secret = os.getenv("SANDBOX_SERVICE_TOKEN", "")
        secret_file = os.getenv("SANDBOX_SERVICE_TOKEN_FILE", "")
        if not secret and secret_file:
            try:
                secret = open(secret_file, encoding="utf-8").read().strip()
            except OSError:
                secret = ""
        if len(secret.encode()) < 32:
            raise HTTPException(status_code=503, detail="sandbox service authentication is not configured")
        supplied = authorization[7:] if authorization and authorization.startswith("Bearer ") else ""
        if not hmac.compare_digest(supplied, secret):
            raise HTTPException(status_code=401, detail="invalid sandbox service token")

    @app.get("/health")
    def health() -> dict[str, str]:
        return {"status": "UP"}

    @app.post("/internal/v1/invocations", response_model=InvocationResponse)
    def invoke(request: InvocationRequest, authorization: str | None = Header(default=None)) -> InvocationResponse:
        authorize(authorization)
        if os.getenv("SANDBOX_ENABLED", "false").lower() not in {"1", "true", "yes"}:
            raise HTTPException(status_code=503, detail="sandbox is disabled")
        try:
            return active_service.invoke(request)
        except KeyError as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc
        except ValueError as exc:
            raise HTTPException(status_code=409 if "idempotency" in str(exc) else 422, detail=str(exc)) from exc
        except Exception as exc:
            raise HTTPException(status_code=500, detail="sandbox execution failed") from exc

    @app.get("/internal/v1/invocations/{invocation_id}", response_model=InvocationResponse)
    def get_invocation(invocation_id: str, subject_id: str = Query(min_length=1),
                       authorization: str | None = Header(default=None)) -> InvocationResponse:
        authorize(authorization)
        try:
            return active_service.get(invocation_id, subject_id)
        except KeyError as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc

    @app.get("/internal/v1/invocations", response_model=list[InvocationResponse])
    def list_invocations(subject_id: str = Query(min_length=1),
                         authorization: str | None = Header(default=None)) -> list[InvocationResponse]:
        authorize(authorization)
        return active_service.list_for_subject(subject_id)

    @app.post("/internal/v1/invocations/{invocation_id}/cancel", status_code=202)
    def cancel_invocation(invocation_id: str, subject_id: str = Query(min_length=1),
                          authorization: str | None = Header(default=None)) -> dict[str, str]:
        authorize(authorization)
        try:
            active_service.cancel(invocation_id, subject_id)
        except KeyError as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc
        return {"invocationId": invocation_id, "status": "CANCELLATION_REQUESTED"}

    return app


app = create_app()
