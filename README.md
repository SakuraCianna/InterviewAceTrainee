# 面霸练习生

> 面向求职、研究生复试、考公和 IELTS Speaking 的 AI 语音面试训练平台

生产站点: <https://sakuracianna.icu/>

[![CI](https://github.com/SakuraCianna/InterviewAceTrainee/actions/workflows/ci.yml/badge.svg)](https://github.com/SakuraCianna/InterviewAceTrainee/actions/workflows/ci.yml)
[![Prepare or Deploy Production](https://github.com/SakuraCianna/InterviewAceTrainee/actions/workflows/deploy-production.yml/badge.svg)](https://github.com/SakuraCianna/InterviewAceTrainee/actions/workflows/deploy-production.yml)

## 当前架构

当前生产基线是模块化 Java 单体加独立 AI Worker，API 与 Worker 使用同一构建产物，以运行角色区分，不在 4 核 4 GB 单机上拆成微服务。

```text
Browser
  -> Nginx
    -> Frontend (React/Vite 静态站点)
    -> API (Spring Boot, 端口 8000)
      -> Material Parser (受限内网进程)
      -> PostgreSQL / Redis / RabbitMQ
      -> hCaptcha Siteverify
    -> Worker (非 Web Spring 应用)
      -> RabbitMQ / PostgreSQL / Spring AI / DeepSeek
```

- API 负责鉴权、事务、业务规则、WebSocket、AI Job 和 Outbox
- Worker 负责 DeepSeek 追问/逐轮评估、整场报告、受控重试、租约恢复和调用日志
- RabbitMQ 消息只传 Job ID 与追踪元数据，不传简历、答案、音频或报告全文
- DLQ 只承接畸形消息和超过投递上限的基础设施毒消息；业务失败以 PostgreSQL 任务状态为准
- 实时 ASR 由浏览器、API WebSocket 与腾讯云 ASR 直连链路处理，不进入普通任务队列；未启动、持续静默和绝对墙钟均有硬超时并立即释放全局容量

### AI Worker 位置

AI Worker 不是另一个独立仓库，而是 `Backend` 同一 Spring Boot JAR 的独立无 Web 进程。生产 Compose 通过 `MIANBA_RUNTIME_ROLE=worker` 启动 `worker` 服务，API 进程只创建任务和 Outbox 事件，Worker 再从 RabbitMQ 消费并调用模型。关键入口如下:

- `Backend/src/main/java/icu/sakuracianna/mianba/aiwork/worker/AiJobWorker.java`: 手动 ACK、幂等消费、租约续期与故障恢复
- `Backend/src/main/java/icu/sakuracianna/mianba/aiwork/worker/SpringAiInterviewGenerator.java`: Spring AI 提示词、严格结构化输出与面试类型策略
- `Backend/src/main/java/icu/sakuracianna/mianba/aiwork/messaging/OutboxPublisher.java`: API 事务提交后的可靠消息发布
- `docker-compose.yml` 的 `worker` 服务: 独立资源限制、数据库账号和 RabbitMQ 消费账号

详细约定见 [Boot 4 + Spring AI 2 架构](docs/refactor/boot4-ai2-architecture.md) 与 [AI 异步任务契约](docs/refactor/async-task-contract.md)。

## 技术基线

| 层级 | 固定版本或技术 |
| --- | --- |
| Java | Java 21 LTS |
| 应用框架 | Spring Boot 4.1.0 |
| AI | Spring AI BOM 2.0.0, DeepSeek 官方 Starter |
| 前端 | React 19, Vite 7, TypeScript |
| 数据库 | `pgvector/pgvector:0.8.5-pg18-bookworm` |
| 缓存 | `redis:8.8.0-alpine` |
| 队列 | `rabbitmq:4.3.2-management` |
| 构建与边缘 | Node 24.18.0, Temurin 21.0.11, Nginx 1.29.7, Docker Compose |

Redis 镜像名是 `alpine`，不存在 `airplane` 标签。PostgreSQL 18 的默认数据目录是 `/var/lib/postgresql/18/docker`，因此 Compose 将持久卷挂到父目录 `/var/lib/postgresql`。

## 目录结构

```text
Backend/              Java 21 API 与 Worker、Flyway、测试和统一 Docker 镜像
Frontend/             React/Vite 前端
nginx/                HTTPS 反向代理、分级限流与安全头
deploy/               生产初始化/运维脚本、本地端口覆盖和非密钥变量模板
docs/operations/      生产重建、验证和回滚 runbook
.github/workflows/    CI 与受控生产交付
docker-compose.yml    4 核 4 GB 单机生产编排
```

## Windows 本地开发

### 环境要求

- Windows 11
- PowerShell 7
- Java 21
- Node.js 24

本项目约定本机不执行 Docker 构建、启动、停止或数据卷操作。本地只进行源码开发和 Java/Node 检查；Compose 渲染、镜像构建及服务容器集成验证由 GitHub CI 完成，生产容器只在服务器通过受控发布工作流操作。

### Java 与前端检查

在 PowerShell 7 中运行:

```powershell
Set-Location .\Backend
.\mvnw.cmd test
.\mvnw.cmd -DskipTests package

Set-Location ..\Frontend
npm ci
npm test -- --run
npm run typecheck
npm run build
```

前端开发服务器:

```powershell
Set-Location .\Frontend
npm ci
npm run dev
```

## 运行配置

生产配置由服务器上的 `shared/secrets` ConfigTree 文件与非敏感发布变量提供，仓库不保存真实值。基础 Secret 文件由 `deploy/prepare-server-secrets.sh` 在缺失时创建，外部凭据由运维人员单独写入；脚本和工作流都不会回传内容。Secret 父目录使用 0700，容器运行文件使用只读 0444：普通 Compose 的文件型 Secret 不重映射 UID，0444 让不同非 root 容器可读，而宿主机其他用户仍无法穿越 0700 父目录。

| Secret 文件 | 用途 |
| --- | --- |
| `postgres_owner_password`, `postgres_api_password`, `postgres_worker_password` | 数据库所有者、API 与隔离 Worker 账号 |
| `redis_app_password` | Redis ACL 应用账号 |
| `rabbitmq_api_password`, `rabbitmq_worker_password` | RabbitMQ 发布/声明账号与只消费 Worker 账号 |
| `jwt_secret` | JWT 签名密钥 |
| `material-parser-token` | API 调用内网材料解析进程的随机认证令牌 |
| `hcaptcha-site-key` | 前端通过公开配置接口读取的 hCaptcha 站点标识；它不是服务端凭据，但仍由运行时注入 |
| `hcaptcha-secret` | API 调用 hCaptcha Siteverify 的服务端密钥，不进入前端、镜像或日志 |
| `deepseek_api_key` | Worker 的 DeepSeek 模型调用 |
| `resend_api_key`, `mail_from` | 验证码邮件交付 |
| `tencent_app_id`, `tencent_secret_id`, `tencent_secret_key` | API 实时 ASR 与 TTS |

`rabbitmq-definitions.json` 不是人工维护的 Secret。脚本从两个 RabbitMQ 密码文件派生该运行配置，并通过同目录临时文件和原子重命名刷新；生成失败时保留旧配置。

根目录 `.env.example` 与 `deploy/compose.env.example` 使用完全相同的 19 个生产 Compose 字段，字段名称和顺序由 `scripts/check-env-schema.ps1` 自动核对。本机 `.env` 只是同结构的忽略文件，不提供 localhost 变体、不保存 Secret，也绝不会被生产发布读取。服务器唯一的非敏感配置事实来源是 `<PRODUCTION_PATH>/shared/compose.env`：它必须是非符号链接的 0600 普通文件，字段、顺序和相邻中文注释与示例完全一致，且注释只使用英文标点。`deploy/validate-compose-env.sh` 以 UTF-8 严格解析该文件，不执行 `source`、不展开命令语法，也不打印字段值。

生产脚本每次调用 Compose 都显式传入 `--env-file`，并先从子进程环境移除全部 19 个同名字段，防止 SSH、运维 Shell 或工作流环境污染覆盖服务器文件。其中六个 commit 专属镜像标签和 `secret`、证书、稳定 runtime-config 三个派生目录由发布脚本重新注入；CORS、API/Worker 连接池、数据库等待时间、Tomcat 线程/队列和 Worker 消费并发等其余 10 项只来自服务器 `shared/compose.env`。Compose 对全部 19 项使用缺失即失败的插值。一次性 `migrate` 角色使用数据库所有者执行 Flyway；API 与 Worker 分别使用 `mianba_api`、`mianba_worker` 且关闭 Flyway，数据库与 RabbitMQ 权限互相隔离。生产 Cookie 强制 Secure。迁移期间保留的 `Backend/.env` 与 `Frontend/.env` 只作为旧 Python/前端部署配置备份，不参与新 Spring Boot Compose 启动。

管理员固定入口与备案展示定义在 `Frontend/src/config/productConfig.ts`，随前端版本审计发布，不属于服务器容量配置，也不接受 `VITE_*` 构建环境临时覆盖。

### hCaptcha 人机验证

- `GET /api/auth/hcaptcha/config` 只返回是否启用与公开站点标识；前端镜像不编译任何实际站点值。
- 未登录用户请求邮箱验证码、密码登录和管理员登录时必须提交一次 hCaptcha token；已登录用户在账号设置中请求邮箱验证码不重复挑战。
- API 在账号查询、密码校验、验证码发送或登录事务前调用官方 Siteverify，并同时校验 token、客户端 IP 与本站点标识。验证服务超时、畸形响应、重复/过期 token 或配置缺失均失败关闭。
- Nginx 内容安全策略只放行 hCaptcha 官方脚本、Frame、样式与连接域名；服务端密钥永不返回浏览器。

## AI 异步任务

需要 AI 的写操作返回 HTTP 202，并设置 `Location: /api/tasks/{taskId}` 与短 `Retry-After`。客户端必须发送 `Idempotency-Key`，重复提交应返回同一任务。

任务状态机:

```text
QUEUED -> RUNNING -> SUCCEEDED
  |          |
  |          -> RETRYING -> QUEUED
  |          -> FAILED
  -> CANCELLED
```

- `GET /api/interviews/active` 与会话快照中的 `active_task` 用于页面刷新恢复；`GET /api/tasks/{taskId}` 是异步状态的事实来源
- `POST /api/tasks/{taskId}/retry` 只允许可重试的失败任务，并要求 CSRF 与新的幂等键
- 页面隐藏或离线时暂停轮询，重新可见或恢复联网后立即同步；写请求响应丢失时只读取会话权威状态，不重放答案正文或创建新的计费操作
- `WS /api/ws/speech/asr/{sessionId}` 只桥接实时语音转写；断线时已有部分转写可安全收尾，没有文本则要求重新回答，不把不连续 PCM 自动拼成答案
- Nginx 对 API 响应设置 `no-store`，任务轮询另有独立限流，202 与轮询结果不得被中间缓存
- Worker 使用单消费者、prefetch 1、手动 ACK；数据库提交成功后才 ACK
- API 将经过裁剪的材料摘要、岗位要求或院校/专业信息作为不可变 `input_ref` 快照，Worker 无需读取材料表；提示词把快照、历史问题、当前问题和回答统一放入不可闭合的不可信数据区
- 工作面试、研究生复试、考公和 IELTS 使用独立阶段与评分维度；IELTS 的首题、追问、阶段名、反馈和最终报告全部强制英文
- 模型只能返回 `score`、`feedback`、`roundName`、`nextQuestion` 四字段 JSON；服务端拒绝重复键、未知字段、错误类型、尾随内容和超长输出，并用可信阶段表与去重兜底题归一化结果
- 每轮评分与反馈持久化到轮次，最终报告的总分是所有已评轮次的四舍五入平均值，并逐轮给出证据与改进建议

RabbitMQ 拓扑使用 `mianba.ai.v1`、`mianba.ai.jobs.v1` 和 `mianba.ai.dlq.v1`。延迟重试由 PostgreSQL Outbox 的 `available_at` 调度，不维护额外 retry queue。主队列满时使用 `reject-publish` 让 Outbox 退避重试，API 还会对账长时间 `QUEUED` 的任务。消息持久化、publisher confirm、Outbox 与幂等收件箱共同保证至少一次投递下不重复扣次数或生成重复报告。

PostgreSQL 镜像包含 pgvector，但当前发布没有 embedding 生成与 RAG 检索流水线；管理员能力健康页会明确返回 `rag_not_implemented`，不会把零向量/零召回探针显示为就绪。现阶段材料个性化只使用上述受控快照。

## 材料配额与数据留存

API 对每个用户执行以下材料配额和内容擦除规则：

- 24 小时内最多新建 10 份材料，30 天内最多新建 30 份材料
- 同时最多保留 20 份 `ready` 材料
- 材料创建 30 天后擦除简历、岗位、院校等内容并标记删除；标记删除满 7 天且无会话或任务引用时物理删除记录
- 文件解析不在 API JVM 内执行：独立 Parser 仅接入内部网络，使用随机 Bearer token、单并发、Xmx 96 MiB 和 256 MiB 容器上限；单次解析超过 8 秒会强制终止 Parser，由容器自动拉起，异常文件不会重启 API
- 用户删除面试后，会话先进入 `deleting`；任何阶段中断时，每小时运行的留存任务会继续完成任务、轮次、报告和会话内容擦除
- `completed`、`cancelled` 或 `deleted` 会话结束满 90 天后擦除问答、报告和 AI 任务输入输出，但保留不含训练正文的会话状态用于一致性与审计

## 首次管理员

全新数据库不内置默认管理员、固定密码或公开 Bootstrap API。先通过正式邮箱流程注册并设置密码，再由批准的服务器运维身份使用 runtime 包内的 `deploy/promote-first-admin.sh` 提升首个管理员。脚本从 0600 文件读取邮箱；用户不存在、被停用、没有密码或数据库已有管理员时会拒绝，并把提升动作写入 `admin_audit`。完整步骤见生产重建 runbook；成功验证后应删除一次性邮箱文件。

## 4 核 4 GB 生产预算

| 服务 | 内存上限 | 内存 + Swap 上限 | CPU | 关键限制 |
| --- | ---: | ---: | ---: | --- |
| API | 768 MiB | 1024 MiB | 1.25 | Xmx 448 MiB, Hikari 6, Tomcat 80 |
| Worker | 640 MiB | 896 MiB | 0.75 | Xmx 352 MiB, Hikari 4, consumer/LLM 1 |
| Material Parser | 256 MiB | 384 MiB | 0.35 | 独立进程 Xmx 96 MiB, 单并发, 无数据库与公网 |
| PostgreSQL | 960 MiB | 1152 MiB | 1.10 | shared_buffers 256 MB, max connections 40 |
| Redis | 256 MiB | 320 MiB | 0.25 | maxmemory 160 MB, noeviction, AOF |
| RabbitMQ | 384 MiB | 512 MiB | 0.40 | watermark 0.55, management 端口不发布 |
| Nginx + Frontend | 128 MiB | 192 MiB | 0.20 | 静态强缓存, API 分级限流 |

所有服务还配置了 PID 限制、日志轮转、停止宽限期和只读根文件系统。PostgreSQL、Redis、RabbitMQ 的数据目录使用持久卷；JVM 临时目录与 Nginx/RabbitMQ 运行目录使用受限 tmpfs。
稳定容器的物理内存硬上限合计 3392 MiB，内存与 Swap 合计上限为 4480 MiB。当前生产机实际约有 3.6 GiB 物理内存和 1.9 GiB Swap；极端情况下物理硬上限只给宿主机留下约 0.3 GiB，但这些数值不是预留量，正常常驻 RSS 应显著低于上限。Swap 只吸收短时峰值，不能作为提高 JVM 堆或长期超配的依据。一次性迁移容器在 API、Parser 与 Worker 启动前退出。

## CI 与生产交付

### CI

`.github/workflows/ci.yml` 在以下场景运行:

- push 到 `sakuracianna`
- Pull Request 到 `main`
- 手动触发

门禁包括 Java 21 Maven test/package、hCaptcha 验证器契约、前端 `npm ci`/audit/test/typecheck/build、无 `.env` 文件的 Compose config 校验、使用临时自签证书的真实 `nginx -t`、部署脚本语法检查，以及 Java/前端 Docker 镜像构建和完整拓扑 readiness。CI 只使用随机占位 Secret 并关闭真实 hCaptcha 外呼；Java 镜像在官方 Maven + Temurin 21 builder 中构建，不依赖 Windows-only `mvnw.cmd`。任一步失败都不应进入发布。

### 预构建生产交付

当前仓库没有已确认可用的镜像仓库配置，因此不虚构 registry push/pull 流程。`deploy-production.yml` 提供受控的无仓库策略:

1. 操作者输入 `sakuracianna` 上完整 40 位 commit SHA
2. 工作流验证该 commit 属于 `origin/sakuracianna`，以 detached HEAD 锁定同一 commit
3. 重跑 Java 与前端门禁，在 GitHub runner 构建两个镜像
4. 将 Java、前端、PostgreSQL、Redis、RabbitMQ、Nginx 六个运行镜像保存为带 SHA 的本地标签 tar，生成 SHA-256，并保留 7 天 artifact；第三方内容在 runner 侧按固定 digest 拉取，服务器不依赖现场 registry
5. `prepare` 只生成候选包；`deploy` 需要 `production` Environment 审批后才传输
6. 生成 runtime allowlist 包，仅含 Compose、Nginx 和部署脚本；构建阶段拒绝任何 test/spec 文件
7. 服务器在唯一 staging 内校验包与脚本 SHA-256，只创建不存在的 `releases/<SHA>`，不覆盖同 SHA、不创建 Git 仓库、不接收源码或测试
8. 候选 release 先通过 Compose/Nginx 配置、API/Worker 深度 readiness 和 HTTPS 边缘探测，然后才原子切换 `current`；失败会恢复 `previous`
9. 生产只执行 `docker load` 与 `docker compose ... --no-build --pull never`，逐个确认六个 commit 专属本地镜像存在，不现场运行 Maven、npm、测试、Docker build 或隐式 registry pull；精确清理本次 staging、最多保留 5 个 release，不执行全局 prune

生产工作流还会通过 GitHub Actions API 要求同一 commit 已在 `sakuracianna` 完整 CI 中成功，历史 SHA 不能绕过 Compose 拓扑和业务烟测直接发布。
生产观察命令统一通过 runtime 包内的 `deploy/production-compose.sh` 解析 `current`、固定 project 名和六个镜像标签；该入口不开放 `up`、`down`、`pull`、`rm` 等状态变更，避免手工环境变量漂移。

启用自动传输前必须由仓库管理员配置并验证以下待配置项:

| GitHub 配置 | 用途 |
| --- | --- |
| Environment `production` 审批人 | 人工发布门禁 |
| `PRODUCTION_HOST` | 经确认的生产主机 |
| `PRODUCTION_USER` | 最小权限部署用户 |
| `PRODUCTION_PORT` | SSH 端口, 未设置时使用 22 |
| `PRODUCTION_SSH_KEY` | 部署私钥 |
| `PRODUCTION_KNOWN_HOSTS` | 固定主机指纹 |
| `PRODUCTION_PATH` | 服务器上经核验的发布根目录, 例如 `/srv/mianba` |
| `PRODUCTION_HEALTH_URL` | 可选健康 URL |
| `PRODUCTION_READINESS_URL` | 可选就绪 URL |

这些配置未就绪时只能运行 `prepare` 并通过获批准的离线渠道交付 artifact，不能声称已经自动部署。发布、生产重建、逐卷清理、健康验证和回滚步骤见 [生产重建 runbook](docs/operations/production-rebuild-runbook.md)。

## 数据重建与备份原则

本次从旧 Python schema 切换到 Java/Flyway 新 schema，项目所有者已明确授权重建生产业务数据，不执行 Alembic 原地升级。这项授权只适用于本次重构，不应被解释为永久允许丢弃数据。

- 本次重建已获明确授权删除旧项目容器、项目网络、项目镜像与业务数据；操作前仍必须核验主机、Compose project、每个容器/网络/卷/镜像及其挂载关系
- 服务器上的 `.env`、密钥目录和证书目录必须保留；只逐项删除已确认属于旧项目的 Docker 对象，不执行无目标的 `docker system prune` 或批量卷清理
- 今后所有迁移、升级和重建默认必须先备份并验证恢复路径，除非项目所有者再次明确书面授权
- 发布流程本身不删除卷、不删除备份、不执行数据库迁移清理
- 正式扩大用户范围前必须配置 PostgreSQL 加密异机备份、恢复演练、磁盘/内存/队列/5xx 告警及证书到期告警；未配置备份目的地时不能把本机卷称为备份

## 支付与退款边界

当前不接入支付网关，也不实现自动退款。支付咨询与退款继续通过微信人工联系处理，系统内 `refund` 只记录客服纠纷工单，不是财务账本；次数补偿必须通过独立、可审计的余额流水完成。

## 安全边界

- 不提交 `.env`、密钥、Token、Cookie、私钥、证书内容或生产连接串
- RabbitMQ 管理端、PostgreSQL 与 Redis 在生产 Compose 中不发布宿主机端口
- 简历、JD、回答、音频元数据和模型输出都视为不可信输入
- 开放面试会话 24 小时后自动取消；材料、面试内容和运行记录按“材料配额与数据留存”中的期限分批擦除或清理
- 生产错误只返回稳定错误码、可理解消息与 request ID，不泄露 SQL、栈、Provider 响应或内部 URL
- Nginx 保留正式域名与证书路径，限制连接、速率、请求体和超时，并对静态指纹资源使用长期缓存
- 高风险匿名认证入口在分级限流后执行 hCaptcha 服务端验证；验证异常时不继续账号或邮件业务
- 服务器发布目录不包含 `src/test`、前端测试、旧 Python tests、源码构建缓存或 Git 元数据

注释约定见 [代码注释规范](docs/development/commenting-standard.md)。公开类型应说明职责，事务、锁、幂等、ACK 顺序和安全失败策略必须说明原因。

## 许可证

当前仓库未添加开源许可证文件。除非仓库所有者另行声明，默认保留所有权利。
