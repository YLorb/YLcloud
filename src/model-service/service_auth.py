from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import time
from dataclasses import dataclass
from typing import Any

from secret_utils import read_secret


AUDIENCE = "ylcloud-model-service"


class ModelServiceAuthError(ValueError):
    pass


@dataclass(frozen=True)
class ModelServiceJwtVerifier:
    active_secret: str
    previous_secret: str | None = None
    previous_valid_until_epoch_seconds: int | None = None
    allowed_issuers: frozenset[str] = frozenset({"ylcloud-app", "ylcloud-workflow"})
    max_ttl_seconds: int = 300
    clock_skew_seconds: int = 30

    def __post_init__(self) -> None:
        if len(self.active_secret.encode("utf-8")) < 32:
            raise ValueError("model service JWT active secret must be at least 32 bytes")
        if self.previous_secret and len(self.previous_secret.encode("utf-8")) < 32:
            raise ValueError("model service JWT previous secret must be at least 32 bytes")
        if self.previous_secret and self.previous_valid_until_epoch_seconds is None:
            raise ValueError("model service JWT previous secret requires a validity deadline")
        if not 1 <= self.max_ttl_seconds <= 300 or not 0 <= self.clock_skew_seconds <= 60:
            raise ValueError("invalid model service JWT TTL or clock skew")
        if (
            self.previous_secret
            and self.previous_valid_until_epoch_seconds is not None
            and self.previous_valid_until_epoch_seconds
            > int(time.time()) + self.max_ttl_seconds + self.clock_skew_seconds
        ):
            raise ValueError("model service JWT previous overlap exceeds permitted window")

    @classmethod
    def from_env(cls) -> "ModelServiceJwtVerifier | None":
        active = read_secret("MODEL_SERVICE_JWT_ACTIVE_SECRET", "YLCLOUD_SERVICE_JWT_ACTIVE_SECRET")
        if not active:
            return None
        previous = read_secret(
            "MODEL_SERVICE_JWT_PREVIOUS_SECRET", "YLCLOUD_SERVICE_JWT_PREVIOUS_SECRET"
        )
        issuers = frozenset(
            value.strip()
            for value in os.getenv(
                "MODEL_SERVICE_JWT_ALLOWED_ISSUERS", "ylcloud-app,ylcloud-workflow"
            ).split(",")
            if value.strip()
        )
        return cls(
            active,
            previous,
            (
                int(os.environ["MODEL_SERVICE_JWT_PREVIOUS_VALID_UNTIL_EPOCH_SECONDS"])
                if previous
                else None
            ),
            issuers,
            int(os.getenv("MODEL_SERVICE_JWT_MAX_TTL_SECONDS", "300")),
            int(os.getenv("MODEL_SERVICE_JWT_CLOCK_SKEW_SECONDS", "30")),
        )

    def verify(self, authorization: str | None, required_scope: str) -> dict[str, Any]:
        if not authorization or not authorization.startswith("Bearer "):
            raise ModelServiceAuthError("missing bearer service token")
        token = authorization[7:].strip()
        try:
            if not token or len(token.encode("ascii")) > 4096:
                raise ValueError
            header_part, payload_part, signature_part = token.split(".")
            header = json.loads(_decode(header_part))
            claims = json.loads(_decode(payload_part))
            signature = _decode_bytes(signature_part)
            signed = f"{header_part}.{payload_part}".encode("ascii")
        except (ValueError, TypeError, UnicodeError, json.JSONDecodeError):
            raise ModelServiceAuthError("invalid service token") from None
        if not isinstance(header, dict) or header.get("alg") != "HS256" or header.get("typ") != "JWT":
            raise ModelServiceAuthError("invalid service token")
        if not isinstance(claims, dict) or not self._valid_signature(signed, signature):
            raise ModelServiceAuthError("invalid service token")
        self._validate_claims(claims)
        scopes = claims.get("scope")
        if isinstance(scopes, str):
            scopes = scopes.split()
        if not isinstance(scopes, list) or not all(isinstance(scope, str) for scope in scopes):
            raise ModelServiceAuthError("invalid service token")
        if required_scope not in scopes:
            raise ModelServiceAuthError("insufficient service scope")
        return claims

    def _valid_signature(self, signed: bytes, signature: bytes) -> bool:
        valid = False
        previous_active = (
            self.previous_secret
            if self.previous_valid_until_epoch_seconds is not None
            and int(time.time()) <= self.previous_valid_until_epoch_seconds
            else None
        )
        for secret in (self.active_secret, previous_active):
            if secret:
                expected = hmac.new(secret.encode("utf-8"), signed, hashlib.sha256).digest()
                valid = hmac.compare_digest(signature, expected) or valid
        return valid

    def _validate_claims(self, claims: dict[str, Any]) -> None:
        try:
            issued_at = _timestamp(claims["iat"])
            expires_at = _timestamp(claims["exp"])
            not_before = _timestamp(claims["nbf"])
        except (KeyError, TypeError, ValueError):
            raise ModelServiceAuthError("invalid service token") from None
        now = int(time.time())
        issuer = claims.get("iss")
        run_id = claims.get("runId")
        if issuer not in self.allowed_issuers or claims.get("sub") != issuer:
            raise ModelServiceAuthError("invalid service token")
        if claims.get("aud") != AUDIENCE or not isinstance(claims.get("jti"), str):
            raise ModelServiceAuthError("invalid service token")
        if not isinstance(run_id, str) or not run_id or len(run_id) > 128:
            raise ModelServiceAuthError("model service token must bind runId")
        if expires_at <= issued_at or expires_at - issued_at > self.max_ttl_seconds:
            raise ModelServiceAuthError("invalid service token")
        if now - self.clock_skew_seconds >= expires_at:
            raise ModelServiceAuthError("service token expired")
        if issued_at > now + self.clock_skew_seconds or not_before > now + self.clock_skew_seconds:
            raise ModelServiceAuthError("service token is not active")


def _timestamp(value: Any) -> int:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise TypeError
    return int(value)


def _decode(value: str) -> str:
    return _decode_bytes(value).decode("utf-8")


def _decode_bytes(value: str) -> bytes:
    return base64.b64decode(value + "=" * (-len(value) % 4), altchars=b"-_", validate=True)
