#!/usr/bin/env bash
set -Eeuo pipefail
export PATH=/usr/sbin:/usr/bin:/sbin:/bin
if [[ ${SSH_ORIGINAL_COMMAND:-} =~ ^deploy\ ([0-9a-f]{40})$ ]]; then
  exec sudo -n /usr/local/sbin/dataforge-deploy-release "${BASH_REMATCH[1]}"
fi
echo "此密钥仅允许部署 main 的已验证提交，不提供命令行、文件传输或端口转发。" >&2
exit 64
