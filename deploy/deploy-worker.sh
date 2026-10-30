#!/usr/bin/env bash
set -Eeuo pipefail
export PATH=/usr/sbin:/usr/bin:/sbin:/bin
export HOME=/root
export GIT_TERMINAL_PROMPT=0
export GITHUB_REPOSITORY=shendeguilai/DataForge
[[ ${EUID} -eq 0 && $# -eq 1 && "$1" =~ ^[0-9a-f]{40}$ ]] || exit 64
release_sha=$1
repo=/opt/DataForge
# Share the production script's lock before changing the checkout, not only before restarting containers.
source /etc/dataforge/dataforge.env
state_file=${DATAFORGE_STATE_FILE:-/srv/dataforge/deployed_commit}
[[ "$state_file" == /* && "$state_file" != / ]] || exit 64
exec 9>"${state_file}.lock"
flock -n 9 || { echo "已有部署或备份正在执行"; exit 1; }
[[ -d "$repo/.git" && ! -L "$repo" ]] || { echo "项目目录不正确" >&2; exit 1; }
[[ $(stat -c %u "$repo") == 0 ]] || { echo "项目必须由 root 管理" >&2; exit 1; }
[[ $(git -C "$repo" branch --show-current) == main ]] || { echo "项目必须处于 main 分支" >&2; exit 1; }
[[ -z $(git -C "$repo" status --porcelain) ]] || { echo "服务器有未提交改动，停止更新" >&2; exit 1; }
git -C "$repo" fetch --no-tags origin main
[[ $(git -C "$repo" rev-parse refs/remotes/origin/main) == "$release_sha" ]] || {
  echo "main 已变化，请重新触发最新版本部署" >&2; exit 1;
}
# Also enforce the gate on the server, so possession of this SSH key cannot skip CI.
python3 /usr/local/libexec/dataforge-check-release.py "$release_sha"
git -C "$repo" merge --ff-only "$release_sha"
exec /usr/bin/env -i HOME=/root PATH="$PATH" LANG=C.UTF-8 \
  GIT_TERMINAL_PROMPT=0 DATAFORGE_KEEP_OLD_BACKUPS=true \
  DATAFORGE_DEPLOY_LOCK_FD=9 \
  "$repo/scripts/prod.sh" deploy-release "$release_sha"
