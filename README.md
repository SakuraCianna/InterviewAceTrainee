# 面霸练习生

面霸练习生是一个面向工作面试、研究生复试、考公面试和雅思口语的 AI 语音模拟训练平台。当前仓库已经按 `Backend` 和 `Frontend` 拆分，后端使用 FastAPI + Python 3.12 + uv，前端使用 React + Vite + GSAP + Iconify SVG。

## 目录

- `Backend`：FastAPI 后端、RESTful API、核心服务、测试
- `Frontend`：React 前端、产品官网、面试房间、管理员后台骨架，图标统一使用 Iconify SVG
- `nginx`：反向代理配置
- `docker-compose.yml`：本地或服务器部署编排
- `需求文档.md`：产品和技术需求文档

## 后端

```powershell
cd Backend
uv venv .venv --python 3.12
uv sync --dev
uv run pytest tests -v
uv run uvicorn app.main:app --reload
```

项目不再拆“开发环境 / 生产环境”两套配置，统一使用 `Backend/.env`。你在本机开发、Docker Compose 或服务器部署时，只需要按实际地址修改同一组变量。

```powershell
Copy-Item .env.example .env
```

当前仓库里已经放了一份 `Backend/.env`。默认假设 PostgreSQL 和 Redis 都部署在同一台机器上：PostgreSQL 负责用户、次数余额、训练会话、报告、模型配置、审计日志和调用日志；Redis 负责邮箱验证码、验证码限流，以及数据库不可用时的本机降级调试。因此连接：

```txt
postgresql+psycopg://postgres:postgres@127.0.0.1:5432/mianba
```

你只需要先在本机 PostgreSQL 手动创建数据库：

```sql
CREATE DATABASE mianba
  WITH ENCODING 'UTF8'
  TEMPLATE template0;
```

PostgreSQL 不使用 MySQL 的 `utf8mb4` 命名；它的 `UTF8` 编码支持 4 字节 Unicode 字符和 emoji，可以按这个显式建库。

如果你的本机 PostgreSQL 用户名或密码不是 `postgres` / `postgres`，只改 `Backend/.env` 里的 `DATABASE_URL`。

Docker Compose 内置 PostgreSQL 的宿主机端口映射为 `5433:5432`，避免和本机 PostgreSQL 的 `5432` 冲突。

数据库迁移：

```powershell
cd Backend
uv run alembic upgrade head
```

当前后端已经具备 JWT 登录、邮箱验证码注册/登录、管理员双重认证接口、按模块扣次数、管理员手动发放次数接口、次数流水、训练会话中断恢复、完整报告保存、AI 供应商配置新增/更新、AI 调用日志，以及带 JWT 校验的面试 WebSocket 通道。登录成功后会写入 `HttpOnly` Cookie，前端不再把 token 放进 `localStorage`；后台登录还会额外校验 `ADMIN_EMAIL_ALLOWLIST` 白名单。

AI 模型路由默认按“国内可用、免费/低成本优先”排序：智谱 `GLM-4.7-Flash`、`glm-z1-flash`、`glm-4-flash-250414`，再到阿里百炼 `qwen-flash`、火山方舟 `doubao-seed-1.6-flash`，最后预置 DeepSeek `deepseek-v4-flash` 作为兜底备用。面试下一轮追问会优先尝试模型路由；未配置 API Key、主模型失败或全部模型失败时，系统会自动退回内置题库继续流程，不会卡死训练。

模型 Key 统一放在 `.env`：

```txt
ZHIPU_API_KEY=
DEEPSEEK_API_KEY=
ALIYUN_BAILIAN_API_KEY=
VOLCENGINE_ARK_API_KEY=
```

注册和登录使用同一套业务闭环：先请求邮箱验证码，再注册或登录。邮箱验证码默认使用 `EMAIL_PROVIDER=dev`，页面会直接显示验证码，方便你在开发时完整体验注册/登录流程。接入 Resend 时改成：

```txt
EMAIL_PROVIDER=resend
EMAIL_FROM_ADDRESS=no-reply@你的已验证域名
RESEND_API_KEY=你的新 Resend API Key
```

例如你买的域名是 `sakuracianna.icu`，并且已经在 Resend 里验证通过，那么可以填：

```txt
EMAIL_FROM_ADDRESS=no-reply@sakuracianna.icu
```

这里的 `no-reply` 不一定要先创建成一个真实邮箱账号，它是发信地址的本地部分；真正关键是 `sakuracianna.icu` 这个域名要在 Resend 完成 DNS 验证。Resend 不是从你的 Gmail 发信，而是使用你在 Resend 验证过的发信域名发送交易邮件。测试域名 `onboarding@resend.dev` 只适合跑通自己的测试邮箱；给真实用户发验证码前，应配置自有域名的 DNS 验证记录。

健康检查：

```txt
GET http://localhost:8000/api/health
GET http://localhost:8000/api/health/readiness
```

`/api/health/readiness` 会检查数据库表、Redis、邮件模式和 JWT 密钥是否达到可运行条件。本地用 `http://localhost` 调试时，`AUTH_COOKIE_SECURE=false` 才能让浏览器带上登录 Cookie；等服务器启用 HTTPS 后，把它改成 `true`。

## 前端

```powershell
cd Frontend
npm install
npm run dev
```

默认页面：

- 官网：`http://localhost:5173/`
- 面试房间：`http://localhost:5173/interview`
- 管理后台：`http://localhost:5173/console-mianba`
- 政策页面：`/terms`、`/privacy`、`/refund`、`/contact`

面试房间支持四个模块：工作面试、研究生复试、考公面试、雅思口语。用户中断后再次进入会先读取 `/api/interviews/active`，可以继续上一场未完成训练；完成后会展示报告并保存到后端。

## Docker Compose

```powershell
docker compose up --build
```

Nginx 默认监听：

```txt
http://localhost/
```
