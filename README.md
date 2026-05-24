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

项目不再拆“开发环境 / 生产环境”两套配置，统一直接维护 `Backend/.env`。这个文件包含真实部署配置和本机联调配置，已被 `.gitignore` 忽略，不会提交到仓库。默认假设 PostgreSQL 和 Redis 都部署在同一台机器上：PostgreSQL 负责用户、次数余额、训练会话、报告、模型配置、审计日志和调用日志；Redis 负责邮箱验证码、验证码限流，以及数据库不可用时的本机降级调试。

你只需要先在本机 PostgreSQL 手动创建数据库：

```sql
CREATE DATABASE mianba
  WITH ENCODING 'UTF8'
  TEMPLATE template0;
```

PostgreSQL 不使用 MySQL 的 `utf8mb4` 命名；它的 `UTF8` 编码支持 4 字节 Unicode 字符和 emoji，可以按这个显式建库。

如果你的本机 PostgreSQL 用户名、密码、端口或数据库名有变化，只改 `Backend/.env` 里的 `DATABASE_URL`，不用再复制或维护示例配置。

Docker Compose 内置 PostgreSQL 的宿主机端口映射为 `5433:5432`，避免和本机 PostgreSQL 的 `5432` 冲突。
后端容器启动时会先执行 `python -m app.cli.safe_migrate`，也就是先用 `pg_dump` 在 `database_backups/` 里保留迁移前备份，再执行 Alembic 迁移，然后才启动 FastAPI。Compose 会把项目根目录的 `database_backups/` 映射到容器内 `/app/database_backups`，备份不会因为容器重建而丢失。

数据库迁移：

```powershell
cd Backend
uv run alembic upgrade head
```

更推荐使用带备份的安全迁移命令：

```powershell
cd Backend
uv run python -m app.cli.safe_migrate
```

这个安全迁移命令会先确认 `DATABASE_URL` 指向的数据库可连接，然后用 PostgreSQL 官方 `pg_dump` 生成一份 custom archive 备份到项目根目录的 `database_backups/`，文件名类似 `mianba_20260524_143012.dump`。备份成功后才会执行 `alembic upgrade head`，并且自动只保留最新 5 份 `.dump` 备份，旧备份会被删除。`database_backups/` 已加入 `.gitignore`，不会提交到仓库。

如果系统找不到 `pg_dump`，可以把 PostgreSQL 客户端工具加入 `PATH`，或者在当前终端显式指定：

```powershell
$env:PG_DUMP_PATH="C:\Program Files\PostgreSQL\18\bin\pg_dump.exe"
uv run python -m app.cli.safe_migrate
```

当前后端已经具备 JWT 登录、邮箱验证码注册/登录、管理员双重认证接口、按模块扣次数、管理员手动发放次数接口、次数流水、训练会话中断恢复、完整报告保存、AI 供应商配置新增/更新、AI 调用日志，以及带 JWT 校验的面试 WebSocket 通道。登录成功后会写入 `HttpOnly` Cookie，前端不再把 token 放进 `localStorage`；后台登录还会额外校验 `ADMIN_EMAIL_ALLOWLIST` 白名单。

管理员后台还支持用户启用/禁用、系统参数配置、供应商密钥脱敏保存和供应商连通性测试。系统参数包含是否开放注册、是否允许普通用户密码登录、是否允许邮箱验证码登录、新用户默认次数、单轮回答建议时长和默认 LLM/ASR/TTS 配置 ID。

AI 模型路由默认按“国内可用、免费/低成本优先”排序：智谱 `GLM-4.7-Flash`、`glm-z1-flash`、`glm-4-flash-250414`，再到阿里百炼 `qwen-flash`、火山方舟 `doubao-seed-1.6-flash`，最后预置 DeepSeek `deepseek-v4-flash` 作为兜底备用。面试下一轮追问会优先尝试模型路由；未配置 API Key、主模型失败或全部模型失败时，系统会自动退回内置题库继续流程，不会卡死训练。

ASR/TTS 也已经纳入同一套供应商配置。首版默认启用浏览器 Web Speech 作为低成本通道，同时预置阿里云 ASR、火山 TTS 等国内供应商配置位，后续接入真实云端语音服务时可以直接在后台启用、填入密钥并调整优先级。

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

`/api/health/readiness` 会检查数据库表、Redis、邮件模式和 JWT 密钥是否达到可运行条件。本机纯 HTTP 调试登录闭环时，`AUTH_COOKIE_SECURE=false` 才能让浏览器带上登录 Cookie；服务器启用 HTTPS 后，把同一个 `Backend/.env` 里的该值改成 `true`。

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

后台入口由前端构建变量 `VITE_ADMIN_ENTRY_PATH` 控制，当前直接使用 `Frontend/.env` 和 `docker-compose.yml` 中的 `/console-mianba`。隐藏路径只降低暴露概率，真正安全仍依赖管理员双重认证、后端权限校验和审计日志。

面试房间支持四个模块：工作面试、研究生复试、考公面试、雅思口语。用户中断后再次进入会先读取 `/api/interviews/active`，可以继续上一场未完成训练；完成后会展示报告并保存到后端。

## Docker Compose

```powershell
docker compose up --build
```

Nginx 默认监听：

```txt
http://sakuracianna.icu/
https://sakuracianna.icu/
```

`nginx/nginx.conf` 已经是正式 HTTPS 配置：80 端口会跳转到 443，证书从 `nginx/certs/fullchain.pem` 和 `nginx/certs/privkey.pem` 挂载到容器内 `/etc/nginx/certs/`。证书文件属于服务器私有资产，`nginx/certs/` 已加入 `.gitignore`。启用 HTTPS 后记得把 `Backend/.env` 里的 `AUTH_COOKIE_SECURE` 改成 `true`，并把 `CORS_ORIGINS` 保留为你的正式域名。
