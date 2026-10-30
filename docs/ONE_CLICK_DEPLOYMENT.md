# 阿里云一键部署（无需电脑保持在线）

适用于当前生产服务器：代码在 `/opt/DataForge`，生产配置在 `/etc/dataforge/dataforge.env`，使用阿里云 ACR 成品镜像。不会自动开放端口、购买服务、改动 root 登录方式或读取业务密码给 GitHub。

## 一次性设置

以下操作在现有阿里云 **root 网页终端**完成。不要把私钥、生产环境文件或 Secrets 截图发到聊天/工单。保持现有控制台连接，直到首次部署验证完毕。

1. 拉取包含本功能的 main；工作区有改动时先停止，不要强制覆盖。

   ```bash
   cd /opt/DataForge && git pull --ff-only
   ```

2. 创建独立部署密钥（不复用 root/GitHub/ACR 密钥；如果文件已存在会停止）。这是为 GitHub Actions 专门创建的无口令密钥。

   ```bash
   test ! -e /root/dataforge-actions && test ! -e /root/dataforge-actions.pub && (umask 077; ssh-keygen -t ed25519 -N '' -C dataforge-actions -f /root/dataforge-actions)
   ```

3. 安装受限入口。**这一步授予该公钥更新生产项目的权限**，运行前应确认你信任本仓库的 main 和有写入权限的维护者。部署会以 root 执行可信 main 的脚本，因此这不是对恶意仓库维护者的隔离边界。

   ```bash
   bash /opt/DataForge/scripts/setup-one-click.sh /root/dataforge-actions.pub
   ```

   安装器创建 `dataforge-deploy` 账号，锁定密码，不加入 docker/sudo/wheel 组；公钥由 root 管理，使用 OpenSSH `restrict` 和强制命令。仅允许 `deploy <40位提交号>`，不提供普通 Shell、SFTP、PTY、端口或代理转发。精确限制的 sudo 入口只允许启动部署服务。已有同名非托管账号时拒绝覆盖。

4. 在 GitHub 仓库 **Settings → Environments → New environment** 创建 `production`（若已存在直接使用）。如套餐支持，建议启用生产审批，并把部署分支限制为 main。

   在 production 环境中添加：

   | 类型 | 名称 | 值 |
   | --- | --- | --- |
   | Variable | `DEPLOY_HOST` | 当前服务器公网 IP `8.148.185.99` |
   | Variable（可选） | `DEPLOY_PORT` | SSH 端口，默认 `22` |
   | Secret | `DEPLOY_SSH_KEY` | 第 2 步生成的完整私钥内容 |
   | Secret | `DEPLOY_KNOWN_HOSTS` | 从可信服务器控制台核对得到的 SSH 主机公钥行 |

   用户在自己的终端查看私钥，直接复制到 GitHub Secret 输入框，不要贴到聊天里：

   ```bash
   cat /root/dataforge-actions
   ```

   取得**主机公钥**（不是上面的部署公钥）；默认端口 22 时：

   ```bash
   awk '{print "8.148.185.99 " $1 " " $2}' /etc/ssh/ssh_host_ed25519_key.pub
   ```

   若使用非 22 端口，主机字段应为 `[8.148.185.99]:实际端口`。不要使用互联网直接扫描的未核验结果，不要关闭 `StrictHostKeyChecking`。更换主机公钥后须从可信控制台重新核对并更新 Secret。

5. 确认云防火墙/安全组允许部署执行器连接现有 SSH 端口。**脚本不会修改防火墙**；若当前仅允许固定来源 IP，而 GitHub 执行器无法连接，请改用有固定出口的执行器或单独设计云命令方案，不要为了省事直接把所有管理端口开放给全网。

## 使用

等待同一提交的 **CI** 和 **Publish production images**（含 `mirror-acr`）成功后：

GitHub → Actions → **Deploy production** → Run workflow → 选择 main → 勾选确认 → Run workflow。

没有推送即自动部署、没有后台定时部署，也不会在 PR 测试中使用生产凭据。电脑断网或关闭不会停止已由 GitHub/服务器接管的部署。无需把服务器 SSH 密钥交给聊天助手。

工作流和服务器分别检查：

- 完整提交号必须仍是 main 的最新提交，检查、构建和镜像同步必须属于同一提交。
- 服务器工作区必须干净，且只能快进 main；不会 reset、覆盖或删除本地改动。
- 先准备所有镜像，之后停止服务并创建数据库与 runtime 备份。
- 始终保留旧备份；部署成功后检查网站健康并写入部署版本。不会自动恢复旧数据库，以免覆盖部署后新增的数据。
- 失败会尝试恢复旧应用并启动现有评测服务；数据库不兼容时必须由管理员决定是否恢复部署前备份。

服务器用 systemd 管理部署进程，SSH 中断不会中止它。自动和手动部署、备份共用同一把锁。若 GitHub 连接中断，先从控制台查看执行结果，**不要立即重复更新或恢复数据库**：

```bash
journalctl --no-pager -u dataforge-one-click.service -n 120
```

```bash
/opt/DataForge/scripts/prod.sh status
```

首次最好再登录网站检查工具列表、账号、作业、CSP 评测和数据生成。健康检查成功不等于所有业务功能已人工验收。

## 排错与权限维护

- CI/镜像“尚未成功”：等待该提交的两个工作流和 ACR 同步完成，再点部署。
- main 已变化：重新触发部署最新提交，旧审批不会部署未经核验的新版本。
- SSH 超时：检查来源限制、SSH 端口和网络。不要关闭主机验证或云防火墙。
- Permission denied：检查 production Secrets、公钥和服务器 SSH 账号策略；不要改动 root 密码或全局认证设置。
- SSH 主机公钥不匹配：停止并从云控制台核对，不要跳过警告。
- 本机 Python、Shell 模拟测试不替代 Linux 服务器上首次 SSH/systemd 实测。

这套方式使用现有 GitHub Actions 和现有阿里云服务器，不会购买新资源；Actions 用量仍按你的 GitHub 套餐和计费设置计算。

撤销部署权限：在服务器用管理员权限将 `/var/lib/dataforge-deploy/.ssh/authorized_keys` 改名为 `authorized_keys.disabled`，并删除 GitHub 的部署私钥 Secret。不要删除 root 的 authorized_keys。公钥轮换由管理员重新审核后运行安装器完成，旧部署公钥会被替换。确认新密钥正常后，自行妥善保存或销毁临时私钥文件；不要把它纳入公开备份或 Git。
