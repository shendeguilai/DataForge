"""Untrusted code runs only here, without network, service credentials or host mounts."""
import io
import json
import os
import resource
import signal
import stat
import subprocess
import sys
import zipfile

MAX_FILE = 64 * 1024 * 1024


def execute(request):
    allowed = {"request.json", "source.cpp", "program", "input"}
    seen = set()
    with zipfile.ZipFile(request) as archive:
        if len(archive.infolist()) > 4:
            raise ValueError("too many execution files")
        for entry in archive.infolist():
            if entry.filename not in allowed or entry.filename in seen or entry.file_size > MAX_FILE:
                raise ValueError("invalid execution file")
            seen.add(entry.filename)
            with archive.open(entry) as src, open(entry.filename, "wb") as dst:
                remaining = MAX_FILE
                while True:
                    chunk = src.read(min(65536, remaining + 1))
                    if not chunk:
                        break
                    remaining -= len(chunk)
                    if remaining < 0:
                        raise ValueError("execution file too large")
                    dst.write(chunk)
    if os.path.getsize("request.json") > 8192:
        raise ValueError("execution metadata too large")
    with open("request.json", encoding="utf-8") as stream:
        meta = json.load(stream)
    action = meta["action"]
    seconds = int(meta.get("timeoutSeconds", 10))
    if seconds < 1 or seconds > 40:
        raise ValueError("invalid timeout")
    if action == "compile":
        standard = meta.get("standard", "c++17")
        if standard not in {"c++14", "c++17", "c++20"}:
            raise ValueError("invalid C++ standard")
        command = ["g++", "source.cpp", "-O2", "-pipe", "-std=" + standard, "-o", "program"]
    elif action == "run":
        args = meta.get("args", [])
        if len(args) > 8 or any(not isinstance(a, str) or len(a) > 128 for a in args):
            raise ValueError("invalid arguments")
        os.chmod("program", 0o500)
        command = ["./program", *args]
    else:
        raise ValueError("invalid action")

    def limits():
        resource.setrlimit(resource.RLIMIT_FSIZE, (MAX_FILE,) * 2)
        resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
        resource.setrlimit(resource.RLIMIT_CPU, (seconds + 1,) * 2)
        resource.setrlimit(resource.RLIMIT_AS, (768 * 1024 * 1024,) * 2)
        resource.setrlimit(resource.RLIMIT_STACK, (256 * 1024 * 1024,) * 2)

    if "input" not in seen:
        open("input", "wb").close()
    with open("input", "rb") as inp, open("output", "wb") as out, open("error", "wb") as err:
        child = subprocess.Popen(command, stdin=inp, stdout=out, stderr=err,
                                 env={"PATH": "/usr/bin:/bin", "LANG": "C.UTF-8"},
                                 preexec_fn=limits, start_new_session=True)
        try:
            child.wait(timeout=seconds)
        except subprocess.TimeoutExpired:
            os.killpg(child.pid, signal.SIGKILL)
            child.wait()
            return {"ok": False, "message": "程序运行超时"}, None
    with open("error", "rb") as error:
        detail = error.read(16000).decode("utf-8", "replace")
    if child.returncode:
        return {"ok": False, "message": "程序退出码 %s：%s" % (child.returncode, detail)}, None
    filename = "program" if action == "compile" else "output"
    fd = os.open(filename, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(fd, "rb") as stream:
        info = os.fstat(stream.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_size > MAX_FILE:
            raise ValueError("invalid execution output")
        return {"ok": True}, (filename, stream.read(MAX_FILE + 1))


def main():
    # The container has a bounded tmpfs; never buffer the upload in Python memory.
    with open("request.zip", "wb") as request:
        total = 0
        while True:
            chunk = sys.stdin.buffer.read(65536)
            if not chunk:
                break
            total += len(chunk)
            if total > 80 * 1024 * 1024:
                raise ValueError("request too large")
            request.write(chunk)
    try:
        report, output = execute("request.zip")
    except Exception as error:
        report, output = {"ok": False, "message": str(error)[:2000]}, None
    # Remove untrusted writable files before packaging the already-read result.
    response = io.BytesIO()
    with zipfile.ZipFile(response, "w", compression=zipfile.ZIP_STORED) as archive:
        archive.writestr("result.json", json.dumps(report).encode())
        if output:
            archive.writestr(*output)
    sys.stdout.buffer.write(response.getvalue())


if __name__ == "__main__":
    main()
