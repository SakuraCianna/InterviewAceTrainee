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
- 数据：PostgreSQL、Redis。
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
CORS_ORIGINS=http://localhost:5173,http://127.0.0.1:5173,https://sakuracianna.icu,https://www.sakuracianna.icu
ADMIN_ENTRY_PATH=/sakuracianna
ACCESS_TOKEN_SECRET=随机长字符串
AUTH_COOKIE_SECURE=false
EMAIL_PROVIDER=dev
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
```

本机使用 HTTP 调试时，`AUTH_COOKIE_SECURE=false`；服务器启用 HTTPS 后改为 `true`。

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

当前迁移包含 14 张业务表：

- `users`：用户账号、角色、启停状态与密码哈希。
- `credit_ledger`：用户次数发放、扣减、补偿流水。
- `interview_sessions`：训练会话、当前进度、中断恢复状态。
- `interview_materials`：简历、岗位要求、目标院校、报考专业等训练材料文本。
- `interview_turns`：每一轮 AI 提问、用户回答与追问链路。
- `interview_reports`：完整复盘报告、评分维度和下一步计划。
- `ai_provider_configs`：固定 DeepSeek LLM 与腾讯云 ASR/TTS 供应商配置。
- `ai_call_logs`：AI 调用日志，包含成功状态、延迟、请求 ID、token、音频时长、字符数和用量信息。
- `content_safety_logs`：内容安全日志，记录用户输入和模型输出命中的红线类别、处置动作和可追溯片段。
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

安全迁移会先使用 `pg_dump` 在项目根目录的 `database_backups/` 下生成迁移前备份，再执行 Alembic 迁移，并保留最新 5 份备份。

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
```

## Docker 运行

确认以下文件已经准备好：

- `Backend/.env`
- `Frontend/.env`
- `nginx/sakuracianna.icu_bundle.pem`
- `nginx/sakuracianna.icu.key`

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

- 工作面试：结合简历、岗位名称、JD、关键词和岗位预设方向生成职业专属题库；如果材料里能识别到项目、系统、平台、论文、课题或作品经历，项目证据、方案取舍、指标复盘和根因定位轮次会优先围绕该项目追问，不只从通用题库里抽题。
- 研究生复试：结合目标院校、报考专业和研究方向抽题；每个专业预设都会带入自己的专业边界，例如计算机侧重数据结构、系统边界和模型评估，法学侧重法条体系、法律适用和案例争点。院校层级来自 `Backend/app/interview_presets/school_tiers.json`，以教育部第二轮“双一流”建设高校名单、原 985/C9 等公开历史类别作为训练压力依据，不宣称官方排名。
- 考公面试：按综合分析、组织协调、应急应变、人际沟通、岗位匹配分别抽题，避免和复试、雅思共用泛化问题。
- 雅思口语：按 IELTS Speaking Part 1、Part 2、Part 3 抽取同一主题链路，当前维护 10 组主题链，保证口语测试节奏。
- 自适应追问：回答通过基础质量校验后，系统会优先使用当前会话已经抽好的下一题作为追问边界，再结合上一轮回答生成上下文追问；明显过短、敷衍或跑题的回答不会进入下一题。

`TENCENT_TTS_VOICE_TYPES` 支持配置多个腾讯云 TTS `VoiceType`，后端会根据 `session_id` 为每场面试固定选择一个音色；同一场训练内的开场、追问和重播不会切换声音。

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
