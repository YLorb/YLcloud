from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import re
import time
from dataclasses import dataclass
from typing import Any, Iterable, Protocol
from uuid import uuid4


WORKFLOW_AUDIENCE = "ylcloud-workflow"
TOOL_GATEWAY_AUDIENCE = "ylcloud-tool-gateway"
MODEL_SERVICE_AUDIENCE = "ylcloud-model-service"
CALLBACK_AUDIENCE = "ylcloud-workflow-callback"
MAX_TOKEN_BYTES = 4096
SCOPE_PATTERN = re.compile(r"^[a-z][a-z0-9_.:-]{1,127}$")
ALLOWED_BINDING_CLAIMS = {
    "userId",
    "sessionId",
    "messageId",
    "runId",
    "executionId",
    "nodeId",
    "invocationId",
}


class ServiceAuthError(RuntimeError):
    def __init__(self, code: str, message: str, *, status_code: int) -> None:
        super().__init__(message)
        self.code = code
        self.status_code = status_code


@dataclass(frozen=True, slots=True)
class ServiceIdentity:
    issuer: str
    subject: str
    audience: str
    scopes: frozenset[str]
    user_id: int | None = None
    session_id: int | None = None
    message_id: int | None = None
    run_id: str | None = None
    execution_id: str | None = None
    node_id: str | None = None
    invocation_id: str | None = None
    bindings_unrestricted: bool = False


@dataclass(frozen=True, slots=True)
class ServiceJwtSettings:
    issuer: str
    subject: str
    audience: str
    active_secret: str
    previous_secret: str | None = None
    previous_valid_until_epoch_seconds: int | None = None
    max_ttl_seconds: int = 300
    clock_skew_seconds: int = 30

    def __post_init__(self) -> None:
        if len(self.active_secret.encode("utf-8")) < 32:
            raise ValueError("active service JWT secret must be at least 32 bytes")
        if self.previous_secret and len(self.previous_secret.encode("utf-8")) < 32:
            raise ValueError("previous service JWT secret must be at least 32 bytes")
        if self.previous_secret and self.previous_valid_until_epoch_seconds is None:
            raise ValueError("previous service JWT secret requires an explicit validity deadline")
        if not 1 <= self.max_ttl_seconds <= 300:
            raise ValueError("service JWT max TTL must be between 1 and 300 seconds")
        if not 0 <= self.clock_skew_seconds <= 60:
            raise ValueError("service JWT clock skew must be between 0 and 60 seconds")
        if (
            self.previous_secret
            and self.previous_valid_until_epoch_seconds is not None
            and self.previous_valid_until_epoch_seconds
            > int(time.time()) + self.max_ttl_seconds + self.clock_skew_seconds
        ):
            raise ValueError("previous service JWT overlap exceeds the permitted window")

    @classmethod
    def workflow_from_env(cls) -> "ServiceJwtSettings | None":
        active = os.getenv("WORKFLOW_SERVICE_JWT_ACTIVE_SECRET", "")
        if not active:
            return None
        return cls(
            issuer=os.getenv("WORKFLOW_SERVICE_JWT_ISSUER", "ylcloud-app"),
            subject=os.getenv("WORKFLOW_SERVICE_JWT_SUBJECT", "ylcloud-app"),
            audience=WORKFLOW_AUDIENCE,
            active_secret=active,
            previous_secret=os.getenv("WORKFLOW_SERVICE_JWT_PREVIOUS_SECRET") or None,
            previous_valid_until_epoch_seconds=(
                int(os.environ["WORKFLOW_SERVICE_JWT_PREVIOUS_VALID_UNTIL_EPOCH_SECONDS"])
                if os.getenv("WORKFLOW_SERVICE_JWT_PREVIOUS_SECRET")
                else None
            ),
            max_ttl_seconds=int(os.getenv("WORKFLOW_SERVICE_JWT_MAX_TTL_SECONDS", "300")),
            clock_skew_seconds=int(os.getenv("WORKFLOW_SERVICE_JWT_CLOCK_SKEW_SECONDS", "30")),
        )


class ServiceAuthenticator(Protocol):
    def is_ready(self) -> bool: ...
    def authenticate(self, authorization: str | None, required_scopes: Iterable[str]) -> ServiceIdentity: ...


class UnconfiguredServiceAuthenticator:
    def is_ready(self) -> bool:
        return False

    def authenticate(self, authorization: str | None, required_scopes: Iterable[str]) -> ServiceIdentity:
        raise ServiceAuthError(
            "SERVICE_AUTH_NOT_CONFIGURED",
            "service authentication is not configured",
            status_code=503,
        )


class AllowAllServiceAuthenticator:
    """显式测试替身；生产默认工厂绝不会选择该实现。"""

    def is_ready(self) -> bool:
        return True

    def authenticate(self, authorization: str | None, required_scopes: Iterable[str]) -> ServiceIdentity:
        return ServiceIdentity(
            "test",
            "test",
            WORKFLOW_AUDIENCE,
            frozenset(required_scopes),
            bindings_unrestricted=True,
        )


