# DataForge 生产部署与持续更新手册

本文档适用于单台 Linux 服务器，使用 Docker Compose 运行 DataForge 与 PostgreSQL。代码固定放在 `/opt/dataforge/app`，数据库、生成文件、备份和密钥全部位于 Git 仓库之外，因此后续 `git pull` 不会覆盖生产数据。

> [!WARNING]
> 当前 8080 提供的是明文 HTTP，账号密码会以未加密连接传输。必须用云安全组或服务器防火墙把 8080 限制为可信来源 IP。应用会编译并运行用户提交的 C++，目前没有任务级 gVisor 沙箱，只允许受控用户。PostgreSQL 5432 不得开放到公网。

## 1. 目录与数据归属

| 路径 | 用途 | 能否被 Git 更新覆盖 |
|---|---|---|
| `/opt/dataforge/app` | Git 仓库和 Docker 构建文件 | 会更新 |
| `/etc/dataforge/dataforge.env` | 生产密码、密钥和路径 | 不会 |
| `/srv/dataforge/postgres` | PostgreSQL 数据文件 | 不会 |
| `/srv/dataforge/runtime` | 生成目录和 ZIP | 不会 |
| `/srv/dataforge/backups` | 数据库与 ZIP 备份 | 不会 |
| `/srv/dataforge/migration` | 首次 H2 迁移输入 | 不会 |
| `/srv/dataforge/deployed_commit` | 当前成功部署的 Git 提交 | 不会 |

用户、任务、文章和 AI 配置保存在 PostgreSQL；抢答题库 JSON 与卡片图片随 Git 发布；Session、打字房间和抢答房间仍在内存中，应用重启后会失效。

## 2. 服务器准备

