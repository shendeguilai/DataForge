import base64
import unittest
from unittest.mock import Mock
from worker import grade_task, normalize_output, run_claim, explain_difference


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

    def test_wrong_answers_show_first_difference_and_bounded_context(self):
        d=explain_difference(b"ok\n1 4\n",b"ok\n1 3\n")
        self.assertEqual((d["differenceLine"],d["differenceColumn"]),(2,3))
        self.assertEqual(d["expected"],"1 3")
        self.assertEqual(d["actual"],"1 4")
        self.assertEqual(explain_difference(b"",b"3\n")["actual"],"〈输出结束〉")
        self.assertEqual(explain_difference(b"3 4",b"3")["expected"],"〈输出结束〉")
        long=explain_difference(b"a"*10000+b"x",b"a"*10000+b"y")
        self.assertLessEqual(len(long["actual"]),162)
        task,blobs=self.fixture();sandbox=Mock()
        sandbox.run.side_effect=[{"verdict":"AC","program":"binary"},{"verdict":"AC","output":base64.b64encode(b"4\n").decode()}]
        case=grade_task(task,blobs.__getitem__,sandbox)["cases"][0]
        self.assertEqual(case["expected"],"3")
        self.assertEqual(case["actual"],"4")

    def test_missing_output_file_message_is_kept_per_case(self):
        task,blobs=self.fixture("FILE");sandbox=Mock()
        sandbox.run.side_effect=[{"verdict":"AC","program":"binary"},{"verdict":"WA","message":"未生成规定的输出文件：sum.out"}]
        case=grade_task(task,blobs.__getitem__,sandbox)["cases"][0]
        self.assertEqual(case["verdict"],"WA")
        self.assertIn("sum.out",case["message"])

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
