# DataForge 算法数据工坊

一个面向 C++ 算法题出题者的测试数据生成 MVP。用户粘贴 Markdown 题面、C++ 标准程序与自然语言数据要求，确认生成方案后，系统会编译生成器和标准程序，生成 `.in/.out` 并提供 ZIP 下载。

## 已实现

- 邀请码注册、Session 登录与 BCrypt 密码哈希
- 管理员后台：用户启停、AI 接口配置和全站任务记录
- 开发环境使用 H2，生产环境使用 PostgreSQL + Flyway 持久化用户、任务、文章和 AI 配置
- 用户只能查看和下载自己的任务，管理员可查看全站记录
- 单页填写、方案确认、实时进度与 ZIP 下载流程
- AI 分析前真实编译出题者的标准程序，并在页面展示编译错误
- AI 方案返回后预编译数据生成器，避免确认后才发现语法错误
- OpenAI 兼容的 AI 接口，可生成数据规划和 C++ 数据生成器
- 未配置 AI 时自动使用本地演示生成器
- 异步任务执行，页面关闭后任务继续
- C++14 / C++17 / C++20 编译
- 每组数据使用可复现随机种子
- 标准程序超时、退出码、输入大小检查
- ZIP 中包含题面、标准程序、生成器、测试数据和 `manifest.json`
- CSP Paper Studio V0.4：登录后可逐题检查 CSP 初赛 Markdown、实时编辑并导出 Word
- CSP 复赛模拟：长期学生名单、Linux / Windows 文件桌面、规范检查、计时收卷、教师评测与成绩发布；支持测试点与子任务计分
- 可达 OJ 作业跟踪：班级与周作业、编辑与复制、AC/补交及每题统计、学生总览、未完成清单、更新变化与 Excel 导出；支持零点自动更新、增量查询和失败重试，加密保存登录凭据。详见 [使用说明](docs/KEDAOJ_HOMEWORK.md)
- 实用工具首页直接显示工具列表，不再展示教师工作台；原有班级、作业、考试与学习配置数据均保留。
- 配置可达 OJ SID 后，后台每半小时尝试身份心跳；与作业自动更新开关独立，失效后提示重新录入，不能保证阻止上游强制过期。
- 每周六 19:00（北京时间）从官方赛程自动添加并切换本周末 ABC；赛前无需榜单或 Cookie，开赛后同步排名仍需有效的 AtCoder Cookie。失败十分钟后重试，无比赛则保留当前榜单。
- 工具列表顺序可由管理员在后台调整，数据工坊从“实用工具”中展开。
- 登录默认保留 7 天，长时间闲置或浏览器 Cookie 到期后需要重新登录。会话保存在数据库中，正常重启不会清除；退出、账号停用、密码重置会撤销相应会话。
- 数据生成支持可选 C++ Validator、小数据对拍、质量报告、取消与沿用方案重试；未设置校验或未实际对拍的测试点会明确标记。

## 启动

本地开发需要 Java 17+、Maven 和可从命令行调用的 `g++`。CSP Paper Studio 还需要 Python 3、Pandoc 3.8.2.1 及其锁定依赖。

先在一个终端启动 CSP Paper Studio 内部服务：

```bash
cd services/csp-paper-studio
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements.lock.txt
.venv/bin/python -m uvicorn service_app:app --host 127.0.0.1 --port 8765
```

再在仓库根目录启动网站：

```powershell
mvn spring-boot:run
```

然后访问 <http://localhost:8080>。

登录后从“实用工具”进入 CSP Paper Studio。浏览器选择的 Markdown 只发送到当前 DataForge 服务进行解析或导出，不会发送给第三方，也不会写入数据库或长期保存在服务器上。

CSP 复赛模拟同样从“实用工具”进入。学生通过教师分享的考场链接或六位数字考场编号、姓名和学号使用，代码在本机编写后上传。真实判题需另外启用 Linux Docker Worker，详见 [使用与部署说明](docs/CSP_SIMULATOR.md)。

首次启动会创建默认管理员：

```text
用户名：admin
密码：admin123456
```

请在正式使用前通过 `ADMIN_USERNAME`、`ADMIN_PASSWORD` 和 `DATAFORGE_SECRET` 环境变量修改默认值。管理员入口登录后显示在页面右上角。

普通用户注册需要邀请码，默认邀请码为 `443322`，可通过 `INVITE_CODE` 环境变量修改。

用户、任务和 AI 配置保存在 `data/`；生成源码和 ZIP 保存在 `runtime/`。只要这两个目录未被删除，服务重启后仍能查看记录并重复下载已完成的数据包。

生产环境使用 PostgreSQL、Docker Compose、仓库外持久目录和 Flyway 数据库迁移。首次部署、H2 全量迁移、备份、GitHub 更新和回滚请严格按照 [生产部署与持续更新手册](docs/PRODUCTION_DEPLOYMENT.md) 操作。

生产服务器无法访问 Docker Hub 时，可使用 [GitHub Actions + GHCR 成品镜像部署](docs/GHCR_IMAGE_DEPLOYMENT.md)，生产机不再执行基础镜像拉取和应用构建。

首次打开会预填“数列求和”演示题，可以不配置 AI 直接体验完整流程。

## 配置 AI

可以登录管理员后台直接配置，也可以使用环境变量。服务兼容常见的 OpenAI Chat Completions 接口：

```powershell
$env:AI_BASE_URL="https://你的接口地址/v1"
$env:AI_API_KEY="你的 API Key"
$env:AI_MODEL="模型名称"
mvn spring-boot:run
```

API Key 仅从服务端环境变量读取，不会发送到浏览器。

## 当前 MVP 边界

- 本地默认使用受限时间进程，适合可信的开发环境；生产环境使用独立的 Docker 隔离执行服务。浏览器或不可信程序无法访问 Docker 套接字，受限容器没有网络访问权限。参见 [生产部署说明](docs/PRODUCTION_DEPLOYMENT.md)。
- ZIP 由仓库外的宿主机持久目录提供；如果未来扩展为多实例，应改为 MinIO、OSS、COS 或 S3。
- Validator 与暴力程序由出题者提供，系统无法保证它们本身正确；小数据对拍只检查实际生成且不超过 64 KB 的输入。没有校验或对拍的测试点不会被标记为通过，边界覆盖仍需人工确认。

## 下一步建议

1. 扩大使用前进行独立安全审计，并评估是否将隔离执行服务迁到独立主机。
2. 多实例部署前验证队列租约、文件存储和并发配额；当前生产手册以单台服务器为准。
3. 按实际容量需要考虑外部对象存储、异地备份和集中监控。
