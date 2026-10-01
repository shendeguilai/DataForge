"""Trusted entrypoint inside a disposable Linux container. No answers or service keys enter it."""
import base64
import json
import math
import os
import resource
import signal
import stat
import subprocess
import sys
import time
import errno

WORK = "/work"
OUTPUT_LIMIT = 16 * 1024 * 1024


def write_file(name, content):
    if not name or "/" in name or "\\" in name or name in (".", ".."):
        raise ValueError("invalid sandbox filename")
    with open(os.path.join(WORK, name), "wb") as stream:
        stream.write(content)


def read_file(name, limit=OUTPUT_LIMIT):
    fd = os.open(os.path.join(WORK, name), os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    try:
        info = os.fstat(fd)
        if not stat.S_ISREG(info.st_mode) or info.st_size > limit:
            raise ValueError("output exceeds limit or is not a regular file")
        with os.fdopen(fd, "rb", closefd=False) as stream:
            return stream.read(limit + 1)
    finally:
        os.close(fd)


def execute(command, memory_mb, cpu_ms, wall_seconds, stdin_name, stdout_name, compiler=False):
    def limits():
        resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
        resource.setrlimit(resource.RLIMIT_FSIZE, (OUTPUT_LIMIT, OUTPUT_LIMIT))
        resource.setrlimit(resource.RLIMIT_AS, (memory_mb * 1024 * 1024,) * 2)
        resource.setrlimit(resource.RLIMIT_STACK, (memory_mb * 1024 * 1024,) * 2)
        resource.setrlimit(resource.RLIMIT_CPU, (math.ceil(cpu_ms / 1000) + 1,) * 2)
        if not compiler:
            resource.setrlimit(resource.RLIMIT_NPROC, (1, 1))

    started = time.monotonic()
    with open(os.path.join(WORK, stdin_name), "rb") as inp, \
            open(os.path.join(WORK, stdout_name), "wb") as out, \
            open(os.path.join(WORK, "stderr"), "wb") as err:
        try:
            child = subprocess.Popen(command, cwd=WORK, stdin=inp, stdout=out, stderr=err,
                                     preexec_fn=limits, start_new_session=True)
        except OSError as error:
            if not compiler and error.errno in (errno.ENOMEM, errno.EAGAIN):
                return dict(verdict="MLE", message="程序无法在内存限制内启动", runtimeMs=0, memoryBytes=0)
            raise
        timed_out = False
        while True:
            pid, status, usage = os.wait4(child.pid, os.WNOHANG)
            if pid:
                break
            if time.monotonic() - started > wall_seconds:
                timed_out = True
                os.killpg(child.pid, signal.SIGKILL)
                _, status, usage = os.wait4(child.pid, 0)
                break
            time.sleep(0.005)
        # Ubuntu 20.04 provides Python 3.8, before os.waitstatus_to_exitcode.
        child.returncode = os.WEXITSTATUS(status) if os.WIFEXITED(status) else -os.WTERMSIG(status)
    runtime_ms = round((usage.ru_utime + usage.ru_stime) * 1000)
    memory_bytes = usage.ru_maxrss * 1024
    message = read_file("stderr", OUTPUT_LIMIT)[:16000].decode("utf-8", "replace")
    verdict = "AC"
    if timed_out or runtime_ms > cpu_ms or child.returncode == -signal.SIGXCPU:
        verdict = "TLE"
    elif child.returncode == -signal.SIGXFSZ:
        verdict = "OLE"
    elif memory_bytes >= memory_mb * 1024 * 1024 or "bad_alloc" in message or "cannot allocate memory" in message.lower():
        verdict = "MLE"
    elif child.returncode:
        verdict = "RE"
    return dict(verdict=verdict, message=message, runtimeMs=runtime_ms, memoryBytes=memory_bytes)


def main():
    payload = json.load(sys.stdin)
    compiler_version = subprocess.check_output(["g++", "-dumpfullversion"], text=True).strip()
    if compiler_version != "9.3.0":
        raise RuntimeError("expected g++ 9.3.0; got " + compiler_version)
    if payload["action"] == "compile":
        source_name=payload.get("sourceName", "source.cpp")
        write_file(source_name, base64.b64decode(payload["source"], validate=True))
        write_file("stdin", b"")
        result = execute(["g++", "./"+source_name, "-o", "program", "-O2", "-std=c++14", "-static"],
                         512, 30000, 40, "stdin", "stdout", compiler=True)
        if result["verdict"] != "AC":
            # A compiler time/resource limit is an infrastructure error, never a student's zero.
            result["verdict"] = "CE" if result["verdict"] == "RE" else "SYSTEM_ERROR"
        else:
            result["program"] = base64.b64encode(read_file("program", OUTPUT_LIMIT)).decode("ascii")
    else:
        write_file("program", base64.b64decode(payload["program"], validate=True))
        os.chmod(os.path.join(WORK, "program"), 0o500)
        write_file("stdin", base64.b64decode(payload["input"], validate=True))
        output_name = "stdout"
        if payload["ioMode"] == "FILE":
            write_file(payload["inputName"], read_file("stdin", 25 * 1024 * 1024))
            write_file("stdin", b"")
            output_name = payload["outputName"]
        result = execute(["./program"], payload["memoryMb"], payload["timeLimitMs"],
                         2 * payload["timeLimitMs"] / 1000 + 1, "stdin", "stdout")
        if result["verdict"] == "AC":
            try:
                result["output"] = base64.b64encode(read_file(output_name)).decode("ascii")
            except FileNotFoundError:
                result["verdict"], result["message"] = "WA", "未生成规定的输出文件：" + output_name + "。题目配置为文件输入输出；请按题目要求读写指定文件，若代码使用cin/cout，请由教师确认是否应改用标准输入输出。"
            except (OSError, ValueError):
                result["verdict"], result["message"] = "OLE", "输出文件超限或类型无效"
    result["environment"] = "Ubuntu 20.04 / g++ " + compiler_version + " / -O2 -std=c++14 -static"
    json.dump(result, sys.stdout)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        json.dump(dict(verdict="SYSTEM_ERROR", message=type(error).__name__ + ": " + str(error)[:1000]), sys.stdout)
