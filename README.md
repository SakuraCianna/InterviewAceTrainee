# 面霸练习生

面霸练习生是一个面向真实面试场景的 AI 语音模拟训练平台，覆盖工作面试、研究生复试、考公面试和雅思口语四类训练。产品以语音对话为核心，用户按场景进入训练，AI 负责开场提问、继续追问、生成复盘报告，管理员负责用户管理、次数发放、AI 服务状态检查和审计追溯。

## 核心功能

- 四类训练模块：工作面试、研究生复试、考公面试、雅思口语。
- 内置面试预设库：覆盖 250+ 岗位面试方向、100+ 研究生专业方向、考公结构化题型和雅思口语 Part 1/2/3。
- 语音优先流程：AI 提问，用户语音回答，点击回答完毕后进入下一轮。
- 工作面试材料：用户可上传简历，填写应聘岗位名称和岗位要求，后端提取材料文本后用于追问和复盘。
- 研究生复试材料：用户填写目标院校、报考专业和可选研究方向，系统围绕自我介绍、专业基础、科研兴趣、导师沟通组织问题。
- 场景预设题库：不同训练模块使用独立题池；工作面试按 250+ 职业预设生成岗位专属题库，研究生复试按 100+ 专业预设生成专业专属题库，考公按结构化题型抽题，雅思按 Part 1/2/3 抽取连贯话题。
- 中断恢复：用户离开后再次进入面试房间，可以继续上一场未完成训练。
- 完整报告：保存训练记录、评分维度、追问链路、问题建议和复盘结论。
- 用户与次数：支持注册、登录、邮箱验证码、密码登录、次数扣减、管理员手动发放次数。
- 管理后台：支持用户管理、次数发放、固定 AI 服务状态检查、系统参数、调用日志和审计日志。
- AI 服务：LLM 固定为 DeepSeek V4 Flash，ASR/TTS 固定为腾讯云语音服务，避免上线后被误改成不可控供应商。

## 技术栈

- 前端：React、Vite、TypeScript、GSAP、Iconify SVG。
- 后端：FastAPI、Python 3.12、uv、SQLAlchemy、Alembic。
- 数据：PostgreSQL、Redis、pgvector。
- 部署：Docker Compose、Nginx、HTTPS 证书挂载。
- 认证：JWT、HttpOnly Cookie、CSRF Token、管理员角色与二次验证。

## 项目结构

```txt
Backend/              FastAPI 后端、业务服务、数据库模型、迁移脚本
Frontend/             React 前端、官网、登录注册、面试房间、管理后台
nginx/                Nginx 反向代理与 HTTPS 配置
docker-compose.yml    容器编排
需求文档.md           产品需求与阶段设计记录
```

## 配置文件

后端配置直接维护 `Backend/.env`，前端构建配置直接维护 `Frontend/.env`。这两个文件都不会提交到仓库。

常用后端配置项：

