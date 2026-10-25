import os
import sys
import types
import unittest
import json
from unittest.mock import patch

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

    def test_trusted_json_chat_reaches_provider_without_prose_protocol_conflict(self):
        answer = '{"questionType":"KNOWLEDGE"}'
        with patch.object(bge_service, "chat_api_key", return_value="synthetic-test-key"), \
                patch.object(bge_service, "CHAT_BASE_URL", "https://model.example.invalid/v1"), \
                patch.object(bge_service, "call_text_model", return_value=(answer, {})) as provider, \
                TestClient(bge_service.app) as client:
            payload = {"question": "材料有什么用途？", "contexts": ['{"evidenceId":"e1","content":"导热率低"}'],
                       "systemPrompt": "Return the classification JSON schema.", "responseProtocol": "TRUSTED_JSON"}
            headers = {"Authorization": f"Bearer {issue(scope='model.chat')}"}
            result = client.post("/chat", json=payload, headers=headers)
            self.assertEqual(200, result.status_code)
            self.assertEqual(answer, result.json()["answer"])
            passed_prompt = provider.call_args.args[5]
            self.assertEqual(payload["contexts"], json.loads(passed_prompt.split("\n", 1)[1])["evidence"])
            self.assertNotIn("如果上下文中没有答案", passed_prompt)
            rejected = client.post("/chat", json={**payload, "responseProtocol": "BYPASS"}, headers=headers)
            self.assertEqual(422, rejected.status_code)


if __name__ == "__main__":
    unittest.main()
