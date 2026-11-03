# 源码系统连接与心跳

DataForge 管理后台的“源码系统连接”与“可达 OJ 作业跟踪”是两套独立配置。前者连接 `https://tea.kedaoi.cn`，使用 `Admin-Token`；后者连接 `https://kedaoi.cn`，使用 SID，两者不能互换。只有 DataForge 管理员可以管理源码系统连接；这是一份全站共享配置，不属于单个普通用户。

## 配置

源码系统已登录后，从浏览器开发者工具 `Application → Cookies → https://tea.kedaoi.cn` 中**由管理员本人**复制 `Admin-Token` 的 Value 纯值。不要把 Token、Cookie 或截图发到聊天，也不要把它写进命令行参数、文件、日志或 Git。

- **通过 HTTPS 访问 DataForge**：打开“后台管理 → 源码系统连接”，输入纯 Token 并保存。保存前会访问源码系统验证身份，成功后加密保存；留空可保留已有 Token 并单独暂停或启用心跳。配置不会回显。
- **当前仅有 HTTP 8080**：不要在网页上输入任何密码或 Token。通过可信的阿里云网页终端或 SSH 登录服务器，在交互式终端执行：

  ```bash
  cd /opt/DataForge && python3 scripts/configure-yuanma-token.py
  ```

  按提示输入 DataForge 管理员用户名、管理员密码和源码系统 Token。密码与 Token 使用隐藏输入，不放入命令参数或文件；脚本在应用容器内访问本机回环地址，并在完成后退出本次临时登录。应用容器默认名为 `dataforge-app-1`，名称不同时可使用 `--container 容器名`。不要在不可信服务器或共享终端运行。

  当前公开的明文 HTTP 同样会暴露普通网站密码和会话。长期使用应配置 HTTPS，并适当限制直接访问应用端口。程序只接受安全连接或应用容器本地回环地址提交新 Token；若通过反向代理配置 HTTPS，还需正确配置受信任代理的原始协议，不能让公网客户端伪造转发头。

保存成功后即可在后台查看连接状态。身份验证失败不会覆盖原有有效凭据。Token 使用项目的 `DATAFORGE_SECRET` 加密，保存在现有数据库中，前端和接口均不返回明文或密文；更换加密密钥前必须做好迁移，否则需重新录入。

现有旧 H2 导入流程不迁移作业跟踪记录或本配置；从旧 H2 迁移后需重新录入源码系统 Token。日常 PostgreSQL 备份与一键部署沿用现有数据库，不受此限制。

## 心跳与限制

- 默认每 **10 分钟**向固定地址 `https://tea.kedaoi.cn/prod-api/getInfo` 发送一次只读身份请求；服务器每分钟检查一次是否到时间。使用 HTTPS、20 秒请求超时，不跟随跳转，不向其他地址转发 Token。
- 关闭浏览器或电脑不会停止服务器心跳；DataForge 服务停机期间不会执行。重启后依据上次检查时间继续，超时后先尝试一次。
- 明确返回认证失败（包括 HTTP 200 内的业务码 401）时暂停自动心跳，须重新录入有效 Token 或手动验证成功。403 是权限问题，不能直接称为 Token 过期；网络失败保留 Token 并按间隔重试，429 限流后至少等待一小时。
- 后台可立即验证、暂停或清除 Token，显示最近检查和最近成功时间。清除后停止心跳，不影响 OJ SID 和作业。
- **验证成功只说明当时接口认可该 Token，不证明它延长了有效期。** 源码系统可能有固定到期、主动注销、账号风控或其他策略；本功能也不能更新浏览器里的 Cookie，不能保证网页登录永不掉线。真实效果须用用户自己的有效凭据长时间观察。

## 部署与测试

更新项目并部署到阿里云后，使用上述安全方式录入 Token。心跳依赖阿里云能够访问源码系统，不使用 Mac 上的本地代理，也不需要 Mac 开机。修改不会创建额外云资源。无效的 Token 不会作为已验证配置保存。

无凭据自动化测试使用本地模拟服务，不访问真实源码系统：

```bash
mvn -Dtest='cn.datacraft.yuanma.*Test' test
python3 -m unittest discover -s tests/deploy -p 'test_*.py' -v
```

开发测试可设置 `dataforge.yuanma.scheduling-enabled=false`；心跳间隔属性 `dataforge.yuanma.heartbeat-seconds` 默认为 600 秒，仅接受 60～86400 秒。生产 Compose 如需覆盖，须通过受控的应用容器环境或 Java 配置传入，不要把 Token 放在环境变量中。
