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
- 本地隐私 RAG：简历/JD/院校/专业输入仅在会话创建请求内处理，公共知识向量存入 Redis 8
- 239 份版本化公共知识文档：200 份岗位文档覆盖 40 类代表岗位（含技术、销售、市场、运营、客服、人力、财务、供应链、产品与设计），另含 39 份院校层次与考研专业代表性文档
- AI 输入/输出/TTS 三道内容风控，审计只保存规则 ID、处置结果和不可逆摘要
- 管理后台：用户管理、积分调整、退款工单、AI 调用日志、任务监控

## 当前架构

当前生产基线是模块化 Java 单体加独立 AI Worker，API 与 Worker 使用同一构建产物，以运行角色区分，不在 4 核 4 GB 单机上拆成微服务。

```text
Browser
  -> Nginx
    -> Frontend (React/Vite 静态站点)
    -> API (Spring Boot, 端口 8000)
      -> Material Parser (受限内网进程)
      -> 本地 ONNX Embedding -> Redis 8 Search/JSON
      -> PostgreSQL / RabbitMQ
      -> hCaptcha Siteverify
    -> Worker (非 Web Spring 应用)
      -> RabbitMQ / PostgreSQL / Spring AI / DeepSeek
```

- API 负责鉴权、事务、业务规则、WebSocket、AI Job 和 Outbox
- Worker 负责 DeepSeek 追问/逐轮评估、整场报告、受控重试、租约恢复和调用日志
- RabbitMQ 消息只传 Job ID 与追踪元数据，不传简历、答案、音频或报告全文
- API 启动时校验版本化 Markdown 语料并增量同步 Redis；索引失败会标记降级，不阻断无 RAG 的普通面试
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
| AI | Spring AI BOM 2.0.0, DeepSeek 官方 Starter, 本地 Transformers ONNX Embedding |
| 前端 | React 19, Vite 7, TypeScript |
| 数据库 | PostgreSQL 18 + pgvector 0.8.5, Flyway 11.19.0 |
| 向量与缓存 | Redis 8.8.0 Search/JSON + HNSW/COSINE |
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

### 个性化会话创建

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/interviews/personalized` | 在一个 multipart 请求内解析个性化输入、检索公共知识并创建会话；原始输入不持久化 |
| `POST` | `/api/interview-packages/personalized` | 在一个 multipart 请求内创建岗位三阶段套餐；原始简历/JD 不持久化 |

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
| `POST` | `/api/interview-packages/personalized` | 携带简历与岗位信息创建套餐并启动技术一面（消耗 3 积分或体验券）|
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
| `content_safety_hmac_secret` | API 与 Worker 共用的风控审计 HMAC 密钥，不用于身份认证 |
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

## 隐私 RAG 与数据留存

API 对个性化输入和业务内容执行以下边界：

- 简历、JD、目标院校、专业和研究方向只存在于 `/personalized` 请求的受控内存对象中，不写 PostgreSQL、Redis、RabbitMQ、日志或 AI Job
- 独立 Parser 仅接入内部网络，使用随机 Bearer token、单并发、Xmx 96 MiB 和 256 MiB 容器上限；API 取得裁剪文本后立即清零上传字节和字符数组
- Redis 只保存仓库内公开 Markdown 文档的向量、公开元数据与语料 manifest；PostgreSQL 只保存索引状态、版本、数量和错误码
- ONNX 模型固定到提交 `e8f8c211226b894fcb81acc59f3b34ba3efd5f42`，Docker 构建校验模型与 tokenizer 的 SHA-256
- 面试会话有效期 24 小时；`completed`、`cancelled` 或 `deleted` 会话结束满 90 天后擦除问答、报告和 AI 任务输入输出
- 工作面试套餐有效期 30 天；过期后内容按相同留存策略擦除

## 前端架构

### 关键 hooks

| Hook | 位置 | 职责 |
| --- | --- | --- |
| `usePersonalizationInput` | `pages/interview/hooks/usePersonalizationInput.ts` | 页面内暂存个性化输入、组装单次创建请求并在成功后释放 |
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
| API | 1024 MiB | 1280 MiB | 1.25 | Xmx 256 MiB, 本地 ONNX, Hikari 6, Tomcat 80 |
| Worker | 640 MiB | 896 MiB | 0.75 | Xmx 352 MiB, Hikari 4, consumer/LLM 1 |
| Material Parser | 256 MiB | 384 MiB | 0.35 | 独立进程 Xmx 96 MiB, 单并发, 无数据库与公网 |
| PostgreSQL | 960 MiB | 1152 MiB | 1.10 | shared_buffers 256 MB, max connections 40 |
| Redis | 320 MiB | 384 MiB | 0.25 | Search/JSON, maxmemory 192 MB, noeviction, AOF |
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

常规发布由受控工作流执行 Flyway；只有按生产重建 runbook 审批的一次性迁移才使用 `docker compose run --pull never`，不得把迁移服务改为常驻进程。

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
- 高风险模型输出在写库与 TTS 前阻断；越过录用/录取决策边界的文本替换为中性训练反馈
- `content_safety` 不保存输入输出原文、命中词或摘录，只保存 HMAC-SHA-256 摘要与规则元数据
- 生产错误只返回稳定错误码、可理解消息与 request ID，不泄露 SQL、栈、Provider 响应或内部 URL
- 高风险匿名认证入口在分级限流后执行 hCaptcha 服务端验证

注释约定见 [代码注释规范](docs/development/commenting-standard.md)。

## 公共知识来源与边界

- 岗位分类与能力表述参考人社系统职业分类、深圳市 2025 人力资源市场工资价位，以及美国 BLS 2024–2034 Occupational Outlook Handbook；文档覆盖技术岗和销售、市场、运营、客服、人力、财务、供应链、产品、设计等非技术岗位
- 考研文档参考教育部 2026 年全国硕士研究生招生工作管理规定及复试录取工作部署；院校与专业内容仅用于代表性训练，具体复试办法、科目和时间必须以目标院校当年官网为准
- Redis 8 的 Search/JSON 与 ACL 配置依据 Redis 官方 8.8 文档；本地嵌入模型为 Apache-2.0 的 `sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2`

来源：[Redis 8 配置](https://redis.io/docs/latest/operate/oss_and_stack/management/config/)、[Spring AI Redis Vector Store](https://docs.spring.io/spring-ai/reference/api/vectordbs/redis.html)、[Spring AI ONNX Embeddings](https://docs.spring.io/spring-ai/reference/api/embeddings/onnx.html)、[教育部 2026 研招规定](https://www.moe.gov.cn/srcsite/A15/moe_778/s3261/202509/t20250918_1413836.html)、[BLS Occupational Outlook Handbook](https://www.bls.gov/ooh/)。

## 许可证

当前仓库未添加开源许可证文件。除非仓库所有者另行声明，默认保留所有权利。
