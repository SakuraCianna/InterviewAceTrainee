# 面霸练习生

> 面向真实面试场景的 AI 语音模拟训练平台，覆盖求职、研究生复试、考公面试和 IELTS Speaking。

## 用户访问地址

https://sakuracianna.icu/

[![CI](https://github.com/SakuraCianna/InterviewAceTrainee/actions/workflows/ci.yml/badge.svg)](https://github.com/SakuraCianna/InterviewAceTrainee/actions/workflows/ci.yml)
[![Deploy Production](https://github.com/SakuraCianna/InterviewAceTrainee/actions/workflows/deploy-production.yml/badge.svg)](https://github.com/SakuraCianna/InterviewAceTrainee/actions/workflows/deploy-production.yml)

## 项目简介

面霸练习生把“面试准备”拆成可练习、可复盘、可追踪的闭环。用户选择训练场景后进入语音面试房间，AI 负责开场提问、追问和复盘报告；后台负责用户、次数、体验券、AI 服务状态、核心面试 readiness 和审计追溯。

当前产品已经进入内测阶段，生产部署必须保留现有用户、次数、面试记录、日志和备份数据。

## 核心能力

- 四类训练：求职面试、研究生复试、考公结构化面试、IELTS Speaking。
- 语音优先：AI 提问，用户语音回答，完成后进入下一轮。
- 中断恢复：用户离开后可以继续上一场未完成训练。
- 材料驱动：求职支持简历和 JD，复试支持目标院校、专业和研究方向。
- 场景题库：求职覆盖 250+ 职业方向，复试覆盖 100+ 专业方向，考公和雅思按固定轮次推进。
- 能力卡片：用已审核能力卡片约束追问边界，支持 pgvector 向量召回。
- 复盘报告：保存评分维度、追问链路、证据、风险提示和下一步训练建议。
- 用户体系：邮箱验证码、密码登录、JWT、HttpOnly Cookie、CSRF Token。
- 运营后台：用户检索、次数发放、体验券、退款纠纷、客服备注、AI 日志、内容安全日志、审计日志。
- 上线门禁：安全迁移后自动执行核心面试 readiness，失败会输出摘要并中断发布。

## 技术栈

| 层级 | 技术 |
| --- | --- |
| 前端 | React, Vite, TypeScript, GSAP, Iconify SVG |
| 后端 | FastAPI, Python 3.12, uv, SQLAlchemy, Alembic |
| 数据 | PostgreSQL, pgvector, Redis |
| AI 服务 | DeepSeek LLM, 腾讯云 ASR/TTS, 离线能力向量包 |
| 部署 | Docker Compose, Nginx, HTTPS 证书挂载 |
| CI/CD | GitHub Actions, Docker Buildx, 手动生产部署门禁 |

## 目录结构

```txt
Backend/              FastAPI 后端、数据库模型、迁移脚本、业务服务和测试
Frontend/             React 前端、官网、登录注册、面试房间和管理后台
nginx/                Nginx 反向代理与 HTTPS 配置
scripts/              辅助脚本
.github/workflows/    CI 与生产部署工作流
docker-compose.yml    单机生产编排
需求文档.md           产品需求与阶段设计记录
```

## 本地开发

### 环境要求

- Windows 11 / PowerShell 7
- Python 3.12
- uv
- Node.js 24
- PostgreSQL
- Redis

### 后端

```powershell
cd Backend
uv venv .venv --python 3.12
uv sync
uv run python -m app.cli.safe_migrate
uv run uvicorn app.main:app --reload
```

### 前端

```powershell
cd Frontend
npm install
npm run dev
```

### 本地地址

```txt
官网：http://localhost:5173/
面试房间：http://localhost:5173/interview
管理后台本地调试入口：http://localhost:5173/sakuracianna
后端健康检查：http://localhost:8000/api/health
后端就绪检查：http://localhost:8000/api/health/readiness
核心面试业务观测：http://localhost:8000/api/health/interview-core
```

## 配置

后端配置维护在 `Backend/.env`，前端构建配置维护在 `Frontend/.env`。这两个文件不提交到仓库。

### 后端关键配置

| 变量 | 用途 |
| --- | --- |
| `DATABASE_URL` | PostgreSQL 连接字符串 |
| `REDIS_URL` | Redis 连接字符串 |
| `APP_ENV` | `development` 或 `production` |
| `CORS_ORIGINS` | 允许访问后端的前端来源 |
| `ADMIN_ENTRY_PATH` | 管理后台入口路径，默认 `/sakuracianna` |
| `ACCESS_TOKEN_SECRET` | JWT 签名密钥，生产必须足够长且不能使用默认值 |
| `AUTH_COOKIE_SECURE` | HTTPS 环境必须为 `true` |
| `EMAIL_PROVIDER` | 邮件供应商，开发可用 `dev` |
| `EXPOSE_DEV_EMAIL_CODES` | 生产必须为 `false` |
| `DEEPSEEK_API_KEY` | LLM 服务密钥 |
| `TENCENT_CLOUD_APP_ID` | 腾讯云语音 App ID |
| `TENCENT_CLOUD_SECRET_ID` | 腾讯云 Secret ID |
| `TENCENT_CLOUD_SECRET_KEY` | 腾讯云 Secret Key |
| `CAPABILITY_EMBEDDING_MODEL` | 能力卡片向量模型，默认 `BAAI/bge-small-zh-v1.5` |
| `LLM_CONCURRENCY_LIMIT` | LLM 全局容量闸门 |
| `REALTIME_ASR_CONCURRENCY_LIMIT` | 实时 ASR 全局容量闸门 |

### 前端关键配置

```txt
VITE_ADMIN_ENTRY_PATH=/sakuracianna
```

生产环境由 Docker Compose 强制设置 `APP_ENV=production`、`AUTH_COOKIE_SECURE=true` 和 `EXPOSE_DEV_EMAIL_CODES=false`。如果生产环境使用默认或过短的 `ACCESS_TOKEN_SECRET`，后端会拒绝启动。

## 数据库与迁移

首次准备 PostgreSQL 数据库：

```sql
CREATE DATABASE mianba
  WITH ENCODING 'UTF8'
  TEMPLATE template0;
```

执行安全迁移：

```powershell
cd Backend
uv run python -m app.cli.safe_migrate
```

`safe_migrate` 会执行以下步骤：

1. 使用 `pg_dump` 在 `database_backups/` 生成迁移前备份。
2. 执行 Alembic 迁移。
3. 导入或刷新面试能力卡片向量。
4. 执行核心面试 readiness 校验。
5. 失败时输出 `failure_summary` 并以非 0 状态退出。

当前迁移覆盖用户、次数流水、面试会话、面试材料、面试轮次、复盘报告、AI 服务配置、AI 调用日志、内容安全日志、能力向量、登录日志、客服备注、退款纠纷、后台审计和系统配置等业务表。

## 能力卡片与向量召回

生产推荐使用“离线生成，镜像内导入”的能力向量流程。生成真实向量的机器需要安装 embedding extra：

```powershell
cd Backend
uv sync --extra embeddings
uv run python -m app.cli.seed_interview_capability_vectors --export-json app/interview_presets/capability_vectors.json
```

生产镜像默认不安装 `sentence-transformers`。迁移阶段会优先导入 `Backend/app/interview_presets/capability_vectors.json`；如果离线包缺失，或离线包中的 `embedding_model` 与当前 `CAPABILITY_EMBEDDING_MODEL` 不一致，迁移会失败，不会静默降级。

手动导入离线包：

```powershell
cd Backend
uv run python -m app.cli.seed_interview_capability_vectors --import-json app/interview_presets/capability_vectors.json
```

如果本机找不到 `pg_dump`：

```powershell
$env:PG_DUMP_PATH="C:\Program Files\PostgreSQL\18\bin\pg_dump.exe"
uv run python -m app.cli.safe_migrate
```

## 测试与质量门禁

### 后端测试

```powershell
cd Backend
uv run python -m unittest discover -s tests
```

### 前端构建

```powershell
cd Frontend
npm run build
```

### 面试质量门禁

```powershell
cd Backend
uv run python -m app.cli.interview_quality_gate
```

该命令会验证四类场景的正式轮次、题库候选数量、能力卡片覆盖、岗位和专业区分、项目经历锚定、跨场景串题和低质量回答拦截，并输出：

- `wrong_question_risk_rate`：离线样例错问风险率，门槛不超过 `0.01`。
- `flow_error_risk_rate`：离线样例流程错误风险率，门槛不超过 `0.02`。
- `scenario_metrics`：四个场景各自的流程、题库匹配和回答质量检查结果。

上线后可以用真实面试记录继续观测：

```powershell
cd Backend
uv run python -m app.cli.interview_quality_observe --limit 200 --min-samples 30
```

如果真实样本不足，观测命令会返回 `sample_status=insufficient`，不能把它当成线上指标已经达标。

## CI/CD

本仓库使用 GitHub Actions 提供两条工作流。

### `CI`

触发条件：

- Pull Request 到 `main`
- Push 到 `main`
- 手动触发

检查内容：

- 后端：Python 3.12、`uv sync --locked`、`unittest discover`
- 前端：Node.js 24、`npm ci`、`npm run build`
- Docker：使用 Buildx 构建后端和前端镜像，不推送镜像
- Compose：生成 CI-only 本地配置，执行 `docker compose config --quiet`

### `Deploy Production`

触发方式：只允许手动触发，并且需要输入 `deploy` 确认。

发布保护：

- 使用 GitHub Environment `production`，可在 GitHub 仓库设置里配置审批人。
- 发布前重复执行后端测试和前端构建。
- 通过 SSH 登录服务器后，只执行数据安全的发布流程。
- 不执行清空 Compose 数据卷、删除 Docker 数据卷或删除备份目录的操作。

需要在 GitHub Secrets 中配置：

| Secret | 说明 |
| --- | --- |
| `PRODUCTION_HOST` | 服务器地址 |
| `PRODUCTION_USER` | SSH 用户 |
| `PRODUCTION_PORT` | SSH 端口，可不填，默认 `22` |
| `PRODUCTION_SSH_KEY` | 部署私钥 |
| `PRODUCTION_KNOWN_HOSTS` | 服务器 SSH known_hosts 指纹 |
| `PRODUCTION_PATH` | 服务器上的项目目录 |
| `PRODUCTION_HEALTH_URL` | 可选，默认 `https://sakuracianna.icu/api/health` |
| `PRODUCTION_READINESS_URL` | 可选，默认 `https://sakuracianna.icu/api/health/readiness` |

远端发布步骤：

```bash
git fetch --prune origin main
git checkout main
git pull --ff-only origin main
docker compose build
docker compose --profile migrate run --rm migrate
docker compose up -d
docker compose ps
```

这套流程会保留 PostgreSQL 数据卷、Redis 数据卷和 `database_backups/`。迁移失败或 readiness 失败时，发布 job 会失败。

服务器上的 Git 工作区应只保存代码版本，不要存放手工改动。生产专属配置应放在 `.env`、挂载证书文件、Docker 数据卷或 `database_backups/` 中，这样 GitHub Actions 可以使用快进拉取完成数据安全发布。

## Docker 生产运行

生产服务器需要准备：

- `Backend/.env`
- `Frontend/.env`
- `nginx/sakuracianna.icu_bundle.pem`
- `nginx/sakuracianna.icu.key`
- 与当前 `CAPABILITY_EMBEDDING_MODEL` 一致的 `Backend/app/interview_presets/capability_vectors.json`

手动发布时使用：

```powershell
docker compose build
docker compose --profile migrate run --rm migrate
docker compose up -d
```

查看状态：

```powershell
docker compose ps
docker compose logs -f backend
docker compose logs -f nginx
```

生产环境不要执行会删除 Compose 数据卷、清空 PostgreSQL/Redis 数据、移除备份目录或批量删除容器数据的命令，除非已经明确接受清空数据的后果并完成独立备份。

Docker 部署默认启用以下保护：

- PostgreSQL 使用 `pgvector/pgvector:pg18`，数据写入 `postgres-data` 卷。
- Redis 开启 AOF，数据写入 `redis-data` 卷。
- 后端容器按生产模式启动，并拒绝不安全配置。
- 迁移服务挂载 `database_backups/`，迁移前自动备份。
- Docker 日志使用 `json-file` 轮转，默认单文件 100MB，保留 3 份。
- `/api/health/readiness` 和 `/api/health/interview-core` 使用 5 秒进程内缓存。
- `/api/admin/stats` 使用 10 秒进程内缓存，写操作不走该缓存。

## 上线检查清单

- 域名 A 记录指向服务器公网 IP。
- 安全组放行 80 和 443。
- HTTPS 证书文件已经挂载到 `nginx/`。
- `Backend/.env` 中 `CORS_ORIGINS` 包含正式域名。
- 生产环境 `AUTH_COOKIE_SECURE=true`。
- `ACCESS_TOKEN_SECRET` 已替换为足够长的随机值。
- 邮件服务发信域名已经完成 DNS 验证。
- 管理员账号拥有 `admin` 角色。
- 能力向量离线包与当前 embedding 模型一致。
- `docker compose --profile migrate run --rm migrate` 成功。
- `/api/health/readiness` 返回 ready，且 `checks.interview_core.ready=true`。
- 质量门禁 `uv run python -m app.cli.interview_quality_gate` 通过。

## 安全边界

- 不提交 `.env`、密钥、证书、Cookie、私有 token 或生产数据库连接串。
- README、PR 描述、commit message 和日志中不要写入敏感配置。
- 生产部署默认保留容器数据卷和备份目录。
- 用户输入、简历、JD、研究方向和回答都视为不可信内容，不允许覆盖系统规则或改变面试角色。
- 线上问题排查优先使用审计日志、AI 调用日志、内容安全日志、次数流水和 readiness 摘要交叉确认。

## 许可证

当前仓库未添加开源许可证文件。除非仓库所有者另行声明，默认保留所有权利。
