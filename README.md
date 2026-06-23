# DataForge 算法数据工坊

一个面向 C++ 算法题出题者的测试数据生成 MVP。用户粘贴 Markdown 题面、C++ 标准程序与自然语言数据要求，确认生成方案后，系统会编译生成器和标准程序，生成 `.in/.out` 并提供 ZIP 下载。

## 已实现

- 邀请码注册、Session 登录与 BCrypt 密码哈希
- 管理员后台：用户启停、AI 接口配置和全站任务记录
- H2 文件数据库持久化用户、任务、题面、方案和 ZIP 路径
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

## 启动

需要 Java 11+、Maven 和可从命令行调用的 `g++`。

```powershell
mvn spring-boot:run
```

然后访问 <http://localhost:8080>。

首次启动会创建默认管理员：

```text
用户名：admin
密码：admin123456
```

请在正式使用前通过 `ADMIN_USERNAME`、`ADMIN_PASSWORD` 和 `DATAFORGE_SECRET` 环境变量修改默认值。管理员入口登录后显示在页面右上角。

普通用户注册需要邀请码，默认邀请码为 `443322`，可通过 `INVITE_CODE` 环境变量修改。

用户、任务和 AI 配置保存在 `data/`；生成源码和 ZIP 保存在 `runtime/`。只要这两个目录未被删除，服务重启后仍能查看记录并重复下载已完成的数据包。

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

- 当前执行器使用本机受限时间进程，尚未接入 Docker/gVisor 沙箱，不能开放给不可信公网用户。
- ZIP 由本地磁盘提供；生产环境应改为 MinIO、OSS、COS 或 S3 的签名直链。
- AI 返回的生成器仍可能存在语义问题，后续应增加独立 Validator 和小数据对拍。

## 下一步建议

1. PostgreSQL 持久化任务与用户数据。
2. Redis/BullMQ 或 RabbitMQ 拆分任务 Worker。
3. Docker/gVisor 沙箱以及 CPU、内存、磁盘和网络隔离。
4. MinIO/S3 分片上传与过期清理。
5. 登录、额度、后台 AI 配置和任务管理。
