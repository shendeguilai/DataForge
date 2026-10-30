#!/usr/bin/env python3
"""Fail closed unless main's exact release has passed CI and its ACR mirror."""
import json
import os
import re
import sys
import urllib.request


def validate(runs, jobs, sha):
    for name in ("CI", "Publish production images"):
        matches = [r for r in runs if r.get("name") == name and r.get("head_sha") == sha
                   and r.get("head_branch") == "main" and r.get("event") in ("push", "workflow_dispatch")]
        if not matches:
            raise ValueError(name + " 尚未运行，请等待构建完成")
        run = max(matches, key=lambda r: (r.get("run_number", 0), r.get("run_attempt", 1)))
        if run.get("status") != "completed" or run.get("conclusion") != "success":
            raise ValueError(name + " 尚未成功，不允许部署")
        if name == "Publish production images":
            mirrors = [j for j in jobs(run["id"]) if j.get("name") == "mirror-acr"]
            if not mirrors or any(j.get("conclusion") != "success" for j in mirrors):
                raise ValueError("阿里云镜像同步尚未成功，不允许部署")


def get(path):
    request = urllib.request.Request("https://api.github.com/" + path,
                                     headers={"Accept": "application/vnd.github+json",
                                              "User-Agent": "DataForge-Deploy/1.0"})
    token = os.environ.get("GITHUB_TOKEN")
    if token:
        request.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(request, timeout=25) as response:
        data = response.read(4 * 1024 * 1024 + 1)
        if len(data) > 4 * 1024 * 1024:
            raise ValueError("GitHub 返回过大")
        return json.loads(data)


def main():
    sha = sys.argv[1] if len(sys.argv) == 2 else ""
    repo = os.environ.get("GITHUB_REPOSITORY", "shendeguilai/DataForge")
    if not re.fullmatch(r"[0-9a-f]{40}", sha) or not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo):
        raise ValueError("部署参数无效")
    prefix = "repos/" + repo
    if get(prefix + "/git/ref/heads/main")["object"]["sha"] != sha:
        raise ValueError("main 已更新，请重新触发最新版本部署")
    runs = []
    for workflow in ("ci.yml", "publish-production-images.yml"):
        runs.extend(get(prefix + "/actions/workflows/" + workflow
                        + "/runs?branch=main&head_sha=" + sha + "&per_page=100")["workflow_runs"])

    def jobs(run_id):
        result = []
        for page in range(1, 11):
            chunk = get(prefix + "/actions/runs/" + str(run_id) + "/jobs?per_page=100&page=" + str(page))["jobs"]
            result.extend(chunk)
            if len(chunk) < 100:
                return result
        raise ValueError("GitHub 任务过多，无法确认镜像同步状态")

    validate(runs, jobs, sha)
    print("版本检查通过：" + sha)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        # Do not print HTTP request headers, response bodies or tokens.
        print("发布检查未通过：" + (str(error) if isinstance(error, ValueError)
                                  else "无法读取 GitHub 状态，请稍后重试"), file=sys.stderr)
        sys.exit(1)