class HmacJwtVerifier:
    """严格验证 HS256、独立 audience、短 TTL、绑定 Claims 与 scope。"""

    def __init__(self, settings: ServiceJwtSettings) -> None:
        self.settings = settings

    def is_ready(self) -> bool:
        return True

    def authenticate(self, authorization: str | None, required_scopes: Iterable[str]) -> ServiceIdentity:
        if not authorization or not authorization.startswith("Bearer "):
            raise _unauthorized("missing bearer service token")
        token = authorization[7:].strip()
        try:
            token_bytes = token.encode("ascii")
        except UnicodeEncodeError:
            raise _unauthorized("invalid service token") from None
        if not token or len(token_bytes) > MAX_TOKEN_BYTES:
            raise _unauthorized("invalid service token")
        claims = self._decode_and_verify(token)
        scopes = _scope_set(claims.get("scope"))
        if set(required_scopes) - scopes:
            raise ServiceAuthError(
                "INSUFFICIENT_SERVICE_SCOPE",
                "service token does not grant the required scope",
                status_code=403,
            )
        return ServiceIdentity(
            issuer=claims["iss"],
            subject=claims["sub"],
            audience=self.settings.audience,
            scopes=frozenset(scopes),
            user_id=_optional_positive_int(claims, "userId"),
            session_id=_optional_positive_int(claims, "sessionId"),
            message_id=_optional_positive_int(claims, "messageId"),
            run_id=_optional_string(claims, "runId"),
            execution_id=_optional_string(claims, "executionId"),
            node_id=_optional_string(claims, "nodeId"),
            invocation_id=_optional_string(claims, "invocationId"),
        )

    def _decode_and_verify(self, token: str) -> dict[str, Any]:
        try:
            encoded_header, encoded_payload, encoded_signature = token.split(".")
            header = json.loads(_decode_segment(encoded_header))
            claims = json.loads(_decode_segment(encoded_payload))
            signature = _decode_bytes(encoded_signature)
        except (ValueError, TypeError, json.JSONDecodeError, UnicodeDecodeError):
            raise _unauthorized("invalid service token") from None
        if not isinstance(header, dict) or header.get("alg") != "HS256" or header.get("typ") != "JWT":
            raise _unauthorized("invalid service token algorithm")
        if not isinstance(claims, dict):
            raise _unauthorized("invalid service token claims")
        signed = f"{encoded_header}.{encoded_payload}".encode("ascii")
        valid = False
        previous_active = (
            self.settings.previous_secret
            if self.settings.previous_valid_until_epoch_seconds is not None
            and int(time.time()) <= self.settings.previous_valid_until_epoch_seconds
            else None
        )
        for secret in (self.settings.active_secret, previous_active):
            if secret:
                expected = hmac.new(secret.encode("utf-8"), signed, hashlib.sha256).digest()
                valid = hmac.compare_digest(signature, expected) or valid
        if not valid:
            raise _unauthorized("invalid service token signature")
        self._validate_registered_claims(claims)
        return claims

    def _validate_registered_claims(self, claims: dict[str, Any]) -> None:
        now = int(time.time())
        try:
            issued_at = _timestamp(claims["iat"])
            expires_at = _timestamp(claims["exp"])
            not_before = _timestamp(claims["nbf"])
        except (KeyError, TypeError, ValueError):
            raise _unauthorized("invalid service token timestamps") from None
        skew = self.settings.clock_skew_seconds
        if claims.get("iss") != self.settings.issuer or claims.get("sub") != self.settings.subject:
            raise _unauthorized("invalid service token identity")
        if claims.get("aud") != self.settings.audience:
            raise _unauthorized("invalid service token audience")
        if expires_at - issued_at > self.settings.max_ttl_seconds or expires_at <= issued_at:
            raise _unauthorized("invalid service token lifetime")
        if now - skew >= expires_at:
            raise _unauthorized("service token expired")
        if issued_at > now + skew or not_before > now + skew:
            raise _unauthorized("service token is not active")
        if not isinstance(claims.get("jti"), str) or not claims["jti"]:
            raise _unauthorized("service token jti is required")