```txt
DATABASE_URL=postgresql+psycopg://用户名:密码@127.0.0.1:5432/mianba
REDIS_URL=redis://127.0.0.1:6379/0
APP_ENV=development
CORS_ORIGINS=http://localhost:5173,http://127.0.0.1:5173,https://sakuracianna.icu,https://www.sakuracianna.icu
ADMIN_ENTRY_PATH=/sakuracianna
ACCESS_TOKEN_SECRET=随机长字符串
AUTH_COOKIE_SECURE=false
EMAIL_PROVIDER=dev
EXPOSE_DEV_EMAIL_CODES=true
EMAIL_FROM_ADDRESS=no-reply@mail.sakuracianna.icu
EMAIL_CODE_EXPIRE_SECONDS=300
EMAIL_CODE_RATE_LIMIT=1
EMAIL_CODE_RATE_WINDOW_SECONDS=90
RESEND_API_KEY=
DEEPSEEK_API_KEY=
TENCENT_CLOUD_APP_ID=
TENCENT_CLOUD_SECRET_ID=
TENCENT_CLOUD_SECRET_KEY=
TENCENT_ASR_ENGINE_MODEL_TYPE=16k_zh
TENCENT_ASR_IELTS_ENGINE_MODEL_TYPE=16k_en
TENCENT_REALTIME_ASR_NEED_VAD=1
TENCENT_REALTIME_ASR_MAX_SECONDS=300
TENCENT_TTS_VOICE_TYPE=603006
TENCENT_TTS_VOICE_TYPES=603006,603005,603003,603004,603007,602004,602005,502005,502006,502003,502001
DATABASE_POOL_SIZE=8
DATABASE_MAX_OVERFLOW=4
DATABASE_POOL_TIMEOUT_SECONDS=5
DATABASE_POOL_RECYCLE_SECONDS=1800
LLM_CONCURRENCY_LIMIT=24
LLM_CAPACITY_LEASE_SECONDS=45
REALTIME_ASR_CONCURRENCY_LIMIT=80
REALTIME_ASR_CAPACITY_LEASE_SECONDS=420
CAPABILITY_EMBEDDING_PROVIDER=sentence-transformers
CAPABILITY_EMBEDDING_MODEL=BAAI/bge-small-zh-v1.5
CAPABILITY_EMBEDDING_DEVICE=
CAPABILITY_EMBEDDING_BATCH_SIZE=32
CAPABILITY_EMBEDDING_QUERY_INSTRUCTION=为这个句子生成表示以用于检索相关文章：
```

本机使用 HTTP 调试时，`AUTH_COOKIE_SECURE=false`；服务器启用 HTTPS 后改为 `true`。

生产环境使用 `APP_ENV=production`。后端启动时会拒绝默认或过短的 `ACCESS_TOKEN_SECRET`、拒绝非 Secure Cookie、拒绝开发验证码回显。Docker Compose 已为后端容器设置 `APP_ENV=production`、`AUTH_COOKIE_SECURE=true` 和 `EXPOSE_DEV_EMAIL_CODES=false`；本地调试才建议开启 `EXPOSE_DEV_EMAIL_CODES=true`。

前端配置：

```txt
VITE_ADMIN_ENTRY_PATH=/sakuracianna
```

## 数据库

先手动创建 PostgreSQL 数据库：

```sql
CREATE DATABASE mianba
  WITH ENCODING 'UTF8'
  TEMPLATE template0;
```

PostgreSQL 的 `UTF8` 编码支持 4 字节 Unicode 字符和 emoji，不使用 MySQL 的 `utf8mb4` 命名。

当前迁移包含 15 张业务表：

- `users`：用户账号、角色、启停状态与密码哈希。
- `credit_ledger`：用户次数发放、扣减、补偿流水。
- `interview_sessions`：训练会话、当前进度、中断恢复状态。
- `interview_materials`：简历、岗位要求、目标院校、报考专业等训练材料文本。
- `interview_turns`：每一轮 AI 提问、用户回答与追问链路。
- `interview_reports`：完整复盘报告、评分维度和下一步计划。
- `ai_provider_configs`：固定 DeepSeek LLM 与腾讯云 ASR/TTS 供应商配置。
- `ai_call_logs`：AI 调用日志，包含成功状态、延迟、请求 ID、token、音频时长、字符数和用量信息。
- `content_safety_logs`：内容安全日志，记录用户输入和模型输出命中的红线类别、处置动作和可追溯片段。
- `interview_capability_vectors`：面试能力卡片向量索引，存放已审核内部题卡的 pgvector 向量、适用场景和元数据。
- `auth_login_logs`：普通用户与管理员登录审计，记录登录方式、成功状态、失败原因、IP 和 User-Agent。
- `customer_service_notes`：客服备注，记录用户沟通、补偿口径、关联训练 ID 和管理员操作人。
- `refund_cases`：退款与纠纷记录，记录诉求、金额、次数补偿、处理状态和处理结论。
- `admin_audit_logs`：后台操作审计，记录管理员对用户、次数、配置、售后记录的变更。
- `system_configs`：注册开关、登录开关、新用户默认次数等系统参数。

