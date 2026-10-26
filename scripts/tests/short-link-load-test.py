import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("load", Path(__file__).parents[1] / "short-link-load.py")
load = importlib.util.module_from_spec(spec)
spec.loader.exec_module(load)


class LoadClassificationTest(unittest.TestCase):
    def test_errors_and_login_redirects_are_not_successful_resolutions(self):
        self.assertFalse(load.accepted("preview", 302, {"location": "/login"}, b""))
        self.assertFalse(load.accepted("preview", 429, {}, b""))
        self.assertFalse(load.accepted("share", 200, {}, b'{"code":200,"data":{"state":"EXPIRED"}}'))
        self.assertTrue(load.accepted("preview", 302, {"location": "/api/file/preview/x/stream"}, b""))
        self.assertTrue(load.accepted("share", 200, {}, b'{"code":200,"data":{"state":"READY"}}'))


if __name__ == "__main__":
    unittest.main()
