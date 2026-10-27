import io
import http.server
import json
import os
import subprocess
import threading
import unittest
import urllib.error
import urllib.request
import uuid
import zipfile
from unittest import mock
import service
from service import command


class IsolationConfigurationTests(unittest.TestCase):
    def test_sandbox_cannot_access_network_host_mounts_or_service_credentials(self):
        args = command("unit-sandbox")
        for flag in ["--network=none", "--read-only", "--cap-drop=ALL", "--user=10001:10001", "--cpus=1", "--pids-limit=32"]:
            self.assertIn(flag, args)
        self.assertNotIn("-v", args)
        self.assertNotIn("--env", args)
        self.assertNotIn("--privileged", args)


class InternalAdapterTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), service.Handler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.base = "http://127.0.0.1:" + str(cls.server.server_port)

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join()

    def request(self, method, path, body=None, key=None, headers=None):
        headers = dict(headers or {})
        if key is not None:
            headers["X-Execution-Key"] = key
        request = urllib.request.Request(self.base + path, data=body, headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=5) as response:
                return response.status, response.read()
        except urllib.error.HTTPError as error:
            return error.code, error.read()

    def test_authentication_health_request_limits_and_forwarding(self):
        secret = "unit-test-independent-secret-123456"

        class FakeProcess:
            returncode = 0
            def __init__(self, args, stdin, stdout, stderr):
                self.request = stdin.read()
                stdout.write(b"sandbox-result")
            def wait(self, timeout):
                return 0
            def poll(self):
                return 0

        with mock.patch.object(service, "SECRET", secret), \
                mock.patch.object(service, "SLOTS", threading.BoundedSemaphore(1)), \
                mock.patch.object(service.subprocess, "run", return_value=subprocess.CompletedProcess([], 0)), \
                mock.patch.object(service.subprocess, "Popen", FakeProcess), \
                mock.patch.object(service.Handler, "log_message"):
            self.assertEqual(self.request("GET", "/health")[0], 200)
            self.assertEqual(self.request("POST", "/execute", b"request", "wrong")[0], 403)
            self.assertEqual(self.request("POST", "/execute", b"x", secret,
                                          {"Content-Length": str(80 * 1024 * 1024 + 1)})[0], 413)
            self.assertEqual(self.request("POST", "/execute", b"request", secret), (200, b"sandbox-result"))

    def test_missing_docker_releases_capacity_and_invalid_secret_stays_unhealthy(self):
        with mock.patch.object(service, "SECRET", "unit-test-independent-secret-123456"), \
                mock.patch.object(service, "SLOTS", threading.BoundedSemaphore(1)), \
                mock.patch.object(service.subprocess, "run", side_effect=OSError("docker unavailable")), \
                mock.patch.object(service.subprocess, "Popen", side_effect=OSError("docker unavailable")), \
                mock.patch.object(service.Handler, "log_message"):
            self.assertEqual(self.request("GET", "/health")[0], 503)
            self.assertEqual(self.request("POST", "/execute", b"request", service.SECRET)[0], 502)
            self.assertTrue(service.SLOTS.acquire(timeout=3), "failed cleanup must release its slot")
            service.SLOTS.release()
        with mock.patch.object(service, "SECRET", "含有非英文字符的密钥" * 4), mock.patch.object(service.Handler, "log_message"):
            self.assertEqual(self.request("GET", "/health")[0], 503)
            self.assertEqual(self.request("POST", "/execute", b"request", "bad")[0], 403)


@unittest.skipUnless(os.environ.get("DATAFORGE_DOCKER_TESTS") == "1", "requires Docker sandbox")
class DockerGenerationTests(unittest.TestCase):
    def execute(self, meta, **files):
        request = io.BytesIO()
        with zipfile.ZipFile(request, "w") as archive:
            archive.writestr("request.json", json.dumps(meta))
            for name, content in files.items():
                archive.writestr(name, content)
        name = "dataforge-generation-test-" + uuid.uuid4().hex
        args = command(name)
        args[-1] = "dataforge-generation-sandbox:local"
        try:
            result = subprocess.run(args, input=request.getvalue(), capture_output=True, timeout=60)
            self.assertEqual(result.returncode, 0, result.stderr)
            with zipfile.ZipFile(io.BytesIO(result.stdout)) as archive:
                report = json.loads(archive.read("result.json"))
                output = {entry: archive.read(entry) for entry in archive.namelist() if entry != "result.json"}
                return report, output
        finally:
            subprocess.run(["docker", "rm", "-f", name], capture_output=True, timeout=15)

    def compile(self, code):
        result, data = self.execute({"action": "compile", "standard": "c++20", "timeoutSeconds": 30}, **{"source.cpp": code})
        self.assertTrue(result["ok"], result)
        return data["program"]

    def test_compile_run_arguments_and_clean_environment(self):
        program = self.compile('#include<bits/stdc++.h>\nint main(int argc,char**argv){long long a,b;std::cin>>a>>b;std::cout<<a+b<<" "<<argv[1]<<" "<<(getenv("DATAFORGE_EXECUTION_SECRET")==nullptr);}')
        result, data = self.execute({"action": "run", "args": ["17"], "timeoutSeconds": 5}, program=program, input=b"2 3\n")
        self.assertTrue(result["ok"], result)
        self.assertEqual(data["output"], b"5 17 1")

    def test_timeout_compile_error_and_traversal(self):
        program = self.compile("int main(){while(true){}}")
        result, _ = self.execute({"action": "run", "timeoutSeconds": 1}, program=program)
        self.assertFalse(result["ok"])
        result, _ = self.execute({"action": "compile", "timeoutSeconds": 30}, **{"source.cpp": "not c++"})
        self.assertFalse(result["ok"])
        result, _ = self.execute({"action": "run", "timeoutSeconds": 1}, **{"../escape": "x"})
        self.assertFalse(result["ok"])


if __name__ == "__main__":
    unittest.main()
