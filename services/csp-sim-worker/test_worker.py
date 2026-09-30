import base64
import unittest
from unittest.mock import Mock
from worker import grade_task, normalize_output, run_claim


class WorkerTests(unittest.TestCase):
    def fixture(self, io="STDIO"):
        task = {"sourceBlob": "source", "problem": {"ioMode": io, "inputName": "sum.in", "outputName": "sum.out",
                "memoryMb": 256, "timeLimitMs": 1000, "cases": [{"id": "1", "inputBlob": "input", "answerBlob": "answer"}]}}
        blobs = {"source": b"int main(){}", "input": b"1 2\n", "answer": b"3\n"}
        return task, blobs

    def test_text_comparison_preserves_interior_whitespace(self):
        self.assertEqual(normalize_output(b"a  \r\nb\t\n\n"), b"a\nb")
        self.assertNotEqual(normalize_output(b"a b"), normalize_output(b"a  b"))
        self.assertNotEqual(normalize_output(b"a\n\nb"), normalize_output(b"a\nb"))

    def test_standard_and_file_io_compare_answers_outside_sandbox(self):
        for io in ("STDIO", "FILE"):
            task, blobs = self.fixture(io)
            sandbox = Mock()
            sandbox.run.side_effect = [{"verdict": "AC", "program": "binary", "environment": "g++ 9.3.0"},
                                       {"verdict": "AC", "output": base64.b64encode(b"3 \n").decode(), "runtimeMs": 2, "memoryBytes": 100}]
            result = grade_task(task, blobs.__getitem__, sandbox)
            self.assertEqual(result["verdict"], "AC")
            payload = sandbox.run.call_args.args[0]
            self.assertEqual(payload["ioMode"], io)
            self.assertNotIn("answer", payload)
            self.assertNotIn("answerBlob", payload)

    def test_compile_error_has_no_test_cases(self):
        task, blobs = self.fixture()
        sandbox = Mock()
        sandbox.run.return_value = {"verdict": "CE", "message": "bad source"}
        result = grade_task(task, blobs.__getitem__, sandbox)
        self.assertEqual(result["verdict"], "CE")
        self.assertEqual(result["cases"], [])
        sandbox.run.assert_called_once()

    def test_wa_and_resource_failures_are_preserved(self):
        for verdict in ("AC", "TLE", "MLE", "RE", "OLE"):
            task, blobs = self.fixture()
            sandbox = Mock()
            sandbox.run.side_effect = [{"verdict": "AC", "program": "binary"}, {"verdict": verdict, "output": "MA=="}]
            expected = "WA" if verdict == "AC" else verdict
            self.assertEqual(grade_task(task, blobs.__getitem__, sandbox)["cases"][0]["verdict"], expected)

    def test_infrastructure_failure_is_reported_separately(self):
        task, _ = self.fixture()
        task["leaseToken"] = "lease"
        client, sandbox = Mock(), Mock()
        client.request.side_effect = RuntimeError("daemon unavailable")
        # Completion succeeds; failed downloads are infrastructure failures, not WA.
        def request(path, *args, **kwargs):
            if path.endswith("/complete"):
                return {}
            raise RuntimeError("daemon unavailable")
        client.request.side_effect = request
        run_claim(client, {"id": "task", "task": task}, sandbox)
        completed = client.request.call_args.args[1]
        self.assertEqual(completed["result"]["verdict"], "SYSTEM_ERROR")


if __name__ == "__main__":
    unittest.main()