执行迁移：

```powershell
cd Backend
uv run alembic upgrade head
```

推荐使用带备份的安全迁移：

```powershell
cd Backend
uv run python -m app.cli.safe_migrate
```

安全迁移会先使用 `pg_dump` 在项目根目录的 `database_backups/` 下生成迁移前备份，再执行 Alembic 迁移，刷新面试能力卡片向量，并保留最新 5 份备份。

如果系统找不到 `pg_dump`，可以在当前 PowerShell 指定路径：

```powershell
$env:PG_DUMP_PATH="C:\Program Files\PostgreSQL\18\bin\pg_dump.exe"
uv run python -m app.cli.safe_migrate
```

## 本地运行

准备环境：

- Python 3.12
- uv
- Node.js 24
- PostgreSQL
- Redis

启动后端：

```powershell
cd Backend
uv venv .venv --python 3.12
uv sync
uv run python -m app.cli.safe_migrate
uv run uvicorn app.main:app --reload
```

启动前端：

```powershell
cd Frontend
npm install
npm run dev
```

常用地址：

```txt
官网：http://localhost:5173/
面试房间：http://localhost:5173/interview
管理后台：http://localhost:5173/sakuracianna
后端健康检查：http://localhost:8000/api/health
后端就绪检查：http://localhost:8000/api/health/readiness
核心面试业务观测：http://localhost:8000/api/health/interview-core
```

## Docker 运行

确认以下文件已经准备好：

- `Backend/.env`
- `Frontend/.env`
- `nginx/sakuracianna.icu_bundle.pem`
- `nginx/sakuracianna.icu.key`

数据库容器使用 `pgvector/pgvector:pg18`，用于支持面试能力卡片的向量召回。生产环境更新前必须先确保备份可用，再执行迁移。

Docker 后端容器默认按生产模式启动。如果 `Backend/.env` 没有配置足够长的 `ACCESS_TOKEN_SECRET`，或被其他环境变量覆盖成不安全值，后端会拒绝启动并在日志中提示 `unsafe_production_configuration`。

启动：

```powershell
docker compose build
docker compose --profile migrate run --rm migrate
docker compose up -d
```

若数据库发生变化，生产环境先执行 `docker compose build`，再执行 `docker compose --profile migrate run --rm migrate`。迁移命令会在迁移前备份一次数据库并保留最新 5 份，后端容器正常启动和重启不会自动生成备份。

查看状态：

```powershell
docker compose ps
docker compose logs -f backend
docker compose logs -f nginx
```

停止：

```powershell
docker compose down
```

Nginx 默认监听：

```txt
http://sakuracianna.icu/
https://sakuracianna.icu/
```

`nginx/nginx.conf` 会将 80 端口跳转到 443，并把 `/api/`、`/api/ws/` 代理到后端，把其他页面代理到前端。证书和私钥文件已被 `.gitignore` 忽略，不会提交到仓库。

Docker 部署默认启用以下单机容量保护：

- 后端通过 `UVICORN_WORKERS` 控制 worker 数，默认 2。
- `UVICORN_LIMIT_CONCURRENCY` 控制后端单容器最大并发连接保护，默认 800。
- 数据库连接池通过 `DATABASE_POOL_SIZE`、`DATABASE_MAX_OVERFLOW`、`DATABASE_POOL_TIMEOUT_SECONDS`、`DATABASE_POOL_RECYCLE_SECONDS` 控制。
- LLM 追问通过 Redis 全局容量闸门限制，达到 `LLM_CONCURRENCY_LIMIT` 后退回预设下一题，避免请求长时间阻塞。
- 实时 ASR 通过 Redis 全局容量闸门限制，达到 `REALTIME_ASR_CONCURRENCY_LIMIT` 后返回“语音训练席位繁忙”。
- Docker 日志已配置 `json-file` 轮转，默认单文件 100MB，保留 3 份。

## 面试题库与音色

