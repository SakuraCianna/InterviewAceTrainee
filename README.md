# 面霸练习生

面霸练习生是一个面向真实面试场景的 AI 语音模拟训练平台，覆盖工作面试、研究生复试、考公面试和雅思口语四类训练。产品以语音对话为核心，用户按场景进入训练，AI 负责开场提问、继续追问、生成复盘报告，管理员负责用户管理、次数发放、模型供应商配置和审计追溯。

## 核心功能

- 四类训练模块：工作面试、研究生复试、考公面试、雅思口语。
- 语音优先流程：AI 提问，用户语音回答，点击回答完毕后进入下一轮。
- 工作面试材料：用户可上传简历，填写应聘岗位名称和岗位要求，后端提取材料文本后用于追问和复盘。
- 研究生复试材料：用户填写报考专业，系统围绕自我介绍、专业基础、科研兴趣、导师沟通组织问题。
- 中断恢复：用户离开后再次进入面试房间，可以继续上一场未完成训练。
- 完整报告：保存训练记录、评分维度、追问链路、问题建议和复盘结论。
- 用户与次数：支持注册、登录、邮箱验证码、密码登录、次数扣减、管理员手动发放次数。
- 管理后台：支持用户管理、模型配置、供应商路由、系统参数、调用日志和审计日志。
- 模型路由：优先使用国内可用供应商，主模型失败时自动尝试备用模型。

## 技术栈

- 前端：React、Vite、TypeScript、GSAP、Iconify SVG。
- 后端：FastAPI、Python 3.12、uv、SQLAlchemy、Alembic。
- 数据：PostgreSQL、Redis。
- 部署：Docker Compose、Nginx、HTTPS 证书挂载。
- 认证：JWT、HttpOnly Cookie、CSRF Token、管理员邮箱白名单与二次验证。

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
ADMIN_ENTRY_PATH=/console-mianba
ADMIN_EMAIL_ALLOWLIST=你的管理员邮箱
ACCESS_TOKEN_SECRET=随机长字符串
AUTH_COOKIE_SECURE=false
EMAIL_PROVIDER=dev
EMAIL_FROM_ADDRESS=no-reply@sakuracianna.icu
RESEND_API_KEY=
DEEPSEEK_API_KEY=
```

本机使用 HTTP 调试时，`AUTH_COOKIE_SECURE=false`；服务器启用 HTTPS 后改为 `true`。

前端配置：

```txt
VITE_ADMIN_ENTRY_PATH=/console-mianba
```

## 数据库

先手动创建 PostgreSQL 数据库：

```sql
CREATE DATABASE mianba
  WITH ENCODING 'UTF8'
  TEMPLATE template0;
```

PostgreSQL 的 `UTF8` 编码支持 4 字节 Unicode 字符和 emoji，不使用 MySQL 的 `utf8mb4` 命名。

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
管理后台：http://localhost:5173/console-mianba
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
docker compose up --build -d
```

若数据库发生变化，生产环境直接执行 `docker compose up -d --build` 即可；后端容器启动时会先执行安全迁移流程。

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

## 上线检查

- 域名 A 记录指向服务器公网 IP。
- 服务器安全组放行 80、443 端口。
- `Backend/.env` 中 `CORS_ORIGINS` 包含正式域名。
- HTTPS 可用后设置 `AUTH_COOKIE_SECURE=true`。
- Resend 或其他邮件服务的发信域名完成 DNS 验证。
- 管理员邮箱加入 `ADMIN_EMAIL_ALLOWLIST`。
- 首次上线前执行 `uv run python -m app.cli.safe_migrate` 或通过 Docker 启动自动迁移。