推荐至少 4 核 CPU、8 GB 内存和 50 GB 可用磁盘。安装 Git、Docker Engine、Docker Compose v2 和 OpenSSL。Docker 应按[官方 Linux 安装文档](https://docs.docker.com/engine/install/)安装。

```bash
git --version
docker version
docker compose version
openssl version
```

创建专用账号和目录：

```bash
sudo useradd --system --uid 10001 --user-group --create-home --home-dir /opt/dataforge --shell /bin/bash dataforge
sudo usermod -aG docker dataforge

sudo install -d -o dataforge -g dataforge /opt/dataforge
sudo install -d -o dataforge -g dataforge /srv/dataforge
sudo install -d -o dataforge -g dataforge /srv/dataforge/backups
sudo install -d -o dataforge -g dataforge /srv/dataforge/migration
sudo install -d -o dataforge -g dataforge /srv/dataforge/runtime
sudo install -d /srv/dataforge/postgres
sudo install -d -o dataforge -g dataforge /etc/dataforge
```

宿主机 `dataforge` 账号和容器应用都使用 UID/GID `10001`，这使备份恢复后的 runtime 仍可写。如果服务器上 `10001` 已被占用，不要直接改用其他 UID，应先同步调整 Dockerfile 和宿主机目录所有者。

重新登录使 `docker` 组生效，然后拉取代码：

```bash
sudo -iu dataforge
git clone https://github.com/你的组织/你的仓库.git /opt/dataforge/app
cd /opt/dataforge/app
```

## 3. 创建生产配置

复制模板，权限必须是 `600`：

```bash
install -m 600 deploy/prod.env.example /etc/dataforge/dataforge.env
```

生成随机值。每个命令单独执行并把输出填入配置，不要直接把命令写入配置文件：

```bash
openssl rand -hex 32   # POSTGRES_PASSWORD
openssl rand -hex 24   # ADMIN_PASSWORD
openssl rand -hex 12   # INVITE_CODE
openssl rand -hex 32   # DATAFORGE_SECRET
```

编辑配置：

```bash
nano /etc/dataforge/dataforge.env
chmod 600 /etc/dataforge/dataforge.env
```

必须替换全部 `CHANGE_ME`。`DATAFORGE_SECRET` 上线后必须保持稳定；更换它会导致数据库中的 AI Key 无法解密。`ADMIN_PASSWORD` 仅在管理员账号不存在时用于初始化，已有管理员要通过 `reset-admin-password` 修改。

验证 Compose 配置。该命令只展开配置，不启动容器：

```bash
docker compose \
  --env-file /etc/dataforge/dataforge.env \
  -f compose.prod.yml config >/dev/null
```

## 4. 首次从 H2 全量迁移

先停止旧服务，确认没有 Java 进程占用 H2。只复制 `dataforge.mv.db`，不要复制 `dataforge.lock.db`。

在旧机器执行：

```bash
scp data/dataforge.mv.db dataforge@生产服务器:/srv/dataforge/migration/dataforge.mv.db
rsync -av --delete runtime/ dataforge@生产服务器:/srv/dataforge/migration/runtime/
```

如果旧环境曾设置 `DATAFORGE_SECRET`，把旧值填入 `LEGACY_DATAFORGE_SECRET`；如果从未设置，旧默认值为：

```text
change-this-secret-before-production
```

回到生产服务器执行：

```bash
cd /opt/dataforge/app
./scripts/prod.sh import-h2
```

导入器会完成以下检查：

1. Flyway 创建空 PostgreSQL 结构。
2. 确认目标业务表为空，按单事务导入用户、任务、AI 配置、打字文章和种子状态。
3. 保留 BCrypt 密码哈希与主键，重置 PostgreSQL 用户序列。
4. 使用旧密钥解密 AI Key，并使用新 `DATAFORGE_SECRET` 重新加密。
5. 把历史 ZIP 复制到生产 runtime，并把数据库绝对路径改为相对文件名。
6. 对每张表计算行数和逻辑 SHA-256；校验通过后才提交事务。

如果任务记录声明存在 ZIP、但迁移目录中找不到对应文件，导入会中止。优先找回文件；确实不需要历史下载时，可临时设置 `LEGACY_ALLOW_MISSING_ARTIFACTS=true`，对应任务将不再显示可下载文件。

相同 H2 文件再次执行时，导入器会根据源文件 SHA-256 安全退出，不会重复插入。

## 5. 首次验收

```bash
./scripts/prod.sh status
curl --fail http://127.0.0.1:8080/actuator/health
./scripts/prod.sh logs
```

验收以下场景：

- 使用原账号登录，管理员后台能看到用户、文章和任务。
- 新建普通账号、修改打字文章。
- 创建一项小规模生成任务并下载 ZIP。
- 历史存在 ZIP 的已完成任务仍可下载。
- 服务器安全组仅允许指定来源访问 8080，5432 没有监听公网端口。

## 6. 日常命令

所有命令在 `/opt/dataforge/app` 下使用 `dataforge` 账号运行：

```bash
./scripts/prod.sh status
./scripts/prod.sh logs
./scripts/prod.sh backup
./scripts/prod.sh reset-admin-password admin
```

备份包含：

- PostgreSQL custom-format dump；
- runtime 文件归档，忽略尚未完成的 `.partial` 文件；
- Git 提交、镜像标签、Flyway 版本；
- `SHA256SUMS` 完整性清单。

安装每日 03:00 自动备份：

```bash
exit
sudo cp /opt/dataforge/app/deploy/dataforge-backup.service /etc/systemd/system/
sudo cp /opt/dataforge/app/deploy/dataforge-backup.timer /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now dataforge-backup.timer
sudo systemctl list-timers dataforge-backup.timer
```

自动清理仅处理 `/srv/dataforge/backups` 下名称以日期开头且超过 14 天的目录。每月至少在测试服务器执行一次恢复演练。禁止把运行中的 `/srv/dataforge/postgres` 当作备份直接复制。

## 7. 从 GitHub 更新生产环境

更新前确认没有正在进行的生成任务、打字比赛或抢答课堂；更新会让所有 Session 和实时房间失效。

```bash
sudo -iu dataforge
cd /opt/dataforge/app
git status --short
./scripts/prod.sh deploy
```

`deploy` 会按顺序：

1. 拒绝有本地改动的工作区，记录当前成功提交。
2. 使用 `git pull --ff-only` 拉取 GitHub 更新。
3. 以新提交号构建镜像；构建失败时旧服务继续运行。
4. 停止应用，创建 `*-predeploy` 数据库和 runtime 备份。
5. 启动新镜像；Flyway 在 JPA 启动前应用新迁移并校验历史校验和。
6. 等待 `/actuator/health` 返回 `UP`，成功后写入 `deployed_commit`。
7. 健康检查失败时自动尝试恢复旧镜像。

生产配置和数据都在仓库外，因此 `git pull` 不会覆盖它们。以后新增数据库字段时，必须新建 `V2__...sql`、`V3__...sql` 等迁移；已在生产执行的 SQL 文件绝对不能修改或删除。

## 8. 恢复与回滚

列出备份：

```bash
ls -1 /srv/dataforge/backups
```

恢复会停止应用、校验 SHA-256、恢复 PostgreSQL 和 runtime、切换到备份记录的 Git 提交并重新构建：

```bash
./scripts/prod.sh restore 20260721T030000Z --confirm
```

恢复前的 runtime 不会删除，而会移动为：

```text
/srv/dataforge/runtime.before-restore-时间戳
```

恢复后仓库处于 detached HEAD，这是为了精确运行备份对应版本。确认问题处理完成后，返回主分支再更新：

```bash
git switch main
git pull --ff-only
./scripts/prod.sh deploy
```

Flyway Community 不提供自动 SQL 回滚。所有常规迁移应采用向后兼容的“先增加、后清理”方式；若旧代码无法运行在新结构上，必须恢复部署前数据库备份。

## 9. 常见故障

### 应用健康检查失败

```bash
./scripts/prod.sh logs
docker compose --env-file /etc/dataforge/dataforge.env -f compose.prod.yml ps
```

重点检查数据库密码、磁盘空间、Flyway 校验错误和 `/srv/dataforge/runtime` 权限。

### Flyway 报 checksum mismatch

说明已经执行的迁移文件被修改。不要执行 `repair` 掩盖问题；恢复该 SQL 文件原始内容，再用新的版本文件表达后续改动。

### AI Key 无法解密

确认 `/etc/dataforge/dataforge.env` 中的 `DATAFORGE_SECRET` 与首次生产导入后保持一致。不要通过修改数据库密文解决。

### 历史 ZIP 不存在

从最近备份恢复对应 `dataforge-<任务 UUID>.zip` 到 `/srv/dataforge/runtime`，所有者设为 UID/GID `10001:10001`。

### PostgreSQL 无法启动

检查磁盘、目录权限和日志：

```bash
df -h
docker compose --env-file /etc/dataforge/dataforge.env -f compose.prod.yml logs db
```

不要删除 PostgreSQL 数据目录或 lock 文件；优先从备份在独立目录恢复验证。
