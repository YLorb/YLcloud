import json
import sys
import types
import unittest
from pathlib import Path
from unittest.mock import patch

try:
    import FlagEmbedding  # noqa: F401
except ImportError:
    # 规划端点不使用向量模型；本地轻量测试只替换导入边界，避免下载模型权重。
    fake = types.ModuleType("FlagEmbedding")
    fake.BGEM3FlagModel = object
    fake.FlagModel = object
    fake.FlagReranker = object
    sys.modules["FlagEmbedding"] = fake

from fastapi.testclient import TestClient
from jsonschema import Draft202012Validator, FormatChecker

import bge_service as service
from intent_plan_contract import IntentCode, IntentPlan


SCHEMA = json.loads(
    (Path(__file__).resolve().parents[1] / "schemas" / "intent-plan.schema.json").read_text(
        encoding="utf-8"
    )
)


class AllowPlanVerifier:
    def verify(self, authorization, required_scope):
        if authorization is None:
            raise service.ModelServiceAuthError("missing bearer service token")
        if required_scope != "model.plan" or authorization != "Bearer valid":
            raise service.ModelServiceAuthError("insufficient service scope")
        return {"runId": "run-1"}


class IntentPlanTest(unittest.TestCase):
    def setUp(self):
        self.verifier = service.SERVICE_JWT_VERIFIER
        self.mock_enabled = service.PLAN_MOCK_ENABLED
        service.SERVICE_JWT_VERIFIER = AllowPlanVerifier()

    def tearDown(self):
        service.SERVICE_JWT_VERIFIER = self.verifier
        service.PLAN_MOCK_ENABLED = self.mock_enabled

    def request(self):
        return {
            "contractVersion": "1.0",
            "schemaVersion": "intent-plan/1.0",
            "promptVersion": "intent-plan-prompt/1.0",
            "question": "解释 Graph Workflow",
            "shortTermContext": [],
            "attempt": 1,
            "confidenceThreshold": 0.7,
        }

    def test_model_enum_matches_authoritative_schema(self):
        Draft202012Validator.check_schema(SCHEMA)
        expected = set(SCHEMA["$defs"]["IntentCode"]["enum"])
        self.assertEqual(expected, {item.value for item in IntentCode})

    def test_mock_endpoint_returns_schema_valid_plan_and_requires_scope(self):
        service.PLAN_MOCK_ENABLED = True
        with TestClient(service.app) as client:
            denied = client.post("/plan", json=self.request())
            self.assertEqual(401, denied.status_code)
            response = client.post(
                "/plan", json=self.request(), headers={"Authorization": "Bearer valid"}
            )
        self.assertEqual(200, response.status_code)
        Draft202012Validator(SCHEMA, format_checker=FormatChecker()).validate(
            response.json()
        )

    def test_real_model_output_is_strictly_validated_and_errors_are_sanitized(self):
        service.PLAN_MOCK_ENABLED = False
        with patch.object(service, "ensure_llm_config"), patch.object(
            service,
            "call_text_model",
            return_value=(
                json.dumps(
                    {
                        "primaryTaskId": "primary",
                        "complexity": "SIMPLE",
                        "tasks": [
                            {
                                "taskId": "primary",
                                "role": "PRIMARY",
                                "intent": "GENERAL_QA",
                                "instruction": "回答问题",
                                "dependencies": [],
                                "confidence": 0.91,
                                "contextSufficiency": "SUFFICIENT",
                                "relevance": "REQUIRED",
                                "uncertainty": "NONE",
                                "disposition": "ACTIVE",
                            }
                        ],
                    },
                    ensure_ascii=False,
                ),
                {},
            ),
        ):
            with TestClient(service.app) as client:
                ok = client.post(
                    "/plan", json=self.request(), headers={"Authorization": "Bearer valid"}
                )
        self.assertEqual(200, ok.status_code)
        IntentPlan.model_validate(ok.json()["plan"])

        with patch.object(service, "ensure_llm_config"), patch.object(
            service, "call_text_model", return_value=("private invalid output", {})
        ):
            with TestClient(service.app) as client:
                failed = client.post(
                    "/plan", json=self.request(), headers={"Authorization": "Bearer valid"}
                )
        self.assertEqual(502, failed.status_code)
        self.assertNotIn("private", failed.text)

        with patch.object(service, "ensure_llm_config"), patch.object(
            service,
            "call_text_model",
            side_effect=service.HTTPException(status_code=500, detail="private upstream body"),
        ):
            with TestClient(service.app) as client:
                upstream_failed = client.post(
                    "/plan", json=self.request(), headers={"Authorization": "Bearer valid"}
                )
        self.assertEqual(502, upstream_failed.status_code)
        self.assertNotIn("private", upstream_failed.text)

    def test_contract_rejects_cycle_duplicate_and_more_than_six_tasks(self):
        base = {
            "primaryTaskId": "a",
            "complexity": "COMPLEX",
            "tasks": [
                {
                    "taskId": "a",
                    "role": "PRIMARY",
                    "intent": "ANALYZE",
                    "instruction": "a",
                    "dependencies": ["b"],
                    "confidence": 0.9,
                    "contextSufficiency": "PARTIAL",
                    "relevance": "REQUIRED",
                    "uncertainty": "NONE",
                    "disposition": "ACTIVE",
                },
                {
                    "taskId": "b",
                    "role": "SUBTASK",
                    "intent": "FILE_READ",
                    "instruction": "b",
                    "dependencies": ["a"],
                    "confidence": 0.9,
                    "contextSufficiency": "PARTIAL",
                    "relevance": "REQUIRED",
                    "uncertainty": "NONE",
                    "disposition": "ACTIVE",
                },
            ],
        }
        with self.assertRaisesRegex(ValueError, "acyclic"):
            IntentPlan.model_validate(base)
        duplicate = json.loads(json.dumps(base))
        duplicate["tasks"][0]["dependencies"] = []
        duplicate["tasks"][1]["taskId"] = "a"
        with self.assertRaisesRegex(ValueError, "unique"):
            IntentPlan.model_validate(duplicate)
        too_many = json.loads(json.dumps(base))
        too_many["tasks"] = [
            {**too_many["tasks"][0], "taskId": f"t{i}", "role": "PRIMARY" if i == 0 else "SUBTASK", "dependencies": []}
            for i in range(7)
        ]
        too_many["primaryTaskId"] = "t0"
        with self.assertRaises(ValueError):
            IntentPlan.model_validate(too_many)


if __name__ == "__main__":
    unittest.main()
