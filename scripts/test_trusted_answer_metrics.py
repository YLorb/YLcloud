import importlib.util
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("metrics", Path(__file__).with_name("evaluate-trusted-answers.py"))
metrics = importlib.util.module_from_spec(spec)
spec.loader.exec_module(metrics)


class MetricsTest(unittest.TestCase):
    def test_zero_denominators_do_not_claim_perfect_quality(self):
        result = metrics.summarize([])
        self.assertIsNone(result["metrics"]["answerAccuracy"]["value"])

    def test_separates_system_failure_from_refusal_and_tracks_coverage(self):
        result = metrics.summarize([
            {"id": "a", "expectedAnswerable": True, "status": "UNAVAILABLE"},
            {"id": "b", "expectedAnswerable": True, "status": "REFUSED"},
            {"id": "c", "expectedAnswerable": False, "status": "ANSWERED", "correct": False}])
        self.assertEqual(.5, result["metrics"]["falseRefusalRate"]["value"])
        self.assertEqual(1, result["metrics"]["unsafeAnswerRate"]["value"])
        self.assertEqual(1/3, result["metrics"]["answerCoverage"]["value"])

    def test_model_self_scores_cannot_replace_review_labels(self):
        with self.assertRaises(ValueError):
            metrics.summarize([{"id": "a", "expectedAnswerable": True, "status": "ANSWERED", "confidence": 1}])

    def test_duplicate_samples_are_rejected(self):
        row = {"id": "a", "expectedAnswerable": False, "status": "REFUSED"}
        with self.assertRaises(ValueError):
            metrics.summarize([row, row])


if __name__ == "__main__":
    unittest.main()
