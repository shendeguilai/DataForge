"""Two leased workers. Only the trusted worker can reach the Docker daemon and judge API."""
import base64
import concurrent.futures
import json
import logging
import os
import subprocess
import threading
import time
import urllib.error
import urllib.request
import uuid

LOG = logging.getLogger("csp-judge")


class JudgeClient:
    def __init__(self, url, key):
        self.url, self.key = url.rstrip("/"), key

    def request(self, path, body=None, lease=None, binary=False):
        headers = {"X-CSP-Worker-Key": self.key}
        if lease:
            headers["X-CSP-Lease"] = lease
        data = None
        if body is not None:
            headers["Content-Type"] = "application/json"
            data = json.dumps(body).encode()
        req = urllib.request.Request(self.url + path, data=data, headers=headers)
        with urllib.request.urlopen(req, timeout=30) as response:
            content = response.read(32 * 1024 * 1024 + 1)
            if len(content) > 32 * 1024 * 1024:
                raise RuntimeError("judge API response too large")
            return content if binary else json.loads(content) if content else {}


class DockerSandbox:
    def __init__(self, image):
        self.image = image

    def run(self, payload):
        name = "dataforge-csp-" + uuid.uuid4().hex
        compile_mode = payload["action"] == "compile"
        memory = 768 if compile_mode else payload["memoryMb"] + 64
        command = ["docker", "run", "--pull=never", "--name", name, "-i", "--network=none",
                   "--read-only", "--cap-drop=ALL", "--security-opt=no-new-privileges:true",
                   "--pids-limit=64" if compile_mode else "--pids-limit=8", "--cpus=1",
                   "--memory=" + str(memory) + "m", "--memory-swap=" + str(memory) + "m",
                   "--user=10001:10001", "--log-driver=none", "--ulimit=nofile=64:64",
                   "--tmpfs=/work:rw,exec,nosuid,size=64m,mode=1777",
                   "--tmpfs=/tmp:rw,noexec,nosuid,size=16m,mode=1777", self.image]
        process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        outputs = {"stdout": bytearray(), "stderr": bytearray()}
        too_large = threading.Event()

        def read_output(stream, target, maximum):
            try:
                while True:
                    chunk = stream.read(65536)
                    if not chunk:
                        break
                    if len(outputs[target]) + len(chunk) > maximum:
                        too_large.set()
                        process.kill()
                        break
                    outputs[target].extend(chunk)
            finally:
                stream.close()

        readers = [threading.Thread(target=read_output, args=(process.stdout, "stdout", 32 * 1024 * 1024), daemon=True),
                   threading.Thread(target=read_output, args=(process.stderr, "stderr", 65536), daemon=True)]
        for reader in readers:
            reader.start()
        try:
            process.stdin.write(json.dumps(payload).encode())
            process.stdin.close()
            process.wait(timeout=65 if compile_mode else payload["timeLimitMs"] / 500 + 30)
            for reader in readers:
                reader.join(timeout=5)
            if too_large.is_set():
                return {"verdict": "SYSTEM_ERROR" if compile_mode else "OLE", "message": "sandbox response exceeds limit"}
            if process.returncode:
                state = subprocess.run(["docker", "inspect", "--format={{json .State}}", name], capture_output=True, timeout=10, check=False)
                if state.returncode == 0 and json.loads(state.stdout).get("OOMKilled"):
                    return {"verdict": "SYSTEM_ERROR" if compile_mode else "MLE", "message": "memory limit exceeded"}
                if not compile_mode and process.returncode in {137, 139}:
                    return {"verdict": "RE", "message": "程序异常结束"}
                raise RuntimeError("sandbox exited without a complete result: " + outputs["stderr"].decode("utf-8", "replace")[:1500])
            result = json.loads(outputs["stdout"])
            if result.get("verdict") == "SYSTEM_ERROR":
                raise RuntimeError(result.get("message", "sandbox failed"))
            return result
        finally:
            if process.poll() is None:
                process.kill()
            process.wait(timeout=5)
            if not process.stdin.closed:
                process.stdin.close()
            subprocess.run(["docker", "rm", "-f", name], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=15, check=False)


