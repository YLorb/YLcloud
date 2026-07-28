import base64
import hashlib
import hmac
import json
import time
import unittest

from service_auth import ModelServiceAuthError, ModelServiceJwtVerifier


ACTIVE = "active-service-secret-32-bytes-minimum-0001"
PREVIOUS = "previous-service-secret-32-bytes-minimum-01"


def issue(secret=ACTIVE, *, audience="ylcloud-model-service", scope="model.embed", run_id="run-1", now=None):
    now = int(time.time()) if now is None else now
    header = {"alg": "HS256", "typ": "JWT"}
    payload = {
        "iss": "ylcloud-workflow",
        "sub": "ylcloud-workflow",
        "aud": audience,
        "iat": now,
        "nbf": now,
        "exp": now + 120,
        "jti": "test-token",
        "scope": [scope],
        "runId": run_id,
    }
    encoded_header = encode(json.dumps(header, separators=(",", ":")).encode())
    encoded_payload = encode(json.dumps(payload, separators=(",", ":")).encode())
    signed = f"{encoded_header}.{encoded_payload}".encode()
    signature = encode(hmac.new(secret.encode(), signed, hashlib.sha256).digest())
    return f"{encoded_header}.{encoded_payload}.{signature}"


def encode(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode()


class ModelServiceAuthTest(unittest.TestCase):
    def test_accepts_active_and_previous_during_rotation(self):
        verifier = ModelServiceJwtVerifier(
            ACTIVE,
            PREVIOUS,
            int(time.time()) + 60,
            clock_skew_seconds=0,
        )
        self.assertEqual("run-1", verifier.verify(f"Bearer {issue()}", "model.embed")["runId"])
        self.assertEqual(
            "run-1", verifier.verify(f"Bearer {issue(PREVIOUS)}", "model.embed")["runId"]
        )

    def test_rejects_forged_expired_wrong_audience_scope_and_missing_run(self):
        verifier = ModelServiceJwtVerifier(ACTIVE, clock_skew_seconds=0)
        values = [
            issue()[:-1] + "A",
            issue(now=int(time.time()) - 301),
            issue(audience="ylcloud-workflow-callback"),
            issue(run_id=""),
        ]
        for token in values:
            with self.subTest(token=token[-12:]), self.assertRaises(ModelServiceAuthError):
                verifier.verify(f"Bearer {token}", "model.embed")
        with self.assertRaisesRegex(ModelServiceAuthError, "scope"):
            verifier.verify(f"Bearer {issue(scope='model.chat')}", "model.embed")


if __name__ == "__main__":
    unittest.main()
