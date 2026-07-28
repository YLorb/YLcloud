import os
import sys
import types
import unittest

os.environ["OFFLINE_FALLBACK"] = "true"
os.environ["FALLBACK_DIMENSION"] = "4"

# 鉴权边界测试不加载数 GB 模型；业务推理由已有测试覆盖。
fake_flag_embedding = types.ModuleType("FlagEmbedding")
fake_flag_embedding.BGEM3FlagModel = object
fake_flag_embedding.FlagModel = object
fake_flag_embedding.FlagReranker = object
sys.modules.setdefault("FlagEmbedding", fake_flag_embedding)

from fastapi.testclient import TestClient

import bge_service
from service_auth import ModelServiceJwtVerifier
from test_service_auth import ACTIVE, issue


class BgeAuthBoundaryTest(unittest.TestCase):
    def setUp(self):
        self.original = bge_service.SERVICE_JWT_VERIFIER
        bge_service.SERVICE_JWT_VERIFIER = ModelServiceJwtVerifier(
            ACTIVE, clock_skew_seconds=0
        )

    def tearDown(self):
        bge_service.SERVICE_JWT_VERIFIER = self.original

    def test_health_is_probeable_but_inference_requires_exact_scope(self):
        with TestClient(bge_service.app) as client:
            self.assertEqual(200, client.get("/health").status_code)
            self.assertEqual(401, client.post("/embed", json={"texts": ["x"]}).status_code)
            denied = client.post(
                "/embed",
                json={"texts": ["x"]},
                headers={"Authorization": f"Bearer {issue(scope='model.chat')}"},
            )
            self.assertEqual(403, denied.status_code)
            accepted = client.post(
                "/embed",
                json={"texts": ["x"]},
                headers={"Authorization": f"Bearer {issue(scope='model.embed')}"},
            )
            self.assertEqual(200, accepted.status_code)
            self.assertEqual(4, accepted.json()["dimension"])


if __name__ == "__main__":
    unittest.main()
