# 面霸练习生

面霸练习生是一个面向计算机求职学生的 AI 语音模拟面试平台。当前仓库已经按 `Backend` 和 `Frontend` 拆分，后端使用 FastAPI + Python 3.12 + uv，前端使用 React + Vite + GSAP。

## 目录

- `Backend`：FastAPI 后端、RESTful API、核心服务、测试
- `Frontend`：React 前端、产品官网、面试房间、管理员后台骨架
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

健康检查：

```txt
GET http://localhost:8000/api/health
```

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

## Docker Compose

```powershell
docker compose up --build
```

Nginx 默认监听：

```txt
http://localhost/
```

