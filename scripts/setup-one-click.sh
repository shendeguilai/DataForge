#!/usr/bin/env bash
# Run once in the existing Aliyun root console, using only a PUBLIC key as input.
set -Eeuo pipefail
export PATH=/usr/sbin:/usr/bin:/sbin:/bin
[[ ${EUID} -eq 0 && $# -eq 1 ]] || { echo "用法：sudo bash scripts/setup-one-click.sh <部署公钥文件>"; exit 64; }
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo=$(cd "$script_dir/.." && pwd)
[[ "$repo" == /opt/DataForge ]] || { echo "本安装器仅适用于已确认的 /opt/DataForge"; exit 1; }
for executable in ssh-keygen useradd usermod sudo visudo systemd-run journalctl python3 git docker flock; do
  command -v "$executable" >/dev/null || { echo "缺少必需命令：$executable"; exit 1; }
done
[[ -f "$1" && ! -L "$1" ]] || { echo "请提供一个普通公钥文件"; exit 1; }
[[ $(wc -l < "$1") -eq 1 ]] || { echo "只能提供一行公钥"; exit 1; }
read -r key_type key_data key_comment < "$1"
[[ "$key_type" == ssh-ed25519 && "$key_data" =~ ^[A-Za-z0-9+/=]+$ ]] || { echo "只接受标准 Ed25519 公钥，不接受私钥或带选项的行"; exit 1; }
ssh-keygen -lf "$1" >/dev/null
[[ $(stat -c %u "$repo") == 0 ]] || { echo "项目目录必须归 root 所有"; exit 1; }
[[ -f /etc/dataforge/dataforge.env ]] || { echo "没有找到现有生产配置"; exit 1; }
deploy_user=dataforge-deploy
deploy_home=/var/lib/dataforge-deploy
if id "$deploy_user" >/dev/null 2>&1; then
  [[ -f /etc/dataforge/one-click-installed && $(getent passwd "$deploy_user" | cut -d: -f6) == "$deploy_home" ]] || {
    echo "同名账号已存在且不是本安装器管理，拒绝修改"; exit 1;
  }
else
  useradd --system --create-home --home-dir "$deploy_home" --shell /bin/bash "$deploy_user"
  install -m 600 /dev/null /etc/dataforge/one-click-installed
fi
usermod -L "$deploy_user"
[[ ! -L "$deploy_home" && ! -L "$deploy_home/.ssh" ]] || { echo "账号目录不可为符号链接"; exit 1; }
[[ " $(id -nG "$deploy_user") " != *" docker "* && " $(id -nG "$deploy_user") " != *" wheel "* && " $(id -nG "$deploy_user") " != *" sudo "* ]] || {
  echo "部署账号存在额外特权组，请人工审查"; exit 1;
}
install -d -o root -g root -m 755 /usr/local/libexec /usr/local/sbin
install -o root -g root -m 755 "$repo/deploy/ssh-entry.sh" /usr/local/libexec/dataforge-ssh-entry
install -o root -g root -m 755 "$repo/deploy/deploy-release.sh" /usr/local/sbin/dataforge-deploy-release
install -o root -g root -m 755 "$repo/deploy/deploy-worker.sh" /usr/local/libexec/dataforge-deploy-worker
install -o root -g root -m 755 "$repo/scripts/check-release.py" /usr/local/libexec/dataforge-check-release.py
# Root-owned home and authorized_keys prevent the deployment account from adding unrestricted keys.
chown root:root "$deploy_home"
chmod 755 "$deploy_home"
install -d -o root -g root -m 755 "$deploy_home/.ssh"
sudo_tmp=$(mktemp)
keys_tmp=$(mktemp)
trap 'rm -f "$sudo_tmp" "$keys_tmp"' EXIT
printf 'dataforge-deploy ALL=(root) NOPASSWD: /usr/local/sbin/dataforge-deploy-release *\n' > "$sudo_tmp"
chmod 600 "$sudo_tmp"
visudo -cf "$sudo_tmp"
install -o root -g root -m 440 "$sudo_tmp" /etc/sudoers.d/dataforge-one-click
printf 'restrict,command="/usr/local/libexec/dataforge-ssh-entry" %s %s dataforge-actions\n' "$key_type" "$key_data" > "$keys_tmp"
install -o root -g root -m 644 "$keys_tmp" "$deploy_home/.ssh/authorized_keys"
echo "专用部署账号已配置；没有修改 root 的 SSH、云防火墙或现有生产密码。"
echo "下一步：核对 SSH 主机公钥，并在 GitHub production 环境中配置部署变量和 Secrets。"