def normalize_output(content):
    # Preserve interior whitespace and blank lines, as required by full-text comparison.
    return b"\n".join(line.rstrip(b" \t\r") for line in content.split(b"\n")).rstrip(b"\n")


def grade_task(task, fetch_blob, sandbox):
    compiled = sandbox.run({"action": "compile", "sourceName": task["problem"].get("sourceName", "source.cpp"), "source": base64.b64encode(fetch_blob(task["sourceBlob"])).decode()})
    result = {"verdict": compiled["verdict"], "message": compiled.get("message", "")[:16000],
              "environment": compiled.get("environment", ""), "cases": []}
    if compiled["verdict"] != "AC":
        return result
    problem = task["problem"]
    for case in problem["cases"]:
        observed = sandbox.run({"action": "run", "program": compiled["program"],
                                "input": base64.b64encode(fetch_blob(case["inputBlob"])).decode(),
                                "ioMode": problem["ioMode"], "inputName": problem.get("inputName"),
                                "outputName": problem.get("outputName"), "memoryMb": problem["memoryMb"],
                                "timeLimitMs": problem["timeLimitMs"]})
        verdict = observed["verdict"]
        if verdict == "AC":
            output = base64.b64decode(observed.get("output", ""), validate=True)
            verdict = "AC" if normalize_output(output) == normalize_output(fetch_blob(case["answerBlob"])) else "WA"
        if verdict not in {"AC", "WA", "TLE", "MLE", "RE", "OLE"}:
            raise RuntimeError("invalid sandbox verdict")
        result["cases"].append({"id": case["id"], "verdict": verdict,
                                "runtimeMs": observed.get("runtimeMs", 0), "memoryBytes": observed.get("memoryBytes", 0)})
    passed = sum(case["verdict"] == "AC" for case in result["cases"])
    result["verdict"] = "AC" if passed == len(result["cases"]) else "PARTIAL" if passed else result["cases"][0]["verdict"]
    return result


def run_claim(client, claim, sandbox):
    task_id, task = claim["id"], claim["task"]
    lease = task["leaseToken"]
    prefix = "/worker/tasks/" + task_id
    stop, lost = threading.Event(), threading.Event()

    def renew():
        while not stop.wait(20):
            try:
                client.request(prefix + "/heartbeat", {"leaseToken": lease})
            except Exception:
                lost.set()
                LOG.warning("lease renewal failed for %s", task_id)
                return

    heartbeat = threading.Thread(target=renew, daemon=True)
    heartbeat.start()
    try:
        def fetch(blob):
            if lost.is_set():
                raise RuntimeError("lease lost")
            return client.request(prefix + "/blobs/" + blob, lease=lease, binary=True)
        try:
            result = grade_task(task, fetch, sandbox)
        except Exception as error:
            LOG.error("task %s failed: %s", task_id, type(error).__name__)
            result = {"verdict": "SYSTEM_ERROR", "message": str(error)[:1500], "environment": "", "cases": []}
        if not lost.is_set():
            client.request(prefix + "/complete", {"leaseToken": lease, "result": result})
    finally:
        stop.set()
        heartbeat.join(timeout=35)


def worker_loop(client, sandbox):
    while True:
        try:
            claim = client.request("/worker/claim", {})
            if not claim:
                time.sleep(3)
                continue
            run_claim(client, claim, sandbox)
        except Exception as error:
            LOG.warning("worker unavailable: %s", type(error).__name__)
            time.sleep(5)


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    key = os.environ.get("CSP_SIM_WORKER_SECRET", "")
    if len(key) < 24:
        raise SystemExit("CSP_SIM_WORKER_SECRET must contain at least 24 characters")
    client = JudgeClient(os.environ.get("CSP_SIM_API_URL", "http://app:8080/api/tools/csp-sim"), key)
    sandbox = DockerSandbox(os.environ.get("CSP_SIM_SANDBOX_IMAGE", "dataforge-csp-sandbox:local"))
    count = max(1, min(4, int(os.environ.get("CSP_SIM_CONCURRENCY", "2"))))
    with concurrent.futures.ThreadPoolExecutor(max_workers=count) as executor:
        for _ in range(count):
            executor.submit(worker_loop, client, sandbox)
