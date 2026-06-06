FROM ghcr.io/astral-sh/uv:python3.12-bookworm-slim

WORKDIR /app
ENV DATABASE_BACKUP_DIR=/app/database_backups \
    UV_DEFAULT_INDEX=https://mirrors.aliyun.com/pypi/simple \
    UV_HTTP_TIMEOUT=300 \
    UV_HTTP_RETRIES=5

RUN sed -i 's|deb.debian.org|mirrors.tuna.tsinghua.edu.cn|g; s|security.debian.org|mirrors.tuna.tsinghua.edu.cn|g' /etc/apt/sources.list.d/debian.sources \
    && apt-get update -o Acquire::Retries=5 \
    && apt-get install -y --no-install-recommends -o Acquire::Retries=5 postgresql-client \
    && rm -rf /var/lib/apt/lists/*

COPY pyproject.toml uv.lock* ./
RUN uv sync --frozen --no-dev

COPY app ./app
COPY alembic.ini ./alembic.ini
COPY migrations ./migrations

EXPOSE 8000
CMD ["sh", "-c", "uv run uvicorn app.main:app --host 0.0.0.0 --port 8000 --workers ${UVICORN_WORKERS:-2} --proxy-headers --forwarded-allow-ips='*' --limit-concurrency ${UVICORN_LIMIT_CONCURRENCY:-800} --timeout-keep-alive ${UVICORN_TIMEOUT_KEEP_ALIVE:-5}"]
