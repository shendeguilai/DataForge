# 使用 GHCR 部署生产镜像

当生产服务器不能访问 Docker Hub 时，DataForge 使用 GitHub Actions 构建 Linux AMD64 成品镜像并发布到 GitHub Container Registry（GHCR）。生产服务器只从 `ghcr.io` 拉取成品，不下载 Dockerfile 基础镜像，也不执行 Maven、pip 或 apt 构建步骤。

## 1. 发布的镜像

`.github/workflows/publish-production-images.yml` 在 `main` 更新时使用仓库内置的 `GITHUB_TOKEN` 发布：

```text
ghcr.io/<owner>/dataforge:<完整 Git 提交号>
ghcr.io/<owner>/dataforge-csp-studio:<完整 Git 提交号>
ghcr.io/<owner>/dataforge-postgres:17.10-bookworm
```

不需要在 GitHub 仓库中保存额外的镜像仓库密码。工作流权限只包含读取代码和写入 GitHub Packages。

应用镜像只发布 `linux/amd64`，与常见阿里云 ECS x86_64 实例匹配。如果 ECS 使用 ARM 实例，必须先扩展工作流平台列表，不能直接运行 AMD64 镜像。

## 2. 创建服务器拉取凭证

私有 GHCR 包需要 GitHub 访问令牌。建议创建只授予 `read:packages` 的专用令牌，不授予代码写入或删除包权限。使用实际运行 Docker 的 Linux 用户登录：

```bash
read -r -s -p "GHCR token: " GHCR_TOKEN
echo
printf '%s' "$GHCR_TOKEN" | docker login ghcr.io --username '<GitHub 用户名>' --password-stdin
unset GHCR_TOKEN
```

令牌只进入该 Linux 用户的 Docker 登录配置，不写入项目环境文件、Git 仓库或 Shell 历史。

## 3. 配置生产服务器

在 `/etc/dataforge/dataforge.env` 中设置：

```text
DATAFORGE_IMAGE_SOURCE=registry
DATAFORGE_REGISTRY_PREFIX=ghcr.io/<owner>/
DATAFORGE_POSTGRES_IMAGE=ghcr.io/<owner>/dataforge-postgres:17.10-bookworm
```

`DATAFORGE_REGISTRY_PREFIX` 必须以 `/` 结尾。

首次发布要先在 GitHub Actions 中确认 `Publish production images` 对目标提交执行成功，然后部署：

```bash
cd /opt/dataforge/app
./scripts/prod.sh deploy
./scripts/prod.sh status
```

registry 模式会先拉取目标提交的全部成品镜像。任何镜像缺失时，旧服务保持运行，部署在停止应用前失败。启动阶段使用 `--no-build --pull never`，不会回退到 Docker Hub。

## 4. 验收

```bash
docker compose --env-file /etc/dataforge/dataforge.env -f compose.prod.yml ps
curl --fail http://127.0.0.1:8080/actuator/health
docker compose --env-file /etc/dataforge/dataforge.env -f compose.prod.yml \
  exec -T csp-studio python -c \
  "import json,urllib.request; assert json.load(urllib.request.urlopen('http://127.0.0.1:8765/health'))['status']=='UP'"
```

`db`、`app`、`csp-studio` 应全部为 `healthy`；CSP 服务只应显示 Compose 内部的 `8765/tcp`，不得出现宿主机端口映射。

## 5. 故障处理

- `unauthorized`：重新执行 `docker login ghcr.io`，确认令牌包含 `read:packages`，并且账号有权读取该仓库的包。
- `manifest unknown`：GitHub Actions 尚未发布当前 Git 提交号对应的镜像，等待工作流成功后重试。
- `no matching manifest for linux/amd64`：镜像架构错误，检查工作流是否使用 `platforms: linux/amd64`。
- GHCR 拉取超时：重新验证 ECS 到 `ghcr.io:443` 的 DNS 和 TCP 连通性，并检查安全组与网络 ACL。