class HmacJwtIssuer:
    def __init__(self, settings: ServiceJwtSettings) -> None:
        self.settings = settings

    def issue(
        self,
        scopes: Iterable[str],
        *,
        ttl_seconds: int = 120,
        now: int | None = None,
        bindings: dict[str, Any] | None = None,
    ) -> str:
        if not 1 <= ttl_seconds <= self.settings.max_ttl_seconds:
            raise ValueError("token TTL exceeds configured maximum")
        issued_at = int(time.time()) if now is None else now
        normalized_scopes = sorted(set(scopes))
        if not normalized_scopes or any(
            not isinstance(scope, str) or not SCOPE_PATTERN.fullmatch(scope)
            for scope in normalized_scopes
        ):
            raise ValueError("service JWT requires valid non-empty scopes")
        binding_claims = dict(bindings or {})
        if set(binding_claims) - ALLOWED_BINDING_CLAIMS:
            raise ValueError("unsupported service JWT binding claim")
        _validate_binding_claims(binding_claims)
        claims: dict[str, Any] = {
            "iss": self.settings.issuer,
            "sub": self.settings.subject,
            "aud": self.settings.audience,
            "iat": issued_at,
            "nbf": issued_at,
            "exp": issued_at + ttl_seconds,
            "jti": str(uuid4()),
            "scope": normalized_scopes,
        }
        claims.update(binding_claims)
        encoded_header = _encode_segment({"alg": "HS256", "typ": "JWT"})
        encoded_payload = _encode_segment(claims)
        signed = f"{encoded_header}.{encoded_payload}".encode("ascii")
        signature = hmac.new(self.settings.active_secret.encode("utf-8"), signed, hashlib.sha256).digest()
        return f"{encoded_header}.{encoded_payload}.{_encode_bytes(signature)}"


class WorkflowServiceTokenIssuer:
    """Workflow 出站 Token 工厂；调用点仍需提供最小 scope 和完整资源绑定。"""

    def __init__(self, active_secret: str, *, max_ttl_seconds: int = 300) -> None:
        self.active_secret = active_secret
        self.max_ttl_seconds = max_ttl_seconds

    @classmethod
    def from_env(cls) -> "WorkflowServiceTokenIssuer | None":
        active = os.getenv("WORKFLOW_SERVICE_JWT_ACTIVE_SECRET", "")
        if not active:
            return None
        return cls(
            active,
            max_ttl_seconds=int(os.getenv("WORKFLOW_SERVICE_JWT_MAX_TTL_SECONDS", "300")),
        )

    def issue(
        self,
        audience: str,
        scopes: Iterable[str],
        bindings: dict[str, Any],
        *,
        ttl_seconds: int = 120,
    ) -> str:
        if audience not in {
            TOOL_GATEWAY_AUDIENCE,
            MODEL_SERVICE_AUDIENCE,
            CALLBACK_AUDIENCE,
        }:
            raise ValueError("unsupported Workflow outbound audience")
        issuer = HmacJwtIssuer(
            ServiceJwtSettings(
                issuer="ylcloud-workflow",
                subject="ylcloud-workflow",
                audience=audience,
                active_secret=self.active_secret,
                max_ttl_seconds=self.max_ttl_seconds,
            )
        )
        return issuer.issue(scopes, ttl_seconds=ttl_seconds, bindings=bindings)


def require_bindings(identity: ServiceIdentity, **expected: Any) -> None:
    if identity.bindings_unrestricted:
        return
    for field, value in expected.items():
        if value is not None and getattr(identity, field) != value:
            raise ServiceAuthError(
                "SERVICE_TOKEN_BINDING_MISMATCH",
                "service token is not bound to this resource",
                status_code=403,
            )


def _scope_set(value: Any) -> set[str]:
    if isinstance(value, str):
        values = value.split()
    elif isinstance(value, list) and all(isinstance(item, str) for item in value):
        values = value
    else:
        raise _unauthorized("invalid service token scope")
    return {item for item in values if item}


def _optional_positive_int(claims: dict[str, Any], name: str) -> int | None:
    value = claims.get(name)
    if value is None:
        return None
    if isinstance(value, bool) or not isinstance(value, int) or value < 1:
        raise _unauthorized("invalid service token binding")
    return value


def _optional_string(claims: dict[str, Any], name: str) -> str | None:
    value = claims.get(name)
    if value is None:
        return None
    if not isinstance(value, str) or not value or len(value) > 128:
        raise _unauthorized("invalid service token binding")
    return value


def _timestamp(value: Any) -> int:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise TypeError("timestamp must be numeric")
    return int(value)


def _validate_binding_claims(bindings: dict[str, Any]) -> None:
    for name in ("userId", "sessionId", "messageId"):
        if name in bindings:
            value = bindings[name]
            if isinstance(value, bool) or not isinstance(value, int) or value < 1:
                raise ValueError("service JWT numeric bindings must be positive integers")
    for name in ("runId", "executionId", "nodeId", "invocationId"):
        if name in bindings:
            value = bindings[name]
            if not isinstance(value, str) or not value or len(value) > 128:
                raise ValueError("service JWT string bindings must be 1-128 characters")


def _encode_segment(value: dict[str, Any]) -> str:
    return _encode_bytes(json.dumps(value, separators=(",", ":"), sort_keys=True).encode("utf-8"))


def _encode_bytes(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def _decode_segment(value: str) -> str:
    return _decode_bytes(value).decode("utf-8")


def _decode_bytes(value: str) -> bytes:
    return base64.b64decode(value + "=" * (-len(value) % 4), altchars=b"-_", validate=True)


def _unauthorized(message: str) -> ServiceAuthError:
    return ServiceAuthError("INVALID_SERVICE_TOKEN", message, status_code=401)
