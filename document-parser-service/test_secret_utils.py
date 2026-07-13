import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from secret_utils import read_secret


class SecretUtilsTest(unittest.TestCase):
    def test_file_takes_precedence_over_environment(self):
        with tempfile.TemporaryDirectory() as directory:
            secret_file = Path(directory) / "secret"
            secret_file.write_text("file-value\n", encoding="utf-8")
            with patch.dict(os.environ, {"TEST_KEY": "env-value", "TEST_KEY_FILE": str(secret_file)}, clear=False):
                self.assertEqual("file-value", read_secret("TEST_KEY"))

    def test_plain_environment_is_compatible_fallback(self):
        with patch.dict(os.environ, {"TEST_FALLBACK": "env-value"}, clear=False):
            os.environ.pop("TEST_FALLBACK_FILE", None)
            self.assertEqual("env-value", read_secret("TEST_FALLBACK"))


if __name__ == "__main__":
    unittest.main()
