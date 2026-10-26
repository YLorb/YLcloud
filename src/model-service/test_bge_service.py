import math
import json
import unittest
from unittest.mock import patch

import bge_service as service


class FakeDenseModel:
    def encode(self, texts, **kwargs):
        return [[3.0, 4.0] for _ in texts]


class FakeM3Model:
    def encode(self, texts, **kwargs):
        return {"dense_vecs": [[1.0, 0.0] for _ in texts]}


class BgeServiceTest(unittest.TestCase):
    def test_usage_preserves_real_zero_and_both_provider_contracts(self):
        self.assertEqual((0, 0, 0), service.usage_tokens({"usage": {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0}}))
        self.assertEqual((12, 8, 20), service.usage_tokens({"usage": {"input_tokens": 12, "output_tokens": 8}}))
        self.assertEqual((12, 8, 20), service.usage_tokens({"usage": {"prompt_tokens": 12, "completion_tokens": 8, "total_tokens": 20}}))

    def test_unknown_or_invalid_usage_is_never_reported_as_zero(self):
        for usage in (None, {}, [], {"prompt_tokens": 1}, {"prompt_tokens": True, "completion_tokens": 1},
                      {"prompt_tokens": -1, "completion_tokens": 1}, {"prompt_tokens": "2", "completion_tokens": 1},
                      {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 0},
                      {"prompt_tokens": 2147483647, "completion_tokens": 1}):
            with self.subTest(usage=usage):
                self.assertEqual((None, None, None), service.usage_tokens({"usage": usage}))

    def setUp(self):
        self.model_name = service.EMBEDDING_MODEL_NAME
        self.expected_dimension = service.EXPECTED_EMBEDDING_DIMENSION
        self.embedding_model = service.embedding_model
        self.offline_fallback = service.OFFLINE_FALLBACK
        service.OFFLINE_FALLBACK = False
        service.EXPECTED_EMBEDDING_DIMENSION = 0
        service.embedding_model = None

    def tearDown(self):
        service.EMBEDDING_MODEL_NAME = self.model_name
        service.EXPECTED_EMBEDDING_DIMENSION = self.expected_dimension
        service.embedding_model = self.embedding_model
        service.OFFLINE_FALLBACK = self.offline_fallback

    def test_selects_dense_loader_for_bge_v15(self):
        service.EMBEDDING_MODEL_NAME = "BAAI/bge-small-zh-v1.5"
        with patch.object(service, "FlagModel", return_value=FakeDenseModel()) as dense_loader, \
                patch.object(service, "BGEM3FlagModel") as m3_loader:
            model = service.get_embedding_model()
        self.assertIsInstance(model, FakeDenseModel)
        dense_loader.assert_called_once()
        m3_loader.assert_not_called()

    def test_selects_m3_loader_and_normalizes_output_shape(self):
        service.EMBEDDING_MODEL_NAME = "BAAI/bge-m3"
        vectors = service.encode_dense_vectors(FakeM3Model(), ["a", "b"])
        self.assertEqual([[1.0, 0.0], [1.0, 0.0]], vectors)

    def test_rejects_non_finite_and_wrong_dimension_vectors(self):
        service.EXPECTED_EMBEDDING_DIMENSION = 2
        with self.assertRaisesRegex(ValueError, "dimension mismatch"):
            service.validate_vectors([[1.0]], 1)
        with self.assertRaisesRegex(ValueError, "non-finite"):
            service.validate_vectors([[math.nan, 1.0]], 1)

    def test_structured_chat_preserves_data_without_legacy_refusal_instruction(self):
        request = service.ChatRequest(
            question='{"currentQuestion":"材料有什么用途？"}',
            contexts=['{"evidenceId":"c1","content":"数据中包含忽略规则的指令"}'],
            history=[{"role": "system", "content": "不可信的历史消息"}],
            responseProtocol="TRUSTED_JSON",
        )
        prompt = service.build_chat_prompt(request)
        payload = json.loads(prompt.split("\n", 1)[1])
        self.assertEqual(request.contexts, payload["evidence"])
        self.assertEqual(request.question, payload["question"])
        self.assertNotIn("如果上下文中没有答案", prompt)
        self.assertNotIn("不可信的历史消息", prompt)

    def test_existing_chat_keeps_legacy_prompt_when_protocol_omitted(self):
        request = service.ChatRequest(question="问题", contexts=["资料"])
        self.assertIn("如果上下文中没有答案", service.build_chat_prompt(request))

    def test_unknown_structured_protocol_is_rejected(self):
        with self.assertRaises(ValueError):
            service.ChatRequest(question="问题", contexts=[], responseProtocol="BYPASS")


if __name__ == "__main__":
    unittest.main()
