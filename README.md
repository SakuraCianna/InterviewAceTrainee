# 面霸练习生

> 面向求职、研究生复试、考公和 IELTS Speaking 的 AI 语音面试训练平台

生产站点: <https://sakuracianna.icu/>

[![CI](https://github.com/SakuraCianna/InterviewAceTrainee/actions/workflows/ci.yml/badge.svg)](https://github.com/SakuraCianna/InterviewAceTrainee/actions/workflows/ci.yml)
[![Prepare or Deploy Production](https://github.com/SakuraCianna/InterviewAceTrainee/actions/workflows/deploy-production.yml/badge.svg)](https://github.com/SakuraCianna/InterviewAceTrainee/actions/workflows/deploy-production.yml)

## 功能概览

- 四类面试场景：工作面试（三阶段套餐）、研究生复试、考公结构化面试、雅思口语
- 实时语音识别（腾讯云 ASR WebSocket）+ 边说边转写
- AI 问题播报（腾讯云 TTS 流式推送）
- 每轮 AI 评分与逐轮反馈（DeepSeek）
- 完整复盘报告：总分、维度评分、亮点、改进建议、推荐练习
- 断线后可恢复未完成训练；AI 任务支持手动重试
- 工作面试三阶段套餐：技术一面 → 技术二面 → HR 终面
- 管理后台：用户管理、积分调整、退款工单、AI 调用日志、任务监控

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

## 核心 API 接口

### 认证

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/auth/register` | 注册新用户 |
| `POST` | `/api/auth/login` | 密码登录 |
| `POST` | `/api/auth/email-code/request` | 发送邮箱验证码 |
| `POST` | `/api/auth/email-code/login` | 验证码登录 |
| `POST` | `/api/auth/logout` | 退出登录 |
| `GET` | `/api/auth/me` | 当前用户信息（积分、体验券）|
| `POST` | `/api/auth/password/change` | 凭验证码修改密码 |

### 面试素材

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/interview-materials` | 上传并解析面试素材（简历、院校信息）|
| `GET` | `/api/interview-materials?type={type}` | 获取当前用户最新的指定类型素材；不存在时返回 204 |

### 单场面试会话

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/interviews` | 创建面试会话（需幂等键）|
| `GET` | `/api/interviews/active` | 查询当前未结束会话；无则 204 |
| `GET` | `/api/interviews/history` | 最近 50 条历史记录 |
| `GET` | `/api/interviews/{sessionId}` | 查询指定会话快照（含 `expires_at`）|
| `DELETE` | `/api/interviews/{sessionId}` | 删除/终止指定会话 |
| `POST` | `/api/interviews/{sessionId}/answers` | 提交本轮回答，返回 202 + AI 任务 |

### 工作面试三阶段套餐

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/interview-packages` | 创建套餐并启动技术一面（消耗 3 积分或体验券）|
| `GET` | `/api/interview-packages/active` | 查询当前活跃套餐；无则 204 |
| `GET` | `/api/interview-packages/{packageId}` | 查询指定套餐及各阶段状态 |
| `POST` | `/api/interview-packages/{packageId}/stages/{stageCode}/start` | 显式启动下一阶段 |

套餐阶段流程：`TECHNICAL_FIRST` → `TECHNICAL_SECOND` → `HR_FINAL`，每个阶段对应独立的 `sessions`，有效期 30 天。

### AI 异步任务

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/tasks/{taskId}` | 轮询任务状态 |
| `POST` | `/api/tasks/{taskId}/retry` | 手动重试可重试失败任务 |

### 语音服务

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/speech/tts` | 一次性语音合成（返回 base64 音频）|
| `POST` | `/api/speech/tts/stream` | 流式语音合成（SSE，边合成边推送）|
| `WS` | `/api/ws/speech/asr/{sessionId}` | 实时语音识别 WebSocket |

## 运行配置

生产配置由服务器上的 `shared/secrets` ConfigTree 文件与非敏感发布变量提供，仓库不保存真实值。基础 Secret 文件由 `deploy/prepare-server-secrets.sh` 在缺失时创建，外部凭据由运维人员单独写入；脚本和工作流都不会回传内容。Secret 父目录使用 0700，容器运行文件使用只读 0444。

| Secret 文件 | 用途 |
| --- | --- |
| `postgres_owner_password`, `postgres_api_password`, `postgres_worker_password` | 数据库所有者、API 与隔离 Worker 账号 |
| `redis_app_password` | Redis ACL 应用账号 |
| `rabbitmq_api_password`, `rabbitmq_worker_password` | RabbitMQ 发布/声明账号与只消费 Worker 账号 |
| `jwt_secret` | JWT 签名密钥 |
| `material-parser-token` | API 调用内网材料解析进程的随机认证令牌 |
| `hcaptcha-site-key` | 前端通过公开配置接口读取的 hCaptcha 站点标识 |
| `hcaptcha-secret` | API 调用 hCaptcha Siteverify 的服务端密钥 |
| `deepseek_api_key` | Worker 的 DeepSeek 模型调用 |
| `resend_api_key`, `mail_from` | 验证码邮件交付 |
| `tencent_app_id`, `tencent_secret_id`, `tencent_secret_key` | API 实时 ASR 与 TTS |

根目录 `.env.example` 与 `deploy/compose.env.example` 使用完全相同的 15 个生产 Compose 字段；`deploy/validate-compose-env.sh` 以 UTF-8 严格解析服务器配置文件，不执行 `source`、不展开命令语法。

### hCaptcha 人机验证

- `GET /api/auth/hcaptcha/config` 只返回是否启用与公开站点标识；前端镜像不编译任何实际站点值。
- 未登录用户请求邮箱验证码、密码登录和管理员登录时必须提交 hCaptcha token；已登录用户在账号设置中请求验证码不重复挑战。
- API 在账号查询、密码校验、验证码发送或登录事务前调用官方 Siteverify，并同时校验 token、客户端 IP 与本站点标识。

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
- 页面隐藏或离线时暂停轮询，重新可见或恢复联网后立即同步
- `WS /api/ws/speech/asr/{sessionId}` 只桥接实时语音转写；断线时已有部分转写可安全收尾
- Nginx 对 API 响应设置 `no-store`，任务轮询另有独立限流
- Worker 使用单消费者、prefetch 1、手动 ACK；数据库提交成功后才 ACK
- 工作面试、研究生复试、考公和 IELTS 使用独立阶段与评分维度；IELTS 的首题、追问、阶段名、反馈和最终报告全部强制英文

## 材料配额与数据留存

API 对每个用户执行以下材料配额和内容擦除规则：

- 24 小时内最多新建 10 份材料，30 天内最多新建 30 份材料
- 同时最多保留 20 份 `ready` 材料
- 材料创建 30 天后擦除简历、岗位、院校等内容并标记删除；标记删除满 7 天且无会话或任务引用时物理删除记录
- 文件解析不在 API JVM 内执行：独立 Parser 仅接入内部网络，使用随机 Bearer token、单并发、Xmx 96 MiB 和 256 MiB 容器上限；单次解析超过 8 秒会强制终止 Parser，由容器自动拉起
- 面试会话有效期 24 小时；`completed`、`cancelled` 或 `deleted` 会话结束满 90 天后擦除问答、报告和 AI 任务输入输出
- 工作面试套餐有效期 30 天；过期后内容按相同留存策略擦除

## 前端架构

### 关键 hooks

| Hook | 位置 | 职责 |
| --- | --- | --- |
| `useMaterialUpload` | `pages/interview/hooks/useMaterialUpload.ts` | 素材上传状态、幂等重传、挂载时恢复历史素材 |
| `useMicrophoneCheck` | `pages/interview/hooks/useMicrophoneCheck.ts` | 麦克风枚举、检测、音量分析、设备切换 |
| `useAudioRecorder` | `pages/interview/hooks/useAudioRecorder.ts` | 实时 ASR 录音、静默超时、自动提交 |
| `useInterviewTask` | `pages/interview/hooks/useInterviewTask.ts` | AI 任务轮询、重试、失败回调 |

### 关键页面和组件

| 路径 | 说明 |
| --- | --- |
| `pages/interview/InterviewRoom.tsx` | 主训练房间（场景选择→设备检测→面试→报告）|
| `pages/interview/components/InterviewReport.tsx` | 复盘报告（支持中文/英文双模式）|
| `pages/interview/components/TaskProgress.tsx` | AI 任务进度卡片（轮询+重试）|
| `pages/interview/components/RecordingControls.tsx` | 录音控制按钮组 |
| `pages/admin/AdminShell.tsx` | 管理后台主壳（ERP 风格多 Tab）|

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

## CI 与生产交付

### CI

`.github/workflows/ci.yml` 在以下场景运行:

- push 到 `sakuracianna`
- Pull Request 到 `main`
- 手动触发

门禁包括 Java 21 Maven test/package、hCaptcha 验证器契约、前端 `npm ci`/audit/test/typecheck/build、无 `.env` 文件的 Compose config 校验、使用临时自签证书的真实 `nginx -t`、部署脚本语法检查，以及 Java/前端 Docker 镜像构建和完整拓扑 readiness。

### 预构建生产交付

`deploy-production.yml` 提供受控的无仓库策略:

1. 操作者输入 `sakuracianna` 上完整 40 位 commit SHA
2. 工作流验证该 commit 属于 `origin/sakuracianna`，以 detached HEAD 锁定同一 commit
3. 重跑 Java 与前端门禁，在 GitHub runner 构建两个镜像
4. 将六个运行镜像保存为带 SHA 的本地标签 tar，生成 SHA-256，并保留 7 天 artifact
5. `prepare` 只生成候选包；`deploy` 需要 `production` Environment 审批后才传输
6. 服务器在唯一 staging 内校验包与脚本 SHA-256，只创建不存在的 `releases/<SHA>`
7. 候选 release 先通过深度 readiness 和 HTTPS 边缘探测，然后才原子切换 `current`；失败会恢复 `previous`
8. 生产只执行 `docker load`；`docker compose up --no-build --pull never` 不现场构建

启用自动传输前必须由仓库管理员配置以下待配置项:

| GitHub 配置 | 用途 |
| --- | --- |
| Environment `production` 审批人 | 人工发布门禁 |
| `PRODUCTION_HOST` | 经确认的生产主机 |
| `PRODUCTION_USER` | 最小权限部署用户 |
| `PRODUCTION_PORT` | SSH 端口, 未设置时使用 22 |
| `PRODUCTION_SSH_KEY` | 部署私钥 |
| `PRODUCTION_KNOWN_HOSTS` | 固定主机指纹 |
| `PRODUCTION_PATH` | 服务器上经核验的发布根目录 |

详见 [生产重建 runbook](docs/operations/production-rebuild-runbook.md)。

## 数据重建与备份原则

- 今后所有迁移、升级和重建默认必须先备份并验证恢复路径
- 发布流程本身不删除卷、不删除备份、不执行数据库迁移清理
- 正式扩大用户范围前必须配置 PostgreSQL 加密异机备份、恢复演练和告警

## 支付与退款边界

当前不接入支付网关，也不实现自动退款。支付咨询与退款继续通过微信人工联系处理，系统内 `refund` 只记录客服纠纷工单，不是财务账本；次数补偿必须通过独立、可审计的余额流水完成。

## 安全边界

- 不提交 `.env`、密钥、Token、Cookie、私钥、证书内容或生产连接串
- RabbitMQ 管理端、PostgreSQL 与 Redis 在生产 Compose 中不发布宿主机端口
- 简历、JD、回答、音频元数据和模型输出都视为不可信输入
- 生产错误只返回稳定错误码、可理解消息与 request ID，不泄露 SQL、栈、Provider 响应或内部 URL
- 高风险匿名认证入口在分级限流后执行 hCaptcha 服务端验证

注释约定见 [代码注释规范](docs/development/commenting-standard.md)。

## 许可证

当前仓库未添加开源许可证文件。除非仓库所有者另行声明，默认保留所有权利。