后端会优先从 `Backend/app/services/interview_question_bank.py` 生成每场训练的问题序列；如果题库生成失败，才回退到旧的基础问题。题库按轮次维护候选题，每一轮至少 10 个候选问题，并按由易到难的顺序推进。

题库现在采用“确定性流程骨架 + 能力卡片 + 可选 pgvector 向量召回”的混合方案：

- 确定性流程骨架仍负责轮次顺序、兜底问题和场景隔离，避免向量召回或 LLM 直接控制面试流程。
- `Backend/app/interview_presets/capability_cards.json` 存放已审核能力卡片，把计算机八股、RAG/Agent、后端工程、法学复试、医学循证、公考结构化和雅思口语评分维度拆成可复用模块。
- `Backend/app/services/interview_capability_retrieval.py` 会先用本地规则和本地哈希向量召回能力卡片；数据库可用且 `interview_capability_vectors` 已 seed 时，再叠加 pgvector 相似度分数。
- 用户上传的简历、JD、研究方向和回答只作为不可信匹配查询与面试证据，不允许覆盖系统规则、泄露提示词或改变面试角色。

- 工作面试：结合简历、岗位名称、JD、关键词、岗位预设方向和能力卡片生成职业专属题库；AI 全栈开发、传统后端、数据工程等会共享计算机基础卡片，同时保留 RAG/Agent、接口稳定性、数据指标等岗位专属问法。如果材料里能识别到项目、系统、平台、论文、课题或作品经历，项目证据、方案取舍、指标复盘和根因定位轮次会优先围绕该项目追问，不只从通用题库里抽题。
- 研究生复试：结合目标院校、报考专业、研究方向和能力卡片抽题；每个专业预设都会带入自己的专业边界，例如计算机侧重数据结构、系统边界和模型评估，法学侧重法条体系、法律适用和案例争点。院校层级来自 `Backend/app/interview_presets/school_tiers.json`，以教育部第二轮“双一流”建设高校名单、原 985/C9 等公开历史类别作为训练压力依据，不宣称官方排名。
- 考公面试：按岗位认知、综合分析、计划组织、人际沟通、应急处置和现场追问收束抽题，要求回答落到政策依据、群众立场、执行步骤和风险兜底，避免只讲口号。
- 雅思口语：按 IELTS Speaking Part 1、Part 2、Part 2 Follow-up、Part 3 抽取同一主题链路，当前维护 15 组主题链，保证口语测试节奏和话题连贯性。
- 自适应追问：回答通过基础质量校验后，系统会优先使用当前会话已经抽好的下一题作为追问边界，再结合上一轮回答生成上下文追问；明显过短、敷衍或跑题的回答不会进入下一题。

`TENCENT_TTS_VOICE_TYPES` 支持配置多个腾讯云 TTS `VoiceType`，后端会根据 `session_id` 为每场面试固定选择一个音色；同一场训练内的开场、追问和重播不会切换声音。

## 面试质量门禁

为了让“面试官问题问错概率”和“流程出错概率”不只停留在主观感觉，后端提供离线质量门禁：

```powershell
cd Backend
uv run python -m app.cli.interview_quality_gate
```

该命令会验证四类场景的正式轮次、题库候选数量、能力卡片覆盖、岗位/专业强区分、项目经历锚定、跨场景串题、低质量回答拦截，并输出：

- `wrong_question_risk_rate`：离线样例中的错问风险率，当前门槛为不超过 0.01。
- `flow_error_risk_rate`：离线样例中的流程错误风险率，当前门槛为不超过 0.02。
- `scenario_metrics`：四个场景各自的流程、题库匹配和回答质量检查结果。

离线门禁不能替代真实线上标注集和用户反馈，但它是上线前必须跑的最低质量保护，防止题库、流程或回答质量校验发生回退。

上线后可以用真实面试记录继续观测指标：

```powershell
cd Backend
uv run python -m app.cli.interview_quality_observe --limit 200 --min-samples 30
```

