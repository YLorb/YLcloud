import math
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
    def setUp(self):
        self.model_name = service.EMBEDDING_MODEL_NAME
        self.expected_dimension = service.EXPECTED_EMBEDDING_DIMENSION
        self.embedding_model = service.embedding_model
        service.EXPECTED_EMBEDDING_DIMENSION = 0
        service.embedding_model = None

    def tearDown(self):
        service.EMBEDDING_MODEL_NAME = self.model_name
        service.EXPECTED_EMBEDDING_DIMENSION = self.expected_dimension
        service.embedding_model = self.embedding_model

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


if __name__ == "__main__":
    unittest.main()
