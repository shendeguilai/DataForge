import ast
import importlib.util
import json
import pathlib
import shutil
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch

ROOT = pathlib.Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("yuanma_configure", ROOT / "scripts/configure-yuanma-token.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def reply(payload, cookie=None, status=200):
    headers = b"HTTP/1.1 " + str(status).encode() + b" OK\r\n"
    if cookie:
        headers += b"Set-Cookie: DATAFORGE_SESSION=" + cookie.encode() + b"; Path=/; HttpOnly\r\n"
    return type("Result", (), {"returncode": 0, "stdout": headers + b"\r\n" + json.dumps(payload).encode()})()


class YuanmaConfigurationTests(unittest.TestCase):
    def test_script_syntax_is_valid_for_python36(self):
        source = (ROOT / "scripts/configure-yuanma-token.py").read_text(encoding="utf-8")
        ast.parse(source, feature_version=(3, 6))

    def test_uses_python36_compatible_subprocess_options(self):
        def run(args, input, stdout, stderr, timeout):
            self.assertIs(stdout, module.subprocess.PIPE)
            self.assertIs(stderr, module.subprocess.PIPE)
            self.assertEqual(timeout, 40)
            return reply({"role": "ADMIN"}, "fake-session")
        with patch.object(module.subprocess, "run", side_effect=run):
            status, payload, session = module.call("dataforge-app-1", "/api/auth/login", "POST", {"password": "fake-password"})
        self.assertEqual((status, session, payload["role"]), (200, "fake-session", "ADMIN"))

    def test_secrets_and_session_are_passed_only_through_stdin(self):
        replies = [reply({"role": "ADMIN"}, "fake-session"),
                   reply({"token": "fake-csrf", "headerName": "X-CSRF-TOKEN"}),
                   reply({"configured": True}), reply({})]
        calls = []
        def run(args, **kwargs):
            calls.append((args, kwargs["input"].decode()))
            return replies.pop(0)
        with patch.object(module.subprocess, "run", side_effect=run):
            self.assertTrue(module.configure("dataforge-app-1", "admin", "fake-password", "fake-token")["configured"])
        self.assertEqual(len(calls), 4)
        for args, data in calls:
            self.assertEqual(args[5:8], ["--disable", "--noproxy", "*"])
            self.assertNotIn("fake-password", " ".join(args))
            self.assertNotIn("fake-token", " ".join(args))
            self.assertNotIn("fake-session", " ".join(args))
        self.assertIn("fake-password", calls[0][1])
        self.assertIn("fake-token", calls[2][1])
        self.assertIn("fake-session", calls[2][1])
        self.assertIn("/api/auth/logout", calls[3][1])

    def test_invalid_login_does_not_try_token_submission(self):
        with patch.object(module.subprocess, "run", return_value=reply({}, status=401)) as runner:
            with self.assertRaises(module.ConfigurationError):
                module.configure("dataforge-app-1", "admin", "fake-password", "fake-token")
            self.assertEqual(runner.call_count, 1)

    def test_config_escaping_cannot_add_a_line(self):
        value = 'fake"\nheader = "injected'
        result = module.quote(value)
        self.assertNotIn("\n", result)
        self.assertIn('\\n', result)

    @unittest.skipUnless(shutil.which("curl"), "curl is required for the local protocol test")
    def test_real_curl_round_trip_against_local_fake_server(self):
        observed = []
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_args):
                pass
            def send_json(self, payload, cookie=False):
                data = json.dumps(payload).encode()
                self.send_response(200)
                if cookie:
                    self.send_header("Set-Cookie", "DATAFORGE_SESSION=fake-session; Path=/; HttpOnly")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)
            def do_GET(self):
                observed.append((self.path, self.headers.get("Cookie")))
                self.send_json({"headerName": "X-CSRF-TOKEN", "token": "fake-csrf"})
            def do_POST(self):
                length = int(self.headers.get("Content-Length", "0"))
                data = self.rfile.read(length)
                observed.append((self.path, data.decode(), self.headers.get("Cookie")))
                self.send_json({"role": "ADMIN"} if self.path.endswith("login") else {}, cookie=self.path.endswith("login"))
            def do_PUT(self):
                data = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                observed.append((self.path, data, self.headers.get("Cookie"), self.headers.get("X-CSRF-TOKEN")))
                self.send_json({"configured": True})
        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        real_run = module.subprocess.run
        try:
            with patch.object(module, "BASE", "http://127.0.0.1:" + str(server.server_port)), \
                 patch.object(module.subprocess, "run", side_effect=lambda args, **kwargs: real_run(args[4:], **kwargs)):
                self.assertTrue(module.configure("fake-container", "admin", 'fake"password', "fake-token")["configured"])
        finally:
            server.shutdown(); server.server_close(); thread.join(timeout=5)
        self.assertEqual(json.loads(observed[0][1])["password"], 'fake"password')
        self.assertEqual(observed[2][1], {"credential": "fake-token", "enabled": True})
        self.assertEqual(observed[2][2:], ("DATAFORGE_SESSION=fake-session", "fake-csrf"))
        self.assertEqual(observed[3][0], "/api/auth/logout")


if __name__ == "__main__":
    unittest.main()