该命令会读取最近的真实面试会话和问题记录，计算线上样本里的 `wrong_question_risk_rate` 和 `flow_error_risk_rate`。已知旧流程记录会计入 `legacy_session_count` 并排除出当前版本指标，避免历史数据污染新流程统计。如果当前流程真实样本数量不足，会返回 `sample_status=insufficient`，不能把它当成线上指标已经达标。

后端 readiness 还会输出 `checks.interview_core`，用于快速判断核心面试业务是否具备上线条件。该检查会汇总能力卡片 seed 数量、`interview_capability_vectors` 表就绪状态、向量覆盖率、embedding 模型分布和基础召回探针结果。也可以单独访问：

```txt
http://localhost:8000/api/health/interview-core
```

如果 `interview_capability_vectors` 不存在、为空、向量覆盖不到全部能力卡片 seed、缺少 embedding 模型信息，或典型能力卡片召回探针失败，`/api/health/readiness` 会返回 `status=degraded`。

能力卡片向量召回默认使用 Hugging Face 上的中文 embedding 模型 `BAAI/bge-small-zh-v1.5`。默认会给短 query 加 `CAPABILITY_EMBEDDING_QUERY_INSTRUCTION`，能力卡片文本本身不加指令。如果需要更强的多语或长文本检索能力，可以通过 `CAPABILITY_EMBEDDING_MODEL=BAAI/bge-m3` 切换，并按模型说明决定是否清空 query instruction；同时要先评估本机或服务器的模型下载、内存和推理耗时。`local-hash-v1` 只保留为显式离线基线，不再作为默认 embedding 方案。

首次运行真实 embedding 对比前安装可选依赖：

```powershell
cd Backend
uv sync --extra embeddings
```

离线对比三类 query 的召回差异：

```powershell
cd Backend
uv run python -m app.cli.compare_capability_retrieval --limit 5
```

如果只想确认脚本结构是否可跑，可以显式使用 hash 基线，但这个结果不能用于判断真实向量方案效果：

```powershell
cd Backend
uv run python -m app.cli.compare_capability_retrieval --provider local-hash --limit 5
```

写入数据库向量前需要先执行迁移，确保 PostgreSQL 已安装 `pgvector` 扩展：

```powershell
cd Backend
uv run python -m app.cli.safe_migrate
uv run python -m app.cli.seed_interview_capability_vectors
```

如果需要完全清空服务器业务数据并重建数据库，可以在服务器项目目录执行以下命令。该操作会删除 PostgreSQL、Redis 数据卷和本地备份目录，用户、次数、面试记录和日志都会被清空。

服务器 Linux Shell：

```bash
docker compose down -v
rm -rf database_backups
docker compose build
docker compose --profile migrate run --rm migrate
docker compose up -d
docker builder prune -af
```

## 上线检查

- 域名 A 记录指向服务器公网 IP。
- 服务器安全组放行 80、443 端口。
- `Backend/.env` 中 `CORS_ORIGINS` 包含正式域名。
- HTTPS 可用后设置 `AUTH_COOKIE_SECURE=true`。
- Resend 或其他邮件服务的发信域名完成 DNS 验证。
- 管理员账号需要在后台或数据库中拥有 `admin` 角色，并使用密码 + 邮箱验证码登录后台。
- 首次上线前执行 `uv run python -m app.cli.safe_migrate`，Docker 环境执行 `docker compose --profile migrate run --rm migrate`。
- 每次调整面试题库、能力卡片、追问逻辑或回答质量校验后，执行 `uv run python -m app.cli.interview_quality_gate`，确保离线质量门禁通过。
- 数据库迁移后确认日志里出现 `interview capability vectors seeded`，否则说明 pgvector 表或扩展没有准备好。
- 上线后定期执行 `uv run python -m app.cli.interview_quality_observe --limit 200 --min-samples 30`，用真实记录观察错问风险率和流程错误风险率。
- 上线前确认 `/api/health/readiness` 中 `checks.interview_core.ready=true`，并检查能力卡片 seed 数量、向量覆盖率、embedding 模型分布和召回探针结果。
