"""Trusted internal adapter. Only this service can invoke Docker; code never receives its key."""
import hmac
import http.server
import os
import re
import subprocess
import tempfile
import threading
import uuid

SECRET = os.environ.get("DATAFORGE_EXECUTION_SECRET", "")
IMAGE = os.environ.get("DATAFORGE_GENERATION_SANDBOX_IMAGE", "dataforge-generation-sandbox:local")
try:
    CONCURRENCY = max(1, min(4, int(os.environ.get("DATAFORGE_GENERATION_CONCURRENCY", "1"))))
except ValueError:
    CONCURRENCY = 1
SLOTS = threading.BoundedSemaphore(CONCURRENCY)


def secret_valid():
    return bool(re.fullmatch(r"[A-Za-z0-9_-]{24,128}", SECRET)) and not SECRET.startswith("CHANGE_ME")


def command(name):
    return ["docker", "run", "--rm", "--pull=never", "--name", name, "-i", "--network=none",
            "--read-only", "--cap-drop=ALL", "--security-opt=no-new-privileges:true",
            "--pids-limit=32", "--cpus=1", "--memory=1024m", "--memory-swap=1024m",
            "--user=10001:10001", "--log-driver=none", "--ulimit=nofile=64:64",
            "--tmpfs=/work:rw,exec,nosuid,size=384m,mode=1777",
            "--tmpfs=/tmp:rw,noexec,nosuid,size=32m,mode=1777", IMAGE]


class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path != "/health":
            self.send_error(404)
            return
        ready = secret_valid()
        if ready:
            try:
                ready = subprocess.run(["docker", "image", "inspect", IMAGE], stdout=subprocess.DEVNULL,
                                       stderr=subprocess.DEVNULL, timeout=5, check=False).returncode == 0
            except (OSError, subprocess.TimeoutExpired):
                ready = False
        self.send_response(200 if ready else 503)
        self.end_headers()
        self.wfile.write(b'{"status":"UP"}' if ready else b'{"status":"DOWN"}')

    def do_POST(self):
        if self.path != "/execute":
            self.send_error(404)
            return
        if not secret_valid() or not hmac.compare_digest(self.headers.get("X-Execution-Key", "").encode("utf-8"), SECRET.encode("ascii")):
            self.send_error(403)
            return
        try:
            size = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            size = 0
        if size <= 0 or size > 80 * 1024 * 1024:
            self.send_error(413)
            return
        if not SLOTS.acquire(blocking=False):
            self.send_error(429)
            return
        name = "dataforge-generation-" + uuid.uuid4().hex
        process = None
        response_started = False
        try:
            self.connection.settimeout(60)
            with tempfile.TemporaryFile() as inp, tempfile.TemporaryFile() as out:
                remaining = size
                while remaining:
                    chunk = self.rfile.read(min(65536, remaining))
                    if not chunk:
                        raise ValueError("incomplete request")
                    inp.write(chunk)
                    remaining -= len(chunk)
                inp.seek(0)
                process = subprocess.Popen(command(name), stdin=inp, stdout=out, stderr=subprocess.DEVNULL)
                process.wait(timeout=50)
                length = out.tell()
                if process.returncode or length > 80 * 1024 * 1024:
                    raise RuntimeError("sandbox failed")
                self.send_response(200)
                self.send_header("Content-Type", "application/zip")
                self.send_header("Content-Length", str(length))
                self.end_headers()
                response_started = True
                out.seek(0)
                while True:
                    chunk = out.read(65536)
                    if not chunk:
                        break
                    self.wfile.write(chunk)
        except Exception:
            if not response_started:
                self.send_error(502, "sandbox unavailable")
        finally:
            try:
                if process is not None and process.poll() is None:
                    process.kill()
                    process.wait(timeout=5)
                subprocess.run(["docker", "rm", "-f", name], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=15, check=False)
            except (OSError, subprocess.TimeoutExpired):
                pass
            finally:
                SLOTS.release()


if __name__ == "__main__":
    http.server.ThreadingHTTPServer(("0.0.0.0", 8780), Handler).serve_forever()
